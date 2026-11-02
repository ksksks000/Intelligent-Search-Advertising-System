package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索索引增量消费并发配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.search.index-consumer")
public class SearchIndexConsumerProperties {

    /** 分区数；同一 activityId 固定落到一个分区，保证单文档顺序 */
    private int partitions = 4;

    /** 每个分区的有界队列容量 */
    private int queueCapacity = 100;

    /** 分区线程空闲存活时间（秒） */
    private long keepAliveSeconds = 60;
}
