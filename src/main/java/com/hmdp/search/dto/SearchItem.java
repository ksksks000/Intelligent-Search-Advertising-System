package com.hmdp.search.dto;

import lombok.Data;

@Data
public class SearchItem {

    private Long id;
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
    private String highlightTitle;
}
