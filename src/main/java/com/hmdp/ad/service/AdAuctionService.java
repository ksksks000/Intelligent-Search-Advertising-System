package com.hmdp.ad.service;

import com.hmdp.ad.dto.AdAuctionResponse;
import com.hmdp.ad.dto.AdBillingEvent;
import com.hmdp.ad.dto.AdSlotItem;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.ad.metrics.AdMetrics;
import com.hmdp.config.AdProperties;
import com.hmdp.dto.UserProfile;
import com.hmdp.profile.service.UserProfileService;
import com.hmdp.utils.RecommendUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 广告竞价：定向召回/pCTR 校准在 50ms 硬预算内完成，超时回退缓存出价；
 * 频控与预算扣减在计划返回后执行，避免超时任务继续扣费造成双重扣款。
 * 采用一价（first-price）：按胜出者 bid 扣费。
 */
@Slf4j
@Service
public class AdAuctionService {

    @Resource
    private AdTargetingService targetingService;
    @Resource
    private AdCtrPredictor ctrPredictor;
    @Resource
    private AdBudgetService budgetService;
    @Resource
    private AdBidCacheService bidCacheService;
    @Resource
    private AdBillingPublisher billingPublisher;
    @Resource
    private AdEventService adEventService;
    @Resource
    private AdProperties properties;
    @Resource
    private AdMetrics metrics;
    @Resource
    private UserProfileService userProfileService;
    @Resource(name = "adBidExecutor")
    private ThreadPoolTaskExecutor adBidExecutor;
    @Resource(name = "adEventExecutor")
    private ThreadPoolTaskExecutor adEventExecutor;

    public AdAuctionResponse auction(String requestId, Long userId, String deviceId, String ip,
                                     String city, String deviceType, int slotCount,
                                     UserProfile profile, List<String> slotLabels) {
        long start = System.currentTimeMillis();
        String reqId = requestId == null || requestId.trim().isEmpty()
                ? "ad-" + UUID.randomUUID().toString().substring(0, 12) : requestId;
        int requested = Math.max(0, slotCount);
        if (!properties.isEnabled() || requested == 0) {
            return empty(start, 0);
        }
        List<String> labels = labels(slotLabels, requested);
        try {
            Future<AuctionPlan> future = adBidExecutor.submit(() ->
                    buildPlan(city, deviceType, profile,
                            RecommendUtils.userKey(userId, deviceId)));
            AuctionPlan plan = future.get(Math.max(1, properties.getAuctionTimeoutMs()),
                    TimeUnit.MILLISECONDS);
            return reserve(reqId, userId, deviceId, ip, city, labels, plan, start);
        } catch (TimeoutException e) {
            log.warn("DEGRADED: ad auction timeout {}ms, use bid cache, city={}",
                    properties.getAuctionTimeoutMs(), city);
            metrics.auction(System.currentTimeMillis() - start, true);
            return fallback(reqId, userId, deviceId, ip, city, deviceType,
                    requested, labels, profile, start, "AUCTION_TIMEOUT");
        } catch (Exception e) {
            log.warn("DEGRADED: ad auction failed, use bid cache, city={}, error={}",
                    city, e.getMessage());
            metrics.auction(System.currentTimeMillis() - start, true);
            return fallback(reqId, userId, deviceId, ip, city, deviceType,
                    requested, labels, profile, start, "AUCTION_ERROR");
        }
    }

    private AuctionPlan buildPlan(String city, String deviceType, UserProfile profile,
                                  String userKey) {
        LocalDateTime now = LocalDateTime.now();
        long targetingStart = System.currentTimeMillis();
        List<AdCampaign> candidates = targetingService.recall(city, deviceType, profile, now,
                userProfileService.adAffinity(userKey));
        long targetingMs = System.currentTimeMillis() - targetingStart;
        long scoreStart = System.currentTimeMillis();
        long maxBid = candidates.stream().mapToLong(c -> c.getBid() == null ? 0 : c.getBid())
                .max().orElse(1);
        List<Scored> scored = new ArrayList<>();
        for (AdCampaign campaign : candidates) {
            long bid = campaign.getBid() == null ? 0 : campaign.getBid();
            if (bid <= 0) {
                continue;
            }
            if (budgetService.remaining(campaign.getId()) <= 0) {
                metrics.budgetReject("exhausted");
                continue;
            }
            double pctr = ctrPredictor.predict(campaign, profile, maxBid);
            double qf = campaign.getQualityFactor() == null
                    ? 1D : campaign.getQualityFactor().doubleValue();
            scored.add(new Scored(campaign, pctr, pctr * bid * qf * 1000D));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.ecpm).reversed());
        bidCacheService.put(city, scored.stream().map(s -> s.campaign)
                .collect(java.util.stream.Collectors.toList()));
        return new AuctionPlan(candidates.size(), scored, targetingMs,
                System.currentTimeMillis() - scoreStart);
    }

    private AdAuctionResponse reserve(String requestId, Long userId, String deviceId, String ip,
                                      String city, List<String> labels,
                                      AuctionPlan plan, long start) {
        AdAuctionResponse response = new AdAuctionResponse();
        response.setCandidateCount(plan.candidateCount);
        if (plan.scored.isEmpty()) {
            response.setDegraded(false);
            response.setCostMs(System.currentTimeMillis() - start);
            response.setFillCount(0);
            metrics.fill(0, labels.size());
            return response;
        }
        String userKey = RecommendUtils.userKey(userId, deviceId);
        boolean anonymous = userId == null;
        List<AdSlotItem> items = new ArrayList<>();
        long reserveStart = System.currentTimeMillis();
        for (String slotId : labels) {
            for (Scored candidate : plan.scored) {
                if (candidate.used) {
                    continue;
                }
                String billingId = "imp:" + requestId + ":" + candidate.campaign.getId() + ":" + slotId;
                AdBudgetService.DeductResult admission = budgetService.tryAdmit(
                        candidate.campaign, userKey, ip, anonymous, billingId, requestId);
                if (!admission.isSuccess()) {
                    if (admission.getCode() <= 0 && admission.getCode() >= -2) {
                        metrics.frequencyReject(admission.getCode());
                    } else {
                        metrics.budgetReject("admit_" + admission.getCode());
                    }
                    continue;
                }
                candidate.used = true;
                AdSlotItem item = toItem(candidate, slotId);
                item.setBillingId(billingId);
                item.setRequestId(requestId);
                items.add(item);
                submitBilling(item, requestId, userKey, userId, deviceId, ip);
                submitImpression(item, requestId, userKey, userId, deviceId, ip, city);
                break;
            }
        }
        response.setItems(items);
        response.setFillCount(items.size());
        response.setDegraded(false);
        response.setCostMs(System.currentTimeMillis() - start);
        log.info("ad auction requestId={}, city={}, candidates={}, fill={}, cost={}ms, "
                        + "targeting={}ms, score={}ms, reserve={}ms",
                requestId, city, plan.candidateCount, items.size(), response.getCostMs(),
                plan.targetingMs, plan.scoreMs, System.currentTimeMillis() - reserveStart);
        metrics.auction(response.getCostMs(), false);
        metrics.fill(items.size(), labels.size());
        return response;
    }

    private AdAuctionResponse fallback(String requestId, Long userId, String deviceId, String ip,
                                       String city, String deviceType, int slotCount,
                                       List<String> labels, UserProfile profile, long start,
                                       String reason) {
        AdAuctionResponse response = new AdAuctionResponse();
        List<AdCampaign> cached = bidCacheService.get(city);
        String userKey = RecommendUtils.userKey(userId, deviceId);
        boolean anonymous = userId == null;
        for (int slot = 0; slot < labels.size() && slot < cached.size(); slot++) {
            AdCampaign campaign = cached.get(slot);
            String billingId = "imp:" + requestId + ":" + campaign.getId() + ":fallback-" + slot;
            AdBudgetService.DeductResult admission = budgetService.tryAdmit(
                    campaign, userKey, ip, anonymous, billingId, requestId);
            if (!admission.isSuccess()) {
                if (admission.getCode() <= 0 && admission.getCode() >= -2) {
                    metrics.frequencyReject(admission.getCode());
                } else {
                    metrics.budgetReject("fallback_" + admission.getCode());
                }
                continue;
            }
            Scored scored = new Scored(campaign, properties.getCtrPrior(),
                    properties.getCtrPrior() * (campaign.getBid() == null ? 0 : campaign.getBid())
                            * 1000D);
            AdSlotItem item = toItem(scored, labels.get(slot));
            item.setBillingId(billingId);
            item.setRequestId(requestId);
            item.setDegraded(true);
            response.getItems().add(item);
            submitBilling(item, requestId, userKey, userId, deviceId, ip);
            submitImpression(item, requestId, userKey, userId, deviceId, ip, city);
        }
        response.setCandidateCount(cached.size());
        response.setFillCount(response.getItems().size());
        response.setDegraded(true);
        response.getDegradedReasons().add(reason);
        response.setCostMs(System.currentTimeMillis() - start);
        metrics.fill(response.getItems().size(), labels.size());
        return response;
    }

    private void submitBilling(AdSlotItem item, String requestId, String userKey,
                               Long userId, String deviceId, String ip) {
        try {
            adEventExecutor.execute(() -> publishBilling(item, requestId, userKey,
                    userId, deviceId, ip));
        } catch (Exception e) {
            publishBilling(item, requestId, userKey, userId, deviceId, ip);
        }
    }

    private void submitImpression(AdSlotItem item, String requestId, String userKey,
                                  Long userId, String deviceId, String ip, String city) {
        try {
            adEventExecutor.execute(() -> adEventService.recordImpression(
                    item, requestId, userKey, userId, deviceId, ip, city));
        } catch (Exception e) {
            log.warn("广告曝光埋点提交失败 campaignId={}", item.getCampaignId(), e);
        }
    }

    private AdSlotItem toItem(Scored scored, String slotId) {
        AdCampaign campaign = scored.campaign;
        AdSlotItem item = new AdSlotItem();
        item.setAdId(campaign.getId());
        item.setCampaignId(campaign.getId());
        item.setActivityId(campaign.getActivityId());
        item.setCategoryId(AdCtrPredictor.firstCategory(campaign));
        item.setSlotId(slotId);
        item.setTitle(campaign.getTitle());
        item.setDescription(campaign.getDescription());
        item.setImage(campaign.getImage());
        item.setLandingUrl(campaign.getLandingUrl());
        item.setBid(campaign.getBid());
        item.setPctr(BigDecimal.valueOf(scored.pctr).setScale(6, RoundingMode.HALF_UP));
        item.setEcpm(BigDecimal.valueOf(scored.ecpm).setScale(4, RoundingMode.HALF_UP));
        item.setDegraded(false);
        return item;
    }

    private void publishBilling(AdSlotItem item, String requestId, String userKey,
                                Long userId, String deviceId, String ip) {
        AdBillingEvent event = new AdBillingEvent();
        event.setBillingId(item.getBillingId());
        event.setRequestId(requestId);
        event.setAdId(item.getAdId());
        event.setCampaignId(item.getCampaignId());
        event.setUserKey(userKey);
        event.setDeviceId(deviceId);
        event.setIp(ip);
        event.setEventType(AdEventService.EVENT_IMPRESSION);
        event.setSlotId(item.getSlotId());
        event.setBid(item.getBid());
        event.setCost(item.getBid());
        billingPublisher.publish(event);
    }

    private List<String> labels(List<String> slotLabels, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(slotLabels != null && i < slotLabels.size()
                    ? slotLabels.get(i) : "ad-" + (i + 1));
        }
        return result;
    }

    private AdAuctionResponse empty(long start, int candidateCount) {
        AdAuctionResponse response = new AdAuctionResponse();
        response.setDegraded(false);
        response.setCostMs(System.currentTimeMillis() - start);
        response.setCandidateCount(candidateCount);
        response.setFillCount(0);
        return response;
    }

    private static final class AuctionPlan {
        private final int candidateCount;
        private final List<Scored> scored;
        private final long targetingMs;
        private final long scoreMs;

        private AuctionPlan(int candidateCount, List<Scored> scored,
                            long targetingMs, long scoreMs) {
            this.candidateCount = candidateCount;
            this.scored = scored;
            this.targetingMs = targetingMs;
            this.scoreMs = scoreMs;
        }
    }

    private static final class Scored {
        private final AdCampaign campaign;
        private final double pctr;
        private final double ecpm;
        private boolean used;

        private Scored(AdCampaign campaign, double pctr, double ecpm) {
            this.campaign = campaign;
            this.pctr = pctr;
            this.ecpm = ecpm;
        }
    }
}
