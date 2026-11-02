package com.hmdp.ad.service;

import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.config.AdProperties;
import com.hmdp.utils.RedisConstants;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 广告频控：用户-广告-日期 ZSet；匿名用户额外校验 IP-广告-日期。
 */
@Slf4j
@Service
public class AdFrequencyService {

    private static final DefaultRedisScript<Long> SCRIPT;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final long TTL_SECONDS = 2 * 24 * 3600L;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("lua/ad_freq.lua"));
        SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private AdProperties properties;

    public FrequencyResult tryAcquire(AdCampaign campaign, String userKey, String ip,
                                      boolean anonymous, String billingId) {
        int dailyLimit = campaign.getFreqPerDay() == null
                ? properties.getDefaultDailyFrequency() : campaign.getFreqPerDay();
        int minInterval = campaign.getFreqMinIntervalSeconds() == null
                ? properties.getDefaultMinIntervalSeconds() : campaign.getFreqMinIntervalSeconds();
        int ipLimit = anonymous ? properties.getAnonymousIpDailyFrequency() : 0;
        String date = LocalDate.now().format(DAY);
        String userFreqKey = RedisConstants.AD_FREQ_USER_KEY + userKey + ":" + campaign.getId()
                + ":" + date;
        String ipFreqKey = RedisConstants.AD_FREQ_IP_KEY
                + (ip == null || ip.trim().isEmpty() ? "unknown" : ip.trim())
                + ":" + campaign.getId() + ":" + date;
        Long code = redis.execute(SCRIPT, Arrays.asList(userFreqKey, ipFreqKey),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(dailyLimit),
                String.valueOf(Math.max(0, minInterval) * 1000L),
                String.valueOf(ipLimit),
                billingId,
                String.valueOf(TTL_SECONDS));
        int value = code == null ? 1 : code.intValue();
        if (value != 1) {
            log.info("广告频控拒绝 campaignId={}, userKey={}, anonymous={}, ip={}, code={}",
                    campaign.getId(), userKey, anonymous, ip, value);
        }
        return new FrequencyResult(value);
    }

    @Data
    public static class FrequencyResult {
        private final int code;

        public boolean isAllowed() {
            return code == 1;
        }
    }
}
