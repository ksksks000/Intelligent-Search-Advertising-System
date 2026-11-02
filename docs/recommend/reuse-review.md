# 阶段 3 复用审查（推荐 Feed）

## 1. 类级复用矩阵

| 已有资产 | 当前位置/现状 | 决策 | 说明 |
|---|---|---|---|
| `feed:{userId}` ZSet | `BlogServiceImpl.saveBlog/queryBlogOfFollow`，存 blogId→时间戳 | **原样复用** | 关注召回读取；不改写 blog 推模式 |
| `follows:{userId}` Set | `FollowServiceImpl.follow` / 取关 | **原样复用 + 启动重建** | 新增 `FollowRedisRebuildRunner` |
| `tb_follow` | 9 条测试关注关系 | **原样复用** | 分页读，SADD 幂等 |
| `act:geo:{city}` | `ActivityServiceImpl.rebuildGeo` + `GeoPreloadRunner` | **原样复用** | 地理召回 `GEOSEARCH`，按城市分片 |
| `activity_index` / `ActivityDocument` | Stage 2 搜索索引，834 条文档，512 维 `dense_vector` | **复用 alias/文档模型，直接 kNN** | 不调用 `ActivitySearchService.search`，避免搜索排序干扰推荐 |
| `SearchIndexService` | 索引 upsert/delete/reindex | **原样复用（只读 alias）** | 向量召回直接查 ES |
| `EmbeddingClient` | 独立 Python 服务 `/encode`，512 维，L2 归一化 | **原样复用** | 用户兴趣用 `encodeQuery`；失败降级 |
| `ActivitySearchService` | 倒排+向量+搜索排序 | **不复用** | 推荐有自己的召回/排序链路 |
| `SearchIndexEventConsumer` | 单线程串行消费 | **小改** | 按 `activityId` 分区并行，保持同一文档顺序 |
| `SearchIndexEventPublisher` | `search:index:stream` | **原样复用** | 事件格式不变 |
| `CacheClient` | 旁路缓存/逻辑过期 | **不复用（新增 FeedCacheService）** | Feed 分页、L1 Caffeine + L2 Redis、TTL 语义不同 |
| `ThreadPoolConfig` | 全局 `taskExecutor` | **复用范式** | 新增 `RecommendExecutorConfig` 隔离召回池/事件池 |
| `SeckillConsumerExecutor` | 有界动态线程池 | **复用范式** | 新增 `SearchIndexConsumerExecutor` 分区池 |
| `tb_activity` / `Activity` | 849 条活动，834 在线，5 城市、10 类目 | **原样复用** | 批量按 ID 加载；标签/城市/坐标 |
| `tb_activity_stats` | **不存在** | **新增** | 统计表 + upsert + 日聚合 |
| `tb_behavior_event` | 存在，但无 `device_id` | **小改** | 加 `device_id` 列；`ext_json` 保留 |
| `tb_blog` / `Blog` | DB 已有 `activity_id/status/tags/quality_score`，实体未映射 | **小改** | 补 4 个实体字段，供关注召回 blog→activity |
| `RateLimiter` / `RateLimitInterceptor` | Stage 0 已有 user/ip/api 令牌桶 | **原样复用** | `/recommend/feed` 挂载 |
| `MvcConfig` | Stage 2 已配置公开路径/限流路径 | **小改** | `/recommend/**` 公开，`/recommend/feed` 限流 |
| `Result` | 固定结构 | **不改** | Feed 数据放 `data` |
| `UserHolder/UserDTO` | 无 city 字段 | **复用** | city/坐标从请求参数；匿名用 `X-Device-Id` |
| `TraceIdFilter` | MVC traceId | **复用** | 召回/事件日志带 traceId |
| `SeckillMetrics` | Micrometer 模式 | **复用范式** | 新增 `RecommendMetrics` |

## 2. 与阶段 2 的接口约定

### 2.1 向量召回

- **直接查 `activity_index`**，不调用 `ActivitySearchService.search`。
- 使用 `SearchIndexService.getAlias()` 获取别名，`ActivityDocument.embedding` 字段做 kNN。
- 过滤 `status=2`，可选 `city`；`k=vectorTopN`，`numCandidates=2*k`。
- 返回 `activityId + similarity`，相似度 clamp 到 `[0,1]`。

### 2.2 用户兴趣向量

- 输入：近 7 天 `tb_behavior_event` 中 `click/view/feed_click/like/order` 的活动。
- 文本：活动 `tags`（按频次重复）+ `title` + `categoryId`，截断到约 512 字符。
- 编码：`EmbeddingClient.encodeQuery(text)`，返回 512 维、L2 归一化向量。
- 存储：`user:interest:{userKey}`，JSON 数组，TTL 24h；`userKey=u{userId}` 或 `d{deviceId}`。
- 失败：`EmbeddingException` → 记录 `DEGRADED: interest embedding`，向量召回跳过并标记 `VECTOR` 降级。

### 2.3 关注召回

- `feed:{userId}` 存的是 **blogId**；通过 `tb_blog.activity_id` 映射到活动。
- `feed` 为空时回退：按 `follows:{userId}` 的 `followUserId` 查 DB `tb_blog`（`status=1`、`activity_id is not null`）。
- 不修改 `BlogServiceImpl` 推模式。

### 2.4 统计与精排

- 精排读取 `tb_activity_stats` 近 7 天聚合 `ctr = click_cnt/pv`。
- 无统计时使用中性先验 0.5，不把 0 当作真实低质。
- 停留时长来自 Redis `rec:dwell:stats`（事件消费者累计），无数据用 0.5。

### 2.5 限流

- `/recommend/feed` 是公开接口；`RateLimitInterceptor` 的 user 维度在未登录时自动跳过，IP/API 维度生效。
- `MvcConfig` 将 `/recommend/**` 加入登录排除列表。

## 3. 与阶段 0/1/2 不一致点

| 不一致 | 说明 | 本阶段处理 |
|---|---|---|
| `tb_behavior_event` 阶段 1 只建表不接流量 | 阶段 3 开始写入曝光/点击/停留 | 新增 `device_id` 列；事件流 `rec:event:stream` |
| `tb_blog` 阶段 1 加了 `activity_id/status/tags/quality_score`，`Blog` 实体未映射 | 关注召回需要 blog→activity | 补 `Blog` 实体字段，不改表 |
| Stage 2 搜索有独立排序逻辑 | 推荐不能复用搜索排序 | 直接 ES kNN |
| Stage 2 索引消费者单线程 | 20 个事件会串行积压 | 分区有界线程池，按 activityId 保序 |
| Stage 2 embedding 服务 8090 当前未启动 | 向量召回不可用 | 实现降级：跳过 VECTOR、记录 degraded，不阻塞 Feed |
| `feed:{userId}` 是 blog 推模式，不是活动 Feed | 需要活动维度召回 | 通过 `tb_blog.activity_id` 映射 |
| `tb_activity_stats` 不存在 | 精排缺少 ctr/dwell | 新建表 + 事件写入 + 日聚合 |
| `UserDTO` 无城市 | 地理召回需要位置 | city/坐标请求参数；匿名 deviceId |

## 4. 关键设计决策

- **召回隔离**：每路召回独立 Future，`get(50ms)` 硬超时；超时/异常只跳过该路，`degradedSources` 记录。
- **保序**：关注召回按 blog 时间；索引消费者按 activityId hash 分区，保证同一文档顺序。
- **两级缓存**：L1 Caffeine 30s / 10k；L2 Redis 5min；key 含 `userKey + city + cursor + size`。
- **冷启动**：无行为 → HOT + GEO + CURATED（有 follow 时也带上 FOLLOW）。
- **规则权重**：无标注数据，先用人工可解释权重；测试用 10 个构造用户场景验证 Top10 合理性。
