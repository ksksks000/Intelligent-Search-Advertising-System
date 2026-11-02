package com.hmdp.service.impl;

import com.hmdp.entity.SeckillFailure;
import com.hmdp.mapper.SeckillFailureMapper;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 死信补偿：消息进入死信流前先确认订单是否真的未落库。
 * <ul>
 *     <li>订单已存在：不回调库存，标记为已补偿</li>
 *     <li>订单不存在且回滚成功：归还 Redis 库存，标记为已补偿</li>
 *     <li>无法确认或回滚失败：落失败表，等待人工处理</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "stream", matchIfMissing = true)
public class SeckillFailureService {

    private final IVoucherOrderService voucherOrderService;
    private final SeckillStockService seckillStockService;
    private final SeckillFailureMapper failureMapper;
    private final SeckillMetrics metrics;

    public SeckillFailureService(IVoucherOrderService voucherOrderService,
                                 SeckillStockService seckillStockService,
                                 SeckillFailureMapper failureMapper,
                                 SeckillMetrics metrics) {
        this.voucherOrderService = voucherOrderService;
        this.seckillStockService = seckillStockService;
        this.failureMapper = failureMapper;
        this.metrics = metrics;
    }

    public void handleDead(SeckillOrderMessage message, String error) {
        if (message == null || message.getRequestId() == null) {
            return;
        }
        Boolean orderExists = null;
        try {
            orderExists = voucherOrderService.getById(message.getRequestId()) != null;
        } catch (Exception e) {
            log.error("死信处理时查询订单失败，requestId={}", message.getRequestId(), e);
        }

        boolean rolledBack = false;
        if (Boolean.FALSE.equals(orderExists) && message.getSegment() != null) {
            try {
                rolledBack = seckillStockService.rollback(
                        message.getVoucherId(), message.getUserId(),
                        String.valueOf(message.getRequestId()), message.getSegment(), true);
            } catch (Exception e) {
                log.error("死信库存回滚失败，requestId={}", message.getRequestId(), e);
            }
            metrics.rollback(rolledBack);
        }

        boolean resolved = Boolean.TRUE.equals(orderExists) || rolledBack;
        record(message, error, resolved);
        log.warn("死信处理完成，requestId={}, orderExists={}, rolledBack={}, resolved={}",
                message.getRequestId(), orderExists, rolledBack, resolved);
    }

    private void record(SeckillOrderMessage message, String error, boolean resolved) {
        String requestId = String.valueOf(message.getRequestId());
        String brief = truncate(error, 500);
        SeckillFailure failure = new SeckillFailure()
                .setRequestId(requestId)
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId())
                .setSegment(message.getSegment())
                .setError(brief)
                .setStatus(resolved ? SeckillFailure.COMPENSATED : SeckillFailure.PENDING);
        try {
            failureMapper.insert(failure);
        } catch (DuplicateKeyException e) {
            failureMapper.updateById(new SeckillFailure()
                    .setRequestId(requestId)
                    .setError(brief)
                    .setStatus(failure.getStatus()));
        } catch (Exception e) {
            log.error("写入失败补偿表失败，requestId={}", requestId, e);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
