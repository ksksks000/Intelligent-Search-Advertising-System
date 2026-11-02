package com.hmdp.service;

import com.hmdp.config.SeckillProperties;
import com.hmdp.config.SeckillStreamProperties;
import com.hmdp.dto.SeckillResult;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.enums.SeckillCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：连真实 Redis，验证分段初始化、并发不超卖、一人一单、时间窗、回滚幂等。
 * 类名为 *IT，Surefire 默认不跑，需要手动指定或交给 Failsafe。
 */
@DataRedisTest
@ContextConfiguration(classes = SeckillStockServiceIT.TestConfig.class)
class SeckillStockServiceIT {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {
    }

    @Autowired
    private StringRedisTemplate redis;

    private SeckillStockService service;
    private SeckillProperties properties;
    private SeckillStreamProperties streamProperties;
    private Long voucherId;

    @BeforeEach
    void setUp() {
        voucherId = System.nanoTime();
        properties = new SeckillProperties();
        properties.setSegmentCount(8);
        properties.setMetaTtlBufferSeconds(3600);
        // 默认用 rabbit 模式，避免每个用例都写 Stream；流模式单独覆盖
        properties.setMq("rabbit");
        streamProperties = new SeckillStreamProperties();
        streamProperties.setStreamKey("seckill:orders:it:" + voucherId);
        service = new SeckillStockService(redis, properties, streamProperties);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(keysOf(voucherId));
    }

    @Test
    @DisplayName("初始化幂等：重复调用只初始化一次")
    void init_isIdempotent() {
        SeckillVoucher voucher = voucher(20);
        assertEquals(1, service.initIfAbsent(voucher));
        assertEquals(0, service.initIfAbsent(voucher));
    }

    @Test
    @DisplayName("并发扣减不超卖：50 库存 100 用户，恰好 50 人成功")
    void concurrentDeduction_noOversell() throws Exception {
        int stock = 50;
        int users = 100;
        assertEquals(1, service.initIfAbsent(voucher(stock)));

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        AtomicInteger success = new AtomicInteger();
        for (int i = 1; i <= users; i++) {
            final long userId = i;
            pool.execute(() -> {
                try {
                    start.await();
                    SeckillResult result = service.trySeckill(voucherId, userId, System.nanoTime());
                    if (result.isSuccess()) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发扣减超时");
        pool.shutdown();

        assertEquals(stock, success.get(), "成功人数必须等于库存");
        assertEquals("0", redis.opsForValue().get(SeckillStockService.totalKey(voucherId)));
        assertNotNull(redis.opsForValue().get(SeckillStockService.soldoutKey(voucherId)));

        int segmentSum = 0;
        for (int i = 1; i <= properties.getSegmentCount(); i++) {
            String value = redis.opsForValue().get(SeckillStockService.segmentKey(voucherId, i));
            segmentSum += value == null ? 0 : Integer.parseInt(value);
        }
        assertEquals(0, segmentSum, "段库存之和必须为 0，不能出现负数或超卖");
    }

    @Test
    @DisplayName("一人一单：同一用户第二次返回重复下单")
    void duplicateUser_rejected() {
        assertEquals(1, service.initIfAbsent(voucher(10)));
        SeckillResult first = service.trySeckill(voucherId, 1L, 1001L);
        SeckillResult second = service.trySeckill(voucherId, 1L, 1002L);

        assertTrue(first.isSuccess());
        assertEquals(SeckillCode.DUPLICATE, second.getCode());
    }

    @Test
    @DisplayName("时间窗：未开始/已结束在 Lua 内快速失败")
    void timeWindow_rejected() {
        SeckillVoucher notStarted = voucher(10);
        notStarted.setBeginTime(LocalDateTime.now().plusHours(1));
        notStarted.setEndTime(LocalDateTime.now().plusHours(2));
        assertEquals(1, service.initIfAbsent(notStarted));
        assertEquals(SeckillCode.NOT_STARTED,
                service.trySeckill(voucherId, 1L, 1L).getCode());

        cleanUp();
        voucherId = System.nanoTime();
        SeckillVoucher ended = voucher(10);
        ended.setBeginTime(LocalDateTime.now().minusHours(2));
        ended.setEndTime(LocalDateTime.now().minusHours(1));
        assertEquals(1, service.initIfAbsent(ended));
        assertEquals(SeckillCode.ENDED,
                service.trySeckill(voucherId, 1L, 1L).getCode());
    }

    @Test
    @DisplayName("回滚幂等：库存与购买标记恢复，重复回滚不重复加库存")
    void rollback_restoresOnce() {
        assertEquals(1, service.initIfAbsent(voucher(10)));
        SeckillResult result = service.trySeckill(voucherId, 1L, 2001L);
        assertTrue(result.isSuccess());
        assertEquals("9", redis.opsForValue().get(SeckillStockService.totalKey(voucherId)));

        assertTrue(service.rollback(voucherId, 1L, "2001", result.getSegment()));
        assertEquals("10", redis.opsForValue().get(SeckillStockService.totalKey(voucherId)));
        assertFalse(redis.opsForSet().isMember(SeckillStockService.orderKey(voucherId), "1"));

        assertFalse(service.rollback(voucherId, 1L, "2001", result.getSegment()));
        assertEquals("10", redis.opsForValue().get(SeckillStockService.totalKey(voucherId)));
    }

    @Test
    @DisplayName("Stream 模式：资格通过后 Lua 原子写入订单消息")
    void streamMode_enqueuesMessage() {
        properties.setMq("stream");
        assertEquals(1, service.initIfAbsent(voucher(10)));

        SeckillResult result = service.trySeckill(voucherId, 7L, 777L);
        assertTrue(result.isSuccess());

        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(streamProperties.getStreamKey(), Range.unbounded());
        assertNotNull(records);
        assertEquals(1, records.size());
        Map<Object, Object> value = records.get(0).getValue();
        assertEquals("777", String.valueOf(value.get("requestId")));
        assertEquals("7", String.valueOf(value.get("userId")));
        assertEquals(String.valueOf(voucherId), String.valueOf(value.get("voucherId")));
        assertEquals(String.valueOf(result.getSegment()), String.valueOf(value.get("segment")));
    }

    private SeckillVoucher voucher(int stock) {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(voucherId);
        voucher.setStock(stock);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(1));
        voucher.setEndTime(LocalDateTime.now().plusHours(1));
        return voucher;
    }

    private List<String> keysOf(Long id) {
        List<String> keys = new ArrayList<>();
        keys.add(SeckillStockService.metaKey(id));
        keys.add(SeckillStockService.totalKey(id));
        keys.add(SeckillStockService.soldoutKey(id));
        keys.add(SeckillStockService.cursorKey(id));
        keys.add(SeckillStockService.rollbackKey(id));
        keys.add(SeckillStockService.orderKey(id));
        keys.add(SeckillStockService.legacyStockKey(id));
        keys.add(streamProperties.getStreamKey());
        for (int i = 1; i <= properties.getSegmentCount(); i++) {
            keys.add(SeckillStockService.segmentKey(id, i));
        }
        return keys;
    }
}
