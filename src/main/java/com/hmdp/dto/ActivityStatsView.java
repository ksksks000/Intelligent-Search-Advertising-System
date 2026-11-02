package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 活动统计聚合视图，供精排读取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityStatsView {

    private long pv;
    private long clickCnt;
    private long orderCnt;
    private double ctr;
    private double cvr;

    public static ActivityStatsView empty() {
        return new ActivityStatsView(0, 0, 0, 0D, 0D);
    }
}
