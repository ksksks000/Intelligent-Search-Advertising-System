package com.hmdp.ad.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 竞价胜出的广告位素材。
 */
@Data
public class AdSlotItem {

    private Long adId;
    private Long campaignId;
    private Long activityId;
    private Long categoryId;
    /** 曝光计费幂等 ID，同时作为点击关联 ID */
    private String billingId;
    private String requestId;
    private String slotId;
    private String title;
    private String description;
    private String image;
    private String landingUrl;
    private Long bid;
    /** 校准后的 pCTR */
    private BigDecimal pctr;
    /** eCPM，分/千次 */
    private BigDecimal ecpm;
    private Boolean degraded;
}
