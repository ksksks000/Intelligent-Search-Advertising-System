package com.hmdp.ad.dto;

import lombok.Data;

@Data
public class AdAuctionRequest {

    private String city;
    private String deviceType;
    private Integer slotCount;
    private String placement;
}
