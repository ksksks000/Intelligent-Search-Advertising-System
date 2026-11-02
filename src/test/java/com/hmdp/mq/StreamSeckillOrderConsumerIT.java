package com.hmdp.mq;

import com.hmdp.config.SeckillConsumerProperties;
import com.hmdp.config.SeckillStreamProperties;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.service.SeckillStockService;
import com.hmdp.service.impl.SeckillFailureService;
import com.hmdp.service.impl.SeckillOrderConsumeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 集成测试：真实 Redis Stream，mock 业务落库，验证 ACK、pending 重试、死信。
 * 类名为 *IT，Surefire 默认不跑。
 */
@DataRedisTest
@ContextConfiguration(classes = StreamSeckillOrderConsumerIT.TestConfig.class)
class StreamSeckillOrderConsumerIT {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {
    }

    @Autowired
    private StringRedisTemplate redis;

    private SeckillStreamProperties properties;
    private SeckillConsumerProperties consumerProperties;
    private SeckillConsumerExecutor consumerExecutor;
    private SeckillOrderConsumeService consumeService;
    private SeckillStockService seckillStockService;
    private SeckillFailureService failureService;
    private SeckillMetrics metrics;
    private StreamSeckillOrderConsumer consumer;
    private String streamKey;
    private String deadKey;
    private String group;

    @BeforeEach
    void setUp() {
        long id = System.nanoTime();
        streamKey = "seckill:orders:it:stream:" + id;
        deadKey = streamKey + ":dead";
        group = "group-" + id;

        properties = new SeckillStreamProperties();
        properties.setStreamKey(streamKey);
        properties.setDeadKey(deadKey);
        properties.setGroup(group);
        properties.setConcurrency(1);
        properties.setBatchSize(10);
        properties.setBlockMillis(200);
        properties.setPendingMinIdleSeconds(1);
        properties.setMaxRetry(2);
        properties.setConsumerPrefix("it");

        consumeService = mock(SeckillOrderConsumeService.class);
        seckillStockService = mock(SeckillStockService.class);
        failureService = mock(SeckillFailureService.class);

        consumerProperties = new SeckillConsumerProperties();
        consumerProperties.setCorePoolSize(2);
        consumerProperties.setMaxPoolSize(4);
        consumerProperties.setQueueCapacity(100);
        consumerProperties.setKeepAliveSeconds(10);
        consumerProperties.setDynamicEnabled(false);
        consumerProperties.setBackpressureSleepMillis(20);
        consumerExecutor = new SeckillConsumerExecutor(consumerProperties, new SimpleMeterRegistry());
        metrics = new SeckillMetrics(new SimpleMeterRegistry());

        consumer = new StreamSeckillOrderConsumer();
        ReflectionTestUtils.setField(consumer, "redis", redis);
        ReflectionTestUtils.setField(consumer, "consumeService", consumeService);
        ReflectionTestUtils.setField(consumer, "seckillStockService", seckillStockService);
        ReflectionTestUtils.setField(consumer, "properties", properties);
        ReflectionTestUtils.setField(consumer, "consumerExecutor", consumerExecutor);
        ReflectionTestUtils.setField(consumer, "consumerProperties", consumerProperties);
        ReflectionTestUtils.setField(consumer, "failureService", failureService);
        ReflectionTestUtils.setField(consumer, "metrics", metrics);
        consumer.start();
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.stop();
        }
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
        }
        redis.delete(streamKey);
        redis.delete(deadKey);
    }

    @Test
    @DisplayName("消费成功：XACK 后 pending 归零")
    void success_acknowledges() throws Exception {
        when(consumeService.handle(any())).thenReturn(ConsumeResult.SUCCESS);
        produce("1001");

        verify(consumeService, timeout(5000)).handle(any());
        await(Duration.ofSeconds(5), () -> pendingCount() == 0);
    }

    @Test
    @DisplayName("消费失败：留在 pending，超时后 XCLAIM 重试并最终 ACK")
    void retry_claimsPending() throws Exception {
        when(consumeService.handle(any())).thenReturn(ConsumeResult.RETRY, ConsumeResult.SUCCESS);
        produce("1002");

        verify(consumeService, timeout(5000)).handle(any());
        await(Duration.ofSeconds(5), () -> pendingCount() == 1);

        Thread.sleep(1200);
        consumer.retryPending();

        verify(consumeService, timeout(5000).times(2)).handle(any());
        await(Duration.ofSeconds(5), () -> pendingCount() == 0);
    }

    @Test
    @DisplayName("业务死信：写入死信流并 ACK")
    void deadLetter_acknowledges() throws Exception {
        when(consumeService.handle(any())).thenReturn(ConsumeResult.DEAD);
        produce("1003");

        await(Duration.ofSeconds(5), () -> deadCount() == 1);
        await(Duration.ofSeconds(5), () -> pendingCount() == 0);
    }

    @Test
    @DisplayName("字段缺失：直接进入死信流，不调用业务消费")
    void malformed_deadLetter() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("segment", "1");
        redis.opsForStream().add(streamKey, fields);

        await(Duration.ofSeconds(5), () -> deadCount() == 1);
        await(Duration.ofSeconds(5), () -> pendingCount() == 0);
        verifyNoInteractions(consumeService);
    }

    private void produce(String requestId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestId", requestId);
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("segment", "1");
        fields.put("createTime", String.valueOf(System.currentTimeMillis()));
        redis.opsForStream().add(streamKey, fields);
    }

    private long pendingCount() {
        PendingMessagesSummary summary = redis.opsForStream().pending(streamKey, group);
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    private long deadCount() {
        Long size = redis.opsForStream().size(deadKey);
        return size == null ? 0 : size;
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("条件在 " + timeout + " 内未满足");
    }
}
