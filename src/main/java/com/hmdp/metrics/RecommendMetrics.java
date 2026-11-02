package com.hmdp.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 推荐指标：Feed 耗时/降级、各召回耗时与结果、缓存命中、事件写入。
 */
@Component
public class RecommendMetrics {

    private final MeterRegistry registry;

    public RecommendMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void feed(long costMs, boolean degraded) {
        registry.timer("recommend.feed").record(Duration.ofMillis(costMs));
        if (degraded) {
            registry.counter("recommend.degraded").increment();
        }
    }

    public void recall(String source, long costMs, boolean success) {
        registry.timer("recommend.recall", "source", source).record(Duration.ofMillis(costMs));
        registry.counter("recommend.recall.result", "source", source,
                "result", success ? "success" : "fail").increment();
    }

    public void cache(String level) {
        registry.counter("recommend.cache", "level", level).increment();
    }

    public void event(String eventType) {
        registry.counter("recommend.event", "type", eventType).increment();
    }
}
