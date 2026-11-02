package com.hmdp.ad.service;

import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.config.AdProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdFrequencyServiceTest {

    private static final long CAMPAIGN_ID = 999002L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @Resource
    private AdFrequencyService frequencyService;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private AdProperties properties;

    @AfterEach
    void cleanup() {
        String date = LocalDate.now().format(DAY);
        redis.delete("ad:freq:u:u-test:" + CAMPAIGN_ID + ":" + date);
        redis.delete("ad:freq:u:u-test-a:" + CAMPAIGN_ID + ":" + date);
        redis.delete("ad:freq:u:u-test-b:" + CAMPAIGN_ID + ":" + date);
        redis.delete("ad:freq:ip:1.2.3.4:" + CAMPAIGN_ID + ":" + date);
        properties.setAnonymousIpDailyFrequency(10);
    }

    @Test
    @DisplayName("同一用户第 N+1 次曝光被拒")
    void userDailyCap() {
        AdCampaign campaign = campaign(2, 0);
        assertTrue(frequencyService.tryAcquire(campaign, "u-test", "1.1.1.1", false, "b1").isAllowed());
        assertTrue(frequencyService.tryAcquire(campaign, "u-test", "1.1.1.1", false, "b2").isAllowed());
        AdFrequencyService.FrequencyResult rejected = frequencyService.tryAcquire(
                campaign, "u-test", "1.1.1.1", false, "b3");
        assertEquals(-1, rejected.getCode());
    }

    @Test
    @DisplayName("匿名流量按 IP 兜底：两个设备同 IP 第三次被拒")
    void anonymousIpCap() {
        properties.setAnonymousIpDailyFrequency(2);
        AdCampaign campaign = campaign(10, 0);
        assertTrue(frequencyService.tryAcquire(campaign, "u-test-a", "1.2.3.4", true, "a1").isAllowed());
        assertTrue(frequencyService.tryAcquire(campaign, "u-test-b", "1.2.3.4", true, "b1").isAllowed());
        AdFrequencyService.FrequencyResult rejected = frequencyService.tryAcquire(
                campaign, "u-test-c", "1.2.3.4", true, "c1");
        assertEquals(-2, rejected.getCode());
        redis.delete("ad:freq:u:u-test-c:" + CAMPAIGN_ID + ":" + LocalDate.now().format(DAY));
    }

    private AdCampaign campaign(int daily, int intervalSeconds) {
        AdCampaign campaign = new AdCampaign();
        campaign.setId(CAMPAIGN_ID);
        campaign.setFreqPerDay(daily);
        campaign.setFreqMinIntervalSeconds(intervalSeconds);
        return campaign;
    }
}
