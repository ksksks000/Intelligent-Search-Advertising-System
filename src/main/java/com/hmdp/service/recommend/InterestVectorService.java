package com.hmdp.service.recommend;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.RecommendContext;
import com.hmdp.dto.UserProfile;
import com.hmdp.search.embedding.EmbeddingClient;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 用户兴趣向量：近 7 天行为标签文本 -> embedding -> user:interest:{userKey}，TTL 24h。
 * @deprecated 阶段 5 起由 {@link com.hmdp.profile.service.UserProfileService} 用 LLM
 * semantic_query 写入同一 user:interest:{userKey}；保留本类作为 embedding 不可用时的兜底。
 */
@Deprecated
@Slf4j
@Service
public class InterestVectorService {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private RecommendProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public float[] getVector(RecommendContext context) {
        String userKey = context.getUserKey();
        if (StrUtil.isBlank(userKey) || "anon".equals(userKey)) {
            return null;
        }
        String key = RedisConstants.USER_INTEREST_KEY + userKey;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, float[].class);
            }
        } catch (Exception e) {
            log.warn("读取用户兴趣向量失败，删除坏缓存，userKey={}, error={}", userKey, e.getMessage());
            redis.delete(key);
        }

        UserProfile profile = context.getProfile();
        if (profile == null || StrUtil.isBlank(profile.getText())) {
            return null;
        }
        float[] vector = buildAndCache(profile, userKey);
        if (vector == null) {
            context.setInterestVectorDegraded(true);
            context.getDegradedSources().add("VECTOR");
        }
        return vector;
    }

    /**
     * 供管理端/异步预热使用：不受 Feed 50ms 召回超时限制，直接编码并缓存。
     */
    public float[] buildAndCache(UserProfile profile, String userKey) {
        if (StrUtil.isBlank(userKey) || "anon".equals(userKey)
                || profile == null || StrUtil.isBlank(profile.getText())) {
            return null;
        }
        String key = RedisConstants.USER_INTEREST_KEY + userKey;
        try {
            float[] vector = embeddingClient.encodeQuery(profile.getText());
            redis.opsForValue().set(key, objectMapper.writeValueAsString(vector),
                    properties.getInterestTtlSeconds(), TimeUnit.SECONDS);
            log.info("用户兴趣向量生成完成，userKey={}, dim={}", userKey, vector.length);
            return vector;
        } catch (Exception e) {
            log.warn("DEGRADED: build interest vector failed, userKey={}, error={}",
                    userKey, e.getMessage());
            return null;
        }
    }
}
