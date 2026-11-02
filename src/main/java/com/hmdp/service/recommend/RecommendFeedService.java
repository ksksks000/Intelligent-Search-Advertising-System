package com.hmdp.service.recommend;

import cn.hutool.core.util.StrUtil;
import com.hmdp.ad.dto.AdAuctionResponse;
import com.hmdp.ad.service.AdAuctionService;
import com.hmdp.ad.service.AdFeedService;
import com.hmdp.config.AdProperties;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.FeedItem;
import com.hmdp.dto.FeedResponse;
import com.hmdp.dto.RecommendContext;
import com.hmdp.dto.UserProfile;
import com.hmdp.entity.Activity;
import com.hmdp.filter.TraceIdFilter;
import com.hmdp.metrics.RecommendMetrics;
import com.hmdp.profile.service.UserProfileService;
import com.hmdp.service.BehaviorEventService;
import com.hmdp.service.RecEventPublisher;
import com.hmdp.utils.RecommendUtils;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐 Feed 编排：缓存 -> 多路召回（50ms 硬超时）-> 粗排 -> 精排 -> 重排 -> 分页 -> 曝光埋点。
 */
@Slf4j
@Service
public class RecommendFeedService {

    @Resource
    private List<RecallService> recallServices;

    @Resource
    private CoarseRanker coarseRanker;

    @Resource
    private FineRanker fineRanker;

    @Resource
    private Reranker reranker;

    @Resource
    private FeedCacheService feedCacheService;

    @Resource
    private BehaviorEventService behaviorEventService;

    @Resource
    private RecEventPublisher recEventPublisher;

    @Resource
    private RecommendMetrics metrics;

    @Resource
    private RecommendProperties properties;

    @Resource
    private AdProperties adProperties;

    @Resource
    private AdAuctionService adAuctionService;

    @Resource
    private AdFeedService adFeedService;

    @Resource
    private UserProfileService userProfileService;

    @Resource
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor recallExecutor;

    private Map<String, RecallService> recallMap;

    @PostConstruct
    public void init() {
        recallMap = recallServices.stream()
                .collect(Collectors.toMap(RecallService::source, Function.identity(), (a, b) -> a));
    }

    public FeedResponse feed(Long userId, String deviceId, String city,
                             Double x, Double y, int cursor, int size) {
        return feed(userId, deviceId, city, x, y, cursor, size, false, null, null);
    }

    public FeedResponse feed(Long userId, String deviceId, String city,
                             Double x, Double y, int cursor, int size,
                             boolean withAds, String ip, String deviceType) {
        long start = System.currentTimeMillis();
        String normalizedCity = StrUtil.isBlank(city) ? "杭州" : city.trim();
        int pageSize = Math.max(1, Math.min(size <= 0 ? properties.getFeedPageSize() : size,
                properties.getMaxPageSize()));
        int pageCursor = Math.max(0, cursor);
        String userKey = RecommendUtils.userKey(userId, deviceId);
        String adCachePart = withAds
                ? "ad" + adProperties.getSlotPositionA() + "-" + adProperties.getSlotPositionB()
                : "noad";
        String cacheKey = RedisConstants.REC_FEED_KEY + userKey + ":" + normalizedCity
                + ":" + pageCursor + ":" + pageSize + ":" + adCachePart;

        FeedResponse cached = feedCacheService.get(cacheKey);
        if (cached != null) {
            long cacheCost = System.currentTimeMillis() - start;
            cached.setCostMs(cacheCost);
            Map<String, Long> cacheStages = new LinkedHashMap<>();
            cacheStages.put("cache", cacheCost);
            cacheStages.put("total", cacheCost);
            cached.setStageCosts(cacheStages);
            metrics.cache(cached.getCacheLevel());
            metrics.feed(cached.getCostMs(), Boolean.TRUE.equals(cached.getDegraded()));
            publishImpressions(cached.getList(), userId, deviceId);
            return cached;
        }

        RecommendContext context = new RecommendContext();
        context.setUserId(userId);
        context.setDeviceId(deviceId);
        context.setUserKey(userKey);
        context.setCity(normalizedCity);
        context.setX(x);
        context.setY(y);
        context.setCursor(pageCursor);
        context.setSize(pageSize);
        context.setStartMs(start);

        boolean coldStart;
        long profileStart = System.currentTimeMillis();
        try {
            coldStart = !behaviorEventService.hasBehavior(userId, deviceId,
                    properties.getBehaviorDays(), properties.getColdStartThreshold());
        } catch (Exception e) {
            coldStart = true;
            context.getDegradedSources().add("PROFILE");
            log.warn("DEGRADED: behavior profile query failed, treat as cold start: {}", e.getMessage());
        }
        if (!coldStart) {
            try {
                UserProfile profile = behaviorEventService.buildProfile(userId, deviceId,
                        properties.getBehaviorDays(), 50);
                context.setProfile(profile);
                if (profile == null || profile.isEmpty()) {
                    coldStart = true;
                }
            } catch (Exception e) {
                coldStart = true;
                context.getDegradedSources().add("PROFILE");
                log.warn("DEGRADED: build profile failed, treat as cold start: {}", e.getMessage());
            }
        }
        context.setColdStart(coldStart);
        if (context.getProfile() != null) {
            context.setProfile(userProfileService.enrich(context.getProfile(), userKey));
        }
        context.getStageCosts().put("profile", System.currentTimeMillis() - profileStart);

        List<String> sources = new ArrayList<>();
        sources.add("FOLLOW");
        sources.add("HOT");
        sources.add("GEO");
        if (coldStart) {
            sources.add("CURATED");
        } else {
            sources.add("TAG");
            if (properties.isVectorEnabled()) {
                sources.add("VECTOR");
            }
        }

        List<ActivityCandidate> candidates = recall(context, sources);
        if (candidates.isEmpty()) {
            RecallService hot = recallMap.get("HOT");
            if (hot != null) {
                try {
                    candidates.addAll(hot.recall(context));
                    context.getDegradedSources().add("FALLBACK");
                } catch (Exception e) {
                    log.warn("DEGRADED: hot fallback failed: {}", e.getMessage());
                }
            }
        }

        List<ActivityCandidate> coarse;
        long coarseStart = System.currentTimeMillis();
        coarse = coarseRanker.rank(candidates, context);
        context.getStageCosts().put("coarse", System.currentTimeMillis() - coarseStart);

        List<ActivityCandidate> fine;
        long fineStart = System.currentTimeMillis();
        try {
            fine = fineRanker.rank(coarse, context);
        } catch (Exception e) {
            fine = coarse;
            context.getDegradedSources().add("FINE_RANK");
            log.warn("DEGRADED: fine rank failed, use coarse result: {}", e.getMessage());
        }
        context.getStageCosts().put("fine", System.currentTimeMillis() - fineStart);

        if (log.isDebugEnabled()) {
            Map<String, Integer> coarseSources = new LinkedHashMap<>();
            for (String source : new String[]{"FOLLOW", "HOT", "GEO", "TAG", "VECTOR", "CURATED"}) {
                int count = 0;
                for (ActivityCandidate candidate : coarse) {
                    if (candidate.getSourceScores().containsKey(source)) {
                        count++;
                    }
                }
                coarseSources.put(source, count);
            }
            List<String> fineTop = fine.stream().limit(10).map(c -> {
                Activity a = c.getActivity();
                return c.getActivityId() + "/" + (a == null ? null : a.getCategoryId())
                        + "/" + c.getSourceScores().keySet();
            }).collect(Collectors.toList());
            log.info("recommend diag userKey={}, cold={}, profileClicks={}, categoryClicks={}, "
                            + "candidates={}, coarseSources={}, fineTop={}",
                    userKey, coldStart, context.getProfile() == null ? null
                            : context.getProfile().getTotalClicks(),
                    context.getProfile() == null ? null : context.getProfile().getCategoryClickCounts(),
                    candidates.size(), coarseSources, fineTop);
        }

        long rerankStart = System.currentTimeMillis();
        List<ActivityCandidate> reranked = reranker.rerank(fine);
        context.getStageCosts().put("rerank", System.currentTimeMillis() - rerankStart);

        long pageStart = System.currentTimeMillis();
        int from = pageCursor * pageSize;
        List<FeedItem> items = new ArrayList<>();
        String nextCursor = null;
        if (from < reranked.size()) {
            int to = Math.min(from + pageSize, reranked.size());
            for (int i = from; i < to; i++) {
                items.add(toFeedItem(reranked.get(i)));
            }
            if (to < reranked.size()) {
                nextCursor = String.valueOf(pageCursor + 1);
            }
        }
        context.getStageCosts().put("page", System.currentTimeMillis() - pageStart);

        if (withAds && pageCursor == 0 && adProperties.isEnabled() && adProperties.getSlotPositionA() > 0) {
            long adStart = System.currentTimeMillis();
            AdAuctionResponse auction = adAuctionService.auction(
                    "feed-" + userKey + "-" + System.currentTimeMillis(),
                    userId, deviceId, ip, normalizedCity, deviceType, 2,
                    context.getProfile(),
                    java.util.Arrays.asList("feed-" + adProperties.getSlotPositionA(),
                            "feed-" + adProperties.getSlotPositionB()));
            items = new ArrayList<>(adFeedService.merge(items, auction, pageCursor));
            context.getStageCosts().put("ad", System.currentTimeMillis() - adStart);
            if (Boolean.TRUE.equals(auction.getDegraded())) {
                context.getDegradedSources().add("AD");
            }
        }

        FeedResponse response = new FeedResponse();
        response.setList(items);
        response.setNextCursor(nextCursor);
        response.setDegraded(!context.getDegradedSources().isEmpty());
        response.setDegradedSources(new ArrayList<>(context.getDegradedSources()));
        response.setCostMs(System.currentTimeMillis() - start);
        response.setCacheLevel("MISS");
        response.setColdStart(coldStart);
        Map<String, Long> stageCosts = new LinkedHashMap<>(context.getStageCosts());
        stageCosts.put("total", response.getCostMs());
        response.setStageCosts(stageCosts);
        // 降级结果使用短 TTL 缓存，避免冷启动/单路超时把低质量页固定 5 分钟
        long ttlSeconds = Boolean.TRUE.equals(response.getDegraded())
                ? Math.min(30, properties.getL2TtlSeconds())
                : properties.getL2TtlSeconds();
        feedCacheService.put(cacheKey, response, ttlSeconds);
        publishImpressions(items, userId, deviceId);
        metrics.feed(response.getCostMs(), response.getDegraded());
        log.info("recommend feed userKey={}, cold={}, cursor={}, size={}, candidates={}, page={}, "
                        + "degraded={}, cost={}ms",
                userKey, coldStart, pageCursor, pageSize, candidates.size(), items.size(),
                context.getDegradedSources(), response.getCostMs());
        log.info("recommend timing userKey={}, stages={}", userKey, stageCosts);
        return response;
    }

    private List<ActivityCandidate> recall(RecommendContext context, List<String> sources) {
        Map<String, Future<List<ActivityCandidate>>> futures = new LinkedHashMap<>();
        for (String source : sources) {
            RecallService service = recallMap.get(source);
            if (service == null) {
                continue;
            }
            try {
                futures.put(source, recallExecutor.submit(() -> service.recall(context)));
            } catch (Exception e) {
                context.getDegradedSources().add(source);
                context.getStageCosts().put("recall." + source, 0L);
                log.warn("DEGRADED: recall={} submit failed: {}", source, e.getMessage());
            }
        }

        List<ActivityCandidate> all = new ArrayList<>();
        for (Map.Entry<String, Future<List<ActivityCandidate>>> entry : futures.entrySet()) {
            String source = entry.getKey();
            long perSourceTimeout = properties.getRecallTimeoutMs();
            if ("VECTOR".equals(source) && properties.getVectorTimeoutMs() > 0) {
                perSourceTimeout = properties.getVectorTimeoutMs();
            }
            perSourceTimeout = Math.max(1, perSourceTimeout);
            long recallStart = System.currentTimeMillis();
            try {
                // 每路独立 50ms 硬超时；各路并行执行，HOT 慢不会饿死 GEO/TAG
                List<ActivityCandidate> list = entry.getValue()
                        .get(perSourceTimeout, TimeUnit.MILLISECONDS);
                if (list != null) {
                    all.addAll(list);
                }
                long elapsed = System.currentTimeMillis() - recallStart;
                context.getStageCosts().put("recall." + source, elapsed);
                metrics.recall(source, elapsed, true);
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                context.getDegradedSources().add(source);
                long elapsed = System.currentTimeMillis() - recallStart;
                context.getStageCosts().put("recall." + source, elapsed);
                metrics.recall(source, elapsed, false);
                log.warn("DEGRADED: recall={} timeout {}ms", source, perSourceTimeout);
            } catch (Exception e) {
                context.getDegradedSources().add(source);
                long elapsed = System.currentTimeMillis() - recallStart;
                context.getStageCosts().put("recall." + source, elapsed);
                metrics.recall(source, elapsed, false);
                log.warn("DEGRADED: recall={} failed: {}", source, e.getMessage());
            }
        }
        return all;
    }

    private void publishImpressions(List<FeedItem> items, Long userId, String deviceId) {
        List<FeedItem> natural = items.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getAd()))
                .collect(Collectors.toList());
        recEventPublisher.publishImpressions(natural, userId, deviceId,
                MDC.get(TraceIdFilter.TRACE_ID));
    }

    private FeedItem toFeedItem(ActivityCandidate candidate) {
        Activity activity = candidate.getActivity();
        FeedItem item = new FeedItem();
        item.setActivityId(activity.getId());
        item.setTitle(activity.getTitle());
        item.setSubTitle(activity.getSubTitle());
        item.setImages(activity.getImages());
        item.setActivityType(activity.getActivityType());
        item.setCategoryId(activity.getCategoryId());
        item.setShopId(activity.getShopId());
        item.setCity(activity.getCity());
        item.setArea(activity.getArea());
        item.setPayValue(activity.getPayValue());
        item.setOriginalPrice(activity.getOriginalPrice());
        item.setQualityScore(activity.getQualityScore());
        item.setScore(Math.round(candidate.getFineScore() * 1000D) / 1000D);
        item.setSources(new ArrayList<>(candidate.getSourceScores().keySet()));
        return item;
    }
}
