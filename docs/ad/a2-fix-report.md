# 阶段 4 前置修复 A-2：TAG 召回与粗排修复报告

> 范围：只做推荐链路 A-2 修复与 A 遗留验证；未进入广告竞价模块。精排权重保持 `0.28/0.17/0.15/0.13/0.12/0.15/0` 未改动；秒杀链路与 `Result` 结构未改。

## 1. 结论

1. **A 阶段失败的根因不是 TAG 召回不足，也不是粗排挤出，而是 Reranker 的同类目延后回填 bug。** 修复 Reranker 后 10 场景立即 10/10 通过，未调精排权重。
2. 修复 1（合成数据细标签 + 城市/类目分布）把杭州 KTV 样本 7 → 23，KTV TAG 召回 7 → 25。
3. 修复 2（TAG 标题词召回 + 新打分）和修复 3（粗排来源归一化 + TAG/FOLLOW 配额）均实现并回归通过；主类目命中相对 Reranker 修复的边际增益为 0，但 KTV/跨品类稳定性和候选结构更健康。
4. VECTOR 已补正向证据：预构建 512 维兴趣向量后，火锅/跨品类 Top10 均出现 VECTOR 来源；VECTOR 召回相对 TAG 分别多覆盖 18 / 17 条活动。默认 50ms 下本机 ES kNN 会超时降级，已增加可配置的 VECTOR 单独超时。
5. B/C 基线完成：20 并发 Feed P50=464.5ms / P99=477ms，瓶颈是召回线程池饱和；20 条索引事件 P50 total=53ms / P99=247ms，瓶颈是 embedding。

## 2. 第一步诊断表

诊断方式：干净设备注入 5 条点击，直连真实 TAG 召回 / 粗排 / 精排。

| 场景 | TAG 召回（总数/主类目） | TAG 主类目分数 min/median/max | 粗排 TAG（总数/主类目） | 精排 Top10 主类目 | 线上 Top10 主类目 | 判断 |
|---|---:|---:|---:|---:|---:|---|
| 火锅偏好（cat1） | 104 / 104 | 0.350 / 0.486 / 0.750 | 74 / 74 | 10 / 10 | 2 / 10 | TAG 足够、粗排未挤出；重排丢失 |
| KTV 偏好（cat2） | 7 / 7 | 0.492 / 0.615 / 0.740 | 6 / 6 | 6 / 10 | 2 / 10 | TAG 量偏少但够用；重排丢失 |
| 美容 SPA（cat6） | 80 / 80 | 0.940 / 0.971 / 0.999 | 70 / 70 | 10 / 10 | 4 / 10 | TAG 足够、粗排未挤出；重排丢失 |

**根因**：旧 Reranker 遇到“同类目连续上限 2”时，把超限的同 类目候选全部追加到结果末尾。单类目偏好用户精排前 7 条都是主类目，重排后前 2 条之后的主类目项被排到第 11 条以后，分页 Top10 只剩 2 条。

**修复**：候选超限时先放入 deferred；每轮优先重试 deferred 中已满足连续性约束的候选，只有完全无候选可放时才强制追加。修复后火锅 2 → 7、KTV 2 → 6、美容 4 → 7。

详细诊断见 `docs/ad/a2-diagnosis.md`。

## 3. 修复内容

### 修复 1：合成数据细标签 + 城市/类目覆盖

`loadtest/gen_synthetic_activities.py` 增加类目细标签，同时保留通用标签：

- cat1：火锅、涮肉、烤鱼、杭帮菜、日料
- cat2：KTV、欢唱、包厢
- cat4：健身、瑜伽、私教、月卡
- cat6：SPA、光子嫩肤、美甲、面部清洁
- cat7：亲子、儿童、手工、烘焙
- cat8：酒吧、鸡尾酒、驻唱、精酿

实际数据示例：

| 活动 | 类目 | tags |
|---|---|---|
| `900002` 老北京铜锅涮肉 2-3 人餐 | cat1 | 火锅,铜锅涮肉,聚餐 |
| `900007` 炉鱼烤鱼新品试吃 | cat1 | 新品首发,烤鱼,试吃 |
| `900010` 开乐迪 KTV 3 小时欢唱券 | cat2 | KTV,欢唱,买一送一 |
| `910002` 西湖生日包厢代金券 | cat2 | KTV,包厢,代金券,杭州 |
| `910006` 拱墅光子嫩肤体验代金券 | cat6 | 美容SPA,光子嫩肤,代金券,杭州 |
| `910036` 南山全身精油SPA新品首发 | cat6 | 美容SPA,SPA,新品首发,深圳 |

城市分配改为 `city_names[(i // 10) % 5]`，保证每个城市覆盖全部 10 个类目。杭州 KTV 在线样本 **7 → 23**。重新生成 `zs_p2_synthetic.sql`，ES 重建 **834 文档 / 15.27s**（embedding batch=4）。

### 修复 2：TAG 标签 + 标题词召回

- 召回条件：`tags LIKE tag` 或 `title LIKE tag`。
- 打分：`0.5 × 标签重叠 + 0.2 × 标题重叠 + 0.2 × 类目匹配 + 0.1 × 质量分`。
- TAG limit=200（可配置 `tag-limit`）。

修复后 TAG 召回（主类目）：火锅 40/40、KTV 25/23、美容 16/16。数量较修复前下降但全部主类目，KTV 从 7 提升到 25，粗排 TAG 保留 31/25/16 条。

### 修复 3：粗排来源归一化 + 配额

- 每个来源在本次候选集内做 min-max 归一化，再乘来源权重：FOLLOW 1.0、VECTOR 0.9、TAG 0.8、HOT 0.7、GEO 0.6、CURATED 0.5。
- 多路命中奖励 `+0.05 × (来源数-1)`。
- Top100 后执行 TAG 保底 30、FOLLOW 保底 10（候选不足不补），配额候选不足时剔除最低分非配额候选。
- 可回滚配置：`source-normalize`、`coarse-tag-quota`、`coarse-follow-quota`。

粗排示例（诊断直连各路召回，含 VECTOR）：

- `900002`：`{CURATED=0.90, HOT=0.93, TAG=0.63, VECTOR=0.91}` → coarse=2.891；TAG 原始 0.63 归一化到 1.0，TAG 贡献 0.80（未归一化仅 0.504）。
- `900010`：`{HOT=0.82, TAG=0.66, VECTOR=0.90}` → coarse=2.280；TAG 区间 [0.372,0.696]，归一化 0.889。
- `910406`：`{CURATED=0.80, GEO=0.95, HOT=0.80, TAG=0.68, VECTOR=0.89}` → coarse=2.762；TAG 归一化 0.996。

## 4. 10 场景回归

| 场景 | 阈值 | A 原权重 | Reranker 修复 | 修复 1 | 修复 1+2 | 修复 1+2+3 | 最终 |
|---|---:|---:|---:|---:|---:|---:|---|
| 新用户-杭州 | 冷启动非空 | 0 | 0 | 0 | 0 | 0 | PASS |
| 火锅偏好 | 6 | 2 | 7 | 7 | 7 | 7 | PASS |
| KTV 偏好 | 6 | 2 | 6 | 7 | 7 | 7 | PASS |
| 跨品类 | 5 | 10 | 10 | 9 | 9 | 9 | PASS |
| 地理偏好-上海 | 5 | 10 | 10 | 7 | 7 | 7 | PASS |
| 美容 SPA | 5 | 4 | 7 | 7 | 7 | 7 | PASS |
| 健身运动-深圳 | 5 | 8 | 7 | 7 | 7 | 7 | PASS |
| 亲子游乐-上海 | 5 | 8 | 7 | 7 | 7 | 7 | PASS |
| 酒吧夜生活-北京 | 5 | 8 | 7 | 7 | 7 | 7 | PASS |
| 匿名新用户-成都 | 冷启动非空 | 0 | 0 | 0 | 0 | 0 | PASS |

- A 原权重：7/10（火锅、KTV、美容不达标）。
- Reranker 修复后：10/10。
- 修复 1 / 1+2 / 1+2+3：均 10/10；主类目命中边际增益为 0，修复 1 改善数据覆盖（KTV 7→23），修复 2/3 为候选结构与稳定性兜底。

## 5. VECTOR 开/关对比

默认 50ms：VECTOR 在本机 ES kNN 下超时降级，Top10 与关闭 VECTOR 一致。

放宽到 150ms + 预构建 512 维兴趣向量后：

| 场景 | VECTOR 关闭 Top10 主类目 | VECTOR 开启 Top10 主类目 | VECTOR_ONLY（不在 TAG） |
|---|---|---|---|
| 火锅偏好 | cat1=7 | cat1=7，含 VECTOR 来源 | 18 条（cat9/cat8/cat2） |
| 跨品类 | cat1=6、cat2=2、cat4=1、cat8=1 | cat1=7、cat2=1、cat8=2 | 17 条（全部 cat8 夜生活） |

具体增量：火锅 Top10 新增 `910008`（cat8 精酿，HOT/GEO/VECTOR，TAG 未召回）；跨品类 Top10 新增 `910008`、`910108`（cat8），cat1 命中 6 → 7。

为把 VECTOR 拉回在线预算做了两点：

- `vector-top-n` 100 → 50；
- ES 查询 `_source=false`，只用 `_id`，避免拉取 50×512 维 embedding 反序列化。

但本机 kNN 仍有 22~195ms 尖刺，因此新增 `vector-timeout-ms`（默认 0 = 沿用 50ms，可回滚）。生产若 ES 稳定可保持 50ms；否则设 150ms 或迁独立向量服务。详见 `docs/ad/vector-on-results.md`。

## 6. B. 20 并发 Feed 延迟

| 阶段 | P50 | P99 | Max |
|---|---:|---:|---:|
| profile | 9.0 | 10.0 | 10 |
| recall.FOLLOW | 55.0 | 66.0 | 66 |
| recall.HOT | 51.5 | 66.0 | 66 |
| recall.GEO | 50.0 | 60.0 | 60 |
| recall.TAG | 51.0 | 51.0 | 51 |
| recall.VECTOR | 51.0 | 64.0 | 64 |
| coarse | 13.0 | 18.0 | 18 |
| fine | 150.5 | 166.0 | 166 |
| rerank | 0.0 | 1.0 | 1 |
| page | 0.0 | 0.0 | 0 |
| total | 464.5 | 477.0 | 477 |

- 20/20 缓存 MISS、均返回 10 条；12/20 触发多路召回超时及 HOT FALLBACK。
- 根因：`recallExecutor` core=6/max=12/queue=100，20 并发 × 5 路 = 100 个任务排队超过 50ms；精排稳定 150ms，主要来自 `fine.exposure` 143ms。
- 进入阶段 4 广告前应扩召回池或解耦广告并发，否则广告请求会放大排队。

详见 `docs/ad/b-feed-latency.md`。

## 7. C. 20 条索引事件消费

| 指标 | P50 | P99 | Max |
|---|---:|---:|---:|
| db | 2 | 3 | 3 |
| index | 49 | 245 | 245 |
| ack | 2 | 2 | 2 |
| total | 53 | 247 | 247 |
| embedding | 32 | 231 | 231 |
| es | 17 | 28 | 28 |

20 条突发事件约 340ms 消费完（约 58 条/s）。前 4 条跨分区同时触发 embedding，耗时 203/205/231/223ms，是 P99 来源；之后回落到 23~61ms。DB/ACK 稳定 1~3ms，ES upsert 13~28ms。重复消费同一批 ID 幂等，索引仍 834 条。

详见 `docs/ad/c-index-timing.md`。

## 8. 效果摘要

> 将推荐 Feed 10 个场景的主类目命中从 7/10 提升到 10/10（火锅 2→7、KTV 2→7、美容 4→7）；定位并修复 Reranker 单类目延后回填缺陷；通过合成细标签把杭州 KTV 样本 7→23、TAG 召回 7→25；新增粗排来源归一化/配额与 VECTOR 覆盖（每场景补 17–18 条 TAG 未召回活动），20 并发 Feed P99 477ms、索引事件 P50 53ms/P99 247ms。

## 9. 阶段 4 广告竞价前置条件

1. **精排权重冻结**：保持 `0.28/0.17/0.15/0.13/0.12/0.15/0`；广告不得反向修改自然排序权重。
2. **广告在线链路禁止 embedding/LLM**：VECTOR 在本机默认 50ms 下仍会降级；广告请求不得依赖向量服务。
3. **预算扣减独立 Lua**：新增 `budget_deduct.lua`，不复用 `seckill_segment.lua`；秒杀链路与 `Result` 不变。
4. **Feed 并发**：先扩/隔离 `recallExecutor`（或广告异步化），否则 20 并发已出现 12/20 降级，叠加广告会恶化 P99。
5. **索引侧**：embedding P99 231ms，广告索引/素材写入应与活动索引隔离或限流；广告在线读路径不写索引。
6. **可回滚开关**：`vector-enabled`、`vector-top-n`、`vector-timeout-ms`、`source-normalize`、`coarse-tag-quota`、`coarse-follow-quota` 均已配置化。

## 10. 本阶段改动文件

- `loadtest/gen_synthetic_activities.py`：细标签、城市/类目覆盖、Upsert 字段补全。
- `src/main/resources/db/zs_p2_synthetic.sql`：重新生成。
- `TagRecallService`：标题词召回 + 新打分。
- `CoarseRanker`：来源归一化、多路奖励、TAG/FOLLOW 配额。
- `Reranker`：deferred 重试回填。
- `RecommendProperties` / `application.yaml`：`source-normalize`、`coarse-tag-quota`、`coarse-follow-quota`、`vector-top-n=50`、`vector-timeout-ms`。
- `VectorRecallService`：`_source=false`，只取 `_id`。
- `RecommendFeedService`：VECTOR 独立超时；诊断日志降为 debug。
- `SearchIndexService` / `SearchIndexEventConsumer`：embedding 批量可配置、db/index/ack/total 计时。
- `AdminRecommendController`：兴趣向量预构建接口。
- `RecommendDiagnosticsIT`：三场景诊断 + VECTOR 覆盖诊断。
