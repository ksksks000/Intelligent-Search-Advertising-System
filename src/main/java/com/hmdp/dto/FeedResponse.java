package com.hmdp.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FeedResponse {

    private List<FeedItem> list;
    private String nextCursor;
    private Boolean degraded;
    private List<String> degradedSources;
    private Long costMs;
    private String cacheLevel;
    private Boolean coldStart;
    /** 各阶段耗时（毫秒），用于阶段 4 前置修复 B 定位瓶颈 */
    private Map<String, Long> stageCosts;
}
