package com.hmdp.ad.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 竞价兜底缓存：保存最近一次可用计划/出价，超时后按规则价返回。
 */
@Slf4j
@Service
public class AdBidCacheService {

    private static final long TTL_SECONDS = 300L;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private ObjectMapper objectMapper;

    public void put(String city, List<AdCampaign> campaigns) {
        try {
            String key = RedisConstants.AD_BID_CACHE_KEY + safe(city);
            redis.opsForValue().set(key, objectMapper.writeValueAsString(campaigns),
                    TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("写广告兜底缓存失败: {}", e.getMessage());
        }
    }

    public List<AdCampaign> get(String city) {
        try {
            String json = redis.opsForValue().get(RedisConstants.AD_BID_CACHE_KEY + safe(city));
            if (json == null) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, new TypeReference<List<AdCampaign>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String safe(String city) {
        return city == null || city.trim().isEmpty() ? "杭州" : city.trim();
    }
}
