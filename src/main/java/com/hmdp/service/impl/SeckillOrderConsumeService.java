package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillIdempotent;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillIdempotentMapper;
import com.hmdp.mq.ConsumeResult;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.repository.SeckillVoucherStockRepository;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 秒杀订单消费事务：幂等表 + 订单落库 + DB 库存扣减在同一事务内完成。
 * <p>
 * 幂等三层：
 * <ol>
 *     <li>订单主键 requestId 快速判断</li>
 *     <li>tb_seckill_idempotent.request_id 唯一</li>
 *     <li>tb_seckill_idempotent / tb_voucher_order 的 (user_id, voucher_id) 唯一</li>
 * </ol>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "stream", matchIfMissing = true)
public class SeckillOrderConsumeService {

    private final IVoucherOrderService voucherOrderService;
    private final SeckillVoucherStockRepository stockRepository;
    private final SeckillIdempotentMapper idempotentMapper;
    private final TransactionTemplate transactionTemplate;

    public SeckillOrderConsumeService(IVoucherOrderService voucherOrderService,
                                      SeckillVoucherStockRepository stockRepository,
                                      SeckillIdempotentMapper idempotentMapper,
                                      PlatformTransactionManager transactionManager) {
        this.voucherOrderService = voucherOrderService;
        this.stockRepository = stockRepository;
        this.idempotentMapper = idempotentMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ConsumeResult handle(SeckillOrderMessage message) {
        if (message == null || message.getRequestId() == null
                || message.getUserId() == null || message.getVoucherId() == null
                || message.getSegment() == null) {
            log.error("秒杀消息字段缺失，进入死信: {}", message);
            return ConsumeResult.DEAD;
        }
        try {
            ConsumeResult result = transactionTemplate.execute(status -> doHandle(message));
            return result == null ? ConsumeResult.RETRY : result;
        } catch (Exception e) {
            log.error("秒杀订单消费失败，requestId={}", message.getRequestId(), e);
            return ConsumeResult.RETRY;
        }
    }

    private ConsumeResult doHandle(SeckillOrderMessage message) {
        Long requestId = message.getRequestId();
        String requestIdText = String.valueOf(requestId);

        // 快速幂等：订单已存在直接成功
        if (voucherOrderService.getById(requestId) != null) {
            return ConsumeResult.SUCCESS;
        }

        SeckillIdempotent idempotent = new SeckillIdempotent()
                .setRequestId(requestIdText)
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId())
                .setStatus(SeckillIdempotent.PROCESSING);
        try {
            int inserted = idempotentMapper.insert(idempotent);
            if (inserted != 1) {
                throw new IllegalStateException("幂等记录写入失败，requestId=" + requestIdText);
            }
        } catch (DuplicateKeyException e) {
            return handleIdempotentConflict(message, requestIdText);
        }

        VoucherOrder order = new VoucherOrder()
                .setId(requestId)
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId());
        try {
            if (!voucherOrderService.save(order)) {
                throw new IllegalStateException("订单落库失败，requestId=" + requestIdText);
            }
        } catch (DuplicateKeyException e) {
            // 同一 requestId 已被其他消费者写入，视为成功
            idempotentMapper.updateById(new SeckillIdempotent()
                    .setRequestId(requestIdText)
                    .setStatus(SeckillIdempotent.SUCCESS));
            return ConsumeResult.SUCCESS;
        }

        if (!stockRepository.deduct(message.getVoucherId())) {
            // 抛异常让事务回滚，消息保留 pending 等待重试/死信
            throw new IllegalStateException("DB 库存扣减失败，voucherId=" + message.getVoucherId());
        }

        int updated = idempotentMapper.updateById(new SeckillIdempotent()
                .setRequestId(requestIdText)
                .setStatus(SeckillIdempotent.SUCCESS));
        if (updated != 1) {
            throw new IllegalStateException("幂等状态更新失败，requestId=" + requestIdText);
        }
        return ConsumeResult.SUCCESS;
    }

    private ConsumeResult handleIdempotentConflict(SeckillOrderMessage message, String requestIdText) {
        SeckillIdempotent existing = idempotentMapper.selectById(requestIdText);
        if (existing != null) {
            if (existing.getStatus() != null && existing.getStatus() == SeckillIdempotent.SUCCESS) {
                return ConsumeResult.SUCCESS;
            }
            // 另一实例正在处理同一 requestId
            return ConsumeResult.RETRY;
        }

        SeckillIdempotent sameUser = idempotentMapper.selectOne(
                new QueryWrapper<SeckillIdempotent>()
                        .eq("user_id", message.getUserId())
                        .eq("voucher_id", message.getVoucherId()));
        if (sameUser != null) {
            log.warn("同一用户重复下单，requestId={}, existingRequestId={}",
                    requestIdText, sameUser.getRequestId());
            return ConsumeResult.DUPLICATE;
        }
        return ConsumeResult.RETRY;
    }
}
