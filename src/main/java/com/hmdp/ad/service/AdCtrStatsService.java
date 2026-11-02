package com.hmdp.ad.service;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 广告 CTR 反馈计数：按类目/计划累计曝光与点击，供规则预估使用。
 */
@Slf4j
@Service
public class AdCtrStatsService {

    private static final long TTL_SECONDS = 7 * 24 * 3600L;

    @Resource
    private StringRedisTemplate redis;

    public void recordImpression(Long campaignId, Long categoryId) {
        increment(campaignId, categoryId, "imp");
    }

    public void recordClick(Long campaignId, Long categoryId) {
        increment(campaignId, categoryId, "click");
    }

    public double categoryCtr(Long categoryId) {
        if (categoryId == null) {
            return 0.02D;
        }
        try {
            String imp = value("cat:" + categoryId + ":imp");
            String click = value("cat:" + categoryId + ":click");
            long impressions = imp == null ? 0 : Long.parseLong(imp);
            long clicks = click == null ? 0 : Long.parseLong(click);
            return impressions > 0 ? (double) clicks / impressions : 0.02D;
        } catch (Exception e) {
            return 0.02D;
        }
    }

    private void increment(Long campaignId, Long categoryId, String suffix) {
        try {
            if (categoryId != null) {
                redis.opsForHash().increment(RedisConstants.AD_CTR_STATS_KEY,
                        "cat:" + categoryId + ":" + suffix, 1);
            }
            if (campaignId != null) {
                redis.opsForHash().increment(RedisConstants.AD_CTR_STATS_KEY,
                        "campaign:" + campaignId + ":" + suffix, 1);
            }
            redis.expire(RedisConstants.AD_CTR_STATS_KEY, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("记录广告 CTR 反馈失败 campaignId={}, categoryId={}, error={}",
                    campaignId, categoryId, e.getMessage());
        }
    }

    private String value(String field) {
        Object value = redis.opsForHash().get(RedisConstants.AD_CTR_STATS_KEY, field);
        return value == null ? null : String.valueOf(value);
    }
}
