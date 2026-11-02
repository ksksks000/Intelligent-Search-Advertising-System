# VECTOR 开启/关闭对比（embedding 8090，interest 预构建）

## 1. 默认 50ms 单路超时：VECTOR 仍降级

- `vector-top-n=50`，ES 只取 `_id`（`_source=false`），避免拉取 512 维 embedding。
- 本机 ES kNN（k=50, num_candidates=100）实测 **22~195ms**，偶发尖刺超过 50ms，因此在线 Feed 默认 50ms 时 `degradedSources=['VECTOR']`，Top10 与关闭 VECTOR 完全一致。
- 新增可配置项 `hmdp.recommend.vector-timeout-ms`（<=0 复用 `recall-timeout-ms=50`）；仅用于按环境放宽，不影响其余四路 50ms 硬超时。

kNN 原始延迟（k=50，`_source=false`，杭州）：

| 次数 | 1 | 2 | 3 | 4 | 5 |
|---|---:|---:|---:|---:|---:|
| 延迟 | 132ms | 51ms | 44ms | 83ms | 18ms |

## 2. 放宽到 150ms + 预构建兴趣向量：VECTOR 正向命中

启动参数：`HMDP_RECOMMEND_VECTOR_TIMEOUT_MS=150`；`POST /admin/recommend/interest/rebuild` 生成 512 维用户兴趣向量。

### 火锅偏好（hotpot）

degraded=[]，Top10 类目 `{1: 7, 2: 1, 8: 1, 10: 1}`：

1. `900009` cat=1 sources=`[HOT, TAG, VECTOR]` score=0.719 — 羊蝎子火锅双人餐秒杀
2. `900002` cat=1 sources=`[HOT, TAG, VECTOR]` score=0.703 — 老北京铜锅涮肉 2-3 人餐
3. `900010` cat=2 sources=`[HOT]` score=0.352 — 开乐迪 KTV 3 小时欢唱券
4. `900020` cat=1 sources=`[HOT, TAG, VECTOR]` score=0.656 — 铜锅涮肉 100 元秒杀券
5. `900005` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.651 — 海底捞四人火锅套餐
6. `910008` cat=8 sources=`[HOT, GEO, VECTOR]` score=0.328 — 滨江精酿啤酒双人套餐（VECTOR 有、TAG 无）
7. `910001` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.643 — 西湖火锅双人餐代金券
8. `900018` cat=1 sources=`[HOT, GEO, TAG]` score=0.557 — 新品桂花拿铁全城首发
9. `910710` cat=10 sources=`[HOT]` score=0.296 — 手部护理类
10. `900006` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.522 — 老北京涮锅冬日暖锅节

### 跨品类（cross）

degraded=[]，Top10 类目 `{1: 7, 2: 1, 8: 2}`：

1. `900002` cat=1 sources=`[HOT, TAG, VECTOR]` score=0.601
2. `900018` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.567
3. `900010` cat=2 sources=`[HOT, TAG, VECTOR]` score=0.554
4. `900009` cat=1 sources=`[HOT, TAG, VECTOR]` score=0.561
5. `900020` cat=1 sources=`[HOT, TAG, VECTOR]` score=0.554
6. `910008` cat=8 sources=`[HOT, GEO, VECTOR]` score=0.474（VECTOR 有、TAG 无）
7. `900005` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.493
8. `910001` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.485
9. `910108` cat=8 sources=`[HOT, VECTOR]` score=0.429（VECTOR 有、TAG 无）
10. `900006` cat=1 sources=`[HOT, GEO, TAG, VECTOR]` score=0.420

## 3. VECTOR 覆盖 TAG 未召回的活动

诊断直连 TAG/VECTOR（TAG limit=200，VECTOR topN=50）：

| 场景 | TAG 召回 | VECTOR 召回 | VECTOR_ONLY（不在 TAG） | VECTOR-only Top 示例 |
|---|---:|---:|---:|---|
| 火锅偏好 | 40 | 50 | 18 | cat9 美食、cat8 精酿/驻唱、cat2 KTV |
| 跨品类 | 80 | 50 | 17 | cat8 精酿/驻唱/鸡尾酒 |

结论：VECTOR 对 TAG 的增量主要是**跨类目夜生活/酒吧**候选。火锅 Top10 新增 `910008`（cat8），跨品类 Top10 新增 `910008`、`910108`（cat8），同时跨品类 cat1 命中由 6 升到 7。

## 4. 回滚与生产建议

- 回滚 VECTOR：`hmdp.recommend.vector-enabled=false`。
- 默认 `vector-timeout-ms: 0`（50ms），保持原有硬超时；本机 ES 抖动下 VECTOR 会降级，不影响其他召回。
- 若生产 ES 延迟稳定在 50ms 内，可保持 50ms；若不能，建议 `vector-timeout-ms: 150` 或把向量召回迁到独立检索服务，避免拖累 Feed 主链路。
- `vector-top-n` 由 100 降到 50，减少 kNN 与反序列化开销，可回滚。
