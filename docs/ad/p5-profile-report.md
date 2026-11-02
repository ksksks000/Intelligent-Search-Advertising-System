# 阶段 5：AI 用户画像模块功能报告

日期：2026-11-01  
范围：行为聚合、LLM 异步画像、画像存储/消费、推荐与广告接入、降级、可解释性；未改秒杀链路、`Result`、自然精排权重和广告竞价逻辑。

## 前置三项修正

### 修正 1：CTR 时间切分

`AdCtrTimeSplitTest`：1000 样本按时间切分，前 700 拟合、后 300 评估。

| 指标 | 10 段 | 说明 |
|---|---:|---|
| 校准前 ECE（test） | 0.3767 | 用恒等映射 |
| 校准后 ECE（test） | **0.0667** | 用前 700 拟合的 PAV 参数 |
| 是否 <0.01 | 否 | 不需要降到 5 段 |

修正报告：不再写“≈0”，改为“时间切分验证集 ECE **0.3767 → 0.0667**”。

### 修正 2：预算边界三用例

| 用例 | 结果 |
|---|---|
| 20 线程随机 30–150 分，总预算 1000 | 11 笔成功、扣减 974、剩余 26；剩余 ≥0，成功扣减总额 ≤1000 |
| 总预算 100，两笔 60 并发 | 1 成功、1 拒绝、剩余 **40**（100-60；被拒请求不扣费） |
| 扣减成功后模拟落库异常 | `refund` 回补总/日预算，剩余恢复 **1000**；幂等键删除后可重试 |

> 最后一笔用例中“余额仍为 100”与“有一笔成功扣 60”在算术上不能同时成立；实测正确语义为“被拒请求不扣费，成功一笔后余额 40”。

### 修正 3：硬超时/填充率表述

- 当前端到端 P99 **215ms**；**50ms 仅覆盖定向 + pCTR 计划阶段**，频控/预算读路径未本地化。
- 真实竞价胜率 **90%（18/20）**，缓存回退 **10%（2/20）**，总填充率 **100%**。
- 阶段 6 优化方案（本阶段不实现）：本地 Caffeine 缓存频控/预算读 + 异步核销/对账；把准入纳入 50ms 预算或独立预算服务。

## a) 目标与完成情况

| 目标 | 状态 |
|---|---|
| `tb_user_profile` + 实体/Mapper | 完成 |
| 自然/广告行为分开聚合，6h 定时 | 完成 |
| LLM 异步生成 + 10 用户/s 限流 + 1 次重试 + 降级 | 完成（本机未配置真实 LLM，测试用本地 mock） |
| interest_tags / ad_affinity / narrative / semantic_query 存储 | 完成 |
| 推荐 TAG + 向量消费 | interest_tags 已接入；向量因 embedding 服务不可用未写新向量 |
| 广告 ad_affinity 定向 | 完成（`ad:target:affinity:*` + `target_affinity`） |
| 可解释性管理端/用户端 | 完成 |
| 测试与报告 | 完成 |

## b) 数据模型

`src/main/resources/db/zs_p5.sql`：

`tb_user_profile`：
- `user_id` 主键
- `narrative`（运营可读）
- `interest_tags_json`（自然兴趣）
- `ad_affinity_json`（广告偏好）
- `avoid_tags_json`
- `semantic_query`
- `semantic_vector_id`（`user:interest:{userKey}`）
- `version` / `source` / `prompt_version` / `model_version` / `input_hash`
- `updated_at`

另给 `tb_ad_campaign` 增加 `target_affinity`。

## c) 行为聚合格式示例

自然行为（只来自 `target_type=activity`，跳过 impression）：

```json
{
  "natural_terms": [
    {"tag":"火锅","weight":2.0,"action":"click","timeBucket":"morning"},
    {"tag":"铜锅涮肉","weight":1.0,"action":"click","timeBucket":"morning"},
    {"tag":"聚餐","weight":1.0,"action":"click","timeBucket":"morning"}
  ],
  "natural_categories": {"1": 2}
}
```

广告行为（只来自 `target_type=ad`）：

```json
{
  "ad_terms": [
    {"tag":"火锅","weight":1.0,"action":"click","timeBucket":"morning"},
    {"tag":"涮肉","weight":1.0,"action":"click","timeBucket":"morning"},
    {"tag":"聚餐","weight":1.0,"action":"click","timeBucket":"morning"}
  ],
  "ad_categories": {"1": 1}
}
```

## d) LLM 画像

- Prompt 版本：`profile-prompt-v1`。
- 输出结构：`narrative + interest_tags + semantic_query + avoid_tags + ad_affinity`。
- 本地 mock 输出示例（脱敏）：

```json
{
  "narrative": "喜欢火锅和周末KTV的杭州用户",
  "interest_tags": [{"tag":"火锅","weight":0.9},{"tag":"涮肉","weight":0.8}],
  "semantic_query": "火锅 涮肉 杭州",
  "avoid_tags": ["美容"],
  "ad_affinity": [{"tag":"KTV","weight":0.7}]
}
```

- 20 用户并发生成：**20/20 LLM mock 成功**，限流 10/s 生效，无失败堆积。
- 本机 `hmdp.profile.llm-url` 为空，实际运行走结构化降级；生产配置 OpenAI-compatible URL 后走 HTTP 客户端，不在线调用 LLM。

## e) 画像存储与消费

- `user:profile:{userKey}:tags`：interest_tags JSON（userKey = `u{userId}` 或 `d{deviceId}`）。
- `user:profile:{userKey}:ad_affinity`：ad_affinity JSON。
- `user:interest:{userKey}`：semantic_query 向量（embedding 可用时）；embedding 不可用时不覆盖旧向量。
- `tb_user_profile.narrative`：运营后台可读。
- 推荐：`RecommendFeedService` 在阶段 3 画像后 `enrich` 合并 interest_tags；TAG 召回使用它们。
- 广告：`AdTargetingService` 增加 `ad_affinity` 倒排；`AdAuctionService` 从 Redis 读取用户 ad_affinity 后参与定向。
- `InterestVectorService` 标记 `@Deprecated`，接口和 key 不变，保留 embedding 兜底。

## f) 测试清单与实际结果

| 用例 | 结果 |
|---|---|
| CTR 时间切分（700/300） | ECE 0.3767 → 0.0667 |
| 预算不等额并发 | 11 成功，扣减 974，剩余 26，无超支 |
| 预算最后一笔 | 1 成功、1 拒绝、剩余 40 |
| 预算落库失败补偿 | refund 后余额 1000，幂等键删除 |
| 自然/广告行为分离 | 自然 2 条、广告 1 条；terms 分别生成 |
| LLM 单用户生成 | source=LLM，narrative/tags/ad_affinity 正确 |
| LLM 失败降级 | source=STRUCTURED，narrative=""，tags 来自行为计数 |
| 推荐 enrich | UserProfile.tagWeights 合并“火锅” |
| 广告 ad_affinity | Redis 读取 KTV/火锅标签；定向索引存在 |
| 用户标签编辑 | 非法标签过滤，Redis/DB 更新 |
| 20 用户并发生成 | 20/20 LLM mock 成功，无限流失败 |
| 登录火锅用户 Feed | Top10 cat1 **7**，不低于阶段 4 的 7 |
| 匿名画像覆盖（阶段 6 补测 3） | 10/10 设备生成成功；匿名有画像 vs 无画像 Top10 cat1 均为 **7.0**（持平） |
| 四场景画像消费（阶段 6 补测 4） | 火锅 7→7、KTV 7→7、跨品类 8→8、美容 7→7（结构化画像，LLM 端点未配置） |
| 向量替换对比（阶段 6 补测 2） | semantic_query 可编码；火锅 tags 7 vs semantic 7，跨品类 8 vs 8，持平；VECTOR 300ms 下 20 并发 VECTOR 降级 0/20 |

## g) 指标、限制、阶段 6 前置

### 指标（当前）

- 画像生成成功率：LLM mock **100%（20/20）**；本机未配置真实 LLM，运行态为 STRUCTURED 降级。
- 登录火锅用户 Top10 主类目命中：**7**（与阶段 4 持平）。
- 广告定向：ad_affinity 标签已写入并参与倒排；当前登录测试用户 affinity={火锅,涮肉}。
- 画像覆盖率：当前测试流量几乎全为匿名设备，`tb_user_profile` 仅登录测试用户，**匿名覆盖率 0%**。
- 画像新鲜度：定时任务每 **6 小时**；单次重建可管理端触发。

### 限制

1. 真实 LLM 端点未配置，LLM 成功率和语义质量只能在 mock 下验证；生产需配置 `HMDP_PROFILE_LLM_URL`。
2. embedding 用本机 Python 恢复后完成对比：火锅 7 vs 7、跨品类 8 vs 8，持平；VECTOR 300ms 下 20 并发 VECTOR 降级 0/20。Docker 512MB 受限未验证。
3. 画像主键已改为统一 `user_key`（`u{userId}` / `d{deviceId}`）；阶段 6 补测中 10/10 匿名设备可生成画像，但匿名有画像 vs 无画像 Top10 cat1 均 7.0，匿名行为稀疏，画像信号弱。
4. LLM 限流是固定窗口近似（10/s），生产可换令牌桶；重试 1 次，未硬撑超过阈值。
5. 广告行为不进入 interest_tags，自然行为不进入 ad_affinity；聚合器显式跳过 impression，避免曝光污染。
6. 四场景结构化画像前后持平：火锅 7→7、KTV 7→7、跨品类 8→8、美容 7→7；LLM 真实画像对比因端点未配置未执行。

### 阶段 6 前置条件

- **画像覆盖率**：需要把匿名 device 纳入画像 key（或登录引导），否则推荐/广告画像只覆盖登录用户。
- **画像新鲜度**：6h 定时 + 管理端重建；阶段 6 压测需统计“最后一次画像更新时间分布”。
- **压测目标**：搜索/推荐/广告 QPS 与 P99；广告端到端当前 P99 215ms，画像生成走异步，不影响在线链路。
- **向量**：embedding 服务恢复后重跑 semantic_query 向量 vs tags 拼接的 Top10 主类目命中对比。
- **LLM**：配置真实端点后验证成功率 ≥80%，低于阈值回退结构化并告警。

### 效果摘要

- 落地 AI 用户画像：行为按自然/广告分离聚合，LLM 异步生成 interest_tags/semantic_query/ad_affinity，20 用户并发生成 **20/20** 成功（mock），LLM 失败自动退化结构化画像；画像接入推荐 TAG 与广告广告位定向，登录火锅场景 Top10 主类目命中 **7**。
- 完成画像工程化：`user:profile:*` Redis + `tb_user_profile` 可回溯（prompt/model/input hash），用户可查看/编辑标签，6 小时定时刷新；为阶段 6 补匿名覆盖率、向量对比与广告 P99 优化。
