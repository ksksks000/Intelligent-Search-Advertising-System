package com.hmdp.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * activity_index 的文档模型，字段与阶段 1 的 tb_activity 对齐。
 * embedding 只在索引/检索时使用，不返回给前端。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityDocument {

    private Long id;
    private String title;
    private String subTitle;
    private String description;
    private String images;
    private List<String> tags;
    private Long categoryId;
    private Long shopId;
    private Integer activityType;
    private String city;
    private String area;
    private Location location;
    private Long beginTime;
    private Long endTime;
    private Integer status;
    private Integer qualityScore;
    private Long payValue;
    private Long originalPrice;
    private float[] embedding;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Location {
        private Double lat;
        private Double lon;
    }
}
