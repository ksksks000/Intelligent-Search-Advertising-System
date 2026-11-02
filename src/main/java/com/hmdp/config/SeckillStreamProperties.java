package com.hmdp.config;

import com.hmdp.utils.RedisConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀 Redis Stream 消费配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.seckill.stream")
public class SeckillStreamProperties {

    /** 订单流 key */
    private String streamKey = RedisConstants.SECKILL_ORDER_STREAM_KEY;

    /** 消费组 */
    private String group = RedisConstants.SECKILL_ORDER_GROUP;

    /** 死信流 key */
    private String deadKey = RedisConstants.SECKILL_ORDER_DEAD_KEY;

    /** 每个实例的消费线程数（同一消费组内并行） */
    private int concurrency = 4;

    /** 单次 XREADGROUP 拉取条数 */
    private int batchSize = 10;

    /** XREADGROUP 阻塞时长（毫秒） */
    private long blockMillis = 2000;

    /** pending 消息空闲多久后允许其他消费者接管（秒） */
    private long pendingMinIdleSeconds = 30;

    /** pending 扫描间隔（毫秒） */
    private long pendingScanIntervalMs = 30000;

    /** 最大重试次数，超过后进入死信流 */
    private int maxRetry = 3;

    /** 流最大长度（近似裁剪），防止无限增长 */
    private long maxLen = 1000000L;

    /** consumer 名称前缀 */
    private String consumerPrefix = "hmdp";
}
