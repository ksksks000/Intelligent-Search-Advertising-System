package com.hmdp.ad.service;

import com.hmdp.ad.dto.AdBillingEvent;
import com.hmdp.ad.entity.AdBillingLog;
import com.hmdp.mapper.AdBillingLogMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计费事件：优先写 Redis Stream 异步落库；Stream 不可用时同步落库兜底。
 */
@Slf4j
@Service
public class AdBillingPublisher {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private AdBillingLogMapper billingLogMapper;

    public void publish(AdBillingEvent event) {
        if (event == null || event.getBillingId() == null) {
            return;
        }
        if (event.getEventTime() == null) {
            event.setEventTime(System.currentTimeMillis());
        }
        Map<String, String> body = new LinkedHashMap<>();
        put(body, "billingId", event.getBillingId());
        put(body, "requestId", event.getRequestId());
        put(body, "adId", event.getAdId());
        put(body, "campaignId", event.getCampaignId());
        put(body, "advertiserId", event.getAdvertiserId());
        put(body, "userKey", event.getUserKey());
        put(body, "deviceId", event.getDeviceId());
        put(body, "ip", event.getIp());
        put(body, "eventType", event.getEventType());
        put(body, "slotId", event.getSlotId());
        put(body, "bid", event.getBid());
        put(body, "cost", event.getCost());
        put(body, "eventTime", event.getEventTime());
        try {
            redis.opsForStream().add(StreamRecords.mapBacked(body)
                    .withStreamKey(RedisConstants.AD_BILLING_STREAM_KEY));
        } catch (Exception e) {
            log.error("写广告计费 Stream 失败，改为同步落库 billingId={}", event.getBillingId(), e);
            save(event);
        }
    }

    public int save(AdBillingEvent event) {
        try {
            AdBillingLog row = new AdBillingLog()
                    .setBillingId(event.getBillingId())
                    .setRequestId(event.getRequestId())
                    .setAdId(event.getAdId())
                    .setCampaignId(event.getCampaignId())
                    .setAdvertiserId(event.getAdvertiserId())
                    .setUserKey(event.getUserKey())
                    .setDeviceId(event.getDeviceId())
                    .setIp(event.getIp())
                    .setEventType(event.getEventType())
                    .setSlotId(event.getSlotId())
                    .setBid(event.getBid())
                    .setCost(event.getCost())
                    .setStatus(AdBillingLog.STATUS_SUCCESS)
                    .setEventTime(toTime(event.getEventTime()));
            return billingLogMapper.insert(row);
        } catch (Exception e) {
            // billing_id 唯一键：重复消费直接视为成功
            if (e.getMessage() != null && e.getMessage().contains("Duplicate")) {
                return 0;
            }
            throw e;
        }
    }

    private LocalDateTime toTime(Long millis) {
        return millis == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private void put(Map<String, String> body, String key, Object value) {
        body.put(key, value == null ? "" : String.valueOf(value));
    }
}
