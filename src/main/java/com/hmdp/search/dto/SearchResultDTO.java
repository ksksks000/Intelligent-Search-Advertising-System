package com.hmdp.search.dto;

import lombok.Data;

import java.util.List;

@Data
public class SearchResultDTO {

    private List<SearchItem> list;
    private Long total;
    private Boolean degraded;
    private Boolean fallback;
    private Long costMs;
    /** false 表示走向量召回时降级为纯倒排 */
    private Boolean vectorDegraded;
    /** 本次 query 向量是否命中 Redis 缓存 */
    private Boolean queryVectorCacheHit;
    private Boolean lexicalOnly;
}
