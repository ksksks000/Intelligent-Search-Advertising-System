package com.hmdp.ad.service;

import com.hmdp.ad.dto.AdBillingEvent;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 广告计费异步落库：ad:billing:stream -> tb_ad_billing_log。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.ad.enabled", havingValue = "true", matchIfMissing = true)
public class AdBillingEventConsumer {

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private AdBillingPublisher publisher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private StreamOperations<String, Object, Object> streamOps;
    private Thread poller;
    private String consumerName;

    @PostConstruct
    public void start() {
        streamOps = redis.opsForStream();
        consumerName = "ad-billing-" + UUID.randomUUID().toString().substring(0, 8);
        createGroupQuietly();
        running.set(true);
        poller = new Thread(this::pollLoop, "ad-billing-consumer");
        poller.setDaemon(true);
        poller.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (poller != null) {
            poller.interrupt();
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(RedisConstants.AD_BILLING_GROUP, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(1)),
                        StreamOffset.create(RedisConstants.AD_BILLING_STREAM_KEY, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        publisher.save(toEvent(record.getValue()));
                        acknowledge(record);
                    } catch (Exception e) {
                        log.error("广告计费落库失败，recordId={}", record.getId(), e);
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    createGroupQuietly();
                }
                log.error("读取广告计费 Stream 失败", e);
            }
        }
    }

    private AdBillingEvent toEvent(Map<Object, Object> value) {
        AdBillingEvent event = new AdBillingEvent();
        event.setBillingId(str(value.get("billingId")));
        event.setRequestId(str(value.get("requestId")));
        event.setAdId(parseLong(value.get("adId")));
        event.setCampaignId(parseLong(value.get("campaignId")));
        event.setAdvertiserId(parseLong(value.get("advertiserId")));
        event.setUserKey(str(value.get("userKey")));
        event.setDeviceId(str(value.get("deviceId")));
        event.setIp(str(value.get("ip")));
        event.setEventType(str(value.get("eventType")));
        event.setSlotId(str(value.get("slotId")));
        event.setBid(parseLong(value.get("bid")));
        event.setCost(parseLong(value.get("cost")));
        event.setEventTime(parseLong(value.get("eventTime")));
        return event;
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        try {
            streamOps.acknowledge(RedisConstants.AD_BILLING_STREAM_KEY,
                    RedisConstants.AD_BILLING_GROUP, record.getId());
        } catch (Exception e) {
            log.error("ACK 广告计费事件失败，recordId={}", record.getId(), e);
        }
    }

    private void createGroupQuietly() {
        try {
            streamOps.createGroup(RedisConstants.AD_BILLING_STREAM_KEY,
                    ReadOffset.from("0"), RedisConstants.AD_BILLING_GROUP);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建广告计费消费组失败: {}", e.getMessage());
        }
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text;
    }

    private Long parseLong(Object value) {
        String text = str(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
