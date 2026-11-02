package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀消费线程池配置：有界队列 + 动态扩容/缩容 + 背压。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.seckill.consumer")
public class SeckillConsumerProperties {

    /** 核心线程数 */
    private int corePoolSize = 8;

    /** 最大线程数 */
    private int maxPoolSize = 32;

    /** 有界队列容量，禁止无界队列 */
    private int queueCapacity = 2000;

    /** 非核心线程空闲存活时间（秒） */
    private int keepAliveSeconds = 60;

    /** 是否开启动态调整 */
    private boolean dynamicEnabled = true;

    /** 动态调整间隔（毫秒） */
    private long adjustIntervalMs = 5000;

    /** 队列高水位，达到后扩容 */
    private double queueHighWatermark = 0.8;

    /** 队列低水位，低于后缩容 */
    private double queueLowWatermark = 0.2;

    /** 缩容下限 */
    private int minCorePoolSize = 4;

    /** 扩容上限 */
    private int maxPoolLimit = 64;

    /** 背压时读取线程休眠时间（毫秒） */
    private long backpressureSleepMillis = 100;
}
