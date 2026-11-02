package com.hmdp.ad.service;

import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.config.AdProperties;
import com.hmdp.dto.UserProfile;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 规则 CTR：类目历史 CTR + 用户类目 CTR + 质量因子 + 出价归一化，再经保序回归校准。
 */
@Service
public class AdCtrPredictor {

    @Resource
    private AdProperties properties;

    @Resource
    private AdCtrStatsService statsService;

    @Resource
    private AdCtrCalibrationService calibrationService;

    public double predict(AdCampaign campaign, UserProfile profile, long maxBid) {
        return calibrationService.calibrate(raw(campaign, profile, maxBid));
    }

    public double raw(AdCampaign campaign, UserProfile profile, long maxBid) {
        Long categoryId = firstCategory(campaign);
        double categoryCtr = statsService.categoryCtr(categoryId);
        double userCategoryCtr = userCategoryCtr(profile, categoryId);
        double quality = campaign.getQualityFactor() == null
                ? 0.5D : Math.min(1D, campaign.getQualityFactor().doubleValue() / 2D);
        long bid = campaign.getBid() == null ? 0 : campaign.getBid();
        double bidNorm = maxBid > 0 ? Math.min(1D, (double) bid / maxBid) : 0.5D;
        double raw = properties.getCtrPrior()
                + properties.getCtrCategoryWeight() * categoryCtr
                + properties.getCtrUserCategoryWeight() * userCategoryCtr
                + properties.getCtrQualityWeight() * quality
                + properties.getCtrBidWeight() * bidNorm;
        return Math.max(properties.getCtrMin(), Math.min(properties.getCtrMax(), raw));
    }

    public static Long firstCategory(AdCampaign campaign) {
        if (campaign == null || campaign.getTargetCategories() == null
                || campaign.getTargetCategories().trim().isEmpty()) {
            return null;
        }
        String first = campaign.getTargetCategories().split("[,，]")[0].trim();
        try {
            return Long.valueOf(first);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double userCategoryCtr(UserProfile profile, Long categoryId) {
        if (profile == null || profile.getTotalClicks() <= 0 || categoryId == null
                || profile.getCategoryClickCounts() == null) {
            return 0.02D;
        }
        Integer clicks = profile.getCategoryClickCounts().get(categoryId);
        return clicks == null ? 0.02D
                : Math.min(1D, (double) clicks / profile.getTotalClicks());
    }
}
