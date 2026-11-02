package com.hmdp.dto;

import lombok.Data;

import java.util.Map;

/**
 * 推荐行为埋点请求。
 */
@Data
public class RecEventDTO {

    public static final String IMPRESSION = "impression";
    public static final String CLICK = "click";
    public static final String DWELL = "dwell";
    public static final String ORDER = "order";

    private String eventType;
    private Long activityId;
    /** search/feed/detail/ad */
    private String scene;
    /** 停留时长，毫秒；dwell 事件使用 */
    private Long dwellMs;
    private String traceId;
    /** 匿名设备ID，未登录时必填 */
    private String deviceId;
    private Map<String, Object> ext;
}
