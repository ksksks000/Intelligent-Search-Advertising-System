package com.hmdp.enums;

import lombok.Getter;

/**
 * 秒杀资格判定业务码，与 Lua 返回值一一对应。
 */
@Getter
public enum SeckillCode {

    SUCCESS(0, "秒杀成功"),
    SOLD_OUT(1, "库存不足"),
    DUPLICATE(2, "不能重复下单"),
    NOT_STARTED(3, "秒杀尚未开始"),
    ENDED(4, "秒杀已结束"),
    SOLD_OUT_FLAG(5, "库存不足"),
    NOT_INITIALIZED(-1, "秒杀暂未开放"),
    UNKNOWN(Integer.MIN_VALUE, "秒杀失败");

    private final int code;
    private final String message;

    SeckillCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public static SeckillCode fromCode(int code) {
        for (SeckillCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
