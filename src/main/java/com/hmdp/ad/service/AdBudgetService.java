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
 * 广告预算：新写 budget_deduct.lua，不复用秒杀分段库存脚本。
 * 同时支持仅预算扣减（测试/管理）和频控+预算合并准入（在线竞价）。
 */
@Slf4j
@Service
public class AdBudgetService {

    private static final DefaultRedisScript<List> DEDUCT_SCRIPT;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final long IDEM_TTL_SECONDS = 7 * 24 * 3600L;
    private static final long FREQ_TTL_SECONDS = 2 * 24 * 3600L;
    private static final String DISABLED_USER_FREQ =
            RedisConstants.AD_FREQ_USER_KEY + "disabled";
    private static final String DISABLED_IP_FREQ =
            RedisConstants.AD_FREQ_IP_KEY + "disabled";

    static {
        DEDUCT_SCRIPT = new DefaultRedisScript<>();
        DEDUCT_SCRIPT.setLocation(new ClassPathResource("lua/budget_deduct.lua"));
        DEDUCT_SCRIPT.setResultType(List.class);
    }

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private AdProperties properties;

    public DeductResult tryDeduct(AdCampaign campaign, String requestId,
                                  String billingId, long cost) {
        return execute(campaign, requestId, billingId, cost,
                DISABLED_USER_FREQ, DISABLED_IP_FREQ, 0, 0, 0, "deduct");
    }

    /**
     * 扣减成功后 DB 落账失败时补偿：budget_deduct.lua 的 refund 分支回补总/日预算并删除幂等键。
     */
    public DeductResult refund(AdCampaign campaign, String requestId, String billingId) {
        long cost = campaign.getBid() == null ? 0 : campaign.getBid();
        return execute(campaign, requestId, billingId, cost,
                DISABLED_USER_FREQ, DISABLED_IP_FREQ, 0, 0, 0, "refund");
    }

    /**
     * 在线竞价准入：频控 + 预算 + 幂等一次完成。code 0/-1/-2 为频控拒绝，-3/-4/-5 为预算拒绝。
     */
    public DeductResult tryAdmit(AdCampaign campaign, String userKey, String ip,
                                 boolean anonymous, String billingId, String requestId) {
        int dailyLimit = campaign.getFreqPerDay() == null
                ? properties.getDefaultDailyFrequency() : campaign.getFreqPerDay();
        int minInterval = campaign.getFreqMinIntervalSeconds() == null
                ? properties.getDefaultMinIntervalSeconds() : campaign.getFreqMinIntervalSeconds();
        int ipLimit = anonymous ? properties.getAnonymousIpDailyFrequency() : 0;
        String date = LocalDate.now().format(DAY);
        String userFreqKey = RedisConstants.AD_FREQ_USER_KEY + userKey + ":"
                + campaign.getId() + ":" + date;
        String ipFreqKey = RedisConstants.AD_FREQ_IP_KEY
                + (ip == null || ip.trim().isEmpty() ? "unknown" : ip.trim())
                + ":" + campaign.getId() + ":" + date;
        long cost = campaign.getBid() == null ? 0 : campaign.getBid();
        return execute(campaign, requestId, billingId, cost,
                userFreqKey, ipFreqKey, dailyLimit,
                Math.max(0, minInterval) * 1000L, ipLimit, "deduct");
    }

    private DeductResult execute(AdCampaign campaign, String requestId, String billingId,
                                 long cost, String userFreqKey, String ipFreqKey,
                                 int dailyLimit, long minIntervalMs, int ipLimit,
                                 String mode) {
        if (campaign == null || campaign.getId() == null) {
            return DeductResult.fail(0);
        }
        List<?> result = redis.execute(DEDUCT_SCRIPT,
                Arrays.asList(userFreqKey, ipFreqKey,
                        totalKey(campaign.getId()),
                        dailyKey(campaign.getId(), LocalDate.now()),
                        idemKey(requestId, billingId)),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(dailyLimit),
                String.valueOf(minIntervalMs),
                String.valueOf(ipLimit),
                billingId == null ? "na" : billingId,
                String.valueOf(FREQ_TTL_SECONDS),
                String.valueOf(cost),
                String.valueOf(campaign.getTotalBudget() == null ? 0 : campaign.getTotalBudget()),
                String.valueOf(campaign.getDailyBudget() == null ? 0 : campaign.getDailyBudget()),
                String.valueOf(properties.getBudgetDailyTtlSeconds()),
                String.valueOf(IDEM_TTL_SECONDS),
                mode);
        if (result == null || result.size() < 3) {
            log.error("预算脚本返回异常，campaignId={}, result={}", campaign.getId(), result);
            return DeductResult.fail(-99);
        }
        int code = ((Number) result.get(0)).intValue();
        long totalAfter = ((Number) result.get(1)).longValue();
        long dailyAfter = ((Number) result.get(2)).longValue();
        if (code != 1 && code != 2) {
            log.info("广告预算/频控拒绝 campaignId={}, requestId={}, billingId={}, code={}",
                    campaign.getId(), requestId, billingId, code);
        }
        return new DeductResult(code, totalAfter, dailyAfter);
    }

    public void initIfAbsent(AdCampaign campaign) {
        if (campaign == null || campaign.getId() == null || campaign.getTotalBudget() == null) {
            return;
        }
        redis.opsForValue().setIfAbsent(totalKey(campaign.getId()),
                String.valueOf(campaign.getTotalBudget()));
    }

    public long topup(Long campaignId, long amount) {
        Long value = redis.opsForValue().increment(totalKey(campaignId), Math.max(0, amount));
        return value == null ? 0 : value;
    }

    public long remaining(Long campaignId) {
        String value = redis.opsForValue().get(totalKey(campaignId));
        return value == null ? 0 : Long.parseLong(value);
    }

    public static String totalKey(Long campaignId) {
        return RedisConstants.AD_BUDGET_TOTAL_KEY + campaignId;
    }

    public static String dailyKey(Long campaignId, LocalDate date) {
        return RedisConstants.AD_BUDGET_DAILY_KEY + campaignId + ":" + date.format(DAY);
    }

    public static String idemKey(String requestId, String billingId) {
        return RedisConstants.AD_BUDGET_IDEM_KEY
                + (requestId == null ? "na" : requestId) + ":" + billingId;
    }

    @Data
    public static class DeductResult {
        private final int code;
        private final long totalAfter;
        private final long dailyAfter;

        public boolean isSuccess() {
            return code == 1 || code == 2;
        }

        public boolean isDuplicate() {
            return code == 2;
        }

        public static DeductResult fail(int code) {
            return new DeductResult(code, 0, 0);
        }
    }
}
