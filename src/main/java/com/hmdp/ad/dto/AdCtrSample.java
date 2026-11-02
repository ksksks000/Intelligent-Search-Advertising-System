package com.hmdp.ad.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CTR 校准样本：raw 为校准前预测概率，clicked 为真实点击。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdCtrSample {

    private double raw;
    private int clicked;
}
