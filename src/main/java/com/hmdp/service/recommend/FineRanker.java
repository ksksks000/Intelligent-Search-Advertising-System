package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.ActivityStatsView;
import com.hmdp.dto.RecommendContext;
import com.hmdp.dto.UserProfile;
import com.hmdp.entity.Activity;
import com.hmdp.service.ActivityStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 精排：ctr + category_ctr + 质量分 + 新鲜度 + 地理距离 + 标签匹配。
 * 阶段 4 前置修复 A：标签 0.28、category_ctr 0.17、全局 ctr 0.15、
 * 质量 0.13、新鲜度 0.12、地理 0.15；dwell 权重降为 0，权重和为 1。
 */
@Slf4j
@Service
public class FineRanker {

    private static final double NEUTRAL = 0.5D;

    @Resource
    private ActivityStatsService activityStatsService;

    @Resource
    private FeedExposureCache exposureCache;

    @Resource
    private RecommendProperties properties;

    public List<ActivityCandidate> rank(List<ActivityCandidate> candidates, RecommendContext context) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = candidates.stream()
                .map(ActivityCandidate::getActivityId).collect(Collectors.toList());
        long statsStart = System.currentTimeMillis();
        Map<Long, ActivityStatsView> stats = activityStatsService.loadStats(ids, 7);
        context.getStageCosts().put("fine.stats", System.currentTimeMillis() - statsStart);
        String userKey = context.getUserKey();
        LocalDateTime now = LocalDateTime.now();
        long scoreStart = System.currentTimeMillis();
        long exposureNanos = 0L;

        for (ActivityCandidate candidate : candidates) {
            Activity activity = candidate.getActivity();
            if (activity == null) {
                continue;
            }
            double ctr = ctrFeature(stats.get(activity.getId()));
            double categoryCtr = categoryCtrFeature(activity, context);
            double quality = qualityFeature(activity);
            double freshness = freshnessFeature(activity, now);
            double geo = geoFeature(activity, context);
            double tag = tagFeature(activity, context);
            double score = properties.getFineTag() * tag
                    + properties.getFineCategoryCtr() * categoryCtr
                    + properties.getFineCtr() * ctr
                    + properties.getFineQuality() * quality
                    + properties.getFineFreshness() * freshness
                    + properties.getFineGeo() * geo;
            long exposureStart = System.nanoTime();
            boolean exposed = exposureCache.exposedNotClicked(userKey, activity.getId());
            exposureNanos += System.nanoTime() - exposureStart;
            if (exposed) {
                score *= properties.getExposedPenalty();
            }
            candidate.setFineScore(score);
        }
        context.getStageCosts().put("fine.score", System.currentTimeMillis() - scoreStart);
        context.getStageCosts().put("fine.exposure", exposureNanos / 1_000_000L);
        candidates.sort(Comparator.comparingDouble(ActivityCandidate::getFineScore).reversed());
        int limit = Math.max(1, properties.getFineTopN());
        return candidates.size() <= limit
                ? candidates : new ArrayList<>(candidates.subList(0, limit));
    }

    private double ctrFeature(ActivityStatsView view) {
        if (view == null || view.getPv() <= 0) {
            return NEUTRAL;
        }
        // 20% ctr 视为满分，避免极端小样本 ctr 直接主导排序
        return Math.min(1D, view.getCtr() / 0.2D);
    }

    /**
     * 用户近 7 天对该活动类目的点击占比；无任何点击用 0.5 先验，
     * 有点击但该类目为 0 时用 0，避免“点过火锅却推美容”。
     */
    private double categoryCtrFeature(Activity activity, RecommendContext context) {
        UserProfile profile = context.getProfile();
        if (profile == null || profile.getTotalClicks() <= 0) {
            return NEUTRAL;
        }
        if (activity.getCategoryId() == null) {
            return 0D;
        }
        Map<Long, Integer> counts = profile.getCategoryClickCounts();
        Integer clicks = counts == null ? null : counts.get(activity.getCategoryId());
        if (clicks == null) {
            return 0D;
        }
        return Math.min(1D, (double) clicks / profile.getTotalClicks());
    }

    private double qualityFeature(Activity activity) {
        int value = activity.getQualityScore() == null ? 0 : activity.getQualityScore();
        return Math.max(0D, Math.min(1D, value / 100D));
    }

    private double freshnessFeature(Activity activity, LocalDateTime now) {
        LocalDateTime begin = activity.getBeginTime();
        if (begin == null) {
            return NEUTRAL;
        }
        long days = begin.isAfter(now)
                ? ChronoUnit.DAYS.between(now, begin)
                : ChronoUnit.DAYS.between(begin, now);
        return 1D / (1D + days / 7D);
    }

    private double geoFeature(Activity activity, RecommendContext context) {
        if (context.getX() == null || context.getY() == null
                || activity.getX() == null || activity.getY() == null) {
            return NEUTRAL;
        }
        double distance = haversine(context.getY(), context.getX(), activity.getY(), activity.getX());
        double radius = Math.max(1D, properties.getGeoRadiusKm());
        return Math.max(0D, 1D - Math.min(1D, distance / radius));
    }

    private double tagFeature(Activity activity, RecommendContext context) {
        Map<String, Integer> tags = context.tagWeights();
        if (tags == null || tags.isEmpty() || activity.getTags() == null) {
            return NEUTRAL;
        }
        Set<String> activityTags = java.util.Arrays.stream(activity.getTags().split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        int matched = 0;
        for (String tag : activityTags) {
            if (tags.containsKey(tag)) {
                matched++;
            }
        }
        int denominator = Math.max(1, Math.min(tags.size(), 5));
        return Math.min(1D, (double) matched / denominator);
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double earth = 6371.0D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earth * c;
    }
}
