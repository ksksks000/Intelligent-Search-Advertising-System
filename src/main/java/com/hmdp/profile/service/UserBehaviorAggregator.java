package com.hmdp.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.config.ProfileProperties;
import com.hmdp.entity.Activity;
import com.hmdp.entity.BehaviorEvent;
import com.hmdp.mapper.AdCampaignMapper;
import com.hmdp.mapper.BehaviorEventMapper;
import com.hmdp.profile.dto.BehaviorSummary;
import com.hmdp.profile.dto.BehaviorTerm;
import com.hmdp.service.IActivityService;
import lombok.extern.slf4j.Slf4j;
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
 * 行为聚合：严格分开自然行为（target_type=activity）与广告行为（target_type=ad）。
 * 输出 content_tags/action/weight/time_bucket 摘要，供 LLM prompt 或结构化降级使用。
 */
@Slf4j
@Service
public class UserBehaviorAggregator {

    @Resource
    private BehaviorEventMapper behaviorEventMapper;
    @Resource
    private IActivityService activityService;
    @Resource
    private AdCampaignMapper adCampaignMapper;
    @Resource
    private ProfileProperties properties;

    public BehaviorSummary aggregate(Long userId) {
        return aggregate(userId, null);
    }

    public BehaviorSummary aggregate(Long userId, String deviceId) {
        BehaviorSummary summary = new BehaviorSummary();
        summary.setUserId(userId);
        summary.setDays(properties.getBehaviorDays());
        if (userId == null && (deviceId == null || deviceId.trim().isEmpty())) {
            return summary;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(properties.getBehaviorDays());
        QueryWrapper<BehaviorEvent> wrapper = new QueryWrapper<BehaviorEvent>()
                .ge("event_time", since)
                .orderByDesc("event_time")
                .last("LIMIT " + Math.max(1, properties.getMaxEventsPerUser()));
        if (userId != null) {
            wrapper.eq("user_id", userId);
        } else {
            wrapper.eq("device_id", deviceId);
        }
        List<BehaviorEvent> events = behaviorEventMapper.selectList(wrapper);
        if (events.isEmpty()) {
            return summary;
        }
        Map<Long, Activity> activityMap = new HashMap<>();
        Map<Long, AdCampaign> campaignMap = new HashMap<>();
        List<Long> activityIds = events.stream()
                .filter(e -> "activity".equals(e.getTargetType()) && e.getTargetId() != null)
                .map(BehaviorEvent::getTargetId).distinct().collect(Collectors.toList());
        List<Long> adIds = events.stream()
                .filter(e -> "ad".equals(e.getTargetType()) && e.getTargetId() != null)
                .map(BehaviorEvent::getTargetId).distinct().collect(Collectors.toList());
        if (!activityIds.isEmpty()) {
            List<Activity> activities = activityService.listByIds(activityIds);
            if (activities != null) {
                for (Activity activity : activities) {
                    activityMap.put(activity.getId(), activity);
                }
            }
        }
        if (!adIds.isEmpty()) {
            List<AdCampaign> campaigns = adCampaignMapper.selectBatchIds(adIds);
            if (campaigns != null) {
                for (AdCampaign campaign : campaigns) {
                    campaignMap.put(campaign.getId(), campaign);
                }
            }
        }

        Map<String, BehaviorTerm> natural = new LinkedHashMap<>();
        Map<String, BehaviorTerm> ad = new LinkedHashMap<>();
        for (BehaviorEvent event : events) {
            // 曝光不是兴趣；自然兴趣和广告偏好只由点击/浏览/转化等动作产生
            if ("impression".equals(event.getEventType())) {
                continue;
            }
            double weight = actionWeight(event.getEventType());
            String bucket = timeBucket(event.getEventTime());
            if ("activity".equals(event.getTargetType())) {
                summary.setNaturalEventCount(summary.getNaturalEventCount() + 1);
                Activity activity = activityMap.get(event.getTargetId());
                if (activity == null) {
                    continue;
                }
                if (activity.getCategoryId() != null) {
                    summary.getNaturalCategories().merge(activity.getCategoryId(), 1, Integer::sum);
                }
                for (String tag : split(activity.getTags())) {
                    mergeTerm(natural, tag, weight, event.getEventType(), bucket);
                }
            } else if ("ad".equals(event.getTargetType())) {
                summary.setAdEventCount(summary.getAdEventCount() + 1);
                AdCampaign campaign = campaignMap.get(event.getTargetId());
                if (campaign == null) {
                    continue;
                }
                String key = campaign.getTargetCategories() == null
                        ? "campaign:" + campaign.getId()
                        : campaign.getTargetCategories();
                summary.getAdCategories().merge(key, 1, Integer::sum);
                for (String tag : split(campaign.getTargetTags())) {
                    mergeTerm(ad, tag, weight, event.getEventType(), bucket);
                }
                for (String tag : split(campaign.getTargetAffinity())) {
                    mergeTerm(ad, tag, weight, event.getEventType(), bucket);
                }
            }
        }
        summary.setNaturalTerms(sorted(natural));
        summary.setAdTerms(sorted(ad));
        return summary;
    }

    public List<Long> activeUserIds() {
        return behaviorEventMapper.selectActiveUserIds(
                LocalDateTime.now().minusDays(properties.getBehaviorDays()));
    }

    public List<String> activeDeviceIds() {
        return behaviorEventMapper.selectActiveDeviceIds(
                LocalDateTime.now().minusDays(properties.getBehaviorDays()));
    }

    public String toPromptText(BehaviorSummary summary) {
        StringBuilder builder = new StringBuilder();
        builder.append("window_days=").append(summary.getDays())
                .append(", natural_events=").append(summary.getNaturalEventCount())
                .append(", ad_events=").append(summary.getAdEventCount()).append('\n');
        builder.append("natural_terms: ").append(termsText(summary.getNaturalTerms())).append('\n');
        builder.append("natural_categories: ").append(summary.getNaturalCategories()).append('\n');
        builder.append("ad_terms: ").append(termsText(summary.getAdTerms())).append('\n');
        return builder.toString();
    }

    private String termsText(List<BehaviorTerm> terms) {
        return terms.stream().limit(properties.getMaxTags())
                .map(t -> t.getTag() + "(" + round(t.getWeight()) + "," + t.getAction()
                        + "," + t.getTimeBucket() + ")")
                .collect(Collectors.joining("; "));
    }

    private void mergeTerm(Map<String, BehaviorTerm> terms, String tag, double weight,
                           String action, String bucket) {
        String normalized = tag == null ? "" : tag.trim();
        if (normalized.isEmpty()) {
            return;
        }
        BehaviorTerm term = terms.get(normalized);
        if (term == null) {
            terms.put(normalized, new BehaviorTerm(normalized, weight, action, bucket));
            return;
        }
        term.setWeight(term.getWeight() + weight);
        if (weight >= actionWeight(term.getAction())) {
            term.setAction(action);
            term.setTimeBucket(bucket);
        }
    }

    private List<BehaviorTerm> sorted(Map<String, BehaviorTerm> terms) {
        List<BehaviorTerm> list = new ArrayList<>(terms.values());
        list.sort(Comparator.comparingDouble(BehaviorTerm::getWeight).reversed());
        return list.stream().limit(properties.getMaxTags()).collect(Collectors.toList());
    }

    private double actionWeight(String action) {
        if (action == null) {
            return 0.5D;
        }
        switch (action) {
            case "impression":
                return 0.1D;
            case "view":
                return 0.3D;
            case "click":
            case "feed_click":
                return 1.0D;
            case "like":
                return 1.5D;
            case "order":
                return 2.0D;
            case "conversion":
                return 3.0D;
            case "dwell":
                return 0.5D;
            default:
                return 0.5D;
        }
    }

    private String timeBucket(LocalDateTime time) {
        if (time == null) {
            return "unknown";
        }
        int hour = time.getHour();
        if (hour >= 6 && hour < 12) {
            return "morning";
        }
        if (hour >= 12 && hour < 18) {
            return "afternoon";
        }
        if (hour >= 18 && hour < 24) {
            return "evening";
        }
        return "night";
    }

    private List<String> split(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (String part : text.split("[,，]")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
