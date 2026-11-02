package com.hmdp.mq;

import com.hmdp.config.SeckillConsumerProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀消费专用线程池：与缓存重建等业务线程池隔离，有界队列，队列满时快速拒绝，
 * 由调用方保留消息 pending 实现背压与可重试。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "stream", matchIfMissing = true)
public class SeckillConsumerExecutor {

    private final SeckillConsumerProperties properties;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCount = new AtomicLong();

    public SeckillConsumerExecutor(SeckillConsumerProperties properties, MeterRegistry registry) {
        this.properties = properties;
        int core = Math.max(1, properties.getCorePoolSize());
        int max = Math.max(core, properties.getMaxPoolSize());
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable,
                    "seckill-consume-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                core,
                max,
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                threadFactory,
                (runnable, pool) -> {
                    rejectedCount.incrementAndGet();
                    throw new RejectedExecutionException("seckill consumer pool saturated");
                });

        Gauge.builder("seckill.pool.active", executor, e -> e.getActiveCount()).register(registry);
        Gauge.builder("seckill.pool.queue", executor, e -> e.getQueue().size()).register(registry);
        Gauge.builder("seckill.pool.size", executor, e -> e.getPoolSize()).register(registry);
        Gauge.builder("seckill.pool.rejected", rejectedCount, AtomicLong::get).register(registry);
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    /**
     * 队列达到高水位时，读取线程应暂停拉取，避免 pending/内存无限增长。
     */
    public boolean isSaturated() {
        int threshold = (int) (properties.getQueueCapacity() * properties.getQueueHighWatermark());
        return executor.getQueue().size() >= Math.max(1, threshold);
    }

    /**
     * 根据队列深度动态调整 core/max，参数全部来自配置。
     */
    @Scheduled(fixedDelayString = "${hmdp.seckill.consumer.adjust-interval-ms:5000}")
    public void adjust() {
        if (!properties.isDynamicEnabled()) {
            return;
        }
        int capacity = Math.max(1, properties.getQueueCapacity());
        int queueSize = executor.getQueue().size();
        double ratio = (double) queueSize / capacity;
        int core = executor.getCorePoolSize();
        int max = executor.getMaximumPoolSize();

        if (ratio >= properties.getQueueHighWatermark() && max < properties.getMaxPoolLimit()) {
            int newMax = Math.min(properties.getMaxPoolLimit(), max + Math.max(1, core / 2));
            int newCore = Math.min(newMax, core + 1);
            executor.setMaximumPoolSize(newMax);
            executor.setCorePoolSize(newCore);
            log.info("秒杀消费线程池扩容 core={}->{}, max={}->{}, queue={}/{}",
                    core, newCore, max, newMax, queueSize, capacity);
        } else if (ratio <= properties.getQueueLowWatermark() && core > properties.getMinCorePoolSize()) {
            int newCore = Math.max(properties.getMinCorePoolSize(), core - 1);
            executor.setCorePoolSize(newCore);
            log.info("秒杀消费线程池缩容 core={}->{}, queue={}/{}", core, newCore, queueSize, capacity);
        }
    }

    public int getActiveCount() {
        return executor.getActiveCount();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public int getPoolSize() {
        return executor.getPoolSize();
    }

    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
