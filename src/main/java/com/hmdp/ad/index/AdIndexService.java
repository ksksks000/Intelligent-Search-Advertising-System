package com.hmdp.ad.index;

import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.mapper.AdCampaignMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 广告索引处理：当前阶段维护计划缓存与索引事件时间戳。
 * 后续计划创建/上下线会通过独立 Stream 驱动该服务刷新定向索引。
 */
@Slf4j
@Service
public class AdIndexService {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private AdCampaignMapper campaignMapper;

    @Resource
    private AdCampaignIndexService campaignIndexService;

    public void handle(AdIndexEvent event) {
        if (event == null) {
            return;
        }
        if (AdIndexEvent.DELETE.equals(event.getEventType())) {
            campaignIndexService.remove(event.getCampaignId());
            return;
        }
        if (event.getCampaignId() != null) {
            AdCampaign campaign = campaignMapper.selectById(event.getCampaignId());
            if (campaign != null) {
                campaignIndexService.refresh(campaign);
            }
        }
        redis.opsForValue().set(RedisConstants.AD_BID_CACHE_KEY + "index:last",
                String.valueOf(event.getVersion() == null ? System.currentTimeMillis() : event.getVersion()),
                10, TimeUnit.MINUTES);
        log.info("ad index event handled adId={}, campaignId={}, type={}, version={}",
                event.getAdId(), event.getCampaignId(), event.getEventType(), event.getVersion());
    }
}
