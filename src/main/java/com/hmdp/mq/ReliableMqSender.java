package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.QueueConfig;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消息的可靠发送组件。
 * <p>
 * 可靠性由三层保证：
 * <ol>
 *     <li>publisher confirm：等待 broker 确认消息已接收（ack 才算成功）</li>
 *     <li>本地重试：失败后在请求线程内重试若干次</li>
 *     <li>待补偿队列：仍失败则写入 Redis List，由 {@link PendingOrderCompensator} 定时重发</li>
 * </ol>
 * 重发依赖消费端按 requestId 的幂等校验，重复投递不会产生重复订单。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "rabbit")
public class ReliableMqSender {

    /** 等待 broker 确认的单次超时时间（毫秒） */
    private static final long CONFIRM_TIMEOUT_MILLIS = 3000L;

    /** 请求线程内最大尝试次数 */
    private static final int MAX_ATTEMPTS = 3;

    /** 两次重试之间的间隔（毫秒） */
    private static final long RETRY_BACKOFF_MILLIS = 200L;

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private MessageConverter messageConverter;

    /**
     * 注册消息退回回调：mandatory=true 时，无法路由到队列的消息会退回生产者。
     * 这类消息 broker 仍然会 confirm ack，因此必须单独处理，否则会静默丢失。
     */
    @PostConstruct
    public void registerReturnsCallback() {
        rabbitTemplate.setReturnCallback(this::onReturned);
    }

    private void onReturned(Message message, int replyCode, String replyText,
                            String exchange, String routingKey) {
        try {
            Object body = messageConverter.fromMessage(message);
            if (body instanceof SeckillOrderMessage) {
                SeckillOrderMessage order = (SeckillOrderMessage) body;
                log.error("MQ 消息无法路由，转入待补偿队列，requestId={}, exchange={}, routingKey={}, replyText={}",
                        order.getRequestId(), exchange, routingKey, replyText);
                savePending(order);
            } else {
                log.error("MQ 消息无法路由，exchange={}, routingKey={}, replyCode={}, replyText={}, body={}",
                        exchange, routingKey, replyCode, replyText, body);
            }
        } catch (Exception e) {
            log.error("处理退回消息失败，exchange={}, routingKey={}", exchange, routingKey, e);
        }
    }

    /**
     * 发送订单消息：broker 确认接收才算成功，失败时本地重试，最终失败写入待补偿队列。
     *
     * @return true 表示 broker 已确认接收；false 表示已转入待补偿队列
     */
    public boolean sendOrder(SeckillOrderMessage order) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (trySend(order)) {
                return true;
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepQuietly();
            }
        }
        savePending(order);
        return false;
    }

    /**
     * 单次发送并等待 broker 确认，供补偿任务复用。
     * 注意：超时不代表消息一定没送达，重发由消费端幂等兜底。
     */
    public boolean trySend(SeckillOrderMessage order) {
        String requestId = String.valueOf(order.getRequestId());
        CorrelationData correlationData = new CorrelationData(requestId);
        try {
            rabbitTemplate.convertAndSend(
                    QueueConfig.X_EXCHANGE, QueueConfig.QUEUE_A_BINDING_KEY, order, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (confirm != null && confirm.isAck()) {
                return true;
            }
            log.warn("MQ 未确认(nack)，requestId={}, reason={}",
                    requestId, confirm == null ? "unknown" : confirm.getReason());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 MQ 确认被中断，requestId={}", requestId);
        } catch (Exception e) {
            log.warn("MQ 发送异常，requestId={}, 原因: {}", requestId, e.getMessage());
        }
        return false;
    }

    /**
     * 把发送失败的订单写入 Redis 待补偿队列，等待定时任务重发。
     */
    public void savePending(SeckillOrderMessage order) {
        try {
            String json = objectMapper.writeValueAsString(order);
            stringRedisTemplate.opsForList().rightPush(RedisConstants.SECKILL_ORDER_PENDING_KEY, json);
            log.error("订单消息发送失败，已写入待补偿队列，requestId={}", order.getRequestId());
        } catch (Exception e) {
            // Redis 也不可用时无法自动补偿，只能记录日志人工处理
            log.error("写入待补偿队列失败，需人工补偿，requestId={}", order.getRequestId(), e);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
