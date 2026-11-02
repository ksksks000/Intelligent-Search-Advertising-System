package com.hmdp.ad.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ad.entity.AdBillingLog;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.mapper.AdBillingLogMapper;
import com.hmdp.mapper.AdCampaignMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.List;

/**
 * 预算对账：Redis 剩余预算 vs DB 计费流水，只告警不自动改数，避免在途消息误修正。
 */
@Slf4j
@Service
public class AdBudgetReconcileService {

    @Resource
    private AdCampaignMapper campaignMapper;
    @Resource
    private AdBillingLogMapper billingLogMapper;
    @Resource
    private StringRedisTemplate redis;

    @Scheduled(fixedDelayString = "${hmdp.ad.reconcile-ms:60000}")
    public void reconcile() {
        for (AdCampaign campaign : campaignMapper.selectList(null)) {
            try {
                reconcileCampaign(campaign);
            } catch (Exception e) {
                log.warn("广告预算对账失败 campaignId={}, error={}",
                        campaign.getId(), e.getMessage());
            }
        }
    }

    private void reconcileCampaign(AdCampaign campaign) {
        String remainingValue = redis.opsForValue().get(AdBudgetService.totalKey(campaign.getId()));
        if (remainingValue == null) {
            return;
        }
        long remaining = Long.parseLong(remainingValue);
        long totalBudget = campaign.getTotalBudget() == null ? 0 : campaign.getTotalBudget();
        List<AdBillingLog> logs = billingLogMapper.selectList(new QueryWrapper<AdBillingLog>()
                .eq("campaign_id", campaign.getId())
                .eq("event_type", "impression"));
        long spent = logs.stream().mapToLong(l -> l.getCost() == null ? 0 : l.getCost()).sum();
        long expected = totalBudget - spent;
        if (remaining != expected) {
            log.warn("广告预算偏差 campaignId={}, redisRemaining={}, dbExpected={}, diff={}",
                    campaign.getId(), remaining, expected, remaining - expected);
        }
        String dailyValue = redis.opsForValue().get(
                AdBudgetService.dailyKey(campaign.getId(), LocalDate.now()));
        if (dailyValue != null && campaign.getDailyBudget() != null) {
            long dailyRemaining = Long.parseLong(dailyValue);
            long dailySpent = logs.stream()
                    .filter(l -> l.getEventTime() != null
                            && l.getEventTime().toLocalDate().equals(LocalDate.now()))
                    .mapToLong(l -> l.getCost() == null ? 0 : l.getCost()).sum();
            long dailyExpected = campaign.getDailyBudget() - dailySpent;
            if (dailyRemaining != dailyExpected) {
                log.warn("广告日预算偏差 campaignId={}, redis={}, dbExpected={}",
                        campaign.getId(), dailyRemaining, dailyExpected);
            }
        }
    }
}
