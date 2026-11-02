package com.hmdp.search.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 搜索热词：ZSet 计频 + 定时衰减 + 列表短缓存。
 */
@Slf4j
@Service
public class SearchHotWordService {

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "的", "了", "和", "与", "在", "是", "我", "有", "什么", "怎么", "哪里", "a", "the"));

    private final StringRedisTemplate redis;

    @Value("${hmdp.search.hot-decay-factor:0.9}")
    private double decayFactor;

    public SearchHotWordService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void record(String query) {
        String word = normalize(query);
        if (word == null) {
            return;
        }
        try {
            redis.opsForZSet().incrementScore(RedisConstants.SEARCH_HOT_KEY, word, 1D);
            redis.expire(RedisConstants.SEARCH_HOT_KEY,
                    RedisConstants.SEARCH_HOT_TTL_SECONDS, TimeUnit.SECONDS);
            redis.delete(RedisConstants.SEARCH_HOT_CACHE_KEY);
        } catch (Exception e) {
            log.warn("记录搜索热词失败，word={}, error={}", word, e.getMessage());
        }
    }

    public List<Map<String, Object>> list(int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        try {
            String cached = redis.opsForValue().get(RedisConstants.SEARCH_HOT_CACHE_KEY);
            if (StrUtil.isNotBlank(cached)) {
                List<Map> raw = JSONUtil.toList(cached, Map.class);
                return raw.stream()
                        .map(item -> (Map<String, Object>) item)
                        .collect(Collectors.toList());
            }
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .reverseRangeWithScores(RedisConstants.SEARCH_HOT_KEY, 0, size - 1);
            List<Map<String, Object>> result = new ArrayList<>();
            if (tuples != null) {
                for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                    if (tuple.getValue() == null) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("word", tuple.getValue());
                    item.put("score", tuple.getScore() == null ? 0D : tuple.getScore());
                    result.add(item);
                }
            }
            redis.opsForValue().set(RedisConstants.SEARCH_HOT_CACHE_KEY, JSONUtil.toJsonStr(result),
                    RedisConstants.SEARCH_HOT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return result;
        } catch (Exception e) {
            log.warn("读取搜索热词失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.search.hot-decay-interval-ms:600000}")
    public void decay() {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redis.opsForZSet()
                    .rangeWithScores(RedisConstants.SEARCH_HOT_KEY, 0, 499);
            if (tuples == null || tuples.isEmpty()) {
                return;
            }
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String word = tuple.getValue();
                Double score = tuple.getScore();
                if (word == null || score == null) {
                    continue;
                }
                double decayed = score * decayFactor;
                if (decayed < 0.1) {
                    redis.opsForZSet().remove(RedisConstants.SEARCH_HOT_KEY, word);
                } else {
                    redis.opsForZSet().add(RedisConstants.SEARCH_HOT_KEY, word, decayed);
                }
            }
            redis.delete(RedisConstants.SEARCH_HOT_CACHE_KEY);
            log.debug("搜索热词衰减完成，处理 {} 条", tuples.size());
        } catch (Exception e) {
            log.warn("搜索热词衰减失败: {}", e.getMessage());
        }
    }

    private String normalize(String query) {
        if (query == null) {
            return null;
        }
        String word = query.trim().toLowerCase();
        if (word.isEmpty() || word.length() > 32 || STOPWORDS.contains(word)) {
            return null;
        }
        return word;
    }
}
