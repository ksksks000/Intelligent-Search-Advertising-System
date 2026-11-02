package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐 Feed 配置：召回超时、排序 TopN、缓存 TTL、规则权重。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.recommend")
public class RecommendProperties {

    /** 单路召回硬超时（毫秒） */
    private long recallTimeoutMs = 50;

    /** VECTOR 单独超时；<=0 时复用 recallTimeoutMs。ES kNN 冷/尖刺时用于诊断与按环境放宽 */
    private long vectorTimeoutMs = 0;

    /** 粗排保留条数 */
    private int coarseTopN = 100;

    /** 精排保留条数 */
    private int fineTopN = 100;

    /** 默认分页大小 */
    private int feedPageSize = 10;

    /** 最大分页大小 */
    private int maxPageSize = 50;

    /** L1 Caffeine TTL（秒） */
    private long l1TtlSeconds = 30;

    /** L2 Redis TTL（秒） */
    private long l2TtlSeconds = 300;

    /** L1 最大条目数 */
    private long l1MaxSize = 10000;

    /** 用户兴趣向量 TTL（秒） */
    private long interestTtlSeconds = 86400;

    /** 行为窗口（天） */
    private int behaviorDays = 7;

    /** 冷启动阈值：窗口内行为数小于该值视为冷启动 */
    private int coldStartThreshold = 1;

    private int hotLimit = 200;
    private int tagLimit = 200;
    private int geoLimit = 100;
    private int vectorTopN = 100;
    private int followLimit = 100;
    private int curatedLimit = 100;

    /** 地理召回半径（km） */
    private double geoRadiusKm = 50D;

    /** 已曝光未点击降权系数 */
    private double exposedPenalty = 0.5D;

    /** 曝光/点击快照本地缓存 TTL（秒），降低逐候选 Redis 查询 */
    private long exposureCacheTtlSeconds = 5;
    private long exposureCacheMaxSize = 20000;

    /** 是否启用向量召回（可回滚；embedding 故障时仍会自动降级） */
    private boolean vectorEnabled = true;

    /** 粗排来源内 min-max 归一化 */
    private boolean sourceNormalize = true;

    /** 粗排 Top100 中 TAG 保底条数（候选不足不补） */
    private int coarseTagQuota = 30;

    /** 粗排 Top100 中 FOLLOW 保底条数（候选不足不补） */
    private int coarseFollowQuota = 10;

    /** 重排：同一类目连续上限 */
    private int categoryMaxConsecutive = 2;

    /** 重排：同一商家连续上限 */
    private int shopMaxConsecutive = 1;

    // 粗排来源权重
    private double sourceFollow = 1.0D;
    private double sourceVector = 0.9D;
    private double sourceTag = 0.8D;
    private double sourceHot = 0.7D;
    private double sourceGeo = 0.6D;
    private double sourceCurated = 0.5D;

    // 精排特征权重（和为 1）
    private double fineCtr = 0.15D;
    /** 阶段 4 前置修复 A：dwell 权重降为 0，权重和保持 1 */
    private double fineDwell = 0.0D;
    private double fineQuality = 0.13D;
    private double fineFreshness = 0.12D;
    private double fineGeo = 0.15D;
    private double fineTag = 0.28D;
    /** 用户近 7 天类目点击占比 */
    private double fineCategoryCtr = 0.17D;

    /** 行为事件流 */
    private String eventStream = "rec:event:stream";
    private String eventGroup = "rec:event:group";
    private String eventDead = "rec:event:dead";
    private boolean eventConsumerEnabled = true;

    /** 统计日聚合 cron */
    private String statsAggregateCron = "0 5 0 * * ?";
}
