package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 纯单元测试：所有外部依赖（Redis、线程池）全部 mock，不启动 Spring、不连中间件。
 * 满足 AIR：自动、独立（每个用例自己造数据）、可重复（无线程/时间等待）。
 */
@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    private static final String KEY_PREFIX = RedisConstants.CACHE_SHOP_KEY;
    private static final String KEY = KEY_PREFIX + 1L;
    private static final String LOCK_KEY = RedisConstants.LOCK_SHOP_KEY + 1L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ThreadPoolTaskExecutor taskExecutor;

    @InjectMocks
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("C 正确：缓存未过期时直接返回，不查库、不加锁、不提交任务")
    void notExpired_shouldReturnCachedValueWithoutRebuild() {
        Shop cached = shop("旧店铺");
        when(valueOperations.get(KEY)).thenReturn(json(expireAfterSeconds(60), cached));

        Shop result = cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, 1L, Shop.class, id -> shop("数据库店铺"), 30L, TimeUnit.MINUTES);

        assertEquals("旧店铺", result.getName());
        verify(taskExecutor, never()).submit(any(Runnable.class));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("E 正确：缓存不存在时回源数据库并写入逻辑过期缓存")
    void cacheMiss_shouldFallbackToDbAndCache() {
        when(valueOperations.get(KEY)).thenReturn(null);

        Shop result = cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, 1L, Shop.class, id -> shop("数据库店铺"), 30L, TimeUnit.MINUTES);

        assertEquals("数据库店铺", result.getName());
        ArgumentCaptor<String> jsonWritten = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), jsonWritten.capture());
        assertEquals("数据库店铺", parse(jsonWritten.getValue()).getName());
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("E 边界：缓存和数据库都不存在时写入空值缓存并返回 null")
    void cacheMissAndDbNull_shouldCacheNullValue() {
        when(valueOperations.get(KEY)).thenReturn(null);

        Shop result = cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, 1L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);

        assertNull(result);
        verify(valueOperations).set(KEY, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("C+D 正确/设计：已过期且抢锁成功时返回旧值，并提交重建任务、重建后释放锁")
    void expiredAndLockAcquired_shouldReturnStaleValueAndSubmitRebuild() {
        Shop stale = shop("旧店铺");
        when(valueOperations.get(KEY)).thenReturn(json(expireAfterSeconds(-1), stale));
        when(valueOperations.setIfAbsent(LOCK_KEY, "1", 10, TimeUnit.SECONDS)).thenReturn(true);
        // 不让异步任务真的跑在别的线程里：捕获后同步执行，结果确定、可重复
        when(taskExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        });

        Shop result = cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, 1L, Shop.class, id -> shop("数据库店铺"), 30L, TimeUnit.MINUTES);

        assertEquals("旧店铺", result.getName());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).submit(task.capture());
        // 断言重建后的缓存写回，且写的是数据库里的新数据
        ArgumentCaptor<String> jsonWritten = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), jsonWritten.capture());
        assertEquals("数据库店铺", parse(jsonWritten.getValue()).getName());
        // 断言锁最终被释放
        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("B 边界：已过期但抢锁失败时不重复提交重建任务")
    void expiredButLockNotAcquired_shouldNotSubmitRebuild() {
        when(valueOperations.get(KEY)).thenReturn(json(expireAfterSeconds(-1), shop("旧店铺")));
        when(valueOperations.setIfAbsent(LOCK_KEY, "1", 10, TimeUnit.SECONDS)).thenReturn(false);

        Shop result = cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, 1L, Shop.class, id -> shop("数据库店铺"), 30L, TimeUnit.MINUTES);

        assertEquals("旧店铺", result.getName());
        verify(taskExecutor, never()).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("E 错误：重建时查库抛异常也要释放锁，异常不向外传播")
    void rebuildFailure_shouldStillReleaseLock() {
        when(valueOperations.get(KEY)).thenReturn(json(expireAfterSeconds(-1), shop("旧店铺")));
        when(valueOperations.setIfAbsent(LOCK_KEY, "1", 10, TimeUnit.SECONDS)).thenReturn(true);
        when(taskExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        });

        assertDoesNotThrow(() -> cacheClient.queryWithLogicalExpire(
                KEY_PREFIX, 1L, Shop.class, id -> {
                    throw new RuntimeException("数据库挂了");
                }, 30L, TimeUnit.MINUTES));

        verify(stringRedisTemplate).delete(LOCK_KEY);
    }

    // ---------- helpers ----------

    private static Shop shop(String name) {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName(name);
        return shop;
    }

    private static LocalDateTime expireAfterSeconds(long seconds) {
        return LocalDateTime.now().plusSeconds(seconds);
    }

    private static String json(LocalDateTime expireTime, Shop shop) {
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(expireTime);
        return JSONUtil.toJsonStr(data);
    }

    private static Shop parse(String json) {
        RedisData data = JSONUtil.toBean(json, RedisData.class);
        return JSONUtil.toBean((cn.hutool.json.JSONObject) data.getData(), Shop.class);
    }
}
