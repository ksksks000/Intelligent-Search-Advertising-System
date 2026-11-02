package com.hmdp.ad.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 广告计划缓存 + 定向倒排索引。广告在线竞价只读 Redis，不查 DB、不调用 embedding。
 */
@Slf4j
@Service
public class AdCampaignIndexService {

    private static final String ALL = "__all__";
    private static final long META_TTL_MINUTES = 60L;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private ObjectMapper objectMapper;

    public void refresh(AdCampaign campaign) {
        if (campaign == null || campaign.getId() == null) {
            return;
        }
        removeFromIndex(campaign.getId());
        try {
            redis.opsForValue().set(RedisConstants.AD_CAMPAIGN_KEY + campaign.getId(),
                    objectMapper.writeValueAsString(campaign), META_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写广告计划缓存失败，campaignId={}, error={}", campaign.getId(), e.getMessage());
            return;
        }
        if (!Integer.valueOf(AdCampaign.STATUS_ONLINE).equals(campaign.getStatus())) {
            redis.opsForSet().remove(RedisConstants.AD_CAMPAIGN_ALL_KEY, String.valueOf(campaign.getId()));
            return;
        }
        String id = String.valueOf(campaign.getId());
        redis.opsForSet().add(RedisConstants.AD_CAMPAIGN_ALL_KEY, id);
        String setsKey = RedisConstants.AD_CAMPAIGN_INDEX_SETS_KEY + campaign.getId();
        addDimension(setsKey, id, RedisConstants.AD_TARGET_TAG_KEY,
                csv(campaign.getTargetTags()));
        addDimension(setsKey, id, RedisConstants.AD_TARGET_CATEGORY_KEY,
                csv(campaign.getTargetCategories()));
        addDimension(setsKey, id, RedisConstants.AD_TARGET_CITY_KEY,
                csv(campaign.getTargetCity()));
        addDimension(setsKey, id, RedisConstants.AD_TARGET_DEVICE_KEY,
                csv(campaign.getTargetDevice()));
        addDimension(setsKey, id, RedisConstants.AD_TARGET_HOUR_KEY,
                csv(campaign.getTargetHours()));
        addDimension(setsKey, id, RedisConstants.AD_TARGET_AFFINITY_KEY,
                csv(campaign.getTargetAffinity()));
        redis.expire(setsKey, 1, TimeUnit.DAYS);
    }

    public void remove(Long campaignId) {
        if (campaignId == null) {
            return;
        }
        removeFromIndex(campaignId);
        redis.delete(RedisConstants.AD_CAMPAIGN_KEY + campaignId);
    }

    public AdCampaign getCampaign(Long campaignId) {
        if (campaignId == null) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(RedisConstants.AD_CAMPAIGN_KEY + campaignId);
            return json == null ? null : objectMapper.readValue(json, AdCampaign.class);
        } catch (Exception e) {
            log.warn("读取广告计划缓存失败，campaignId={}, error={}", campaignId, e.getMessage());
            return null;
        }
    }

    private void addDimension(String setsKey, String campaignId, String prefix, List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isEmpty() && !"ALL".equalsIgnoreCase(value)) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(ALL);
        }
        for (String value : normalized) {
            String setKey = prefix + value;
            redis.opsForSet().add(setKey, campaignId);
            redis.opsForSet().add(setsKey, setKey);
        }
    }

    private void removeFromIndex(Long campaignId) {
        String setsKey = RedisConstants.AD_CAMPAIGN_INDEX_SETS_KEY + campaignId;
        Set<String> setKeys = redis.opsForSet().members(setsKey);
        if (setKeys != null) {
            for (String setKey : setKeys) {
                redis.opsForSet().remove(setKey, String.valueOf(campaignId));
            }
        }
        redis.delete(setsKey);
    }

    private List<String> csv(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(new LinkedHashSet<>(Arrays.stream(text.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList())));
    }

    public static String allValue() {
        return ALL;
    }
}
