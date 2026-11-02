package com.hmdp.ad.index;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 广告索引事件发布到独立 Stream：ad:index:stream。
 */
@Slf4j
@Component
public class StreamAdIndexEventPublisher implements AdIndexEventPublisher {

    @Resource
    private StringRedisTemplate redis;

    @Override
    public void publish(Long adId, Long campaignId, String eventType) {
        if (adId == null && campaignId == null) {
            return;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("adId", adId == null ? "" : String.valueOf(adId));
        body.put("campaignId", campaignId == null ? "" : String.valueOf(campaignId));
        body.put("eventType", eventType == null ? AdIndexEvent.UPSERT : eventType);
        body.put("version", String.valueOf(System.currentTimeMillis()));
        try {
            redis.opsForStream().add(StreamRecords.mapBacked(body)
                    .withStreamKey(RedisConstants.AD_INDEX_STREAM_KEY));
        } catch (Exception e) {
            log.error("发布广告索引事件失败，adId={}, campaignId={}", adId, campaignId, e);
        }
    }
}
