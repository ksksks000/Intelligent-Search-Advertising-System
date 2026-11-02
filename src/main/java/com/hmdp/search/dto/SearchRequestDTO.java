package com.hmdp.search.dto;

import lombok.Data;

@Data
public class SearchRequestDTO {

    private String query;
    private Integer current = 1;
    private Integer size = 10;
    private Long categoryId;
    private Integer activityType;
    private String city;
    /** 价格区间，单位分，作用在 payValue */
    private Long minPayValue;
    private Long maxPayValue;
}
