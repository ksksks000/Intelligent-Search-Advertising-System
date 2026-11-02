package com.hmdp.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.UserProfile;
import com.hmdp.entity.Activity;
import com.hmdp.entity.BehaviorEvent;
import com.hmdp.mapper.BehaviorEventMapper;
import com.hmdp.utils.RecommendTagUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 行为事件查询与用户画像（简化版：近 7 天点击/浏览/收藏标签）。
 */
@Slf4j
@Service
public class BehaviorEventService {

    private static final List<String> INTEREST_EVENTS = Arrays.asList(
            "click", "view", "feed_click", "like", "order", "dwell");

    @Resource
    private BehaviorEventMapper behaviorEventMapper;

    @Resource
    private IActivityService activityService;

    public void save(BehaviorEvent event) {
        behaviorEventMapper.insert(event);
    }

    public List<Long> recentActivityIds(Long userId, String deviceId, int days, int limit) {
        if (userId == null && StrUtil.isBlank(deviceId)) {
            return Collections.emptyList();
        }
        QueryWrapper<BehaviorEvent> wrapper = baseWrapper(userId, deviceId, days);
        wrapper.isNotNull("target_id")
                .last("LIMIT " + Math.max(1, limit));
        return behaviorEventMapper.selectList(wrapper).stream()
                .map(BehaviorEvent::getTargetId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
    }

    public boolean hasBehavior(Long userId, String deviceId, int days, int threshold) {
        if (userId == null && StrUtil.isBlank(deviceId)) {
            return false;
        }
        Long count = behaviorEventMapper.selectCount(baseWrapper(userId, deviceId, days));
        return count != null && count >= Math.max(1, threshold);
    }

    /**
     * 近 N 天兴趣画像：标签权重、类目权重、拼接文本。
     */
    public UserProfile buildProfile(Long userId, String deviceId, int days, int limit) {
        List<Long> activityIds = recentActivityIds(userId, deviceId, days, limit);
        if (activityIds.isEmpty()) {
            return UserProfile.empty();
        }
        List<Activity> activities = activityService.listByIds(activityIds);
        if (activities == null || activities.isEmpty()) {
            return UserProfile.empty();
        }
        Map<String, Integer> tagWeights = new LinkedHashMap<>();
        Map<Long, Integer> categoryWeights = new LinkedHashMap<>();
        Map<Long, Integer> categoryClickCounts = new LinkedHashMap<>();
        int totalClicks = 0;
        StringBuilder text = new StringBuilder();
        for (Activity activity : activities) {
            StringBuilder activityTags = new StringBuilder();
            if (activity.getTags() != null) {
                for (String tag : activity.getTags().split("[,，]")) {
                    String normalized = tag.trim();
                    if (normalized.isEmpty() || RecommendTagUtils.isGeneric(normalized)) {
                        continue;
                    }
                    tagWeights.merge(normalized, 1, Integer::sum);
                    append(activityTags, normalized);
                }
            }
            if (activity.getCategoryId() != null) {
                categoryWeights.merge(activity.getCategoryId(), 1, Integer::sum);
            }
            append(text, activity.getTitle());
            append(text, activityTags.toString());
        }
        // 点击类目占比：只统计 click/feed_click，用于精排 category_ctr
        QueryWrapper<BehaviorEvent> clickWrapper = clickWrapper(userId, deviceId, days);
        List<BehaviorEvent> clickEvents = behaviorEventMapper.selectList(clickWrapper);
        totalClicks = clickEvents.size();
        if (!clickEvents.isEmpty()) {
            List<Long> clickActivityIds = clickEvents.stream()
                    .map(BehaviorEvent::getTargetId).filter(id -> id != null)
                    .distinct().collect(Collectors.toList());
            List<Activity> clickActivities = activityService.listByIds(clickActivityIds);
            Map<Long, Long> activityCategory = clickActivities == null ? Collections.emptyMap()
                    : clickActivities.stream().filter(a -> a.getId() != null && a.getCategoryId() != null)
                    .collect(Collectors.toMap(Activity::getId, Activity::getCategoryId, (a, b) -> a));
            for (BehaviorEvent event : clickEvents) {
                Long categoryId = activityCategory.get(event.getTargetId());
                if (categoryId != null) {
                    categoryClickCounts.merge(categoryId, 1, Integer::sum);
                }
            }
        }
        // 高频标签重复拼接，强化兴趣信号
        tagWeights.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(entry -> {
                    for (int i = 0; i < Math.min(3, entry.getValue()); i++) {
                        append(text, entry.getKey());
                    }
                });
        String profileText = text.length() > 512 ? text.substring(0, 512) : text.toString();
        log.debug("profile userId={}, deviceId={}, totalClicks={}, categoryClicks={}, tags={}",
                userId, deviceId, totalClicks, categoryClickCounts, tagWeights);
        return UserProfile.builder()
                .activityIds(activityIds)
                .tagWeights(tagWeights)
                .categoryWeights(categoryWeights)
                .categoryClickCounts(categoryClickCounts)
                .totalClicks(totalClicks)
                .text(profileText.trim())
                .build();
    }

    private QueryWrapper<BehaviorEvent> clickWrapper(Long userId, String deviceId, int days) {
        QueryWrapper<BehaviorEvent> wrapper = new QueryWrapper<>();
        wrapper.eq("target_type", "activity")
                .in("event_type", Arrays.asList("click", "feed_click"))
                .ge("event_time", LocalDateTime.now().minusDays(Math.max(1, days)))
                .isNotNull("target_id")
                .orderByDesc("event_time")
                .last("LIMIT 200");
        if (userId != null) {
            wrapper.eq("user_id", userId);
        } else {
            wrapper.eq("device_id", deviceId);
        }
        return wrapper;
    }

    private QueryWrapper<BehaviorEvent> baseWrapper(Long userId, String deviceId, int days) {
        QueryWrapper<BehaviorEvent> wrapper = new QueryWrapper<>();
        wrapper.eq("target_type", "activity")
                .in("event_type", INTEREST_EVENTS)
                .ge("event_time", LocalDateTime.now().minusDays(Math.max(1, days)))
                .orderByDesc("event_time");
        if (userId != null) {
            wrapper.eq("user_id", userId);
        } else {
            wrapper.eq("device_id", deviceId);
        }
        return wrapper;
    }

    private void append(StringBuilder builder, String text) {
        if (text != null && !text.trim().isEmpty()) {
            builder.append(text.trim()).append(' ');
        }
    }
}
