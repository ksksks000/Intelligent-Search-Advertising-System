package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class FeedItem {

    private Long activityId;
    private String title;
    private String subTitle;
    private String images;
    private Integer activityType;
    private Long categoryId;
    private Long shopId;
    private String city;
    private String area;
    private Long payValue;
    private Long originalPrice;
    private Integer qualityScore;
    private Double score;
    private List<String> sources;
    /** 广告位：true 表示该条为广告，自然排序字段不参与广告竞价 */
    private Boolean ad;
    private Long adId;
    private Long campaignId;
    private String slotId;
    private String billingId;
    private String requestId;
    private Long bid;
    private Double pctr;
    private Double ecpm;
}
