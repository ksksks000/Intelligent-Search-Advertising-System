package com.hmdp.search.index;

import com.hmdp.entity.Activity;
import com.hmdp.service.IActivityService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 搜索索引增量消费者：活动变更 -> Redis Stream -> 幂等 upsert/delete。
 * <p>
 * 阶段 3 起改为「activityId hash 分区 + 有界队列」并行消费：
 * 同一 activityId 固定落到一个单线程分区，保证同一文档顺序；分区满时保留 pending，
 * 由定时 XPENDING/XCLAIM 重试。
 */
@Slf4j
@Component
public class SearchIndexEventConsumer {

    private static final long PENDING_IDLE_SECONDS = 30L;
    private static final byte[] STREAM_KEY_BYTES =
            RedisConstants.SEARCH_INDEX_STREAM_KEY.getBytes(StandardCharsets.UTF_8);

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private SearchIndexService indexService;

    @Resource
    private IActivityService activityService;

    @Resource
    private SearchIndexConsumerExecutor consumerExecutor;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private StreamOperations<String, Object, Object> streamOps;
    private Thread poller;
    private String consumerName;

    @PostConstruct
    public void start() {
        streamOps = redis.opsForStream();
        consumerName = "search-index-" + UUID.randomUUID().toString().substring(0, 8);
        createGroupQuietly();
        running.set(true);
        poller = new Thread(this::pollLoop, "search-index-consumer");
        poller.setDaemon(true);
        poller.start();
        log.info("搜索索引消费者启动，stream={}, group={}, consumer={}, partitions={}",
                RedisConstants.SEARCH_INDEX_STREAM_KEY, RedisConstants.SEARCH_INDEX_GROUP,
                consumerName, consumerExecutor == null ? "?" : "configured");
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
                long readStart = System.currentTimeMillis();
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(RedisConstants.SEARCH_INDEX_GROUP, consumerName),
                        StreamReadOptions.empty().count(20).block(Duration.ofSeconds(1)),
                        StreamOffset.create(RedisConstants.SEARCH_INDEX_STREAM_KEY, ReadOffset.lastConsumed()));
                long readMs = System.currentTimeMillis() - readStart;
                if (records == null || records.isEmpty()) {
                    continue;
                }
                log.info("search index poll read={}ms, records={}", readMs, records.size());
                for (MapRecord<String, Object, Object> record : records) {
                    Long activityId = parseLong(record.getValue().get("activityId"));
                    if (activityId == null) {
                        acknowledge(record);
                        continue;
                    }
                    boolean accepted = consumerExecutor.execute(activityId,
                            () -> process(record, activityId));
                    if (!accepted) {
                        // 分区队列满：剩余记录不 ACK，留 pending 由 XCLAIM 重试
                        break;
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    createGroupQuietly();
                }
                log.error("读取搜索索引事件失败", e);
                sleepQuietly(500);
            }
        }
    }

    /**
     * 定时接管空闲 pending，避免分区队列满或实例宕机导致事件永久滞留。
     */
    @Scheduled(fixedDelayString = "${hmdp.search.index-consumer.pending-scan-interval-ms:30000}")
    public void retryPending() {
        if (!running.get() || streamOps == null) {
            return;
        }
        try {
            PendingMessages pending = streamOps.pending(
                    RedisConstants.SEARCH_INDEX_STREAM_KEY, RedisConstants.SEARCH_INDEX_GROUP,
                    Range.unbounded(), 100);
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery()
                        .compareTo(Duration.ofSeconds(PENDING_IDLE_SECONDS)) < 0) {
                    continue;
                }
                List<ByteRecord> claimed = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                        connection.xClaim(STREAM_KEY_BYTES,
                                RedisConstants.SEARCH_INDEX_GROUP,
                                consumerName,
                                Duration.ofSeconds(PENDING_IDLE_SECONDS),
                                message.getId()));
                if (claimed == null) {
                    continue;
                }
                for (ByteRecord byteRecord : claimed) {
                    MapRecord<String, Object, Object> record = toMapRecord(byteRecord);
                    Long activityId = parseLong(record.getValue().get("activityId"));
                    if (activityId == null) {
                        acknowledge(record);
                        continue;
                    }
                    if (!consumerExecutor.execute(activityId, () -> process(record, activityId))) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("搜索索引 pending 重试失败", e);
        }
    }

    private void process(MapRecord<String, Object, Object> record, Long activityId) {
        long totalStart = System.currentTimeMillis();
        long dbMs = 0L;
        long indexMs = 0L;
        long ackMs = 0L;
        try {
            String eventType = String.valueOf(record.getValue().get("eventType"));
            if (SearchIndexEvent.DELETE.equals(eventType)) {
                long indexStart = System.currentTimeMillis();
                indexService.delete(activityId);
                indexMs = System.currentTimeMillis() - indexStart;
            } else {
                long dbStart = System.currentTimeMillis();
                Activity activity = activityService.getById(activityId);
                dbMs = System.currentTimeMillis() - dbStart;
                long indexStart = System.currentTimeMillis();
                if (activity == null || !Integer.valueOf(Activity.STATUS_ONLINE).equals(activity.getStatus())) {
                    indexService.delete(activityId);
                } else {
                    indexService.upsert(activity);
                }
                indexMs = System.currentTimeMillis() - indexStart;
            }
            long ackStart = System.currentTimeMillis();
            acknowledge(record);
            ackMs = System.currentTimeMillis() - ackStart;
            long totalMs = System.currentTimeMillis() - totalStart;
            log.info("search index timing activityId={}, partition={}, db={}ms, index={}ms, ack={}ms, total={}ms",
                    activityId, consumerExecutor.partition(activityId), dbMs, indexMs, ackMs, totalMs);
        } catch (Exception e) {
            // 不 ACK，保留 pending；索引可由全量重建兜底
            log.error("处理搜索索引事件失败，recordId={}, activityId={}",
                    record.getId(), activityId, e);
        }
    }

    private MapRecord<String, Object, Object> toMapRecord(ByteRecord byteRecord) {
        Map<Object, Object> value = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : byteRecord.getValue().entrySet()) {
            value.put(new String(entry.getKey(), StandardCharsets.UTF_8),
                    new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return StreamRecords.mapBacked(value)
                .withStreamKey(RedisConstants.SEARCH_INDEX_STREAM_KEY)
                .withId(byteRecord.getId());
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        try {
            streamOps.acknowledge(RedisConstants.SEARCH_INDEX_STREAM_KEY,
                    RedisConstants.SEARCH_INDEX_GROUP, record.getId());
        } catch (Exception e) {
            log.error("ACK 搜索索引事件失败，recordId={}", record.getId(), e);
        }
    }

    private void createGroupQuietly() {
        try {
            streamOps.createGroup(RedisConstants.SEARCH_INDEX_STREAM_KEY,
                    ReadOffset.from("0"), RedisConstants.SEARCH_INDEX_GROUP);
            log.info("创建搜索索引消费组成功");
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.info("搜索索引消费组已存在");
            } else {
                log.warn("创建搜索索引消费组失败，将在读取时重试: {}", e.getMessage());
            }
        }
    }

    private Long parseLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
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
