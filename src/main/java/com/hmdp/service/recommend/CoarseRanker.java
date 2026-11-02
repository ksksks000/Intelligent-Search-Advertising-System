package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;
import com.hmdp.entity.Activity;
import com.hmdp.service.IActivityService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 粗排：多路合并 + 来源加权 + 基础过滤，取 Top100。
 */
@Service
public class CoarseRanker {

    @Resource
    private IActivityService activityService;

    @Resource
    private RecommendProperties properties;

    public List<ActivityCandidate> rank(List<ActivityCandidate> candidates, RecommendContext context) {
        Map<Long, ActivityCandidate> merged = new LinkedHashMap<>();
        for (ActivityCandidate candidate : candidates) {
            if (candidate == null || candidate.getActivityId() == null) {
                continue;
            }
            ActivityCandidate target = merged.computeIfAbsent(candidate.getActivityId(), id -> {
                ActivityCandidate value = new ActivityCandidate();
                value.setActivityId(id);
                return value;
            });
            for (Map.Entry<String, Double> entry : candidate.getSourceScores().entrySet()) {
                target.addSource(entry.getKey(), entry.getValue());
            }
        }
        if (merged.isEmpty()) {
            return new ArrayList<>();
        }
        List<Activity> activities;
        long loadStart = System.currentTimeMillis();
        activities = activityService.listByIds(merged.keySet());
        if (context != null) {
            context.getStageCosts().put("coarse.db", System.currentTimeMillis() - loadStart);
        }
        if (activities == null || activities.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime future = now.plusDays(7);
        Map<String, double[]> bounds = new HashMap<>();
        if (properties.isSourceNormalize()) {
            for (ActivityCandidate candidate : merged.values()) {
                for (Map.Entry<String, Double> entry : candidate.getSourceScores().entrySet()) {
                    double[] bound = bounds.computeIfAbsent(entry.getKey(),
                            key -> new double[]{Double.MAX_VALUE, -Double.MAX_VALUE});
                    bound[0] = Math.min(bound[0], entry.getValue());
                    bound[1] = Math.max(bound[1], entry.getValue());
                }
            }
        }
        List<ActivityCandidate> ranked = new ArrayList<>();
        for (Activity activity : activities) {
            if (!Integer.valueOf(Activity.STATUS_ONLINE).equals(activity.getStatus())) {
                continue;
            }
            if (activity.getEndTime() != null && activity.getEndTime().isBefore(now)) {
                continue;
            }
            if (activity.getBeginTime() != null && activity.getBeginTime().isAfter(future)) {
                continue;
            }
            if (activity.getStock() != null && activity.getStock() <= 0) {
                continue;
            }
            ActivityCandidate candidate = merged.get(activity.getId());
            if (candidate == null) {
                continue;
            }
            candidate.setActivity(activity);
            double score = 0D;
            for (Map.Entry<String, Double> entry : candidate.getSourceScores().entrySet()) {
                double value = entry.getValue();
                double[] bound = bounds.get(entry.getKey());
                if (bound != null) {
                    value = bound[1] > bound[0]
                            ? (value - bound[0]) / (bound[1] - bound[0]) : 0.5D;
                }
                score += sourceWeight(entry.getKey()) * value;
            }
            // 多路命中奖励，避免单路噪声压过多路共识
            score += 0.05D * Math.max(0, candidate.getSourceScores().size() - 1);
            candidate.setCoarseScore(score);
            ranked.add(candidate);
        }
        ranked.sort(Comparator.comparingDouble(ActivityCandidate::getCoarseScore).reversed());
        int limit = Math.max(1, properties.getCoarseTopN());
        List<ActivityCandidate> selected = ranked.size() <= limit
                ? new ArrayList<>(ranked)
                : new ArrayList<>(ranked.subList(0, limit));
        selected = applyQuota(selected, ranked, "TAG", properties.getCoarseTagQuota(), limit);
        selected = applyQuota(selected, ranked, "FOLLOW", properties.getCoarseFollowQuota(), limit);
        selected.sort(Comparator.comparingDouble(ActivityCandidate::getCoarseScore).reversed());
        return selected;
    }

    private List<ActivityCandidate> applyQuota(List<ActivityCandidate> selected,
                                               List<ActivityCandidate> ranked,
                                               String source, int quota, int limit) {
        if (quota <= 0) {
            return selected;
        }
        long count = selected.stream()
                .filter(c -> c.getSourceScores().containsKey(source)).count();
        if (count >= quota) {
            return selected;
        }
        List<ActivityCandidate> extras = ranked.stream()
                .filter(c -> c.getSourceScores().containsKey(source))
                .filter(c -> !selected.contains(c))
                .limit(quota - count)
                .collect(Collectors.toList());
        if (extras.isEmpty()) {
            return selected;
        }
        selected.addAll(extras);
        while (selected.size() > limit) {
            ActivityCandidate remove = null;
            for (int i = selected.size() - 1; i >= 0; i--) {
                ActivityCandidate candidate = selected.get(i);
                boolean quotaSource = candidate.getSourceScores().containsKey("TAG")
                        || candidate.getSourceScores().containsKey("FOLLOW");
                if (!quotaSource) {
                    remove = candidate;
                    break;
                }
            }
            if (remove == null) {
                remove = selected.get(selected.size() - 1);
            }
            selected.remove(remove);
        }
        return selected;
    }

    private double sourceWeight(String source) {
        switch (source) {
            case "FOLLOW":
                return properties.getSourceFollow();
            case "VECTOR":
                return properties.getSourceVector();
            case "TAG":
                return properties.getSourceTag();
            case "HOT":
                return properties.getSourceHot();
            case "GEO":
                return properties.getSourceGeo();
            case "CURATED":
                return properties.getSourceCurated();
            default:
                return 0D;
        }
    }
}
