package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 广告模块配置。所有阈值均可通过 application.yaml 覆盖，便于回滚。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.ad")
public class AdProperties {

    private boolean enabled = true;

    /** Feed 广告固定插入位置（从 1 开始） */
    private int slotPositionA = 3;
    private int slotPositionB = 6;

    /** 竞价链路硬预算（毫秒） */
    private long auctionTimeoutMs = 50;
    private int maxCandidates = 100;

    /** 频控默认值，活动未配置时使用 */
    private int defaultDailyFrequency = 3;
    private int defaultMinIntervalSeconds = 300;
    private int anonymousIpDailyFrequency = 10;

    /** 预算 key 保留时间（秒） */
    private long budgetDailyTtlSeconds = 172800;

    /** CTR 先验/截断 */
    private double ctrMin = 0.001D;
    private double ctrMax = 0.900D;
    private double ctrPrior = 0.02D;
    private double ctrCategoryWeight = 0.45D;
    private double ctrUserCategoryWeight = 0.30D;
    private double ctrQualityWeight = 0.15D;
    private double ctrBidWeight = 0.10D;

    /** 延迟反馈窗口（秒） */
    private long conversionWindowSeconds = 86400L;

    /** 广告索引 embedding 限流（默认 10 条/秒） */
    private int indexEmbeddingRatePerSecond = 10;

    /** 广告索引独立消费线程池 */
    private int indexConsumerPartitions = 2;
    private int indexConsumerQueueCapacity = 100;
    private int indexEmbeddingTimeoutMs = 2000;

    /** 线程池参数（均可配置） */
    private int adRecallCorePoolSize = 10;
    private int adRecallMaxPoolSize = 20;
    private int adRecallQueueCapacity = 100;
    private int adBidCorePoolSize = 20;
    private int adBidMaxPoolSize = 40;
    private int adBidQueueCapacity = 200;
    private int adIndexCorePoolSize = 2;
    private int adIndexMaxPoolSize = 4;
    private int adIndexQueueCapacity = 200;
    private int adEventCorePoolSize = 2;
    private int adEventMaxPoolSize = 8;
    private int adEventQueueCapacity = 500;

    /** 竞价限流（固定窗口，简化实现） */
    private boolean rateLimitEnabled = true;
    private int rateLimitPerSecond = 100;
}
