package com.hmdp.search.index;

import com.hmdp.config.SearchIndexConsumerProperties;
import lombok.extern.slf4j.Slf4j;
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
 * 搜索索引消费分区池：activityId hash 到固定单线程分区，保证同一文档顺序；
 * 每个分区有界队列，满时拒绝，由调用方保留 pending。
 */
@Slf4j
@Component
public class SearchIndexConsumerExecutor {

    private final ThreadPoolExecutor[] partitions;
    private final AtomicLong rejectedCount = new AtomicLong();

    public SearchIndexConsumerExecutor(SearchIndexConsumerProperties properties) {
        int count = Math.max(1, properties.getPartitions());
        int queueCapacity = Math.max(1, properties.getQueueCapacity());
        this.partitions = new ThreadPoolExecutor[count];
        for (int i = 0; i < count; i++) {
            final int index = i;
            AtomicInteger threadNo = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable,
                        "search-index-" + index + "-" + threadNo.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            };
            partitions[i] = new ThreadPoolExecutor(
                    1, 1,
                    Math.max(1, properties.getKeepAliveSeconds()), TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    factory,
                    (runnable, pool) -> {
                        rejectedCount.incrementAndGet();
                        throw new RejectedExecutionException("search index partition queue full");
                    });
        }
    }

    public int partition(Long activityId) {
        int hash = activityId == null ? 0 : activityId.hashCode();
        return Math.floorMod(hash, partitions.length);
    }

    /**
     * @return true 已提交；false 分区队列已满，调用方不得 ACK
     */
    public boolean execute(Long activityId, Runnable task) {
        int partition = partition(activityId);
        try {
            partitions[partition].execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("搜索索引消费队列已满，保留 pending，activityId={}, partition={}",
                    activityId, partition);
            return false;
        }
    }

    public int getQueueSize() {
        int total = 0;
        for (ThreadPoolExecutor partition : partitions) {
            total += partition.getQueue().size();
        }
        return total;
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }

    @PreDestroy
    public void shutdown() {
        for (ThreadPoolExecutor partition : partitions) {
            partition.shutdown();
        }
        for (ThreadPoolExecutor partition : partitions) {
            try {
                if (!partition.awaitTermination(10, TimeUnit.SECONDS)) {
                    partition.shutdownNow();
                }
            } catch (InterruptedException e) {
                partition.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
