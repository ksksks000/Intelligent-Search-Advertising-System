package com.hmdp.service;

import com.hmdp.config.RateLimitProperties;
import com.hmdp.dto.RateLimitResult;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 秒杀入口令牌桶限流：user / ip / api 三个维度在一个 Lua 脚本内原子判定。
 */
@Slf4j
@Service
public class RateLimiter {

    private static final DefaultRedisScript<List> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public RateLimitResult tryAcquire(Long userId, String ip, String api) {
        if (!properties.isEnabled()) {
            return RateLimitResult.allow(Long.MAX_VALUE);
        }
        RateLimitProperties.Dimension user = properties.getUser();
        RateLimitProperties.Dimension ipDimension = properties.getIp();
        RateLimitProperties.Dimension apiDimension = properties.getApi();
        long now = System.currentTimeMillis();
        long ttl = Math.max(1, properties.getTtlSeconds()) * 1000L;
        try {
            List<?> result = redis.execute(
                    SCRIPT,
                    Arrays.asList(userKey(userId), ipKey(ip), apiKey(api)),
                    String.valueOf(now),
                    "1",
                    String.valueOf(ttl),
                    String.valueOf(user == null || userId == null ? 0 : user.getCapacity()),
                    String.valueOf(user == null ? 0 : user.getRefillRate()),
                    String.valueOf(ipDimension == null || ip == null ? 0 : ipDimension.getCapacity()),
                    String.valueOf(ipDimension == null ? 0 : ipDimension.getRefillRate()),
                    String.valueOf(apiDimension == null || api == null ? 0 : apiDimension.getCapacity()),
                    String.valueOf(apiDimension == null ? 0 : apiDimension.getRefillRate()));
            if (result == null || result.size() < 3) {
                log.error("限流脚本返回异常，result={}", result);
                return RateLimitResult.allow(Long.MAX_VALUE);
            }
            return new RateLimitResult(
                    ((Number) result.get(0)).intValue() == 1,
                    ((Number) result.get(1)).longValue(),
                    ((Number) result.get(2)).intValue());
        } catch (Exception e) {
            // Redis 异常时放行，由后续秒杀 Lua 统一失败，避免限流层成为单点
            log.error("限流执行失败，userId={}, ip={}, api={}", userId, ip, api, e);
            return RateLimitResult.allow(Long.MAX_VALUE);
        }
    }

    public static String userKey(Long userId) {
        return RedisConstants.RATE_LIMIT_USER_KEY + (userId == null ? "anonymous" : userId);
    }

    public static String ipKey(String ip) {
        return RedisConstants.RATE_LIMIT_IP_KEY + (ip == null || ip.isEmpty() ? "unknown" : ip);
    }

    public static String apiKey(String api) {
        String normalized = (api == null || api.isEmpty()) ? "unknown" : api.replace('/', ':');
        return RedisConstants.RATE_LIMIT_API_KEY + normalized;
    }
}
