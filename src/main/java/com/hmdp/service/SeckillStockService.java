package com.hmdp.service;

import com.hmdp.config.SeckillProperties;
import com.hmdp.config.SeckillStreamProperties;
import com.hmdp.dto.SeckillResult;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.enums.SeckillCode;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 秒杀库存分段服务：负责分段初始化、重置、资格判定与回滚。
 * <p>
 * Redis key 约定（{voucherId} 为实际数字 id，非 hash tag）：
 * <ul>
 *     <li>seckill:meta:{voucherId}          Hash：begin/end/segments/total/cap:i</li>
 *     <li>seckill:stock:{voucherId}:total   总剩余库存</li>
 *     <li>seckill:stock:{voucherId}:s:{i}   第 i 段剩余库存</li>
 *     <li>seckill:soldout:{voucherId}       售罄标记</li>
 *     <li>seckill:seg:cursor:{voucherId}    段轮转游标</li>
 *     <li>seckill:rollback:{voucherId}      已回滚 requestId 集合</li>
 *     <li>seckill:order:{voucherId}         已下单用户集合（沿用原 key，保持兼容）</li>
 * </ul>
 * 当前部署为单节点 Redis；若迁移集群，需要给上述 key 统一加 {voucherId} hash tag。
 */
@Slf4j
@Service
public class SeckillStockService {

    private static final DefaultRedisScript<List> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> INIT_SCRIPT;
    private static final DefaultRedisScript<Long> RESET_SCRIPT;
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill_segment.lua"));
        SECKILL_SCRIPT.setResultType(List.class);

        INIT_SCRIPT = new DefaultRedisScript<>();
        INIT_SCRIPT.setLocation(new ClassPathResource("lua/seckill_stock_init.lua"));
        INIT_SCRIPT.setResultType(Long.class);

        RESET_SCRIPT = new DefaultRedisScript<>();
        RESET_SCRIPT.setLocation(new ClassPathResource("lua/seckill_stock_reset.lua"));
        RESET_SCRIPT.setResultType(Long.class);

        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("lua/seckill_stock_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final SeckillProperties properties;
    private final SeckillStreamProperties streamProperties;

    public SeckillStockService(StringRedisTemplate redis,
                               SeckillProperties properties,
                               SeckillStreamProperties streamProperties) {
        this.redis = redis;
        this.properties = properties;
        this.streamProperties = streamProperties;
    }

    /**
     * 幂等初始化：meta 已存在时不做任何事，返回 0；首次初始化返回 1。
     */
    public int initIfAbsent(SeckillVoucher voucher) {
        return initIfAbsent(voucher, 0);
    }

    /**
     * @param existingOrders DB 中该券已有订单数；用于计算初始总量（剩余库存 + 已售）
     */
    public int initIfAbsent(SeckillVoucher voucher, int existingOrders) {
        Plan plan = plan(voucher, existingOrders);
        Long result = redis.execute(INIT_SCRIPT, plan.keys, plan.args.toArray());
        return result == null ? 0 : result.intValue();
    }

    /**
     * 强制重置：按当前 DB 库存重建分段，保留 order set。
     * 仅用于管理操作，执行前应暂停秒杀流量，避免与在途请求竞争。
     */
    public boolean reset(SeckillVoucher voucher) {
        return reset(voucher, 0);
    }

    public boolean reset(SeckillVoucher voucher, int existingOrders) {
        Plan plan = plan(voucher, existingOrders);
        Long result = redis.execute(RESET_SCRIPT, plan.keys, plan.args.toArray());
        return result != null && result == 1L;
    }

    /**
     * 执行 Lua 原子资格判定并扣减段库存。
     */
    public SeckillResult trySeckill(Long voucherId, Long userId, Long orderId) {
        long now = System.currentTimeMillis();
        long buffer = properties.getMetaTtlBufferSeconds() * 1000L;
        List<?> result = redis.execute(
                SECKILL_SCRIPT,
                Collections.singletonList(metaKey(voucherId)),
                voucherId.toString(),
                userId.toString(),
                orderId.toString(),
                String.valueOf(now),
                String.valueOf(buffer),
                isStreamMode() ? "1" : "0",
                streamProperties.getStreamKey(),
                String.valueOf(Math.max(1L, streamProperties.getMaxLen())));
        if (result == null || result.size() < 2) {
            log.error("秒杀脚本返回异常，requestId={}, voucherId={}, result={}", orderId, voucherId, result);
            return new SeckillResult(SeckillCode.NOT_INITIALIZED, 0, orderId);
        }
        SeckillCode code = SeckillCode.fromCode(((Number) result.get(0)).intValue());
        int segment = ((Number) result.get(1)).intValue();
        return new SeckillResult(code, segment, orderId);
    }

    /**
     * 回滚一次预扣：requestId 防重，段库存不超过容量，总库存同步恢复。
     * 默认同时移除一人一单标记。
     */
    public boolean rollback(Long voucherId, Long userId, String requestId, int segment) {
        return rollback(voucherId, userId, requestId, segment, true);
    }

    /**
     * @param removeUser 是否移除一人一单标记；DB 唯一键判定为重复订单时应传 false，
     *                   只归还库存，保留用户已有订单的资格标记
     */
    public boolean rollback(Long voucherId, Long userId, String requestId, int segment, boolean removeUser) {
        long now = System.currentTimeMillis();
        long buffer = properties.getMetaTtlBufferSeconds() * 1000L;
        Long result = redis.execute(
                ROLLBACK_SCRIPT,
                Arrays.asList(
                        rollbackKey(voucherId),
                        orderKey(voucherId),
                        totalKey(voucherId),
                        soldoutKey(voucherId),
                        metaKey(voucherId),
                        segmentKey(voucherId, segment)),
                requestId,
                userId.toString(),
                String.valueOf(segment),
                String.valueOf(now),
                String.valueOf(buffer),
                removeUser ? "1" : "0");
        return result != null && result == 1L;
    }

    public boolean isStreamMode() {
        return "stream".equalsIgnoreCase(properties.getMq());
    }

    /**
     * 实际段数：不超过配置值，也不超过库存，避免大量空段。
     */
    public static int resolveSegmentCount(int stock, int configured) {
        if (stock <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(configured, stock));
    }

    /**
     * 按段均分库存，余数从前往后各加 1，保证容量之和等于总库存。
     */
    public static int[] splitCapacity(int total, int segments) {
        if (segments <= 0) {
            throw new IllegalArgumentException("segments must be > 0");
        }
        int[] caps = new int[segments];
        int base = total / segments;
        int remainder = total % segments;
        for (int i = 0; i < segments; i++) {
            caps[i] = base + (i < remainder ? 1 : 0);
        }
        return caps;
    }

    private Plan plan(SeckillVoucher voucher, int existingOrders) {
        Long id = voucher.getVoucherId();
        int stock = voucher.getStock() == null ? 0 : voucher.getStock();
        int segments = resolveSegmentCount(stock, properties.getSegmentCount());
        int[] caps = splitCapacity(stock, segments);

        long now = System.currentTimeMillis();
        long begin = toMillis(voucher.getBeginTime());
        long end = toMillis(voucher.getEndTime());
        long expireAt = Math.max(now, end) + properties.getMetaTtlBufferSeconds() * 1000L;

        List<String> keys = new ArrayList<>(7 + segments);
        keys.add(metaKey(id));
        keys.add(totalKey(id));
        keys.add(cursorKey(id));
        keys.add(soldoutKey(id));
        keys.add(orderKey(id));
        keys.add(rollbackKey(id));
        keys.add(legacyStockKey(id));
        for (int i = 1; i <= segments; i++) {
            keys.add(segmentKey(id, i));
        }

        List<String> args = new ArrayList<>(6 + segments);
        args.add(String.valueOf(stock));
        args.add(String.valueOf(stock + Math.max(0, existingOrders)));
        args.add(String.valueOf(segments));
        args.add(String.valueOf(begin));
        args.add(String.valueOf(end));
        args.add(String.valueOf(expireAt));
        for (int cap : caps) {
            args.add(String.valueOf(cap));
        }
        return new Plan(keys, args);
    }

    private static long toMillis(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static String metaKey(Long voucherId) {
        return RedisConstants.SECKILL_META_KEY + voucherId;
    }

    public static String totalKey(Long voucherId) {
        return RedisConstants.SECKILL_STOCK_KEY + voucherId + ":total";
    }

    public static String segmentKey(Long voucherId, int segment) {
        return RedisConstants.SECKILL_STOCK_KEY + voucherId + ":s:" + segment;
    }

    public static String soldoutKey(Long voucherId) {
        return RedisConstants.SECKILL_SOLDOUT_KEY + voucherId;
    }

    public static String cursorKey(Long voucherId) {
        return RedisConstants.SECKILL_SEGMENT_CURSOR_KEY + voucherId;
    }

    public static String rollbackKey(Long voucherId) {
        return RedisConstants.SECKILL_ROLLBACK_KEY + voucherId;
    }

    public static String orderKey(Long voucherId) {
        return RedisConstants.SECKILL_ORDER_KEY + voucherId;
    }

    public static String legacyStockKey(Long voucherId) {
        return RedisConstants.SECKILL_STOCK_KEY + voucherId;
    }

    private static final class Plan {
        private final List<String> keys;
        private final List<String> args;

        private Plan(List<String> keys, List<String> args) {
            this.keys = keys;
            this.args = args;
        }
    }
}
