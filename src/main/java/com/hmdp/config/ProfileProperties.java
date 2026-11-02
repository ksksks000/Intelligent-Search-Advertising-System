package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 用户画像配置：行为窗口、LLM 端点/模型、限流、线程池。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.profile")
public class ProfileProperties {

    private boolean enabled = true;

    /** 行为聚合窗口（天） */
    private int behaviorDays = 7;

    /** 定时聚合间隔（毫秒），默认 6 小时 */
    private long aggregateIntervalMs = 6 * 3600_000L;

    /** LLM 端点为空的本地开发环境走结构化降级；生产配 OpenAI-compatible URL */
    private String llmUrl = "";
    private String llmModel = "profile-llm-local";
    private String promptVersion = "profile-prompt-v1";
    private long llmTimeoutMs = 3000;
    private int maxRetry = 1;
    private int ratePerSecond = 10;

    private int executorCoreSize = 2;
    private int executorMaxSize = 8;
    private int executorQueueCapacity = 1000;

    /** 单用户行为事件上限 */
    private int maxEventsPerUser = 500;
    /** 画像兴趣标签上限 */
    private int maxTags = 12;
}
