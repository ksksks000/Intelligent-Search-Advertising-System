package com.hmdp.metrics;

import com.hmdp.enums.SeckillCode;
import com.hmdp.mq.ConsumeResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 秒杀核心指标：请求、Lua 结果、限流、消费、死信、回滚。
 * 通过 /actuator/prometheus 暴露。
 */
@Component
public class SeckillMetrics {

    private final MeterRegistry registry;

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(SeckillCode code) {
        registry.counter("seckill.requests", "code", code.name()).increment();
    }

    public void rateLimitRejected(int dimension) {
        registry.counter("seckill.ratelimit.rejected", "dimension", dimensionName(dimension)).increment();
    }

    public void consume(ConsumeResult result) {
        registry.counter("seckill.consume", "result", result.name()).increment();
    }

    public void deadLetter() {
        registry.counter("seckill.dead").increment();
    }

    public void rollback(boolean success) {
        registry.counter("seckill.stock.rollback", "result", success ? "success" : "fail").increment();
    }

    private static String dimensionName(int dimension) {
        switch (dimension) {
            case 1:
                return "user";
            case 2:
                return "ip";
            case 3:
                return "api";
            default:
                return "none";
        }
    }
}
