package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 秒杀元数据（Hash：begin/end/segments/total/cap:i） */
    public static final String SECKILL_META_KEY = "seckill:meta:";
    /** 售罄标记 */
    public static final String SECKILL_SOLDOUT_KEY = "seckill:soldout:";
    /** 段轮转游标 */
    public static final String SECKILL_SEGMENT_CURSOR_KEY = "seckill:seg:cursor:";
    /** 已回滚 requestId 集合，防止重复回滚导致超卖 */
    public static final String SECKILL_ROLLBACK_KEY = "seckill:rollback:";
    /** 已下单用户集合（一人一单） */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /** 秒杀订单发送失败后的待补偿队列（Redis List，元素为订单 JSON） */
    public static final String SECKILL_ORDER_PENDING_KEY = "seckill:order:pending";
    /** 秒杀订单 Stream（Lua 内与扣减原子写入） */
    public static final String SECKILL_ORDER_STREAM_KEY = "seckill:orders:stream";
    /** 秒杀订单消费组 */
    public static final String SECKILL_ORDER_GROUP = "seckill:orders:group";
    /** 秒杀订单死信 Stream */
    public static final String SECKILL_ORDER_DEAD_KEY = "seckill:orders:dead";
    /** 入口限流 key 前缀 */
    public static final String RATE_LIMIT_USER_KEY = "rate:seckill:user:";
    public static final String RATE_LIMIT_IP_KEY = "rate:seckill:ip:";
    public static final String RATE_LIMIT_API_KEY = "rate:seckill:api:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String FOLLOW_KEY = "follows:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOP_TYPE_KEY = "shop_type:";
    public static final Long SHOP_TYPE_LONG=10L;

    /** 活动详情缓存 */
    public static final String ACTIVITY_DETAIL_KEY = "act:detail:";
    /** 活动 GEO 索引：阶段 2 起按城市分片 act:geo:{city} */
    public static final String ACTIVITY_GEO_KEY_PREFIX = "act:geo:";
    /** 阶段 1 的单 key，重建时清理 */
    public static final String ACTIVITY_GEO_KEY_LEGACY = "act:geo";
    /** 活动在线状态缓存，供秒杀入口校验 */
    public static final String ACTIVITY_STATUS_KEY = "act:status:";
    public static final Long ACTIVITY_STATUS_TTL_SECONDS = 300L;
    public static final Long ACTIVITY_DETAIL_TTL = 30L;

    /** 搜索热词 ZSet */
    public static final String SEARCH_HOT_KEY = "search:hot";
    /** 热词列表短缓存 */
    public static final String SEARCH_HOT_CACHE_KEY = "search:hot:cache";
    public static final Long SEARCH_HOT_TTL_SECONDS = 7 * 24 * 3600L;
    public static final Long SEARCH_HOT_CACHE_TTL_SECONDS = 60L;
    /** 搜索索引增量事件流 */
    public static final String SEARCH_INDEX_STREAM_KEY = "search:index:stream";
    public static final String SEARCH_INDEX_GROUP = "search:index:group";
    /** 重建期间的双写目标索引 */
    public static final String SEARCH_REBUILD_TARGET_KEY = "search:rebuild:target";
    public static final Long SEARCH_REBUILD_TARGET_TTL_SECONDS = 3600L;
    /** 搜索 query 向量缓存：search:query:vec:{hash}，TTL 1h */
    public static final String SEARCH_QUERY_VEC_KEY = "search:query:vec:";

    // ---------------- 阶段 3：推荐 Feed ----------------
    /** 推荐 Feed L2 缓存：rec:feed:{userKey}:{city}:{cursor}:{size} */
    public static final String REC_FEED_KEY = "rec:feed:";
    /** 推荐行为事件流/消费组/死信 */
    public static final String REC_EVENT_STREAM_KEY = "rec:event:stream";
    public static final String REC_EVENT_GROUP = "rec:event:group";
    public static final String REC_EVENT_DEAD_KEY = "rec:event:dead";
    /** UV HyperLogLog：rec:uv:{activityId}:{yyyyMMdd} */
    public static final String REC_UV_KEY = "rec:uv:";
    /** 停留时长聚合 Hash：field = {activityId}:sum / {activityId}:count */
    public static final String REC_DWELL_KEY = "rec:dwell:stats";
    /** 曝光/点击频控：rec:imp:{userKey} ZSet、rec:click:{userKey} Set */
    public static final String REC_IMPRESSION_KEY = "rec:imp:";
    public static final String REC_CLICK_KEY = "rec:click:";
    /** 用户兴趣向量：user:interest:{userKey} */
    public static final String USER_INTEREST_KEY = "user:interest:";
    /** 热门候选短缓存：rec:hot:{city} */
    public static final String REC_HOT_KEY = "rec:hot:";

    public static final Long REC_FEED_L2_TTL_SECONDS = 300L;
    public static final Long REC_INTEREST_TTL_SECONDS = 86400L;
    public static final Long REC_BEHAVIOR_TTL_SECONDS = 7 * 24 * 3600L;

    // ---------------- 阶段 4：广告 ----------------
    /** 广告索引独立事件流/消费组/死信，绝不与 search:index:stream 共用 */
    public static final String AD_INDEX_STREAM_KEY = "ad:index:stream";
    public static final String AD_INDEX_GROUP = "ad:index:group";
    public static final String AD_INDEX_DEAD_KEY = "ad:index:dead";
    /** 广告计划缓存：ad:campaign:{id}（JSON） */
    public static final String AD_CAMPAIGN_KEY = "ad:campaign:";
    public static final String AD_CAMPAIGN_ALL_KEY = "ad:campaign:all";
    /** 计划曾经写入的倒排 Set key 集合，便于更新/下线时精确摘除 */
    public static final String AD_CAMPAIGN_INDEX_SETS_KEY = "ad:campaign:index:sets:";
    /** 定向倒排索引 Set：ad:target:{dimension}:{value} -> campaignId */
    public static final String AD_TARGET_TAG_KEY = "ad:target:tag:";
    public static final String AD_TARGET_CATEGORY_KEY = "ad:target:category:";
    public static final String AD_TARGET_CITY_KEY = "ad:target:city:";
    public static final String AD_TARGET_DEVICE_KEY = "ad:target:device:";
    public static final String AD_TARGET_HOUR_KEY = "ad:target:hour:";
    public static final String AD_TARGET_AFFINITY_KEY = "ad:target:affinity:";
    /** 预算：总预算、日预算、幂等键 */
    public static final String AD_BUDGET_TOTAL_KEY = "ad:budget:total:";
    public static final String AD_BUDGET_DAILY_KEY = "ad:budget:daily:";
    public static final String AD_BUDGET_IDEM_KEY = "ad:budget:idem:";
    /** 频控 ZSet：ad:freq:u:{userKey}:{adId}:{date}、ad:freq:ip:{ip}:{adId}:{date} */
    public static final String AD_FREQ_USER_KEY = "ad:freq:u:";
    public static final String AD_FREQ_IP_KEY = "ad:freq:ip:";
    /** 计费事件流（异步落库/对账） */
    public static final String AD_BILLING_STREAM_KEY = "ad:billing:stream";
    public static final String AD_BILLING_GROUP = "ad:billing:group";
    public static final String AD_BILLING_DEAD_KEY = "ad:billing:dead";
    /** 延迟反馈等待流与转化完成标记 */
    public static final String AD_CONVERSION_STREAM_KEY = "ad:conversion:wait";
    public static final String AD_CONVERSION_GROUP = "ad:conversion:group";
    public static final String AD_CONVERSION_DONE_KEY = "ad:conversion:done:";
    /** 延迟反馈扫描索引与元数据 */
    public static final String AD_CONVERSION_INDEX_KEY = "ad:conversion:index";
    public static final String AD_CONVERSION_META_KEY = "ad:conversion:meta:";
    /** 广告索引 embedding 限流 key */
    public static final String AD_INDEX_RATE_KEY = "ad:index:rate:";
    /** 竞价兜底缓存：ad:bid:cache:{city} */
    public static final String AD_BID_CACHE_KEY = "ad:bid:cache:";
    /** 广告 CTR 反馈计数 */
    public static final String AD_CTR_STATS_KEY = "ad:ctr:stats";
    public static final String AD_CTR_CALIBRATION_KEY = "ad:ctr:calibration";
    /** 广告竞价限流 key */
    public static final String AD_RATE_LIMIT_KEY = "ad:rate:";

    // ---------------- 阶段 5：AI 用户画像 ----------------
    /** 用户画像标签：user:profile:{userId}:tags */
    public static final String USER_PROFILE_TAGS_KEY = "user:profile:";
    public static final String USER_PROFILE_AD_AFFINITY_KEY = "user:profile:";
    /** LLM 画像任务状态/速率：user:profile:llm:* */
    public static final String USER_PROFILE_LLM_RATE_KEY = "user:profile:llm:rate:";
    public static final String USER_PROFILE_LLM_LAST_KEY = "user:profile:llm:last";
}
