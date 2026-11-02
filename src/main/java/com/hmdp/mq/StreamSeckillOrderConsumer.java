package com.hmdp.mq;

import com.hmdp.config.SeckillConsumerProperties;
import com.hmdp.config.SeckillStreamProperties;
import com.hmdp.filter.TraceIdFilter;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.service.SeckillStockService;
import com.hmdp.service.impl.SeckillFailureService;
import com.hmdp.service.impl.SeckillOrderConsumeService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费端：消费组 + XREADGROUP + ACK + XPENDING/XCLAIM + 死信流。
 * <p>
 * 可靠性语义：
 * <ul>
 *     <li>处理成功才 ACK；失败不 ACK，消息保留在 pending</li>
 *     <li>定时扫描 pending，空闲超过阈值后 XCLAIM 重试</li>
 *     <li>超过最大重试次数进入死信流，人工/对账任务处理</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "stream", matchIfMissing = true)
public class StreamSeckillOrderConsumer {

    private static final String FIELD_REQUEST_ID = "requestId";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_VOUCHER_ID = "voucherId";
    private static final String FIELD_SEGMENT = "segment";
    private static final String FIELD_CREATE_TIME = "createTime";

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private SeckillOrderConsumeService consumeService;
    @Resource
    private SeckillStockService seckillStockService;
    @Resource
    private SeckillStreamProperties properties;
    @Resource
    private SeckillConsumerExecutor consumerExecutor;
    @Resource
    private SeckillConsumerProperties consumerProperties;
    @Resource
    private SeckillFailureService failureService;
    @Resource
    private SeckillMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private StreamOperations<String, Object, Object> streamOps;
    private ExecutorService pollers;
    private String consumerPrefix;

    @PostConstruct
    public void start() {
        streamOps = redis.opsForStream();
        consumerPrefix = properties.getConsumerPrefix() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        createGroupQuietly();
        running.set(true);

        int concurrency = Math.max(1, properties.getConcurrency());
        pollers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "seckill-stream-consumer");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < concurrency; i++) {
            final String consumerName = consumerPrefix + "-" + i;
            pollers.execute(() -> pollLoop(consumerName));
        }
        log.info("秒杀 Stream 消费者启动，stream={}, group={}, concurrency={}",
                properties.getStreamKey(), properties.getGroup(), concurrency);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollers != null) {
            pollers.shutdownNow();
            try {
                pollers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("秒杀 Stream 消费者已停止");
    }

    @Scheduled(fixedDelayString = "${hmdp.seckill.stream.pending-scan-interval-ms:30000}")
    public void retryPending() {
        if (!running.get() || streamOps == null) {
            return;
        }
        try {
            PendingMessages pending = streamOps.pending(
                    properties.getStreamKey(), properties.getGroup(),
                    Range.unbounded(), Math.max(1L, properties.getBatchSize() * 10L));
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery()
                        .compareTo(Duration.ofSeconds(properties.getPendingMinIdleSeconds())) < 0) {
                    continue;
                }
                List<ByteRecord> claimed = redis.execute((RedisCallback<List<ByteRecord>>) connection ->
                        connection.xClaim(
                                properties.getStreamKey().getBytes(StandardCharsets.UTF_8),
                                properties.getGroup(),
                                consumerPrefix + "-retry",
                                Duration.ofSeconds(properties.getPendingMinIdleSeconds()),
                                message.getId()));
                if (claimed == null) {
                    continue;
                }
                for (ByteRecord byteRecord : claimed) {
                    if (!submit(toMapRecord(byteRecord), message.getTotalDeliveryCount())) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("pending 扫描失败，group={}", properties.getGroup(), e);
        }
    }

    private void pollLoop(String consumerName) {
        while (running.get()) {
            try {
                // 背压：队列接近高水位时暂停拉取，避免 pending / 内存无限增长
                if (consumerExecutor.isSaturated()) {
                    sleepQuietly(consumerProperties.getBackpressureSleepMillis());
                    continue;
                }
                List<MapRecord<String, Object, Object>> records = streamOps.read(
                        Consumer.from(properties.getGroup(), consumerName),
                        StreamReadOptions.empty()
                                .count(Math.max(1, properties.getBatchSize()))
                                .block(Duration.ofMillis(Math.max(1L, properties.getBlockMillis()))),
                        StreamOffset.create(properties.getStreamKey(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    if (!submit(record, 1L)) {
                        // 线程池已满：剩余消息不 ACK，留在 pending，由 XCLAIM 重试
                        break;
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                if (contains(e, "NOGROUP")) {
                    createGroupQuietly();
                }
                log.error("读取秒杀 Stream 失败，consumer={}", consumerName, e);
                sleepQuietly();
            }
        }
    }

    private boolean submit(MapRecord<String, Object, Object> record, long deliveryCount) {
        try {
            consumerExecutor.execute(() -> process(record, deliveryCount));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("消费线程池已满，记录保留 pending 等待重试，recordId={}", record.getId());
            sleepQuietly(consumerProperties.getBackpressureSleepMillis());
            return false;
        }
    }

    private void process(MapRecord<String, Object, Object> record, long deliveryCount) {
        SeckillOrderMessage message = toMessage(record);
        String traceId = message == null ? "record-" + record.getId() : "req-" + message.getRequestId();
        String previousTraceId = MDC.get(TraceIdFilter.TRACE_ID);
        MDC.put(TraceIdFilter.TRACE_ID, traceId);
        try {
            if (message == null) {
                moveToDead(record, "字段缺失或解析失败");
                return;
            }
            if (deliveryCount > properties.getMaxRetry()) {
                moveToDead(record, "超过最大重试次数: " + deliveryCount);
                return;
            }

            ConsumeResult result;
            try {
                result = consumeService.handle(message);
            } catch (Exception e) {
                log.error("消费异常，requestId={}", message.getRequestId(), e);
                result = ConsumeResult.RETRY;
            }
            metrics.consume(result);

            switch (result) {
                case SUCCESS:
                    acknowledge(record);
                    break;
                case DUPLICATE:
                    rollbackDuplicate(message);
                    acknowledge(record);
                    break;
                case DEAD:
                    moveToDead(record, "业务判定为死信");
                    break;
                case RETRY:
                default:
                    log.warn("消费失败，保留 pending 等待重试，requestId={}, deliveryCount={}",
                            message.getRequestId(), deliveryCount);
                    break;
            }
        } finally {
            if (previousTraceId == null) {
                MDC.remove(TraceIdFilter.TRACE_ID);
            } else {
                MDC.put(TraceIdFilter.TRACE_ID, previousTraceId);
            }
        }
    }

    private void rollbackDuplicate(SeckillOrderMessage message) {
        try {
            seckillStockService.rollback(message.getVoucherId(), message.getUserId(),
                    String.valueOf(message.getRequestId()), message.getSegment(), false);
        } catch (Exception e) {
            log.error("重复订单库存回滚失败，requestId={}", message.getRequestId(), e);
        }
    }

    private MapRecord<String, Object, Object> toMapRecord(ByteRecord byteRecord) {
        Map<Object, Object> value = new LinkedHashMap<>();
        for (Map.Entry<byte[], byte[]> entry : byteRecord.getValue().entrySet()) {
            value.put(new String(entry.getKey(), StandardCharsets.UTF_8),
                    new String(entry.getValue(), StandardCharsets.UTF_8));
        }
        return StreamRecords.mapBacked(value)
                .withStreamKey(properties.getStreamKey())
                .withId(byteRecord.getId());
    }

    private SeckillOrderMessage toMessage(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> value = record.getValue();
            Object requestId = value.get(FIELD_REQUEST_ID);
            Object userId = value.get(FIELD_USER_ID);
            Object voucherId = value.get(FIELD_VOUCHER_ID);
            Object segment = value.get(FIELD_SEGMENT);
            Object createTime = value.get(FIELD_CREATE_TIME);
            if (requestId == null || userId == null || voucherId == null || segment == null) {
                return null;
            }
            return new SeckillOrderMessage(
                    Long.parseLong(String.valueOf(requestId)),
                    Long.parseLong(String.valueOf(userId)),
                    Long.parseLong(String.valueOf(voucherId)),
                    Integer.parseInt(String.valueOf(segment)),
                    createTime == null ? null : Long.parseLong(String.valueOf(createTime)));
        } catch (Exception e) {
            log.error("解析秒杀消息失败，recordId={}, value={}", record.getId(), record.getValue(), e);
            return null;
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        try {
            streamOps.acknowledge(properties.getStreamKey(), properties.getGroup(), record.getId());
        } catch (Exception e) {
            log.error("ACK 失败，recordId={}", record.getId(), e);
        }
    }

    private void moveToDead(MapRecord<String, Object, Object> record, String reason) {
        try {
            // 先做订单确认与库存回滚，再写死信流；失败表用于人工兜底
            failureService.handleDead(toMessage(record), reason);
        } catch (Exception e) {
            log.error("死信补偿处理失败，recordId={}", record.getId(), e);
        }
        try {
            Map<String, String> dead = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
                dead.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            dead.put("error", reason);
            dead.put("failedAt", String.valueOf(System.currentTimeMillis()));
            streamOps.add(properties.getDeadKey(), dead);
            acknowledge(record);
            metrics.deadLetter();
            log.error("消息进入死信流，recordId={}, reason={}", record.getId(), reason);
        } catch (Exception e) {
            log.error("写入死信流失败，recordId={}", record.getId(), e);
        }
    }

    private void createGroupQuietly() {
        try {
            streamOps.createGroup(properties.getStreamKey(), ReadOffset.from("0"), properties.getGroup());
            log.info("创建秒杀消费组成功，stream={}, group={}",
                    properties.getStreamKey(), properties.getGroup());
        } catch (Exception e) {
            if (contains(e, "BUSYGROUP")) {
                log.info("秒杀消费组已存在，stream={}, group={}",
                        properties.getStreamKey(), properties.getGroup());
            } else {
                log.warn("创建秒杀消费组失败，将在消费时重试: {}", e.getMessage());
            }
        }
    }

    private static boolean contains(Throwable throwable, String text) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(text)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly() {
        sleepQuietly(1000L);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
