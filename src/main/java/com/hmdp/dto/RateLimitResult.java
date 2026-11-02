package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 限流判定结果。
 */
@Data
@AllArgsConstructor
public class RateLimitResult {

    private boolean allowed;
    private long remaining;
    /** 命中的维度：1 user / 2 ip / 3 api / 0 none */
    private int dimension;

    public static RateLimitResult allow(long remaining) {
        return new RateLimitResult(true, remaining, 0);
    }
}
