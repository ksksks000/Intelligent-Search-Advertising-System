package com.hmdp.service;

import com.hmdp.config.RecommendProperties;
import com.hmdp.entity.BehaviorEvent;
import com.hmdp.utils.RecommendUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推荐行为事件消费者：Stream -> tb_behavior_event + tb_activity_stats。
 * 成功才 ACK；失败保留 pending，定时 XCLAIM 重试。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.recommend.event-consumer-enabled",
        havingValue = "true", matchIfMissing = true)
public class RecEventConsumer {

    private static final long PENDING_IDLE_SECONDS = 30L;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private RecommendProperties properties;

    @Resource
    private BehaviorEventService behaviorEventService;

    @Resource
    private ActivityStatsService activityStatsService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private StreamOperations<String, Object, Object> streamOps;
    private Thread poller;
    private String consumerName;

    @PostConstruct
    public void start() {
        streamOps = redis.opsForStream();
        consumerName = "rec-event-" + UUID.randomUUID().toString().substring(0, 8);
        createGroupQuietly();
        running.set(true);
        poller = new Thread(this::pollLoop, "rec-event-consumer");
        poller.setDaemon(true);
        poller.start();
        log.info("推荐行为消费者启动，stream={}, group={}, consumer={}",
                properties.getEventStream(), properties.getEventGroup(), consumerName);
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
                        Consumer.from(properties.getEventGroup(), consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(1)),
                        StreamOffset.create(properties.getEventStream(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    process(record);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    createGroupQuietly();
                }
                log.error("读取推荐行为事件失败", e);
                sleepQuietly(500);
            }
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.recommend.event-pending-scan-ms:30000}")
    public void retryPending() {
        if (!running.get() || streamOps == null) {
            return;
        }
        try {
            PendingMessages pending = streamOps.pending(properties.getEventStream(),
                    properties.getEventGroup(), Range.unbounded(), 100);
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery()
                        .compareTo(Duration.ofSeconds(PENDING_IDLE_SECONDS)) < 0) {
                    continue;
                }
                List<ByteRecord> claimed = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                        connection.xClaim(properties.getEventStream().getBytes(StandardCharsets.UTF_8),
                                properties.getEventGroup(), consumerName,
                                Duration.ofSeconds(PENDING_IDLE_SECONDS), message.getId()));
                if (claimed == null) {
                    continue;
                }
                for (ByteRecord byteRecord : claimed) {
                    process(toMapRecord(byteRecord));
                }
            }
        } catch (Exception e) {
            log.error("推荐行为 pending 重试失败", e);
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> value = record.getValue();
            String eventType = str(value.get("eventType"));
            Long activityId = parseLong(value.get("activityId"));
            if (eventType == null || activityId == null) {
                acknowledge(record);
                return;
            }
            Long userId = parseLong(value.get("userId"));
            String deviceId = str(value.get("deviceId"));
            String userKey = RecommendUtils.userKey(userId, deviceId);

            BehaviorEvent event = new BehaviorEvent()
                    .setUserId(userId)
                    .setDeviceId(deviceId)
                    .setEventType(eventType)
                    .setTargetType("activity")
                    .setTargetId(activityId)
                    .setScene(str(value.get("scene")))
                    .setTraceId(str(value.get("traceId")))
                    .setEventTime(toTime(value.get("eventTime")))
                    .setExtJson(str(value.get("extJson")));
            behaviorEventService.save(event);
            activityStatsService.recordEvent(activityId, eventType, userKey);
            acknowledge(record);
        } catch (Exception e) {
            // 不 ACK，保留 pending；由 retryPending 接管
            log.error("处理推荐行为事件失败，recordId={}", record.getId(), e);
        }
    }

    private MapRecord<String, Object, Object> toMapRecord(ByteRecord byteRecord) {
        Map<Object, Object> value = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : byteRecord.getValue().entrySet()) {
            value.put(new String(entry.getKey(), StandardCharsets.UTF_8),
                    new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return StreamRecords.mapBacked(value)
                .withStreamKey(properties.getEventStream())
                .withId(byteRecord.getId());
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        try {
            streamOps.acknowledge(properties.getEventStream(), properties.getEventGroup(), record.getId());
        } catch (Exception e) {
            log.error("ACK 推荐行为事件失败，recordId={}", record.getId(), e);
        }
    }

    private void createGroupQuietly() {
        try {
            streamOps.createGroup(properties.getEventStream(),
                    ReadOffset.from("0"), properties.getEventGroup());
            log.info("创建推荐行为消费组成功");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.info("推荐行为消费组已存在");
            } else {
                log.warn("创建推荐行为消费组失败，将在读取时重试: {}", e.getMessage());
            }
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

    private LocalDateTime toTime(Object value) {
        Long millis = parseLong(value);
        if (millis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
