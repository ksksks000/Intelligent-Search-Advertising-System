package com.hmdp.service.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.FeedResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * Feed 两级缓存：L1 Caffeine 30s / L2 Redis 5min。
 */
@Slf4j
@Service
public class FeedCacheService {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private RecommendProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    private Cache<String, FeedResponse> l1;

    @PostConstruct
    public void init() {
        l1 = Caffeine.newBuilder()
                .maximumSize(Math.max(100, properties.getL1MaxSize()))
                .expireAfterWrite(Math.max(1, properties.getL1TtlSeconds()), TimeUnit.SECONDS)
                .build();
    }

    public FeedResponse get(String key) {
        try {
            FeedResponse local = l1.getIfPresent(key);
            if (local != null) {
                local.setCacheLevel("L1");
                return local;
            }
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            FeedResponse remote = objectMapper.readValue(json, FeedResponse.class);
            l1.put(key, remote);
            remote.setCacheLevel("L2");
            return remote;
        } catch (Exception e) {
            log.warn("读取 Feed 缓存失败，key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    public void put(String key, FeedResponse response) {
        put(key, response, Math.max(1, properties.getL2TtlSeconds()));
    }

    public void put(String key, FeedResponse response, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(response),
                    Math.max(1, ttlSeconds), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写 Feed L2 缓存失败，key={}, error={}", key, e.getMessage());
        }
        l1.put(key, response);
    }
}
