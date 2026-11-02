package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.annotation.Resource;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ProfileExecutorConfig {

    @Resource
    private ProfileProperties properties;

    @Bean("profileExecutor")
    public ThreadPoolTaskExecutor profileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, properties.getExecutorCoreSize()));
        executor.setMaxPoolSize(Math.max(properties.getExecutorCoreSize(),
                properties.getExecutorMaxSize()));
        executor.setQueueCapacity(Math.max(1, properties.getExecutorQueueCapacity()));
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("profile-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
