package com.hmdp.service;

import com.hmdp.config.RateLimitProperties;
import com.hmdp.dto.RateLimitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：真实 Redis 验证令牌桶的三个维度与补充逻辑。
 */
@DataRedisTest
@ContextConfiguration(classes = RateLimiterIT.TestConfig.class)
class RateLimiterIT {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {
    }

    @Autowired
    private StringRedisTemplate redis;

    private RateLimiter limiter;
    private RateLimitProperties properties;
    private Long userId;
    private String ip;
    private String api;

    @BeforeEach
    void setUp() {
        userId = System.nanoTime();
        ip = "ip-" + userId;
        api = "/voucher-order/seckill/" + userId;
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(60);
        limiter = new RateLimiter(redis, properties);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(RateLimiter.userKey(userId));
        redis.delete(RateLimiter.ipKey(ip));
        redis.delete(RateLimiter.apiKey(api));
    }

    @Test
    @DisplayName("用户维度：容量 2 时前两次通过、第三次拒绝")
    void userDimension() {
        properties.setUser(new RateLimitProperties.Dimension(2, 0));
        properties.setIp(new RateLimitProperties.Dimension(0, 0));
        properties.setApi(new RateLimitProperties.Dimension(0, 0));

        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        RateLimitResult third = limiter.tryAcquire(userId, ip, api);
        assertFalse(third.isAllowed());
        assertEquals(1, third.getDimension());
    }

    @Test
    @DisplayName("令牌补充：容量 1、1/s，等待 1.1s 后可再次通过")
    void refill() throws Exception {
        properties.setUser(new RateLimitProperties.Dimension(1, 1));
        properties.setIp(new RateLimitProperties.Dimension(0, 0));
        properties.setApi(new RateLimitProperties.Dimension(0, 0));

        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        assertFalse(limiter.tryAcquire(userId, ip, api).isAllowed());
        Thread.sleep(1100);
        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
    }

    @Test
    @DisplayName("IP 维度：用户关闭时由 IP 限流命中")
    void ipDimension() {
        properties.setUser(new RateLimitProperties.Dimension(0, 0));
        properties.setIp(new RateLimitProperties.Dimension(2, 0));
        properties.setApi(new RateLimitProperties.Dimension(0, 0));

        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        RateLimitResult third = limiter.tryAcquire(userId, ip, api);
        assertFalse(third.isAllowed());
        assertEquals(2, third.getDimension());
    }

    @Test
    @DisplayName("API 维度：全局接口限流")
    void apiDimension() {
        properties.setUser(new RateLimitProperties.Dimension(0, 0));
        properties.setIp(new RateLimitProperties.Dimension(0, 0));
        properties.setApi(new RateLimitProperties.Dimension(1, 0));

        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        RateLimitResult second = limiter.tryAcquire(userId, ip, api);
        assertFalse(second.isAllowed());
        assertEquals(3, second.getDimension());
    }

    @Test
    @DisplayName("关闭限流：直接放行")
    void disabled() {
        properties.setEnabled(false);
        properties.setUser(new RateLimitProperties.Dimension(1, 0));

        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
        assertTrue(limiter.tryAcquire(userId, ip, api).isAllowed());
    }
}
