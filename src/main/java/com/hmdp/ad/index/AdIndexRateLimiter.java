package com.hmdp.ad.index;

import com.hmdp.config.AdProperties;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 广告索引侧 embedding/富化限流：默认 10 条/秒。
 * 在线竞价链路不经过这里；仅索引消费者调用。
 */
@Slf4j
@Component
public class AdIndexRateLimiter {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private AdProperties properties;

    public boolean tryAcquire() {
        int limit = Math.max(1, properties.getIndexEmbeddingRatePerSecond());
        long second = System.currentTimeMillis() / 1000L;
        String key = RedisConstants.AD_INDEX_RATE_KEY + second;
        try {
            Long count = redis.opsForValue().increment(key);
            redis.expire(key, 2, TimeUnit.SECONDS);
            return count != null && count <= limit;
        } catch (Exception e) {
            log.warn("广告索引限流失败，放行: {}", e.getMessage());
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
