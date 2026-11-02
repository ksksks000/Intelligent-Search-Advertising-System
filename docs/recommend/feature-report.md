# 阶段 3 功能报告：推荐 Feed（多路召回 + 粗排/精排 + 两级缓存）

日期：2026-10-31

## a) 阶段目标与范围

本阶段在阶段 0/1/2 的领域模型、活动 GEO、搜索索引/embedding 基础上，实现推荐 Feed：

- 五路召回：关注、热门、地理、标签、向量；
- 粗排 Top100、精排规则加权、重排打散；
- Caffeine L1 + Redis L2 两级缓存；
- 冷启动、单路 50ms 超时降级、降级标记；
- 推荐曝光/点击/停留埋点 → Redis Stream → `tb_behavior_event` + `tb_activity_stats`；
- `/recommend/feed` 公开并接入限流。

**不改秒杀链路、不改 `Result` 结构、不接 LLM。**

## b) 架构与数据流

```
GET /recommend/feed
  → RateLimitInterceptor（/recommend/feed）
  → RecommendFeedService
      L1 Caffeine 30s → L2 Redis 5min
      → 每路召回独立 Future（50ms 硬超时，超时只跳过该路）
          FOLLOW  follows:{userId} + feed:{userId} → tb_blog.activity_id
          HOT     tb_activity.qualityScore + tb_activity_stats.ctr
          GEO     act:geo:{city} GEOSEARCH
          TAG     近 7 天行为 tags/categoryId
          VECTOR  用户兴趣向量 vs activity_index.embedding（直接 ES kNN）
          CURATED 平台级/高质量活动（冷启动）
      → 粗排：来源加权 + 基础过滤，Top100
      → 精排：ctr/停留/质量/新鲜度/地理/标签
      → 重排：类目/商家连续限制 + 已曝光未点击降权
      → 分页
      → 异步曝光埋点
  ← FeedResponse{list,nextCursor,degraded,degradedSources,cacheLevel,coldStart,costMs}

POST /recommend/event
  → RecEventPublisher（异步）
      Redis：rec:imp / rec:click / rec:dwell:stats
      XADD rec:event:stream
  → RecEventConsumer
      tb_behavior_event
      tb_activity_stats（pv/click/order，ctr/cvr）
      HLL rec:uv:{activityId}:{date}
  → 每日 00:05 聚合 UV
```

## c) 前置修复结果

### c.1 follows:* 启动重建

- 新增 `FollowRedisRebuildRunner`（`ApplicationRunner`，`@Order(3)`），分页 500 读 `tb_follow`，按 userId 批量 `SADD follows:{userId}`；
- 幂等、单用户失败不影响其他用户、整体失败不阻塞启动；
- 实测启动日志：`{pages=1, relations=9, users=3}`，耗时 302ms；
- 测试 `FollowRedisRebuildRunnerTest` 通过：清空 `follows:9001/9002` 后重建，集合内容与 DB 一致。

### c.1.1 FOLLOW 召回登录态验证与修复

匿名设备场景不会触发 FOLLOW；用 user_id=1（`follows:1={2,4}`、`feed:1` 有 blogId）做登录态验证时发现：

- `feed:1` 中存在 blog 30/31（`activity_id=NULL`、`status=0`），旧逻辑按 blogId `listByIds` 后过滤为空，**没有回退 DB**，导致 FOLLOW 召回为空；
- 已修复为单次查询：`(blogId IN feed OR user_id IN follows) AND activity_id IS NOT NULL AND status=1`，减少一次 DB 往返，避免 50ms 超时和漏召回；
- 临时把 user 2 的 blog 5 挂到在线活动 900002，Feed size=50 返回 `sources=['FOLLOW','HOT','CURATED']`，验证后已恢复 blog 5 的 `activity_id=1`。

### c.2 索引增量并发消费

- 新增 `SearchIndexConsumerExecutor`：4 个单线程分区，`activityId.hashCode() % partitions` 固定分区，保证同一文档顺序；每分区 `ArrayBlockingQueue(100)`，满时拒绝；
- `SearchIndexEventConsumer` 改为分区提交；分区满不 ACK，`@Scheduled` 用 `XPENDING + XCLAIM` 重试；
- 实测 20 个 `SearchIndexEvent`：**7.734s 全部消费完，pending=0，lag=0**；embedding 服务不可用时按纯倒排写入，WARN 记录降级；
- 单测 `SearchIndexConsumerExecutorTest`：同一 activityId 串行保序、队列满返回 false；
- 未做旧单线程的严格基线对比（避免编造），但从串行改为 4 分区并行，且 pending/lag 归零。

### c.3 tb_activity_stats 与写入链路

新增表：

```sql
tb_activity_stats(activity_id, stat_date, pv, uv, click_cnt, order_cnt, ctr, cvr,
                  create_time, update_time, PK(activity_id, stat_date))
```

- `ActivityStatsMapper.upsert` 原子累加 pv/click/order 并重算 ctr/cvr；
- 曝光写 HLL `rec:uv:{activityId}:{yyyyMMdd}`；
- 每日 00:05 `aggregateYesterday` 用 `PFCOUNT` 回填 uv；
- 实测：79 行统计；`900009` pv=6/click=4/ctr=0.6667，`900020` pv=5/click=4/ctr=0.8；调用管理端日聚合后 uv 回填为 5/4；
- 测试 `ActivityStatsServiceTest` 通过。

### c.4 tb_behavior_event 接流量

- `tb_behavior_event` 增加 `device_id` + `idx_device_time`；
- `/recommend/event` 接收曝光/点击/停留/下单；未登录用 `X-Device-Id` 兜底；
- `RecEventConsumer` 写 `tb_behavior_event` 并更新统计；成功 ACK，失败 pending + XCLAIM；
- 实测：单设备 `smoke-hotpot-v4` 产生 **25 条行为**（5 次点击 + 两页共 20 次曝光），事件流 pending=0；
- 曝光/点击频控写入 `rec:imp:{userKey}`、`rec:click:{userKey}`；停留写入 `rec:dwell:stats`。

## d) 五路召回设计与权重

| 召回 | 实现 | 召回分 | 粗排权重 | 理由 |
|---|---|---|---|---|
| FOLLOW | `follows:{userId}` + `feed:{userId}`（blogId）→ `tb_blog.activity_id`；feed 空回退 DB | 博客新鲜度：`0.5+0.5/(1+days/7)` | 1.0 | 关注是强意图，优先级最高 |
| VECTOR | 用户兴趣向量 vs `activity_index.embedding`，直接 ES kNN | 余弦相似度 clamp [0,1] | 0.9 | 语义泛化能力强，次于明确关注 |
| TAG | 近 7 天点击/浏览/收藏活动标签 + 类目 | `0.6*标签重叠 + 0.3*类目命中 + 0.1*质量` | 0.8 | 可解释、冷启动后快速生效 |
| HOT | 在线 + 未过期 + qualityScore + 近 7 天 ctr | `0.7*质量 + 0.3*ctr/0.2`；无统计时纯质量 | 0.7 | 保底质量与全局热度 |
| GEO | `act:geo:{city}`，请求坐标或城市中心，50km | `1 - distance/50km` | 0.6 | 本地生活强相关，但需要位置 |
| CURATED | 平台级（shopId NULL）或 qualityScore≥90 | qualityScore/100 | 0.5 | 冷启动/运营兜底 |

- 多路命中奖励：`+0.05 * (来源数-1)`，避免单路噪声压过多路共识；
- 标签停用词：过滤城市名和“代金券/限时折扣/线下活动/新品首发/限量秒杀/秒杀/优惠券/折扣/满减”，避免 TAG 召回被通用标签带偏。

## e) 粗排/精排/重排

### 粗排

- 过滤：非在线、已结束、7 天后才开始、库存≤0；
- 打分：`Σ 来源权重 × 归一化召回分 + 0.05×(来源数-1)`；
- 取 Top100。

### 精排（规则权重，和为 1）

| 特征 | 权重 | 归一化/依据 |
|---|---|---|
| ctr | 0.25 | `min(ctr/0.2,1)`；无统计用 0.5 先验 |
| 停留时长 | 0.15 | `min(avgDwell/60s,1)`；无数据 0.5 |
| qualityScore | 0.20 | `min(score/100,1)` |
| 新鲜度 | 0.15 | `1/(1+days/7)`，未来活动按距开始天数 |
| 地理距离 | 0.15 | `1-min(distance/50km,1)`；无坐标 0.5 |
| 标签匹配 | 0.10 | 用户 top 标签与活动标签重叠比例；无画像 0.5 |

- 已曝光未点击（24h 内）降权 `×0.5`；
- 无标注数据，权重来自业务先验和 10 个构造场景的人工检查；阶段 6 有日志/反馈后可替换为 LR/GBDT。

### 重排

- 同一 `categoryId` 连续 ≤2；
- 同一 `shopId` 连续 ≤1；
- 违反约束的候选延迟到缓冲，最后追加，尽量保持精排顺序；
- 单测 `RerankerTest` 覆盖类目/商家连续限制。

## f) 两级缓存

| 层 | Key | TTL | 说明 |
|---|---|---|---|
| L1 Caffeine | `rec:feed:{userKey}:{city}:{cursor}:{size}` | 30s，max 10000 | 进程内，按 userId/deviceId 分片 |
| L2 Redis | 同上 | 非降级 300s；降级 30s | 降级页短 TTL，避免低质量页固定 5min |

- `userKey = u{userId}` 或 `d{deviceId}` 或 `anon`；
- 实测：
  - L1 命中：`cacheLevel=L1`，`costMs=0`；
  - 重启应用清空 L1 后，同一 key `cacheLevel=L2`，返回一致；
  - Redis key 存在且 TTL 符合预期；
- 命中率/压测数据待阶段 6 回填。

## g) 降级实测

- **VECTOR 稳定降级**：embedding 服务 8090 当前未启动，warm Feed 的 `degradedSources=['VECTOR']`；Feed 仍返回 10 条，TAG/HOT/GEO 正常；
- **HOT/GEO/TAG 偶发 50ms 超时**：冷请求/并发下出现，超时只跳过该路；例如 KTV/亲子场景 HOT 超时，但 TAG/GEO 仍把对应品类排到 Top1/2；
- 降级标记随响应返回，日志 `DEGRADED: recall=... timeout 50ms`；
- 20 并发用户：**20/20 成功、0 异常、0 空结果**；18 个响应带降级标记（VECTOR 或 50ms 超时），平均 1043ms、最大 1174ms；
- 降级页短 TTL 30s，不会长期污染缓存。

## h) 冷启动效果

- 新用户（无行为）：HOT + GEO + CURATED（有 follow 时也带 FOLLOW）；
- 实测杭州新用户：Top10 为火锅/杭帮菜/SPA/咖啡等杭州本地活动，来源 HOT/GEO/CURATED；
- 实测成都匿名新用户：Top10 为美甲/足疗/按摩等成都本地活动；
- 无空结果；冷启动不调用 VECTOR/TAG，避免无画像噪声。

## i) 10 个用户场景结果

完整 Top10（含标题/类目/来源/分数）见 `docs/recommend/scenario-results.md`。摘要：

| 场景 | coldStart | degradedSources | Top 类目（前5） | 结论 |
|---|---|---|---|---|
| 新用户-杭州 | true | [] | 1,1,6,1,1 | 热门+地理+精选，杭州本地 |
| 火锅偏好 | false | VECTOR | 1,1,6,6,1 | Top1/2 火锅套餐/涮肉 |
| KTV偏好 | false | HOT,VECTOR | 2,2,1,1,6 | Top1/2 KTV，TAG/GEO 兜底 |
| 跨品类 | false | VECTOR | 1,1,2,1,2 | 火锅+KTV 混合 |
| 地理偏好-上海 | false | VECTOR | 2,2,7,7,2 | 上海 KTV/亲子 |
| 美容SPA | false | VECTOR | 6,6,2,1,2 | Top1/2 SPA |
| 健身运动-深圳 | false | VECTOR | 4,4,9,4,4 | 健身/轰趴 |
| 亲子游乐-上海 | false | HOT,VECTOR | 7,7,2,7,2 | 亲子为主 |
| 酒吧夜生活-北京 | false | VECTOR | 8,8,3,3,8 | 酒吧为主 |
| 匿名新用户-成都 | true | [] | 10,10,5,10,5 | 成都本地美甲/足疗 |

## j) 测试用例与结果

| 用例 | 结果 |
|---|---|
| follows Redis 清空后重建 | 通过（9 关系/3 用户，302ms） |
| 索引 20 事件并发消费 | 7.734s，pending/lag=0；同 activityId 保序单测通过 |
| 统计表写入 + 日聚合 | 79 行；ctr/cvr/UV 回填正确 |
| 行为埋点 device_id 兜底 | 25 条/设备，pending=0 |
| 五路召回来源标记 | FOLLOW 登录态验证通过（900002 返回 FOLLOW/HOT/CURATED）；HOT/GEO/TAG/CURATED 场景可见；VECTOR 降级 |
| 10 用户场景 Top10 | 结果见上表/附件，品类匹配合理 |
| 重排类目/商家连续限制 | 单测通过 |
| L1/L2 缓存 | L1=0ms；L2 重启后命中；TTL 正确 |
| 降级（停 embedding） | Feed 仍返回，degradedSources 含 VECTOR |
| 冷启动 | 杭州/成都新用户均返回 10 条非空 |
| 20 并发 Feed | 20/20 成功，0 异常，0 空；avg 1043ms，max 1174ms |

阶段 3 单测（`FollowRedisRebuildRunnerTest`、`SearchIndexConsumerExecutorTest`、`ActivityStatsServiceTest`、`CoarseRankerTest`、`RerankerTest`、`InterestVectorServiceTest`）**9/9 通过**。

## k) 指标与可观测

- `/actuator/prometheus`：
  - `recommend.feed`（timer）
  - `recommend.recall{source}`（timer）
  - `recommend.recall.result{source,result}`
  - `recommend.cache{level}`
  - `recommend.degraded`
  - `recommend.event{type}`
- 日志：`DEGRADED: recall=...`、`recommend feed ... degraded=...`、`traceId` 由 `TraceIdFilter` 贯穿。

## l) 效果摘要（量化待阶段 6 回填）

1. 设计并实现推荐 Feed 五路召回（关注/热门/地理/标签/向量）+ 粗排/精排/重排，采用 Caffeine+Redis 两级缓存与单路 50ms 超时降级；10 类用户场景 Top10 命中对应品类，20 并发无异常。
2. 将搜索索引增量改为 activityId 分区并行消费，20 个事件 7.7s 消化、pending/lag 归零；建立曝光/点击/停留埋点与 `tb_activity_stats` 日聚合，支撑 ctr/cvr 精排特征。

> Feed P99、QPS、缓存命中率待阶段 6 压测回填。

## m) 阶段 4 前置条件（广告竞价）

1. **广告位预留**：`FeedResponse`/`FeedItem` 增加 `ad`/`adSlot` 字段；在分页/重排阶段按固定位置（如第 3/6 位）插入广告；缓存 key 需带是否含广告或按位置缓存。
2. **统计字段**：当前 `tb_activity_stats` 的 pv/uv/click/order/ctr/cvr 可做效果统计，但竞价还需要 `bid_price`、`revenue`、`ad_id`、`campaign_id`、`slot_id`、`win_rate` 等字段，建议新增 `tb_ad_stats` 或扩展列。
3. **行为覆盖**：Feed 曝光/点击/停留已覆盖；广告曝光需使用 `scene=ad`，并在 `ext_json` 写 `adId/campaignId/slotId/bid`；`tb_behavior_event` 现有 `scene` + `ext_json` 可承载，建议后续加 `ad_id`/`campaign_id` 便于索引。

## n) 已知限制

- embedding 服务 8090 未启动，向量召回只验证了降级路径，未验证正样本效果；
- 50ms 硬超时在冷 JVM/并发下会让 HOT/GEO/TAG 偶发降级；当前由其他路/ HOT fallback 兜底，阶段 6 需结合压测调参或加本地候选缓存；
- 单机 Feed 场景实测约 300–600ms，20 并发 avg 1.04s；P99/QPS 需阶段 6 独立压测；
- 行为事件无唯一 eventId，重复消费可能重复计数；后续可加 eventId + 幂等；
- 标签权重/精排权重为人工先验，无标注数据；阶段 6 可用曝光点击日志做离线评估。

## o) 文件与配置

核心新增：

```
controller/RecommendController.java
controller/AdminRecommendController.java
service/recommend/RecallService.java
service/recommend/{Follow,Hot,Geo,Tag,Vector,Curated}RecallService.java
service/recommend/{InterestVectorService,CoarseRanker,FineRanker,Reranker,FeedCacheService,RecommendFeedService}.java
service/{BehaviorEventService,ActivityStatsService,RecEventPublisher,RecEventConsumer}.java
geo/{FollowRedisRebuildRunner,RecommendWarmupRunner}.java
search/index/SearchIndexConsumerExecutor.java
entity/{ActivityStats,BehaviorEvent}.java
mapper/{ActivityStatsMapper,BehaviorEventMapper}.java
metrics/RecommendMetrics.java
config/{RecommendProperties,SearchIndexConsumerProperties,RecommendExecutorConfig}.java
dto/{RecEventDTO,RecommendContext,ActivityCandidate,FeedItem,FeedResponse,UserProfile,ActivityStatsView}.java
utils/{RecommendUtils,RecommendTagUtils}.java
resources/db/zs_p3.sql
docs/recommend/{reuse-review,scenario-results,feature-report}.md
tests: FollowRedisRebuildRunnerTest, SearchIndexConsumerExecutorTest,
       ActivityStatsServiceTest, CoarseRankerTest, RerankerTest, InterestVectorServiceTest
```

配置入口：`application.yaml -> hmdp.recommend`、`hmdp.search.index-consumer`、`hmdp.follow.rebuild-on-startup`。
