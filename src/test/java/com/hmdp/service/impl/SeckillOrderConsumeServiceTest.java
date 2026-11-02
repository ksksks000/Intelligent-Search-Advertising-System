package com.hmdp.service.impl;

import com.hmdp.entity.SeckillIdempotent;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillIdempotentMapper;
import com.hmdp.mq.ConsumeResult;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.repository.SeckillVoucherStockRepository;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 纯单元测试：用 mock 覆盖消费事务的幂等分支，不连 MySQL。
 */
class SeckillOrderConsumeServiceTest {

    private IVoucherOrderService voucherOrderService;
    private SeckillVoucherStockRepository stockRepository;
    private SeckillIdempotentMapper idempotentMapper;
    private SeckillOrderConsumeService service;

    @BeforeEach
    void setUp() {
        voucherOrderService = mock(IVoucherOrderService.class);
        stockRepository = mock(SeckillVoucherStockRepository.class);
        idempotentMapper = mock(SeckillIdempotentMapper.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new SeckillOrderConsumeService(
                voucherOrderService, stockRepository, idempotentMapper, txManager);
    }

    @Test
    @DisplayName("正常消费：幂等表 -> 订单 -> 扣库存 -> 标记成功")
    void success() {
        SeckillOrderMessage message = message(100L, 1L, 2L, 3);
        when(idempotentMapper.insert(any())).thenReturn(1);
        when(voucherOrderService.save(any())).thenReturn(true);
        when(stockRepository.deduct(2L)).thenReturn(true);
        when(idempotentMapper.updateById(any())).thenReturn(1);

        assertEquals(ConsumeResult.SUCCESS, service.handle(message));

        verify(idempotentMapper).insert(any());
        verify(voucherOrderService).save(any());
        verify(stockRepository).deduct(2L);
        ArgumentCaptor<SeckillIdempotent> captor = ArgumentCaptor.forClass(SeckillIdempotent.class);
        verify(idempotentMapper).updateById(captor.capture());
        assertEquals(SeckillIdempotent.SUCCESS, captor.getValue().getStatus().intValue());
    }

    @Test
    @DisplayName("订单已存在：直接成功，不重复写库")
    void existingOrder_shortCircuits() {
        when(voucherOrderService.getById(100L)).thenReturn(new VoucherOrder());

        assertEquals(ConsumeResult.SUCCESS, service.handle(message(100L, 1L, 2L, 3)));

        verify(idempotentMapper, never()).insert(any());
        verify(voucherOrderService, never()).save(any());
        verify(stockRepository, never()).deduct(any());
    }

    @Test
    @DisplayName("DB 库存扣减失败：返回可重试，且不标记成功")
    void stockDeductFailure_retry() {
        when(idempotentMapper.insert(any())).thenReturn(1);
        when(voucherOrderService.save(any())).thenReturn(true);
        when(stockRepository.deduct(2L)).thenReturn(false);

        assertEquals(ConsumeResult.RETRY, service.handle(message(100L, 1L, 2L, 3)));

        verify(idempotentMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("同一 requestId 并发：未完成时返回可重试，已完成时返回成功")
    void sameRequest_concurrent() {
        when(idempotentMapper.insert(any())).thenThrow(new DuplicateKeyException("dup"));
        when(idempotentMapper.selectById("100"))
                .thenReturn(new SeckillIdempotent().setRequestId("100")
                        .setStatus(SeckillIdempotent.PROCESSING));
        assertEquals(ConsumeResult.RETRY, service.handle(message(100L, 1L, 2L, 3)));

        when(idempotentMapper.selectById("100"))
                .thenReturn(new SeckillIdempotent().setRequestId("100")
                        .setStatus(SeckillIdempotent.SUCCESS));
        assertEquals(ConsumeResult.SUCCESS, service.handle(message(100L, 1L, 2L, 3)));
    }

    @Test
    @DisplayName("同一用户已下单：DB 唯一键兜底返回重复")
    void duplicateUser() {
        when(idempotentMapper.insert(any())).thenThrow(new DuplicateKeyException("dup"));
        when(idempotentMapper.selectById("100")).thenReturn(null);
        when(idempotentMapper.selectOne(any()))
                .thenReturn(new SeckillIdempotent().setRequestId("99").setStatus(1));

        assertEquals(ConsumeResult.DUPLICATE, service.handle(message(100L, 1L, 2L, 3)));
        verify(voucherOrderService, never()).save(any());
        verify(stockRepository, never()).deduct(any());
    }

    @Test
    @DisplayName("字段缺失：直接进入死信，不开启事务")
    void invalidMessage_dead() {
        SeckillOrderMessage invalid = new SeckillOrderMessage(null, 1L, 2L, 3, 1L);

        assertEquals(ConsumeResult.DEAD, service.handle(invalid));
        verifyNoInteractions(voucherOrderService, stockRepository, idempotentMapper);
    }

    private static SeckillOrderMessage message(Long requestId, Long userId, Long voucherId, Integer segment) {
        return new SeckillOrderMessage(requestId, userId, voucherId, segment, System.currentTimeMillis());
    }
}
