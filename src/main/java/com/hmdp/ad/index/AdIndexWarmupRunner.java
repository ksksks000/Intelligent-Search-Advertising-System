package com.hmdp.ad.index;

import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.ad.service.AdBidCacheService;
import com.hmdp.ad.service.AdBudgetService;
import com.hmdp.config.AdProperties;
import com.hmdp.mapper.AdCampaignMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AdIndexWarmupRunner implements ApplicationRunner {

    @Resource
    private AdProperties properties;
    @Resource
    private AdCampaignMapper campaignMapper;
    @Resource
    private AdCampaignIndexService indexService;
    @Resource
    private AdBudgetService budgetService;
    @Resource
    private AdBidCacheService bidCacheService;

    private static final List<String> CITIES = Arrays.asList(
            "杭州", "上海", "北京", "深圳", "成都");

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        int count = 0;
        Map<String, List<AdCampaign>> byCity = new HashMap<>();
        for (AdCampaign campaign : campaignMapper.selectList(null)) {
            budgetService.initIfAbsent(campaign);
            indexService.refresh(campaign);
            count++;
            if (Integer.valueOf(AdCampaign.STATUS_ONLINE).equals(campaign.getStatus())) {
                String targetCity = campaign.getTargetCity();
                if (targetCity == null || targetCity.trim().isEmpty()
                        || "ALL".equalsIgnoreCase(targetCity)) {
                    for (String city : CITIES) {
                        byCity.computeIfAbsent(city, k -> new ArrayList<>()).add(campaign);
                    }
                } else {
                    byCity.computeIfAbsent(targetCity.trim(), k -> new ArrayList<>()).add(campaign);
                }
            }
        }
        for (Map.Entry<String, List<AdCampaign>> entry : byCity.entrySet()) {
            bidCacheService.put(entry.getKey(), entry.getValue());
        }
        log.info("广告定向索引预热完成，campaigns={}, cities={}, cost={}ms", count,
                byCity.size(), System.currentTimeMillis() - start);
    }
}
