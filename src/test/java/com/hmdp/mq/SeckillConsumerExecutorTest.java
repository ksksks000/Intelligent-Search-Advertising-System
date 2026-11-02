package com.hmdp.mq;

import com.hmdp.config.SeckillConsumerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试：验证线程池有界、队列满快速拒绝、背压判断。
 */
class SeckillConsumerExecutorTest {

    @Test
    @DisplayName("有界队列：核心/最大线程与队列都满后快速拒绝")
    void rejectsWhenSaturated() throws Exception {
        SeckillConsumerProperties properties = new SeckillConsumerProperties();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(2);
        properties.setQueueCapacity(1);
        properties.setKeepAliveSeconds(10);
        properties.setDynamicEnabled(false);
        SeckillConsumerExecutor executor = new SeckillConsumerExecutor(properties, new SimpleMeterRegistry());

        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable task = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            executor.execute(task); // 核心线程
            executor.execute(task); // 进入队列
            executor.execute(task); // 扩容到最大线程

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(1, executor.getQueueSize());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(task));
            assertEquals(1, executor.getRejectedCount());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("背压判断：队列达到高水位返回 saturated")
    void isSaturated() throws Exception {
        SeckillConsumerProperties properties = new SeckillConsumerProperties();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(10);
        properties.setQueueHighWatermark(0.5);
        properties.setKeepAliveSeconds(10);
        properties.setDynamicEnabled(false);
        SeckillConsumerExecutor executor = new SeckillConsumerExecutor(properties, new SimpleMeterRegistry());

        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            executor.execute(blocker); // 占满唯一的线程
            for (int i = 0; i < 5; i++) {
                executor.execute(blocker);
            }
            assertTrue(executor.isSaturated());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
