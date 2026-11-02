package com.hmdp.ad.index;

import lombok.Data;

/**
 * 广告索引事件：计划/广告创建、更新、上下线。
 */
@Data
public class AdIndexEvent {

    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";

    private Long adId;
    private Long campaignId;
    private String eventType;
    private Long version;
}
