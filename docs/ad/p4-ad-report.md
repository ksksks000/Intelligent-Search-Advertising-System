# 阶段 4：广告竞价模块功能报告

日期：2026-10-31  
范围：广告数据模型、定向召回、频控、预算扣减、CTR 校准、竞价、硬超时、Feed 插入、埋点、延迟反馈、管理端；未进入阶段 5，未修改秒杀链路和 `Result` 结构，精排自然权重保持 `0.28/0.17/0.15/0.13/0.12/0.15/0`。

## a) 目标与完成情况

| 目标 | 状态 |
|---|---|
| 三项工程修复（线程池/精排批量曝光/广告索引隔离） | 完成 |
| 4 张广告表 + 实体 + Mapper | 完成 |
| 标签/城市/时段/设备倒排定向 | 完成，Redis Set + Lua 求交 |
| 频控（用户/广告/日 + 匿名 IP） | 完成 |
| `budget_deduct.lua` 双预算 + 幂等 | 完成，在线使用该脚本 |
| CTR 规则预估 + 分段保序回归校准 | 完成 |
| eCPM 竞价 + 一价计费 | 完成 |
| 竞价硬超时/缓存回退 | 完成（计划阶段 50ms；准入后置，见限制） |
| Feed 第 3/6 位插入 + 缓存 key 区分 | 完成 |
| 曝光/点击/转化埋点 + 计费流水 | 完成 |
| 延迟反馈 Stream + 超时扫描 | 完成 |
| 管理端创建/充值/上下架/看板 | 完成 |

## b) 数据模型

DDL：`src/main/resources/db/zs_p4.sql`。

- `tb_advertiser`：广告主、状态、余额。
- `tb_ad_campaign`：广告计划、落地活动、bid、总/日预算、定向标签/类目/城市/设备/时段、起止时间、质量因子、频控参数、状态。
- `tb_ad_billing_log`：曝光/点击/转化计费流水；`billing_id` 唯一，支撑 requestId+billingId 幂等。
- `tb_ad_exposure_log`：曝光明细、pctr、ecpm、点击/转化状态、超时状态、ext_json。

演示种子：4 个杭州广告计划（火锅/KTV/美容/全城通投），广告主 1。

## c) 架构与在线链路

```
Feed/竞价请求
  -> AdTargetingService（Redis 倒排 Set + ad_target.lua，单次求交）
  -> AdCtrPredictor（类目 CTR + 用户类目 CTR + 质量 + 出价归一化）
  -> AdCtrCalibrationService（分段保序回归）
  -> eCPM = pCTR × bid × qualityFactor × 1000，降序
  -> AdBudgetService.tryAdmit（budget_deduct.lua：频控 + 总/日预算 + 幂等）
  -> 曝光埋点/计费 Stream（异步）
  -> AdFeedService 固定位 3/6 插入
```

- 竞价排序选择**一价（first-price）**：胜出者按自己的 bid 扣费，预算/对账确定，避免二价机制下计费解释成本。
- 在线路径不调用 embedding/LLM；广告索引是独立 `ad:index:stream`。
- 预算扣减使用 `budget_deduct.lua`；秒杀 `seckill_segment.lua` 未被复用。

## d) 三项工程修复

### 1. 召回线程池隔离与扩容

- `recallExecutor` 从 6/12/100 调整为 **20/40/200**（`hmdp.recommend.recall-*` 可配置）。
- 新增 `adRecallExecutor`、`adBidExecutor`、`adEventExecutor`，广告链路不占用自然召回池。
- 20 并发 Feed 对比（A-2 B 基线 → 修复后）：

| 指标 | 修复前 | 修复后 |
|---|---:|---:|
| 总耗时 P50/P99 | 464.5 / 477ms | 363.5 / 375ms |
| 全源降级（含 FALLBACK） | 12/20 | 0/20 |
| fine P50 | 150.5ms | 98.5ms |
| fine.exposure P50 | 143ms | 69ms |

修复后仍有单路 `HOT`/`VECTOR` 降级，但不再出现全源 FALLBACK；VECTOR 因本机 ES kNN 超 50ms 继续降级（embedding 服务已因内存被系统停止）。

### 2. 精排曝光批量查询

- 新增 `FeedExposureCache`：每用户 5 秒读取一次曝光 ZSet + 点击 Set，精排候选本地 O(1) 查询。
- 逐候选 2 次 Redis（约 200 次/Feed）降为每用户 2 次/5 秒；`fine.exposure` P50 143 → 69ms。

### 3. 广告索引独立通道

- `ad:index:stream` + `ad:index:group` + `adIndexExecutor` + `AdIndexRateLimiter`（默认 10 条/秒）。
- 20 条广告索引 + 20 条自然索引同时发布：两个 group 独立消费，2s 内均完成，`pending=0`，自然索引不受广告事件阻塞。
- 广告索引消费者按 campaign 刷新 Redis 定向倒排，不做在线 embedding。

## e) 功能实现要点

### 定向召回

- 维度：用户画像 Top 标签、Top 类目、城市、设备类型、当前小时。
- Redis Set：`ad:target:tag:*`、`ad:target:category:*`、`ad:target:city:*`、`ad:target:device:*`、`ad:target:hour:*`；`__all__` 表示通投。
- `ad_target.lua` 一次 Redis 往返完成 SUNION/求交；在线不查 DB。

### 频控

- ZSet：`ad:freq:u:{userKey}:{adId}:{yyyyMMdd}`。
- 匿名用户额外 `ad:freq:ip:{ip}:{adId}:{yyyyMMdd}`，默认 IP 日上限 10。
- Lua 原子检查最小间隔 + 日次数；成员为 billingId；TTL 2 天。
- 实测：同一用户第 3 次（limit=2）返回 -1；两个设备同 IP 第 3 次返回 -2。

### 预算扣减

- `budget_deduct.lua`：总预算 + 日预算双 key；`DECRBY` 后校验 `>=0`，不足原子回补；幂等键 `requestId + billingId`；总/日 key 缺失时按 campaign 初始化；日 key 按日期新 key 自动重置。
- DB 落账：`AdBillingPublisher` 写 `ad:billing:stream`，`AdBillingEventConsumer` 异步写 `tb_ad_billing_log`；Stream 失败同步落库兜底。
- 对账：`AdBudgetReconcileService` 定时比较 Redis 剩余与 DB 计费。

### CTR 校准

- 规则：`pCTR = prior + 0.45*类目历史CTR + 0.30*用户类目CTR + 0.15*质量 + 0.10*出价归一化`，截断到 [0.001, 0.9]。
- 校准：按 raw 分 10 段，计算段内平均预测/真实点击率，PAV 保证单调，线性插值；参数持久化 `ad:ctr:calibration`。
- 时间切分验证（阶段 5 修正 1）：前 700 条拟合、后 300 条评估，**ECE 0.3767 → 0.0667**；训练集内 ECE 不再作为对外口径。
- 点击/转化反馈写入 `ad:ctr:stats` Hash，供后续重校准。

### 竞价与硬超时

- eCPM 排序，Top 填充；一价计费；多广告竞争时高 eCPM 胜出（实测 campaign3 eCPM≈33.6k 先于 campaign1≈16.6k）。
- 计划阶段（定向 + pCTR）在 `adBidExecutor` 内以 `auction-timeout-ms=50` 限制；超时读取 `ad:bid:cache:{city}` 回退规则出价并标记 `degraded=true`。
- 计划阶段只做只读计算；频控/预算准入在计划返回后执行，避免超时任务继续扣费造成双重扣款。
- 实测冷启动首请求 `AUCTION_TIMEOUT` 仍能通过缓存返回 2 条广告；后续预热请求正常。

### Feed 插入与缓存

- 固定第 3/6 位替换自然结果，总数保持 10；频控/预算过滤后保留自然位，不出现空位。
- 缓存 key：`rec:feed:{userKey}:{city}:{cursor}:{size}:ad3-6` 与 `...:noad`；实测两种 key 均存在且互不污染。
- 实测 Feed：第 3 位 campaign1（火锅）、第 6 位 campaign4（全城通投）；KTV 画像第 3 位 campaign2、第 6 位 campaign4。

### 埋点与延迟反馈

- 曝光/点击/转化写 `tb_behavior_event`：`scene=ad`、`target_type=ad`、`ext_json` 含 `adId/campaignId/slotId/bid/pctr/ecpm/requestId`（点击/转化含 clickId/orderId）。
- 曝光计费写 `tb_ad_billing_log`（cost=bid）；点击/转化 cost=0。
- 点击写 `ad:conversion:wait` Stream + `ad:conversion:index` ZSet + meta；转化回填 `ad:conversion:done:{clickId}` 并更新曝光；定时扫描窗口外未转化样本标记 TIMEOUT。
- 实测：点击 1 次、转化 1 次均返回成功，done key 生成，看板 clicks=1/conversions=1。

### 管理端

- `POST /admin/ad/advertiser`、`POST /admin/ad/campaign`、`POST /admin/ad/campaign/{id}/status`、`POST /admin/ad/campaign/{id}/topup`、`GET /admin/ad/dashboard`。
- 验收：创建广告主/计划、充值 500、暂停计划均成功；dashboard 返回曝光/点击/转化/消耗/CTR/CVR。

## f) 测试用例清单与实际结果

| 用例 | 结果 |
|---|---|
| 20 并发 Feed（工程修复） | P50 363.5ms/P99 375ms，全源降级 0/20；fine P50 98.5ms |
| 精排曝光批量 | fine.exposure P50 143→69ms |
| 广告索引隔离 | 20 广告 + 20 自然事件，两个 group 独立，2s 内完成，pending=0 |
| 定向差异 | 火锅画像广告 campaign1+4；KTV 画像 campaign2+4 |
| 频控用户日上限 | limit=2，第 3 次 code=-1 |
| 匿名 IP 兜底 | 两设备同 IP，第 3 次 code=-2 |
| 预算并发（等额） | 20 线程 × 100 分 / 总预算 1000：成功 10，剩余 0，无超支 |
| 预算不等额并发（阶段 5 修正 2） | 20 线程随机 30–150 分 / 1000：11 笔成功，扣减 974，剩余 26，无超支 |
| 预算最后一笔（阶段 5 修正 2） | 总预算 100，两笔 60 并发：1 成功 1 拒绝，剩余 40 |
| 预算落库失败回滚（阶段 5 修正 2） | refund 后余额恢复 1000，幂等键删除 |
| 预算幂等 | 同 requestId+billingId 重试返回 duplicate，余额只扣一次 |
| 日预算重置 | 日预算耗尽 code=-4；删除日 key 后恢复成功 |
| CTR 校准（时间切分） | 前 700 拟合/后 300 评估：ECE 0.3767 → 0.0667 |
| 多广告 eCPM | campaign3(33.6k) 排在 campaign1(16.6k) 前 |
| 硬超时降级 | 冷启动首请求 AUCTION_TIMEOUT，缓存回退仍返回 2 条广告 |
| Feed 3/6 位 | 第 3/6 位为广告，总条数 10 |
| 缓存 key | `ad3-6` 与 `noad` 同时存在、互不命中 |
| 点击→转化 | 点击/转化接口成功，done key 生成，曝光 clicked/converted=1 |
| 20 并发竞价 | 填充 20/20（100%），18/20 正常、2/20 回退；预算余额均非负 |
| 管理端 | 创建/充值/暂停成功，dashboard 正常 |

## g) 指标、限制、阶段 5 前置

### 指标

- 广告真实竞价胜率 **90%（18/20）**，缓存回退填充 **10%（2/20）**，总填充率 **100%**。
- 预算超支率：**0**（Redis 余额非负；DB 计费与预算对账）。
- **当前端到端 P99 215ms；50ms 仅覆盖“定向 + pCTR”计划阶段，频控/预算读路径尚未本地化。** 20 并发竞价 P50 186ms；计划阶段硬超时触发 2/20，回退后仍填充。
- CTR 时间切分验证集 ECE **0.3767 → 0.0667**（前 700 拟合、后 300 评估；不使用训练集 ≈0 口径）。
- 延迟反馈：点击/转化全链路写入成功，曝光转化回填成功。

### 限制与风险

1. **50ms 硬超时只覆盖计划阶段**：频控/预算准入在计划返回后执行，端到端 P99 215ms。优化方案（阶段 6 前置，本阶段不实现）：本地 Caffeine 缓存频控/预算读 + Redis 异步核销/对账；或将准入下沉到单次 Lua 并把准入耗时纳入 50ms 预算。
2. 本机 ES kNN 抖动导致 VECTOR 召回默认 50ms 下降级；广告在线不依赖 VECTOR。
3. 广告索引消费者 RateLimiter 为固定窗口近似限流（默认 10/s），生产可换令牌桶。
4. 广告曝光计费在 async 事件中落库，极端宕机依赖 `ad:billing:stream` + 对账补偿。
5. 广告行为当前 `target_type=ad`，尚未进入自然画像；阶段 5 需决定是否纳入。

### 阶段 5 前置条件

- 行为埋点覆盖：曝光/点击/转化均落 `tb_behavior_event`，scene=ad；`ext_json` 字段已固定：`adId,campaignId,slotId,bid,pctr,ecpm,requestId,clickId,orderId`。
- 画像输入：当前 `BehaviorEventService` 只取 `target_type='activity'`，广告行为不会污染自然画像；阶段 5 要训练推荐/广告联合画像时，需要显式加入 `target_type='ad'` 或独立聚合，避免 CTR 反馈与自然兴趣混用。
- 预算/频控：`budget_deduct.lua` 已可单次完成频控+双预算+幂等；阶段 6 压测应关注准入后的 Redis 串行化对 P99 的影响。

### 效果摘要

- 设计并落地广告竞价模块：Redis 倒排定向 + 频控 Lua + `budget_deduct.lua` 双预算/幂等 + 分段保序回归 CTR 校准 + eCPM 竞价 + Feed 3/6 位插入；20 并发竞价总填充率 **100%**（真实胜率 90%、回退 10%）、预算超支 **0**，CTR 时间切分验证集 ECE **0.3767 → 0.0667**。
- 完成推荐 Feed 三项工程修复：召回池扩容使 20 并发全源降级 **12/20 → 0/20**、fine P50 **150.5 → 98.5ms**；新增广告索引独立 Stream/消费组/限流，20 条广告与自然索引事件互不阻塞。
