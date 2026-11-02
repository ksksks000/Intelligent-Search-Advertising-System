package com.hmdp.profile.service;

import com.hmdp.config.ProfileProperties;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * LLM 画像生成限流：默认 10 用户/秒（固定窗口近似）。
 */
@Slf4j
@Component
public class ProfileLlmRateLimiter {

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ProfileProperties properties;

    public boolean tryAcquire() {
        long second = System.currentTimeMillis() / 1000L;
        String key = RedisConstants.USER_PROFILE_LLM_RATE_KEY + second;
        try {
            Long count = redis.opsForValue().increment(key);
            redis.expire(key, 2, TimeUnit.SECONDS);
            return count != null && count <= Math.max(1, properties.getRatePerSecond());
        } catch (Exception e) {
            log.warn("画像限流失败，放行: {}", e.getMessage());
            return true;
        }
    }

    public void acquireBlocking() {
        while (!tryAcquire()) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
