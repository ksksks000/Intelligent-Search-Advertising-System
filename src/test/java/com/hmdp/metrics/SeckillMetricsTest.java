package com.hmdp.metrics;

import com.hmdp.enums.SeckillCode;
import com.hmdp.mq.ConsumeResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeckillMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeckillMetrics metrics = new SeckillMetrics(registry);

    @Test
    @DisplayName("指标按标签正确累计")
    void recordsCounters() {
        metrics.request(SeckillCode.SUCCESS);
        metrics.request(SeckillCode.SOLD_OUT);
        metrics.rateLimitRejected(1);
        metrics.rateLimitRejected(2);
        metrics.consume(ConsumeResult.SUCCESS);
        metrics.consume(ConsumeResult.RETRY);
        metrics.deadLetter();
        metrics.rollback(true);
        metrics.rollback(false);

        assertEquals(1.0, registry.get("seckill.requests")
                .tag("code", "SUCCESS").counter().count());
        assertEquals(1.0, registry.get("seckill.requests")
                .tag("code", "SOLD_OUT").counter().count());
        assertEquals(1.0, registry.get("seckill.ratelimit.rejected")
                .tag("dimension", "user").counter().count());
        assertEquals(1.0, registry.get("seckill.ratelimit.rejected")
                .tag("dimension", "ip").counter().count());
        assertEquals(1.0, registry.get("seckill.consume")
                .tag("result", "SUCCESS").counter().count());
        assertEquals(1.0, registry.get("seckill.consume")
                .tag("result", "RETRY").counter().count());
        assertEquals(1.0, registry.get("seckill.dead").counter().count());
        assertEquals(1.0, registry.get("seckill.stock.rollback")
                .tag("result", "success").counter().count());
        assertEquals(1.0, registry.get("seckill.stock.rollback")
                .tag("result", "fail").counter().count());
    }
}
