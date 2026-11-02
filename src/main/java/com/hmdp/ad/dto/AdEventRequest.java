package com.hmdp.ad.dto;

import lombok.Data;

@Data
public class AdEventRequest {

    private Long adId;
    private Long campaignId;
    private String slotId;
    private String requestId;
    private String billingId;
    private String deviceId;
    private String ip;
    private Long activityId;
    private Long categoryId;
    /** conversion 时使用 */
    private String clickId;
    private String orderId;
}
