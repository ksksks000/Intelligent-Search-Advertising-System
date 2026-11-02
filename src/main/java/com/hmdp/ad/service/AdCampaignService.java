package com.hmdp.ad.service;

import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.ad.index.AdCampaignIndexService;
import com.hmdp.ad.index.AdIndexEvent;
import com.hmdp.ad.index.AdIndexEventPublisher;
import com.hmdp.mapper.AdCampaignMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 广告计划生命周期：DB 落库 -> 预算初始化 -> Redis 定向索引 -> 独立索引事件。
 */
@Slf4j
@Service
public class AdCampaignService {

    @Resource
    private AdCampaignMapper campaignMapper;
    @Resource
    private AdCampaignIndexService indexService;
    @Resource
    private AdBudgetService budgetService;
    @Resource
    private AdIndexEventPublisher indexEventPublisher;

    @Transactional
    public AdCampaign create(AdCampaign campaign) {
        if (campaign.getStatus() == null) {
            campaign.setStatus(AdCampaign.STATUS_DRAFT);
        }
        campaignMapper.insert(campaign);
        budgetService.initIfAbsent(campaign);
        indexService.refresh(campaign);
        indexEventPublisher.publish(campaign.getId(), campaign.getId(), AdIndexEvent.UPSERT);
        return campaign;
    }

    public AdCampaign update(AdCampaign campaign) {
        campaignMapper.updateById(campaign);
        AdCampaign latest = campaignMapper.selectById(campaign.getId());
        budgetService.initIfAbsent(latest);
        indexService.refresh(latest);
        indexEventPublisher.publish(latest.getId(), latest.getId(), AdIndexEvent.UPSERT);
        return latest;
    }

    public boolean updateStatus(Long campaignId, int status) {
        AdCampaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            return false;
        }
        campaign.setStatus(status);
        campaignMapper.updateById(campaign);
        indexService.refresh(campaign);
        indexEventPublisher.publish(campaignId, campaignId,
                status == AdCampaign.STATUS_ONLINE ? AdIndexEvent.UPSERT : AdIndexEvent.DELETE);
        return true;
    }

    public long topup(Long campaignId, long amount) {
        AdCampaign campaign = campaignMapper.selectById(campaignId);
        if (campaign == null || amount <= 0) {
            return 0;
        }
        long total = (campaign.getTotalBudget() == null ? 0 : campaign.getTotalBudget()) + amount;
        campaign.setTotalBudget(total);
        campaignMapper.updateById(campaign);
        return budgetService.topup(campaignId, amount);
    }

    public AdCampaign get(Long campaignId) {
        return campaignMapper.selectById(campaignId);
    }

    public List<AdCampaign> list() {
        return campaignMapper.selectList(null);
    }
}
