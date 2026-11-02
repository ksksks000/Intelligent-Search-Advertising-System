package com.hmdp.service.recommend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签召回：用户近期行为标签/类目 -> 在线活动。
 */
@Service
public class TagRecallService implements RecallService {

    @Resource
    private IActivityService activityService;

    @Resource
    private RecommendProperties properties;

    @Override
    public String source() {
        return "TAG";
    }

    @Override
    public List<ActivityCandidate> recall(RecommendContext context) {
        Map<String, Integer> tagWeights = context.tagWeights();
        Map<Long, Integer> categoryWeights = context.categoryWeights();
        if (tagWeights.isEmpty() && categoryWeights.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> topTags = tagWeights.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5).map(Map.Entry::getKey).collect(Collectors.toList());
        List<Long> topCategories = categoryWeights.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3).map(Map.Entry::getKey).collect(Collectors.toList());

        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<Activity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Activity::getStatus, Activity.STATUS_ONLINE)
                .ge(Activity::getEndTime, now)
                .le(Activity::getBeginTime, now.plusDays(7))
                .eq(context.getCity() != null && !context.getCity().trim().isEmpty(),
                        Activity::getCity, context.getCity())
                .and(q -> {
                    boolean first = true;
                    for (String tag : topTags) {
                        if (!first) {
                            q.or();
                        }
                        // 修复 2：tags + 标题词都参与匹配，避免“标题有火锅、tags 只有美食”漏召回
                        q.like(Activity::getTags, tag).or().like(Activity::getTitle, tag);
                        first = false;
                    }
                    for (Long categoryId : topCategories) {
                        if (!first) {
                            q.or();
                        }
                        q.eq(Activity::getCategoryId, categoryId);
                        first = false;
                    }
                })
                .last("LIMIT " + Math.max(1, properties.getTagLimit()));
        List<Activity> activities = activityService.list(wrapper);
        if (activities == null || activities.isEmpty()) {
            return new ArrayList<>();
        }
        int tagDenominator = Math.max(1, Math.min(tagWeights.size(), 5));
        List<ActivityCandidate> candidates = new ArrayList<>(activities.size());
        for (Activity activity : activities) {
            Set<String> activityTags = splitTags(activity.getTags());
            int matched = 0;
            for (String tag : activityTags) {
                if (tagWeights.containsKey(tag)) {
                    matched++;
                }
            }
            double tagScore = Math.min(1D, (double) matched / tagDenominator);
            int titleMatched = 0;
            String title = activity.getTitle() == null ? "" : activity.getTitle();
            for (String tag : tagWeights.keySet()) {
                if (!title.isEmpty() && title.contains(tag)) {
                    titleMatched++;
                }
            }
            double titleScore = Math.min(1D, (double) titleMatched / tagDenominator);
            double categoryScore = activity.getCategoryId() != null
                    && categoryWeights.containsKey(activity.getCategoryId()) ? 1D : 0D;
            double quality = activity.getQualityScore() == null ? 0D
                    : Math.min(1D, activity.getQualityScore() / 100D);
            // 修复 2 公式：0.5 tags + 0.2 标题词 + 0.2 类目 + 0.1 质量
            double score = 0.5D * tagScore + 0.2D * titleScore
                    + 0.2D * categoryScore + 0.1D * quality;
            candidates.add(ActivityCandidate.of(activity.getId(), source(), score));
        }
        candidates.sort(Comparator.comparingDouble(ActivityCandidate::getRecallScore).reversed());
        return candidates;
    }

    private Set<String> splitTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) {
            return java.util.Collections.emptySet();
        }
        return java.util.Arrays.stream(tags.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}
