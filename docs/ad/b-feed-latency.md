# B. Feed 20 并发延迟基线

- 环境：应用 8081，vector-enabled=true，默认 `recall-timeout-ms=50`、`vector-timeout-ms=0`；20 个独立设备，每个 5 条点击，兴趣向量已预构建。
- 方式：20 个请求同时打 `/recommend/feed?city=杭州&size=10`，全部缓存 MISS，每页 10 条。

## 每阶段耗时（ms）

| 阶段 | 样本 | P50 | P99 | Max |
|---|---:|---:|---:|---:|
| profile | 20 | 9.0 | 10.0 | 10 |
| recall.FOLLOW | 20 | 55.0 | 66.0 | 66 |
| recall.HOT | 20 | 51.5 | 66.0 | 66 |
| recall.GEO | 20 | 50.0 | 60.0 | 60 |
| recall.TAG | 20 | 51.0 | 51.0 | 51 |
| recall.VECTOR | 20 | 51.0 | 64.0 | 64 |
| coarse | 20 | 13.0 | 18.0 | 18 |
| coarse.db | 20 | 12.0 | 18.0 | 18 |
| fine | 20 | 150.5 | 166.0 | 166 |
| fine.score | 20 | 147.0 | 163.0 | 163 |
| fine.stats | 20 | 3.0 | 7.0 | 7 |
| fine.exposure | 20 | 143.0 | 158.0 | 158 |
| rerank | 20 | 0.0 | 1.0 | 1 |
| page | 20 | 0.0 | 0.0 | 0 |
| total | 20 | 464.5 | 477.0 | 477 |

## 降级与瓶颈

- 20 个请求全部 MISS、全部返回 10 条。
- 12/20 降级为 `[GEO, FOLLOW, FALLBACK, TAG, HOT, VECTOR]`；5/20 仅 `[VECTOR]`；其余为单路 + VECTOR。
- 根因：召回线程池 `recallExecutor` 为 core=6/max=12/queue=100。20 并发 × 5 路 = 100 个召回任务，任务排队超过 50ms 单路超时，先提交的 future 在 `get(50ms)` 时大量超时，触发 HOT FALLBACK。
- 精排 150ms 是当前最大稳定开销，主要来自 `fine.exposure`（143ms）；粗排仅 13ms。
- 结论：功能正确但 20 并发下召回池是首要瓶颈。进入阶段 4 广告链路前应先扩大/隔离召回线程池，或把广告请求与自然 Feed 召回解耦，避免广告并发进一步放大排队。
