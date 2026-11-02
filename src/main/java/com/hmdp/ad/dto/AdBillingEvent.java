package com.hmdp.ad.dto;

import lombok.Data;

@Data
public class AdBillingEvent {

    private String billingId;
    private String requestId;
    private Long adId;
    private Long campaignId;
    private Long advertiserId;
    private String userKey;
    private String deviceId;
    private String ip;
    private String eventType;
    private String slotId;
    private Long bid;
    private Long cost;
    private Long eventTime;
}
