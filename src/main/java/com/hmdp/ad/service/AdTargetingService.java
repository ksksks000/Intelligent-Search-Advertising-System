package com.hmdp.ad.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.ad.index.AdCampaignIndexService;
import com.hmdp.dto.UserProfile;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 定向召回：标签/类目/城市/设备/时段的倒排 Set 在 Lua 内一次求交，在线只读 Redis。
 */
@Slf4j
@Service
public class AdTargetingService {

    private static final DefaultRedisScript<List> TARGET_SCRIPT;

    static {
        TARGET_SCRIPT = new DefaultRedisScript<>();
        TARGET_SCRIPT.setLocation(new ClassPathResource("lua/ad_target.lua"));
        TARGET_SCRIPT.setResultType(List.class);
    }

    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private AdCampaignIndexService indexService;

    private Cache<String, List<AdCampaign>> candidateCache;

    @PostConstruct
    public void init() {
        candidateCache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .build();
    }

    public List<AdCampaign> recall(String city, String deviceType, UserProfile profile,
                                   LocalDateTime now) {
        return recall(city, deviceType, profile, now, Collections.emptySet());
    }

    public List<AdCampaign> recall(String city, String deviceType, UserProfile profile,
                                   LocalDateTime now, Set<String> adAffinity) {
        String normalizedCity = city == null || city.trim().isEmpty() ? "杭州" : city.trim();
        String normalizedDevice = deviceType == null || deviceType.trim().isEmpty()
                ? "UNKNOWN" : deviceType.trim().toUpperCase();
        String cacheKey = normalizedCity + "|" + normalizedDevice + "|" + now.getHour()
                + "|" + signature(profile) + "|" + affinitySignature(adAffinity);
        List<AdCampaign> cached = candidateCache.getIfPresent(cacheKey);
        if (cached != null) {
            List<AdCampaign> deliverable = new ArrayList<>();
            for (AdCampaign campaign : cached) {
                if (campaign.isDeliverable(now)) {
                    deliverable.add(campaign);
                }
            }
            return deliverable;
        }
        List<String> cityKeys = Arrays.asList(
                RedisConstants.AD_TARGET_CITY_KEY + normalizedCity,
                RedisConstants.AD_TARGET_CITY_KEY + AdCampaignIndexService.allValue());
        List<String> deviceKeys = Arrays.asList(
                RedisConstants.AD_TARGET_DEVICE_KEY + normalizedDevice,
                RedisConstants.AD_TARGET_DEVICE_KEY + AdCampaignIndexService.allValue());
        List<String> hourKeys = Arrays.asList(
                RedisConstants.AD_TARGET_HOUR_KEY + now.getHour(),
                RedisConstants.AD_TARGET_HOUR_KEY + AdCampaignIndexService.allValue());
        List<String> interestKeys = interestKeys(profile, adAffinity);

        List<String> ids = query(cityKeys, deviceKeys, hourKeys, interestKeys);
        if (ids.isEmpty() && !interestKeys.isEmpty()) {
            // 画像过窄时退化为城市+设备+时段，避免广告位长期空白
            ids = query(cityKeys, deviceKeys, hourKeys, Collections.emptyList());
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        long loadStart = System.currentTimeMillis();
        List<String> keys = new ArrayList<>(ids.size());
        for (String id : ids) {
            keys.add(RedisConstants.AD_CAMPAIGN_KEY + id);
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        log.info("ad target ids={}, metaLoad={}ms", ids.size(),
                System.currentTimeMillis() - loadStart);
        List<AdCampaign> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String json : values) {
            if (json == null) {
                continue;
            }
            try {
                AdCampaign campaign = objectMapper.readValue(json, AdCampaign.class);
                if (campaign != null && campaign.isDeliverable(now)) {
                    result.add(campaign);
                }
            } catch (Exception e) {
                log.debug("解析广告计划缓存失败: {}", e.getMessage());
            }
        }
        candidateCache.put(cacheKey, result);
        return result;
    }

    private String signature(UserProfile profile) {
        if (profile == null) {
            return "cold";
        }
        List<String> values = new ArrayList<>();
        if (profile.getTagWeights() != null) {
            profile.getTagWeights().keySet().stream().sorted().limit(5).forEach(values::add);
        }
        if (profile.getCategoryWeights() != null) {
            profile.getCategoryWeights().keySet().stream().sorted().limit(5)
                    .forEach(id -> values.add("c" + id));
        }
        return String.join(",", values);
    }

    private String affinitySignature(Set<String> affinity) {
        if (affinity == null || affinity.isEmpty()) {
            return "none";
        }
        return affinity.stream().sorted().limit(5)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private List<String> interestKeys(UserProfile profile, Set<String> adAffinity) {
        List<String> keys = new ArrayList<>();
        if (profile != null) {
            if (profile.getTagWeights() != null) {
                profile.getTagWeights().entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(5)
                        .forEach(e -> keys.add(RedisConstants.AD_TARGET_TAG_KEY + e.getKey()));
            }
            if (profile.getCategoryWeights() != null) {
                profile.getCategoryWeights().entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(5)
                        .forEach(e -> keys.add(RedisConstants.AD_TARGET_CATEGORY_KEY + e.getKey()));
            }
        }
        if (adAffinity != null) {
            adAffinity.stream().limit(5)
                    .forEach(tag -> keys.add(RedisConstants.AD_TARGET_AFFINITY_KEY + tag));
        }
        if (!keys.isEmpty()) {
            keys.add(RedisConstants.AD_TARGET_TAG_KEY + AdCampaignIndexService.allValue());
            keys.add(RedisConstants.AD_TARGET_AFFINITY_KEY + AdCampaignIndexService.allValue());
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private List<String> query(List<String> cityKeys, List<String> deviceKeys,
                               List<String> hourKeys, List<String> interestKeys) {
        List<String> keys = new ArrayList<>();
        keys.addAll(cityKeys);
        keys.addAll(deviceKeys);
        keys.addAll(hourKeys);
        keys.addAll(interestKeys);
        long start = System.currentTimeMillis();
        List<?> result = redis.execute(TARGET_SCRIPT, keys,
                String.valueOf(cityKeys.size()),
                String.valueOf(deviceKeys.size()),
                String.valueOf(hourKeys.size()),
                String.valueOf(interestKeys.size()));
        log.info("ad target lua keys={}, cost={}ms", keys.size(),
                System.currentTimeMillis() - start);
        List<String> ids = new ArrayList<>();
        if (result != null) {
            for (Object value : result) {
                if (value != null) {
                    ids.add(String.valueOf(value));
                }
            }
        }
        return ids;
    }
}
