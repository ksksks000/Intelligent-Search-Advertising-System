package com.hmdp.service.impl;

import com.hmdp.config.SeckillProperties;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillResult;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.metrics.SeckillMetrics;
import com.hmdp.mq.SeckillOrderMessage;
import com.hmdp.mq.SeckillOrderPublisher;
import com.hmdp.service.IActivityService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.SeckillStockService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

/**
 * 订单服务：秒杀资格判定已前移到 Redis Lua（时间窗 + 分段库存 + 一人一单），
 * 校验通过后异步发送到消息队列，由消费端创建订单、扣减 DB 库存。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillOrderPublisher orderPublisher;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillProperties seckillProperties;

    @Resource
    private SeckillMetrics metrics;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IActivityService activityService;

    /**
     * 应用启动时按 DB 库存初始化秒杀分段。
     * 已初始化（meta 存在）的优惠券不会被覆盖，避免重启重置实时库存。
     */
    @PostConstruct
    public void preloadSeckillVouchers() {
        if (!seckillProperties.isInitOnStartup()) {
            log.info("秒杀库存分段预热已关闭（hmdp.seckill.init-on-startup=false）");
            return;
        }
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        if (vouchers == null || vouchers.isEmpty()) {
            log.info("秒杀库存分段预热完成，数据库中没有秒杀优惠券数据");
            return;
        }
        int loaded = 0;
        for (SeckillVoucher voucher : vouchers) {
            try {
                List<VoucherOrder> existingOrders = list(
                        new QueryWrapper<VoucherOrder>().eq("voucher_id", voucher.getVoucherId()));
                int orderCount = existingOrders == null ? 0 : existingOrders.size();
                if (seckillStockService.initIfAbsent(voucher, orderCount) > 0) {
                    loaded++;
                }
                seedOrderSet(voucher.getVoucherId(), existingOrders);
            } catch (Exception e) {
                log.error("秒杀库存分段预热失败，voucherId={}", voucher.getVoucherId(), e);
            }
        }
        log.info("秒杀库存分段预热完成，共 {} 个，实际初始化 {} 个", vouchers.size(), loaded);
    }

    /**
     * 从 DB 已有订单回填一人一单集合；SADD 幂等，重复启动不会产生副作用。
     */
    private void seedOrderSet(Long voucherId, List<VoucherOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        String[] userIds = orders.stream()
                .map(order -> String.valueOf(order.getUserId()))
                .toArray(String[]::new);
        stringRedisTemplate.opsForSet().add(SeckillStockService.orderKey(voucherId), userIds);
        log.info("已从 DB 回填一人一单集合，voucherId={}, count={}", voucherId, userIds.length);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 活动-券联动校验放在入口：活动下线后，即使券本身仍上架也不允许秒杀
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null || !Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("优惠券不存在或已下架");
        }
        if (voucher.getActivityId() != null && !activityService.isActivityOnline(voucher.getActivityId())) {
            return Result.fail("活动已下线");
        }

        // requestId 在 Lua 之前生成：Lua 通过后作为订单号，P3 起同时作为 Stream 消息幂等键
        long requestId = redisIdWorker.nextId("order");

        // 资格判定 + 段内扣减 + 一人一单标记，全部在一个 Lua 脚本内原子完成
        SeckillResult result = seckillStockService.trySeckill(voucherId, userId, requestId);
        metrics.request(result.getCode());
        if (!result.isSuccess()) {
            log.debug("秒杀资格判定失败，requestId={}, voucherId={}, userId={}, code={}",
                    result.getRequestId(), voucherId, userId, result.getCode());
            return Result.fail(result.getCode().getMessage());
        }

        // Stream 模式下消息已由 Lua 原子写入；Rabbit 模式下由 publisher 发送
        SeckillOrderMessage message = new SeckillOrderMessage(
                result.getRequestId(), userId, voucherId, result.getSegment(), System.currentTimeMillis());
        orderPublisher.publish(message);
        log.debug("秒杀预扣成功，requestId={}, voucherId={}, userId={}, segment={}",
                result.getRequestId(), voucherId, userId, result.getSegment());
        // API 语义保持不变：data 仍为订单号
        return Result.ok(result.getRequestId());
    }
}
