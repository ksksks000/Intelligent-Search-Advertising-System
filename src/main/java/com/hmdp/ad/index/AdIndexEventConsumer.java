package com.hmdp.ad.index;

import com.hmdp.utils.RedisConstants;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 广告索引独立消费者：ad:index:stream + ad:index:group + adIndexExecutor。
 * 与自然活动索引消费者完全隔离，索引侧 embedding/富化走 AdIndexRateLimiter。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.ad.enabled", havingValue = "true", matchIfMissing = true)
public class AdIndexEventConsumer {

    private static final long PENDING_IDLE_SECONDS = 30L;

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private AdIndexService indexService;
    @Resource(name = "adIndexRateLimiter")
    private AdIndexRateLimiter adIndexRateLimiter;
    @Resource(name = "adIndexExecutor")
    private ThreadPoolTaskExecutor executor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private StreamOperations<String, Object, Object> streamOps;
    private Thread poller;
    private String consumerName;

    @PostConstruct
    public void start() {
        streamOps = redis.opsForStream();
        consumerName = "ad-index-" + UUID.randomUUID().toString().substring(0, 8);
        createGroupQuietly();
        running.set(true);
        poller = new Thread(this::pollLoop, "ad-index-consumer");
        poller.setDaemon(true);
        poller.start();
        log.info("广告索引消费者启动，stream={}, group={}, consumer={}",
                RedisConstants.AD_INDEX_STREAM_KEY, RedisConstants.AD_INDEX_GROUP, consumerName);
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
                        Consumer.from(RedisConstants.AD_INDEX_GROUP, consumerName),
                        StreamReadOptions.empty().count(20).block(Duration.ofSeconds(1)),
                        StreamOffset.create(RedisConstants.AD_INDEX_STREAM_KEY, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    submit(record);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    createGroupQuietly();
                }
                log.error("读取广告索引事件失败", e);
                sleepQuietly(500);
            }
        }
    }

    private void submit(MapRecord<String, Object, Object> record) {
        try {
            executor.execute(() -> process(record));
        } catch (Exception e) {
            log.warn("广告索引线程池已满，记录保留 pending，recordId={}", record.getId());
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> value = record.getValue();
            AdIndexEvent event = new AdIndexEvent();
            event.setAdId(parseLong(value.get("adId")));
            event.setCampaignId(parseLong(value.get("campaignId")));
            event.setEventType(str(value.get("eventType")));
            event.setVersion(parseLong(value.get("version")));
            adIndexRateLimiter.acquireBlocking();
            indexService.handle(event);
            acknowledge(record);
        } catch (Exception e) {
            log.error("处理广告索引事件失败，recordId={}", record.getId(), e);
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.ad.index-pending-scan-ms:30000}")
    public void retryPending() {
        if (!running.get() || streamOps == null) {
            return;
        }
        try {
            PendingMessages pending = streamOps.pending(RedisConstants.AD_INDEX_STREAM_KEY,
                    RedisConstants.AD_INDEX_GROUP, Range.unbounded(), 100);
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery()
                        .compareTo(Duration.ofSeconds(PENDING_IDLE_SECONDS)) < 0) {
                    continue;
                }
                List<ByteRecord> claimed = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                        connection.xClaim(RedisConstants.AD_INDEX_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                                RedisConstants.AD_INDEX_GROUP, consumerName,
                                Duration.ofSeconds(PENDING_IDLE_SECONDS), message.getId()));
                if (claimed == null) {
                    continue;
                }
                for (ByteRecord byteRecord : claimed) {
                    submit(toMapRecord(byteRecord));
                }
            }
        } catch (Exception e) {
            log.error("广告索引 pending 重试失败", e);
        }
    }

    private MapRecord<String, Object, Object> toMapRecord(ByteRecord byteRecord) {
        Map<Object, Object> value = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : byteRecord.getValue().entrySet()) {
            value.put(new String(entry.getKey(), StandardCharsets.UTF_8),
                    new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return StreamRecords.mapBacked(value)
                .withStreamKey(RedisConstants.AD_INDEX_STREAM_KEY)
                .withId(byteRecord.getId());
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        try {
            streamOps.acknowledge(RedisConstants.AD_INDEX_STREAM_KEY,
                    RedisConstants.AD_INDEX_GROUP, record.getId());
        } catch (Exception e) {
            log.error("ACK 广告索引事件失败，recordId={}", record.getId(), e);
        }
    }

    private void createGroupQuietly() {
        try {
            streamOps.createGroup(RedisConstants.AD_INDEX_STREAM_KEY,
                    ReadOffset.from("0"), RedisConstants.AD_INDEX_GROUP);
            log.info("创建广告索引消费组成功");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.info("广告索引消费组已存在");
            } else {
                log.warn("创建广告索引消费组失败，将在读取时重试: {}", e.getMessage());
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

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
