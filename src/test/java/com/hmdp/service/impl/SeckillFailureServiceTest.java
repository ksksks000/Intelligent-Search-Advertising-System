package com.hmdp.service.impl;

import com.hmdp.entity.SeckillFailure;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillFailureMapper;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillStockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纯单元测试：死信补偿在"订单是否存在/回滚是否成功"下的分支。
 */
class SeckillFailureServiceTest {

    private IVoucherOrderService voucherOrderService;
    private SeckillStockService seckillStockService;
    private SeckillFailureMapper failureMapper;
    private SeckillFailureService service;

    @BeforeEach
    void setUp() {
        voucherOrderService = mock(IVoucherOrderService.class);
        seckillStockService = mock(SeckillStockService.class);
        failureMapper = mock(SeckillFailureMapper.class);
        when(failureMapper.insert(any())).thenReturn(1);
        service = new SeckillFailureService(voucherOrderService, seckillStockService, failureMapper,
                new SeckillMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("订单已存在：不回滚库存，标记已补偿")
    void orderExists() {
        when(voucherOrderService.getById(100L)).thenReturn(new VoucherOrder());

        service.handleDead(message(100L), "boom");

        verify(seckillStockService, never())
                .rollback(anyLong(), anyLong(), anyString(), anyInt(), anyBoolean());
        assertEquals(SeckillFailure.COMPENSATED, capture().getStatus().intValue());
    }

    @Test
    @DisplayName("订单不存在且回滚成功：归还库存，标记已补偿")
    void rollbackSuccess() {
        when(voucherOrderService.getById(100L)).thenReturn(null);
        when(seckillStockService.rollback(2L, 1L, "100", 3, true)).thenReturn(true);

        service.handleDead(message(100L), "boom");

        verify(seckillStockService).rollback(2L, 1L, "100", 3, true);
        assertEquals(SeckillFailure.COMPENSATED, capture().getStatus().intValue());
    }

    @Test
    @DisplayName("回滚失败：落表待人工处理")
    void rollbackFail() {
        when(voucherOrderService.getById(100L)).thenReturn(null);
        when(seckillStockService.rollback(2L, 1L, "100", 3, true)).thenReturn(false);

        service.handleDead(message(100L), "boom");

        assertEquals(SeckillFailure.PENDING, capture().getStatus().intValue());
    }

    @Test
    @DisplayName("订单查询异常：不盲目回滚，落表待人工处理")
    void orderQueryFail() {
        when(voucherOrderService.getById(100L)).thenThrow(new RuntimeException("db down"));

        service.handleDead(message(100L), "boom");

        verify(seckillStockService, never())
                .rollback(anyLong(), anyLong(), anyString(), anyInt(), anyBoolean());
        assertEquals(SeckillFailure.PENDING, capture().getStatus().intValue());
    }

    private SeckillFailure capture() {
        ArgumentCaptor<SeckillFailure> captor = ArgumentCaptor.forClass(SeckillFailure.class);
        verify(failureMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static SeckillOrderMessage message(Long requestId) {
        return new SeckillOrderMessage(requestId, 1L, 2L, 3, System.currentTimeMillis());
    }
}
