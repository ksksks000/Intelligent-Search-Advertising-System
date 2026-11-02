package com.hmdp.search.index;

import com.hmdp.config.SearchIndexConsumerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchIndexConsumerExecutorTest {

    @Test
    @DisplayName("同一 activityId 固定分区且串行保序")
    void sameActivityOrdered() throws Exception {
        SearchIndexConsumerProperties properties = new SearchIndexConsumerProperties();
        properties.setPartitions(2);
        properties.setQueueCapacity(10);
        SearchIndexConsumerExecutor executor = new SearchIndexConsumerExecutor(properties);
        try {
            Long activityId = 12345L;
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(2);
            assertTrue(executor.execute(activityId, () -> {
                sleep(100);
                order.add(1);
                done.countDown();
            }));
            assertTrue(executor.execute(activityId, () -> {
                order.add(2);
                done.countDown();
            }));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(1, 2), order);
            assertEquals(executor.partition(activityId), executor.partition(activityId));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("分区队列满时返回 false，调用方保留 pending")
    void rejectsWhenQueueFull() throws Exception {
        SearchIndexConsumerProperties properties = new SearchIndexConsumerProperties();
        properties.setPartitions(1);
        properties.setQueueCapacity(1);
        SearchIndexConsumerExecutor executor = new SearchIndexConsumerExecutor(properties);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Runnable blocker = () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            assertTrue(executor.execute(1L, blocker));
            assertTrue(executor.execute(1L, blocker));
            assertFalse(executor.execute(1L, blocker));
            assertEquals(1L, executor.getRejectedCount());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
