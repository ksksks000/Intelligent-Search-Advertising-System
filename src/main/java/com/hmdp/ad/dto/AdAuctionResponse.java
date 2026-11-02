package com.hmdp.ad.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdAuctionResponse {

    private List<AdSlotItem> items = new ArrayList<>();
    private Boolean degraded;
    private List<String> degradedReasons = new ArrayList<>();
    private Long costMs;
    private Integer candidateCount;
    private Integer fillCount;
    private String bidMode = "FIRST_PRICE";
}
