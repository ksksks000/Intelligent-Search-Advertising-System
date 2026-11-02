package com.hmdp.dto;

import com.hmdp.enums.SeckillCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lua 秒杀资格判定结果：业务码、命中的库存段、本次请求的 requestId。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResult {

    private SeckillCode code;
    private int segment;
    private Long requestId;

    public boolean isSuccess() {
        return code == SeckillCode.SUCCESS;
    }
}
