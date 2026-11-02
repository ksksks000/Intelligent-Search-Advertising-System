package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.ThreadPoolConfig;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：连真实 Redis，验证「缓存键格式、逻辑过期判断、异步重建」在真环境下确实成立。
 * 只启动 Redis 相关的自动配置（不加载 MySQL / RabbitMQ / Web），比 @SpringBootTest 轻。
 * 注意：类名为 *IT，Surefire 默认不跑，需手动指定或交给 Failsafe。
 */
@DataRedisTest
@ContextConfiguration(classes = {CacheClient.class, ThreadPoolConfig.class, CacheClientIT.TestConfig.class})
class CacheClientIT {

    /** 显式测试配置：只注册基础包，避免加载启动类上的 @MapperScan */
    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {
    }


    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 唯一前缀 + 唯一 id，避免与真实店铺数据、其他测试互相污染 */
    private final String keyPrefix = RedisConstants.CACHE_SHOP_KEY + "it:" + UUID.randomUUID() + ":";
    private final long id = System.nanoTime();
    private final String key = keyPrefix + id;
    private final String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(lockKey);
    }

    @Test
    @DisplayName("真 Redis：缓存不存在时返回 null")
    void cacheMiss_shouldReturnNull() {
        assertNull(cacheClient.queryWithLogicalExpire(
                keyPrefix, id, Shop.class, i -> shop("数据库店铺"), 30L, TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("真 Redis：缓存已过期时先返回旧值，随后异步重建为新值")
    void expired_shouldReturnStaleThenRebuildAsync() throws InterruptedException {
        RedisData expired = new RedisData();
        expired.setData(shop("旧店铺"));
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(expired));

        Shop result = cacheClient.queryWithLogicalExpire(
                keyPrefix, id, Shop.class, i -> shop("数据库店铺"), 30L, TimeUnit.MINUTES);

        // 1. 同步返回的是旧值
        assertEquals("旧店铺", result.getName());

        // 2. 异步重建：轮询等待缓存被改写（不要 sleep 固定时长）
        await(Duration.ofSeconds(5), () -> {
            String json = stringRedisTemplate.opsForValue().get(key);
            return json != null && "数据库店铺".equals(parseName(json));
        });

        // 3. 重建后的逻辑过期时间应该在未来，且锁已释放
        assertTrue(parse(stringRedisTemplate.opsForValue().get(key)).getExpireTime().isAfter(LocalDateTime.now()));
        assertNull(stringRedisTemplate.opsForValue().get(lockKey));
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("条件在 " + timeout + " 内未满足");
    }

    private static Shop shop(String name) {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName(name);
        return shop;
    }

    private static RedisData parse(String json) {
        return JSONUtil.toBean(json, RedisData.class);
    }

    private static String parseName(String json) {
        RedisData data = parse(json);
        return JSONUtil.toBean((JSONObject) data.getData(), Shop.class).getName();
    }
}
