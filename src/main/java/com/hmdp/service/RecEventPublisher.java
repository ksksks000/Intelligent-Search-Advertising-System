package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.FeedItem;
import com.hmdp.dto.RecEventDTO;
import com.hmdp.filter.TraceIdFilter;
import com.hmdp.metrics.RecommendMetrics;
import com.hmdp.utils.RecommendUtils;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 推荐行为事件发布：异步写 Redis Stream，并维护曝光/点击/停留的短周期 Redis 结构。
 */
@Slf4j
@Component
public class RecEventPublisher {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private RecommendProperties properties;

    @Resource
    private RecommendMetrics metrics;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    @Qualifier("recEventExecutor")
    private ThreadPoolTaskExecutor eventExecutor;

    /**
     * 点击/停留等单事件：异步发布，不阻塞调用方。
     */
    public void publish(RecEventDTO event, Long userId, String deviceId) {
        if (event == null || event.getActivityId() == null || event.getEventType() == null) {
            return;
        }
        try {
            eventExecutor.execute(() -> doPublish(event, userId, deviceId));
        } catch (Exception e) {
            log.warn("推荐事件提交线程池失败，降级为同步写入: {}", e.getMessage());
            doPublish(event, userId, deviceId);
        }
    }

    /**
     * Feed 曝光批量埋点：一个异步任务写整页，避免每页几十次线程切换。
     */
    public void publishImpressions(List<FeedItem> items, Long userId, String deviceId, String traceId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            eventExecutor.execute(() -> {
                for (int i = 0; i < items.size(); i++) {
                    FeedItem item = items.get(i);
                    RecEventDTO event = new RecEventDTO();
                    event.setEventType(RecEventDTO.IMPRESSION);
                    event.setActivityId(item.getActivityId());
                    event.setScene("feed");
                    event.setTraceId(traceId);
                    event.setDeviceId(deviceId);
                    Map<String, Object> ext = new LinkedHashMap<>();
                    ext.put("position", i);
                    event.setExt(ext);
                    doPublish(event, userId, deviceId);
                }
            });
        } catch (Exception e) {
            log.warn("Feed 曝光埋点提交失败: {}", e.getMessage());
        }
    }

    private void doPublish(RecEventDTO event, Long userId, String deviceId) {
        Long activityId = event.getActivityId();
        if (activityId == null) {
            return;
        }
        String eventType = event.getEventType();
        String userKey = RecommendUtils.userKey(userId, deviceId);
        long now = System.currentTimeMillis();
        try {
            if (RecEventDTO.IMPRESSION.equals(eventType)) {
                String key = RedisConstants.REC_IMPRESSION_KEY + userKey;
                redis.opsForZSet().add(key, String.valueOf(activityId), now);
                redis.expire(key, RedisConstants.REC_BEHAVIOR_TTL_SECONDS, TimeUnit.SECONDS);
            } else if (RecEventDTO.CLICK.equals(eventType)) {
                String key = RedisConstants.REC_CLICK_KEY + userKey;
                redis.opsForSet().add(key, String.valueOf(activityId));
                redis.expire(key, RedisConstants.REC_BEHAVIOR_TTL_SECONDS, TimeUnit.SECONDS);
            } else if (RecEventDTO.DWELL.equals(eventType)) {
                long dwellMs = event.getDwellMs() == null ? 0 : Math.max(0, event.getDwellMs());
                redis.opsForHash().increment(RedisConstants.REC_DWELL_KEY,
                        activityId + ":sum", dwellMs);
                redis.opsForHash().increment(RedisConstants.REC_DWELL_KEY,
                        activityId + ":count", 1);
                redis.expire(RedisConstants.REC_DWELL_KEY,
                        RedisConstants.REC_BEHAVIOR_TTL_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("更新推荐频控 Redis 失败，activityId={}, type={}: {}",
                    activityId, eventType, e.getMessage());
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("userId", userId == null ? "" : String.valueOf(userId));
        body.put("deviceId", deviceId == null ? "" : deviceId);
        body.put("activityId", String.valueOf(activityId));
        body.put("scene", event.getScene() == null ? "" : event.getScene());
        body.put("traceId", firstNonBlank(event.getTraceId(), MDC.get(TraceIdFilter.TRACE_ID)));
        body.put("eventTime", String.valueOf(now));
        body.put("dwellMs", String.valueOf(event.getDwellMs() == null ? 0 : event.getDwellMs()));
        body.put("extJson", toJson(event.getExt()));
        try {
            redis.opsForStream().add(StreamRecords.mapBacked(body)
                    .withStreamKey(properties.getEventStream()));
            metrics.event(eventType);
        } catch (Exception e) {
            log.error("写推荐行为事件流失败，activityId={}, type={}", activityId, eventType, e);
        }
    }

    private String toJson(Map<String, Object> ext) {
        if (ext == null || ext.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ext);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }
}
