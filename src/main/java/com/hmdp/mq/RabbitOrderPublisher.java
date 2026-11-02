package com.hmdp.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * RabbitMQ 模式：Lua 只做资格判定与扣减，由应用在返回前可靠发送消息。
 */
@Component
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "rabbit")
public class RabbitOrderPublisher implements SeckillOrderPublisher {

    @Resource
    private ReliableMqSender reliableMqSender;

    @Override
    public void publish(SeckillOrderMessage message) {
        reliableMqSender.sendOrder(message);
    }
}
