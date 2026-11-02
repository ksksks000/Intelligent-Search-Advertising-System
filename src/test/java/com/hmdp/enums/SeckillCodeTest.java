package com.hmdp.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeckillCodeTest {

    @Test
    @DisplayName("Lua 返回码可映射为枚举，未知码降级为 UNKNOWN")
    void fromCode() {
        assertEquals(SeckillCode.SUCCESS, SeckillCode.fromCode(0));
        assertEquals(SeckillCode.SOLD_OUT, SeckillCode.fromCode(1));
        assertEquals(SeckillCode.DUPLICATE, SeckillCode.fromCode(2));
        assertEquals(SeckillCode.NOT_STARTED, SeckillCode.fromCode(3));
        assertEquals(SeckillCode.ENDED, SeckillCode.fromCode(4));
        assertEquals(SeckillCode.SOLD_OUT_FLAG, SeckillCode.fromCode(5));
        assertEquals(SeckillCode.NOT_INITIALIZED, SeckillCode.fromCode(-1));
        assertEquals(SeckillCode.UNKNOWN, SeckillCode.fromCode(999));
    }

    @Test
    @DisplayName("失败码带有可直接返回前端的提示")
    void message() {
        assertEquals("不能重复下单", SeckillCode.DUPLICATE.getMessage());
        assertEquals("库存不足", SeckillCode.SOLD_OUT.getMessage());
    }
}
