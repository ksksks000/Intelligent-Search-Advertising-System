package com.hmdp.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 纯单元测试：只验证分段算法，不依赖 Redis / Spring。
 */
class SeckillStockServiceTest {

    @Test
    @DisplayName("库存均分：10/3 = 4,3,3")
    void splitCapacity_even() {
        assertArrayEquals(new int[]{4, 3, 3}, SeckillStockService.splitCapacity(10, 3));
    }

    @Test
    @DisplayName("段容量之和等于总库存")
    void splitCapacity_sumEqualsTotal() {
        int total = 1000;
        int[] caps = SeckillStockService.splitCapacity(total, 16);
        int sum = 0;
        for (int cap : caps) {
            sum += cap;
        }
        assertEquals(total, sum);
    }

    @Test
    @DisplayName("库存小于段数时下调段数，避免空段")
    void resolveSegmentCount_cappedByStock() {
        assertEquals(1, SeckillStockService.resolveSegmentCount(0, 16));
        assertEquals(5, SeckillStockService.resolveSegmentCount(5, 16));
        assertEquals(16, SeckillStockService.resolveSegmentCount(100, 16));
    }
}
