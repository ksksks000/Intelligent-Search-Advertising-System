# 阶段 5 复用审查矩阵

| 已有资产 | 判断 | 说明 |
|---|---|---|
| `tb_behavior_event` / `BehaviorEvent` | **原样复用** | 已含 user_id/device_id/scene/ext_json；新增 `selectActiveUserIds` 查询，不改表结构。 |
| `BehaviorEventService` | **不复用聚合逻辑，保留读取** | 阶段 3 画像只取 activity 行为；阶段 5 新增 `UserBehaviorAggregator`，严格按 target_type 分流。 |
| `InterestVectorService` | **标记 deprecated，接口/Key 不变** | 仍写 `user:interest:{userKey}`；阶段 5 由 `UserProfileService` 用 LLM `semantic_query` 写同一 key，embedding 不可用时保留阶段 3 兜底。 |
| `EmbeddingClient` | **原样复用** | semantic_query 编码；服务不可用抛 `EmbeddingException`，画像降级为标签召回。 |
| `RedisConstants` | **小改** | 新增 `user:profile:` 前缀：tags / ad_affinity / llm rate。 |
| `RecommendFeedService` | **小改** | 构建阶段 3 画像后调用 `UserProfileService.enrich` 合并 interest_tags；TAG 召回逻辑不变。 |
| `AdTargetingService` | **小改** | 新增 `ad_affinity` 维度，广告计划 `target_affinity` 入倒排；在线仍只读 Redis，不调 LLM/embedding。 |
| `tb_user_profile` | **新增** | 阶段 1 未建；本阶段建表，含 narrative/tags/ad_affinity/vector_id/version/source/prompt/model/input_hash。 |
| `tb_ad_campaign` | **小改** | 增加 `target_affinity` 列，供广告画像定向。 |
| `ProfileVocabulary` | **新增** | 受控词表，LLM 输出白名单过滤。 |
| `ProfileExecutorConfig` / `ProfileLlmRateLimiter` | **新增** | 独立画像线程池与 LLM 10 用户/秒限流。 |
| `RecommendUtils.userKey` | **原样复用** | `u{userId}` 作为画像/向量 key，兼容阶段 3。 |

## 阶段 3 InterestVectorService 如何被替换

- **接口不变**：仍写 `user:interest:{userKey}`，`VectorRecallService` 无需改。
- **输入变化**：阶段 3 用近 7 天标签拼接文本；阶段 5 用 LLM 生成的 `semantic_query` 调 `EmbeddingClient`。
- **降级**：LLM/embedding 不可用时 `UserProfileService` 不写新向量，`VectorRecallService` 继续走阶段 3 的缓存/降级路径；`InterestVectorService` 保留为兜底。

## 自然行为与广告行为边界

- `interest_tags` 只从 `target_type=activity` 的行为聚合；
- `ad_affinity` 只从 `target_type=ad` 的行为聚合；
- 曝光不算兴趣，聚合器跳过 `impression`，避免“被展示过”污染标签；
- 广告计划定向使用 `ad:target:affinity:*`，与自然标签倒排分离。

## 与阶段 0–4 报告的不一致/说明

1. 阶段 1 只建了行为表，没有 `tb_user_profile`；本阶段新增，符合阶段 1 预留“本阶段只建表不接流量”的边界。
2. 阶段 3 `InterestVectorService` 自述“阶段 5 用 LLM 画像替换”；本阶段以 deprecated + 同 key 的方式完成，减少 `VectorRecallService` 改动。
3. 阶段 4 广告行为已写 `scene=ad`/`ext_json`；阶段 5 直接按 `target_type=ad` 聚合 ad_affinity，无需改广告埋点。
4. 当前画像表以 `user_id` 为主键，匿名 `device_id` 流量暂不生成画像；这是阶段 6 画像覆盖率的前置项。
