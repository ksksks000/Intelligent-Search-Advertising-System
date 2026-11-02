package com.hmdp.service.recommend;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.config.RecommendProperties;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户曝光/点击快照：一次读取整个 ZSet/Set，候选精排阶段本地 O(1) 查询。
 * 5 秒短 TTL 允许短暂陈旧，换取把逐候选 2 次 Redis 调用降为每用户 2 次/5 秒。
 */
@Slf4j
@Component
public class FeedExposureCache {

    private static final long EXPIRE_MS = 24 * 3600_000L;
    private static final int MAX_IMPRESSIONS = 500;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private RecommendProperties properties;

    private Cache<String, Snapshot> cache;

    @PostConstruct
    public void init() {
        cache = Caffeine.newBuilder()
                .maximumSize(Math.max(100, properties.getExposureCacheMaxSize()))
                .expireAfterWrite(Math.max(1, properties.getExposureCacheTtlSeconds()), TimeUnit.SECONDS)
                .build();
    }

    public boolean exposedNotClicked(String userKey, Long activityId) {
        if (activityId == null) {
            return false;
        }
        Snapshot snapshot = cache.get(userKey, this::load);
        if (snapshot == null) {
            return false;
        }
        Long exposedAt = snapshot.impressions.get(activityId);
        if (exposedAt == null || System.currentTimeMillis() - exposedAt > EXPIRE_MS) {
            return false;
        }
        return !snapshot.clicks.contains(activityId);
    }

    public void invalidate(String userKey) {
        if (userKey != null) {
            cache.invalidate(userKey);
        }
    }

    private Snapshot load(String userKey) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(RedisConstants.REC_IMPRESSION_KEY + userKey,
                            0, MAX_IMPRESSIONS - 1L);
            Map<Long, Long> impressions = new HashMap<>();
            if (tuples != null) {
                for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                    if (tuple.getValue() == null || tuple.getScore() == null) {
                        continue;
                    }
                    try {
                        impressions.put(Long.valueOf(tuple.getValue()), tuple.getScore().longValue());
                    } catch (NumberFormatException ignored) {
                        // 忽略非活动 ID 的历史脏数据
                    }
                }
            }
            Set<String> clickMembers = redis.opsForSet()
                    .members(RedisConstants.REC_CLICK_KEY + userKey);
            Set<Long> clicks = new HashSet<>();
            if (clickMembers != null) {
                for (String member : clickMembers) {
                    try {
                        clicks.add(Long.valueOf(member));
                    } catch (NumberFormatException ignored) {
                        // 忽略脏数据
                    }
                }
            }
            return new Snapshot(impressions, clicks);
        } catch (Exception e) {
            log.debug("加载曝光快照失败，userKey={}, error={}", userKey, e.getMessage());
            return new Snapshot(Collections.emptyMap(), Collections.emptySet());
        }
    }

    private static final class Snapshot {
        private final Map<Long, Long> impressions;
        private final Set<Long> clicks;

        private Snapshot(Map<Long, Long> impressions, Set<Long> clicks) {
            this.impressions = impressions;
            this.clicks = clicks;
        }
    }
}
