package com.hmdp.ad.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 广告链路指标：竞价耗时、降级、预算/频控拒绝、填充。
 */
@Component
public class AdMetrics {

    private final MeterRegistry registry;

    public AdMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void auction(long costMs, boolean degraded) {
        registry.timer("ad.auction").record(Duration.ofMillis(costMs));
        if (degraded) {
            registry.counter("ad.auction.degraded").increment();
        }
    }

    public void budgetReject(String reason) {
        registry.counter("ad.budget.reject", "reason", reason).increment();
    }

    public void frequencyReject(int code) {
        registry.counter("ad.frequency.reject", "code", String.valueOf(code)).increment();
    }

    public void fill(int filled, int requested) {
        registry.counter("ad.fill.requested").increment(requested);
        registry.counter("ad.fill.filled").increment(filled);
    }
}
