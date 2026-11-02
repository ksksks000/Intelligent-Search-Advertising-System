package com.hmdp.mq;

/**
 * 秒杀订单投递抽象：Stream 模式下 Lua 已原子入队，Rabbit 模式下由应用发送。
 */
public interface SeckillOrderPublisher {

    void publish(SeckillOrderMessage message);
}
