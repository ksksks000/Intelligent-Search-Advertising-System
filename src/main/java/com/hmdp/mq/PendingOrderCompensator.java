package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 待补偿订单的重发任务。
 * <p>
 * 定时从 Redis List 取出因 broker 不可用而发送失败的订单，重新投递到 RabbitMQ。
 * 只处理"消息未确认送达"的订单，不会回滚已扣减的 Redis 库存，
 * 重复投递由消费端按 requestId 幂等处理。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "rabbit")
public class PendingOrderCompensator {

    /** 单轮最多处理的订单数，避免长时间占用调度线程 */
    private static final int BATCH_SIZE = 100;

    @Resource
    private ReliableMqSender reliableMqSender;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${hmdp.mq.compensate-interval-ms:10000}")
    public void resendPendingOrders() {
        String key = RedisConstants.SECKILL_ORDER_PENDING_KEY;
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return;
        }

        int success = 0;
        long limit = Math.min(size, BATCH_SIZE);
        for (long i = 0; i < limit; i++) {
            String json = stringRedisTemplate.opsForList().leftPop(key);
            if (json == null) {
                break;
            }
            SeckillOrderMessage order;
            try {
                order = objectMapper.readValue(json, SeckillOrderMessage.class);
            } catch (Exception e) {
                // 脏数据无法反序列化，丢弃避免阻塞后续补偿
                log.error("待补偿订单反序列化失败，已丢弃，内容: {}", json, e);
                continue;
            }
            if (reliableMqSender.trySend(order)) {
                success++;
            } else {
                // broker 仍不可用：放回队尾，本轮提前结束，避免空转
                stringRedisTemplate.opsForList().rightPush(key, json);
                log.warn("MQ 仍不可用，本轮补偿提前结束，requestId={}", order.getRequestId());
                break;
            }
        }

        if (success > 0) {
            log.info("待补偿订单重发成功 {} 条", success);
        }
        Long remain = stringRedisTemplate.opsForList().size(key);
        if (remain != null && remain > 0) {
            log.warn("仍有 {} 条订单待补偿", remain);
        }
    }
}
