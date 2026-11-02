package com.hmdp.ad.service;

import com.hmdp.config.AdProperties;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 竞价接口固定窗口限流：按 user/IP 维度，key 前缀 ad:rate:。
 */
@Slf4j
@Service
public class AdRateLimiter {

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private AdProperties properties;

    public boolean tryAcquire(Long userId, String ip) {
        if (!properties.isRateLimitEnabled()) {
            return true;
        }
        String dimension = userId == null ? "ip:" + (ip == null ? "unknown" : ip)
                : "u:" + userId;
        long second = System.currentTimeMillis() / 1000L;
        String key = RedisConstants.AD_RATE_LIMIT_KEY + dimension + ":" + second;
        try {
            Long count = redis.opsForValue().increment(key);
            redis.expire(key, 2, TimeUnit.SECONDS);
            return count != null && count <= Math.max(1, properties.getRateLimitPerSecond());
        } catch (Exception e) {
            log.warn("广告限流失败，放行: {}", e.getMessage());
            return true;
        }
    }
}
