package com.hmdp.search.index;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class StreamSearchIndexEventPublisher implements SearchIndexEventPublisher {

    private final StringRedisTemplate redis;

    public StreamSearchIndexEventPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void publish(Long activityId, String eventType) {
        if (activityId == null) {
            return;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("activityId", String.valueOf(activityId));
        body.put("version", String.valueOf(System.currentTimeMillis()));
        try {
            redis.opsForStream().add(StreamRecords.mapBacked(body)
                    .withStreamKey(RedisConstants.SEARCH_INDEX_STREAM_KEY));
        } catch (Exception e) {
            // 索引事件失败不影响活动主流程，后续可通过全量重建修复
            log.error("发布搜索索引事件失败，activityId={}, type={}", activityId, eventType, e);
        }
    }
}
