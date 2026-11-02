package com.hmdp.ad.service;

import com.hmdp.ad.entity.AdCampaign;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdBudgetServiceTest {

    private static final long CAMPAIGN_ID = 999001L;

    @Resource
    private AdBudgetService budgetService;
    @Resource
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.delete(AdBudgetService.totalKey(CAMPAIGN_ID));
        redis.delete(AdBudgetService.dailyKey(CAMPAIGN_ID, LocalDate.now()));
        redis.delete("ad:budget:idem:req-:bill-");
        redis.delete("ad:budget:idem:req-idem:bill-idem");
        redis.delete("ad:budget:idem:req-d1:bill-d1");
        redis.delete("ad:budget:idem:req-d2:bill-d2");
        redis.delete("ad:budget:idem:req-d3:bill-d3");
        for (int i = 0; i < 30; i++) {
            redis.delete("ad:budget:idem:req-" + i + ":bill-" + i);
            redis.delete("ad:budget:idem:uneq-" + i + ":uneq-bill-" + i);
            redis.delete("ad:budget:idem:last-" + i + ":last-bill-" + i);
        }
        redis.delete("ad:budget:idem:refund-req:refund-bill");
    }

    @Test
    @DisplayName("并发扣减不超支")
    void concurrentDeductNeverOverspends() throws Exception {
        reset(1000, 1000);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                AdBudgetService.DeductResult result = budgetService.tryDeduct(
                        campaign(1000, 1000), "req-" + index, "bill-" + index, 100);
                if (result.isSuccess()) {
                    success.incrementAndGet();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        System.out.printf("budget concurrent success=%d remaining=%d%n",
                success.get(), budgetService.remaining(CAMPAIGN_ID));
        assertEquals(10, success.get());
        assertEquals(0, budgetService.remaining(CAMPAIGN_ID));
    }

    @Test
    @DisplayName("幂等重试不重复扣减")
    void idempotentRetry() {
        reset(1000, 1000);
        AdBudgetService.DeductResult first = budgetService.tryDeduct(
                campaign(1000, 1000), "req-idem", "bill-idem", 100);
        AdBudgetService.DeductResult second = budgetService.tryDeduct(
                campaign(1000, 1000), "req-idem", "bill-idem", 100);
        assertTrue(first.isSuccess() && !first.isDuplicate());
        assertTrue(second.isDuplicate());
        assertEquals(900, budgetService.remaining(CAMPAIGN_ID));
        redis.delete("ad:budget:idem:req-idem:bill-idem");
    }

    @Test
    @DisplayName("日预算耗尽后，新一天 key 重建恢复")
    void dailyBudgetReset() {
        reset(1000, 100);
        assertTrue(budgetService.tryDeduct(campaign(1000, 100),
                "req-d1", "bill-d1", 100).isSuccess());
        AdBudgetService.DeductResult exhausted = budgetService.tryDeduct(campaign(1000, 100),
                "req-d2", "bill-d2", 100);
        assertEquals(-4, exhausted.getCode());
        redis.delete(AdBudgetService.dailyKey(CAMPAIGN_ID, LocalDate.now()));
        AdBudgetService.DeductResult reset = budgetService.tryDeduct(campaign(1000, 100),
                "req-d3", "bill-d3", 100);
        assertTrue(reset.isSuccess());
        redis.delete("ad:budget:idem:req-d1:bill-d1");
        redis.delete("ad:budget:idem:req-d2:bill-d2");
        redis.delete("ad:budget:idem:req-d3:bill-d3");
    }

    @Test
    @DisplayName("边界1：20线程随机30-150分，不超总预算")
    void unequalConcurrentDeduct() throws Exception {
        reset(1000, 1000);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicLong charged = new AtomicLong();
        Random random = new Random(20261101L);
        for (int i = 0; i < threads; i++) {
            final int index = i;
            final long cost = 30 + random.nextInt(121);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                AdBudgetService.DeductResult result = budgetService.tryDeduct(
                        campaign(1000, 1000), "uneq-" + index, "uneq-bill-" + index, cost);
                if (result.isSuccess()) {
                    success.incrementAndGet();
                    charged.addAndGet(cost);
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        long remaining = budgetService.remaining(CAMPAIGN_ID);
        System.out.printf("budget unequal success=%d charged=%d remaining=%d%n",
                success.get(), charged.get(), remaining);
        assertTrue(remaining >= 0);
        assertTrue(charged.get() <= 1000);
        assertEquals(1000 - charged.get(), remaining);
    }

    @Test
    @DisplayName("边界2：总预算100，两笔60并发，只有一笔成功")
    void lastBudgetRace() throws Exception {
        reset(100, 100);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            final int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                AdBudgetService.DeductResult result = budgetService.tryDeduct(
                        campaign(100, 100), "last-" + index, "last-bill-" + index, 60);
                if (result.isSuccess()) {
                    success.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        long remaining = budgetService.remaining(CAMPAIGN_ID);
        System.out.printf("budget last success=%d rejected=%d remaining=%d%n",
                success.get(), rejected.get(), remaining);
        assertEquals(1, success.get());
        assertEquals(1, rejected.get());
        // 100 - 一笔成功的 60 = 40；被拒请求未扣费。
        assertEquals(40, remaining);
    }

    @Test
    @DisplayName("边界3：扣减成功后落库异常，refund 回补余额")
    void refundAfterDbFailure() {
        reset(1000, 1000);
        AdBudgetService.DeductResult deducted = budgetService.tryDeduct(
                campaign(1000, 1000), "refund-req", "refund-bill", 100);
        assertTrue(deducted.isSuccess());
        assertEquals(900, budgetService.remaining(CAMPAIGN_ID));
        // 模拟随后 DB 落账抛异常，调用补偿。
        AdBudgetService.DeductResult refunded = budgetService.refund(
                campaign(1000, 1000), "refund-req", "refund-bill");
        System.out.printf("budget refund code=%d remaining=%d%n",
                refunded.getCode(), budgetService.remaining(CAMPAIGN_ID));
        assertEquals(1000, budgetService.remaining(CAMPAIGN_ID));
        // 幂等键已删除，重试可重新扣减。
        assertTrue(budgetService.tryDeduct(campaign(1000, 1000),
                "refund-req", "refund-bill", 100).isSuccess());
        assertEquals(900, budgetService.remaining(CAMPAIGN_ID));
    }

    private void reset(long total, long daily) {
        cleanup();
        redis.delete(AdBudgetService.totalKey(CAMPAIGN_ID));
        redis.delete(AdBudgetService.dailyKey(CAMPAIGN_ID, LocalDate.now()));
        redis.opsForValue().set(AdBudgetService.totalKey(CAMPAIGN_ID), String.valueOf(total));
        redis.opsForValue().set(AdBudgetService.dailyKey(CAMPAIGN_ID, LocalDate.now()),
                String.valueOf(daily));
    }

    private AdCampaign campaign(long total, long daily) {
        AdCampaign campaign = new AdCampaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setTotalBudget(total);
        campaign.setDailyBudget(daily);
        campaign.setBid(100L);
        return campaign;
    }
}
