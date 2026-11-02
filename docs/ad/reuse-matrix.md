# 阶段 4 复用审查矩阵

## 1. 类级复用矩阵

| 已有资产 | 判断 | 理由 |
|---|---|---|
| `SeckillStockService` + `seckill_segment.lua` | **新增，不复用** | 秒杀是“一次性库存分段 + 一人一单”；广告是“总/日双预算、可充值、按曝光计费、频控非一人一次”。只复用“Lua 原子判定”范式，扣减逻辑写入 `budget_deduct.lua`。 |
| `StreamOrderPublisher` / `StreamSeckillOrderConsumer` | **新增同类，复用范式** | 复用 Stream + consumer group + ACK + pending 的思路；广告计费、广告索引各自独立 stream/group/consumer，不共享秒杀消费组。 |
| `tb_seckill_idempotent` | **新增表，复用范式** | 秒杀以 requestId 唯一；广告以 `request_id + billing_id` 生成幂等键，落库唯一键 `tb_ad_billing_log.billing_id`。 |
| `SeckillReconcileService` | **新增同类，复用范式** | 新增 `AdBudgetReconcileService`，比较 Redis 剩余预算与 DB 计费流水，只告警不自动改数。 |
| `RateLimiter` | **小改/新增** | 秒杀 key 前缀是 `rate:seckill:*`；广告竞价新增 `AdRateLimiter`，使用 `ad:rate:*`，不污染秒杀限流桶。 |
| `RecommendFeedService` | **小改** | 仅在分页后追加广告位、缓存 key 增加 ad 参数；自然精排权重和排序链路不变。 |
| `FeedCacheService` | **原样复用** | 仍为 L1/L2；key 区分 `:ad3-6` 与 `:noad`。 |
| `tb_behavior_event` / `BehaviorEvent` | **原样复用** | 广告曝光/点击/转化写 `scene=ad`、`target_type=ad`、`ext_json` 携带广告字段，不改表。 |
| `tb_activity_stats` | **不复用** | 自然活动 CTR 是活动维度；广告 CTR 需要广告计划/类目维度，使用 `ad:ctr:stats` Redis Hash，后续可落新表。 |
| `ActivitySearchService` | **思路复用，不复用实现** | 只借鉴倒排/kNN 思想；广告在线定向使用 Redis Set + `ad_target.lua`，在线链路禁止 embedding/LLM。 |
| `RedisConstants` | **小改** | 新增 `ad:*` 前缀常量，索引/预算/频控/计费/转化 key 全部集中管理。 |
| `MvcConfig` | **小改** | `/ad/**` 公开；`/admin/ad/**` 走已有 `X-Admin-Token` 管理端拦截。 |

## 2. 预算扣减 vs 秒杀库存语义差异

| 维度 | 秒杀库存 | 广告预算 |
|---|---|---|
| 资源 | 一次性库存分段 | 总预算 + 每日预算 |
| 重置 | 活动结束失效 | 日预算按天新 key 自动重置，可充值 |
| 计费 | 下单成功扣 1 | 每次曝光按 bid 扣费（一价），点击/转化 cost=0 |
| 幂等 | requestId | requestId + billingId |
| 频控 | 一人一单 Set | 用户-广告-日 ZSet + 匿名 IP 兜底；不是一人一次 |
| 出价 | 固定券价 | 拍卖出价可变，eCPM 排序后扣费 |
| 失败补偿 | 库存回滚 | 预算拒绝直接跳过候选，不进入曝光 |

因此：**禁止复用 `seckill_segment.lua`**；`budget_deduct.lua` 使用负值 `DECRBY`（即负 INCRBY）后校验 `>=0`，不足时原子回补；同一脚本在 ARGV 无频控参数时也可只做纯预算扣减，供测试和管理端使用。

## 3. 广告插入 Feed 策略

- 选择**固定位置插入**：第 3/6 位替换自然结果，总条数保持 10。
- 理由：自然精排权重冻结，固定位可预测、不反向影响自然排序；合并排序会让广告 eCPM 直接改写自然序列，违背本阶段约束。
- 缓存 key：`rec:feed:{userKey}:{city}:{cursor}:{size}:ad3-6` 与 `...:noad` 分开；广告页缓存命中仍返回广告，不带广告请求不会命中广告缓存。
- 频控/预算过滤导致某个广告位无候选时，保留原自然结果，不出现空位。

## 4. 广告索引通道隔离

- 自然索引：`search:index:stream` / `search:index:group` / `SearchIndexConsumerExecutor`。
- 广告索引：`ad:index:stream` / `ad:index:group` / `adIndexExecutor` / `AdIndexRateLimiter`（默认 10 条/秒）。
- 广告索引消费者按 campaign 刷新 Redis 定向倒排；自然索引消费者完全不受影响。实测 20 条广告 + 20 条自然事件同时发布，两个 group 独立消费，2s 内各自完成且 pending=0。
- 在线竞价链路不调用 embedding/LLM；广告索引侧的富化被限流，且默认不依赖 embedding。
