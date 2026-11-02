package com.hmdp.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀入口令牌桶限流配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** 限流 key 的 TTL（秒），避免冷用户 key 常驻 */
    private long ttlSeconds = 60;

    private Dimension user = new Dimension(10, 5);

    private Dimension ip = new Dimension(50, 20);

    private Dimension api = new Dimension(100, 50);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Dimension {
        /** 桶容量；<=0 表示关闭该维度 */
        private int capacity;
        /** 每秒补充令牌数 */
        private double refillRate;
    }
}
