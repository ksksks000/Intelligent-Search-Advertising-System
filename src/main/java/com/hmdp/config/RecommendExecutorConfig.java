package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 推荐专用线程池：召回与业务线程池隔离。
 */
@Configuration
public class RecommendExecutorConfig {

    @Value("${hmdp.recommend.recall-core-pool-size:20}")
    private int recallCorePoolSize;
    @Value("${hmdp.recommend.recall-max-pool-size:40}")
    private int recallMaxPoolSize;
    @Value("${hmdp.recommend.recall-queue-capacity:200}")
    private int recallQueueCapacity;

    @Bean("recallExecutor")
    public ThreadPoolTaskExecutor recallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(recallCorePoolSize);
        executor.setMaxPoolSize(recallMaxPoolSize);
        executor.setQueueCapacity(recallQueueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rec-recall-");
        // 召回是短任务；队列满时快速失败，由 Feed 编排层标记降级
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean("recEventExecutor")
    public ThreadPoolTaskExecutor recEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rec-event-");
        // 埋点不允许静默丢失；队列满时由调用线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
