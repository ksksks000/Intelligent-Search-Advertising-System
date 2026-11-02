package com.hmdp.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stream 模式：XADD 已在 seckill_segment.lua 内与库存扣减原子完成，
 * 应用侧无需再次投递，避免重复消息。
 */
@Component
@ConditionalOnProperty(name = "hmdp.seckill.mq", havingValue = "stream", matchIfMissing = true)
public class StreamOrderPublisher implements SeckillOrderPublisher {

    @Override
    public void publish(SeckillOrderMessage message) {
        // no-op：消息已由 Lua 原子写入 Redis Stream
    }
}
