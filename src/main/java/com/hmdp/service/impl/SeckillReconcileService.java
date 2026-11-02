package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillIdempotent;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillIdempotentMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillStockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀对账：定时比较 Redis（总库存/已购集合/初始总量）与 DB（库存/订单数），
 * 只告警不自动修正，避免在异步链路正常延迟时误改数据。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "hmdp.seckill.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReconcileService {

    private final StringRedisTemplate redis;
    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;
    private final SeckillIdempotentMapper idempotentMapper;

    public SeckillReconcileService(StringRedisTemplate redis,
                                   ISeckillVoucherService seckillVoucherService,
                                   IVoucherOrderService voucherOrderService,
                                   SeckillIdempotentMapper idempotentMapper) {
        this.redis = redis;
        this.seckillVoucherService = seckillVoucherService;
        this.voucherOrderService = voucherOrderService;
        this.idempotentMapper = idempotentMapper;
    }

    @Scheduled(fixedDelayString = "${hmdp.seckill.reconcile.interval-ms:60000}")
    public void reconcile() {
        try {
            List<SeckillVoucher> vouchers = seckillVoucherService.list();
            int warnings = 0;
            for (SeckillVoucher voucher : vouchers) {
                warnings += reconcileVoucher(voucher);
            }
            warnings += checkStaleIdempotent();

            if (warnings > 0) {
                log.warn("秒杀对账完成，发现 {} 处需要关注的不一致", warnings);
            } else {
                log.debug("秒杀对账完成，未发现不一致");
            }
        } catch (Exception e) {
            log.error("秒杀对账执行失败", e);
        }
    }

    private int reconcileVoucher(SeckillVoucher voucher) {
        Long voucherId = voucher.getVoucherId();
        Object metaTotalValue = redis.opsForHash().get(SeckillStockService.metaKey(voucherId), "total");
        if (metaTotalValue == null) {
            return 0;
        }
        String redisTotalValue = redis.opsForValue().get(SeckillStockService.totalKey(voucherId));
        if (redisTotalValue == null) {
            log.warn("Redis 总库存缺失，voucherId={}", voucherId);
            return 1;
        }

        long metaTotal = Long.parseLong(String.valueOf(metaTotalValue));
        long redisTotal = Long.parseLong(redisTotalValue);
        Long orderSetSizeValue = redis.opsForSet().size(SeckillStockService.orderKey(voucherId));
        long orderSetSize = orderSetSizeValue == null ? 0 : orderSetSizeValue;
        int dbStock = voucher.getStock() == null ? 0 : voucher.getStock();
        long dbOrders = voucherOrderService.count(
                new QueryWrapper<VoucherOrder>().eq("voucher_id", voucherId));

        int warnings = 0;
        if (redisTotal + orderSetSize != metaTotal) {
            log.warn("Redis 内部不一致，voucherId={}, total={}, orderSet={}, metaTotal={}",
                    voucherId, redisTotal, orderSetSize, metaTotal);
            warnings++;
        }
        if (dbStock + dbOrders != metaTotal) {
            log.warn("DB 内部不一致，voucherId={}, dbStock={}, dbOrders={}, metaTotal={}",
                    voucherId, dbStock, dbOrders, metaTotal);
            warnings++;
        }
        if (redisTotal != dbStock) {
            // 异步消费存在正常延迟，这里只记录 debug，持续偏差可结合日志排查
            log.debug("Redis/DB 库存存在偏差（可能是消费延迟），voucherId={}, redisTotal={}, dbStock={}",
                    voucherId, redisTotal, dbStock);
        }
        return warnings;
    }

    private int checkStaleIdempotent() {
        Long stale = idempotentMapper.selectCount(
                new QueryWrapper<SeckillIdempotent>()
                        .eq("status", SeckillIdempotent.PROCESSING)
                        .lt("update_time", LocalDateTime.now().minusMinutes(5)));
        if (stale != null && stale > 0) {
            log.warn("存在 {} 条超过 5 分钟的处理中幂等记录，需检查 pending 消息", stale);
            return 1;
        }
        return 0;
    }
}
