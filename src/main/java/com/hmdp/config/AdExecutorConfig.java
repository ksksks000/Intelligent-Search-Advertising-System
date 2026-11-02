package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.Resource;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 广告专用线程池：召回、竞价、索引均与自然推荐线程池隔离。
 */
@Configuration
public class AdExecutorConfig {

    @Resource
    private AdProperties properties;

    @Bean("adRecallExecutor")
    public ThreadPoolTaskExecutor adRecallExecutor() {
        return executor("ad-recall-", properties.getAdRecallCorePoolSize(),
                properties.getAdRecallMaxPoolSize(), properties.getAdRecallQueueCapacity());
    }

    @Bean("adBidExecutor")
    public ThreadPoolTaskExecutor adBidExecutor() {
        return executor("ad-bid-", properties.getAdBidCorePoolSize(),
                properties.getAdBidMaxPoolSize(), properties.getAdBidQueueCapacity());
    }

    @Bean("adIndexExecutor")
    public ThreadPoolTaskExecutor adIndexExecutor() {
        return executor("ad-index-", properties.getAdIndexCorePoolSize(),
                properties.getAdIndexMaxPoolSize(), properties.getAdIndexQueueCapacity());
    }

    @Bean("adEventExecutor")
    public ThreadPoolTaskExecutor adEventExecutor() {
        return executor("ad-event-", properties.getAdEventCorePoolSize(),
                properties.getAdEventMaxPoolSize(), properties.getAdEventQueueCapacity());
    }

    private ThreadPoolTaskExecutor executor(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, core));
        executor.setMaxPoolSize(Math.max(Math.max(1, core), max));
        executor.setQueueCapacity(Math.max(1, queue));
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
