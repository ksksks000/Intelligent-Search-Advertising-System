package com.hmdp.search.dto;

import lombok.Data;

@Data
public class ReindexResult {

    private String index;
    private Long docCount;
    private Long elapsedMs;
}
