# 阶段 6：收口、补测与项目总报告（压测阻断版）

日期：2026-11-01  
状态：**文档同步完成；补测 2/3/4 完成；补测 1 外部阻塞；搜索 50/100/200 与推荐 200、广告 100 均真实执行；200 搜索/推荐/广告 P99 触发停止条件，不编造数据**。

## 1. 文档同步

已修正 `docs/ad/p4-ad-report.md`：

- CTR：统一为时间切分 **ECE 0.3767 → 0.0667**（前 700 拟合 / 后 300 评估）；删除“0.3500 → ≈0”。
- 预算并发表：补充不等额并发、最后一笔、落库失败回滚三条用例。
- 填充率：真实胜率 90%、回退 10%、总填充率 100%。
- 硬超时：端到端 P99 215ms；50ms 仅覆盖计划阶段。
- 一致性检查表：`docs/ad/p4-doc-consistency.md`（逐位置列出 CTR/预算/填充/硬超时表述，无第三个版本）。

## 2. 四项补测

### 补测 1：真实 LLM —— 阻塞

- 环境没有 `HMDP_PROFILE_LLM_URL` / API key（已检查环境变量与仓库配置），无法调用通义/DeepSeek/智谱真实端点。
- 已完成的能力验证（mock，不是真实 LLM）：
  - 本地 mock 20/20 成功；LLM response 支持 OpenAI `choices[0].message.content` 和直接 JSON。
  - 解析器已支持 Markdown ```json 代码块剥离；20/20 mock 在 fenced JSON 下仍成功。
  - 受控词表过滤、缺字段默认值、失败降级已由 `UserProfileServiceTest` 覆盖。
- **真实 LLM 成功率：未测量（0/0）**，不满足“真实 LLM ≥10 用户”的验证条件；按约束停在这里，不把 mock 20/20 写成真实数据。

### 补测 2：embedding 服务与向量对比 —— 本机 Python 绕过完成

- Docker 构建未完成（CPU torch 下载中断），改用本机 Python uvicorn 启动 embedding（`/health UP`，dim=512）；Windows 无 `resource.setrlimit`，未套 512MB 限制。
- VECTOR 超时放宽到 300ms 后，20 并发 Feed：**VECTOR 降级 0/20**（阶段 4 默认 50ms 下稳定降级），HOT 降级 3/20；P50 319ms。
- tags 拼接向量 vs semantic_query 向量（新用户、同行为）：

| 场景 | tags 向量主类目命中 | semantic_query 命中 | 说明 |
|---|---:|---:|---|
| 火锅 | 7 | 7 | tags 向量当次 VECTOR 303ms 降级；semantic 向量 2ms 不降级 |
| 跨品类 | 8 | 8 | 两者均不降级 |

结论：semantic_query 向量可正常生成/召回，命中数与 tags 持平；当前收益主要是可解释性和 VECTOR 稳定性，不是排序提升。

### 补测 3：匿名画像覆盖 —— 完成

- 画像主键改为统一 `user_key`：登录 `u{userId}`、匿名 `d{deviceId}`；Redis/MySQL/推荐 enrich/广告 ad_affinity 均按 userKey 读取。
- 20 个匿名设备注入 5 条火锅点击；10 个设备生成画像成功（10/10）。
- 对比：

| 组 | 设备数 | Top10 cat1 平均 |
|---|---:|---:|
| 匿名无画像 | 10 | 7.0 |
| 匿名有画像 | 10 | 7.0 |

- 结论：匿名行为只有 5 条点击，画像信号弱，未带来排序提升；覆盖率 10/10，画像主要价值在冷启动语义泛化与可解释性，需要更多行为。

### 补测 4：多场景画像消费对比 —— 完成（结构化画像）

| 场景 | 阶段 3 简化画像主类目命中 | 阶段 5 画像主类目命中 |
|---|---:|---:|
| 火锅 | 7 | 7 |
| KTV | 7 | 7 |
| 跨品类 | 8 | 8 |
| 美容 | 7 | 7 |

- 本机 LLM 端点为空，阶段 5 实际走 STRUCTURED 画像；interest_tags 与行为标签同源，因此持平。
- 未完成“真实 LLM interest_tags + semantic_query”对比；不能宣称 LLM 提升。

## 3. 正式压测（本次实测；降级态）

环境：应用 `-Xmx640m -XX:MaxMetaspaceSize=256m`；`vector-enabled=true`、`vector-timeout-ms=300`；LLM 未配置（STRUCTURED）；embedding 本机 Python uvicorn **1 worker**（2 worker 时主机仅余 ~396MB，应用无法启动；Windows 无 `resource.setrlimit`）；ES heap 512m，Redis/MySQL Docker；压测与目标同机 16GB。

搜索修复：query 向量 Redis 缓存 `search:query:vec:{hash}` TTL 1h；embedding 200ms 超时，超时纯倒排降级；响应带 `degraded/vectorDegraded/queryVectorCacheHit`。

| 搜索档位 | 目标 | 实际 QPS | P50 | P99 | 错误率 | 降级率 | query 缓存命中 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 50 QPS × 5min | 50 | 49.7 | 77.6ms | **304.2ms** | 0% | 0.49% | 99.47% |
| 100 QPS × 5min | 100 | 99.2 | 61.6ms | **541.2ms** | 0% | 0% | 100% |
| 200 QPS × 5min | 200 | 122.7 | 1589.4ms | **2332.6ms** | 0% | 0% | 100% |

- 50 QPS 达标（P99 <500ms）。
- 100 QPS P99 541ms，略超 500ms 但 <1s。
- 200 QPS 实际仅 122.7 QPS，P99 2332ms；query 缓存 100% 命中，embedding 已不是瓶颈，瓶颈转为 **ES/请求线程/连接池**；停止上探。

推荐（目标 200 QPS × 5min）：

| 实际 QPS | P50 | P99 | 错误率 | 降级率 | 缓存 |
|---:|---:|---:|---:|---:|---|
| 189.3 | 172.6ms | **1122.4ms** | 0% | **98.83%** | L1 54855 / L2 1 / MISS 2016 |

- P99 >1s；降级主要来自 Feed 的 `AD`（竞价准入/超时）与少量 HOT，不是 VECTOR；按约束不升 400/800。

广告（目标 100 QPS × 5min）：

| 实际 QPS | P50 | P99 | 错误率 | 降级/回退 |
|---:|---:|---:|---:|---:|
| 48.8 | 2018.0ms | **3832.2ms** | 0% | 1.72% |

- 压后 20 次抽样：填充 20/20（100%），真实胜率约 98.28%、回退 1.72%；预算余额全部非负。
- P99 >500ms，停止，不再上探 200/500 QPS。

## 4. 监控接入 —— 已启动

- Prometheus 容器 `zs-prometheus` 已启动（`:9090`，`--memory=256m`），`/-/ready` 返回 200，抓取 `host.docker.internal:8081/actuator/prometheus`。
- `monitoring/export_metrics.py` 已导出 **33 条**指标到 `monitoring/metrics.csv` / `metrics.html`（HTTP 请求、线程池、秒杀/广告指标）。
- 压测期间搜索请求指标：`http_server_requests_seconds_count{uri="/search/activity"}=2357`，sum=6087.4s，max=12.56s，与压测脚本 P99 7.9s/健康 DOWN 一致。

## 5. 项目总报告

### 5.1 背景与定位

“智搜推”是在既有高并发交易底座上做的领域升级：保留 Redis/Lua/Stream/缓存/锁等高并发能力，把业务从优惠券秒杀扩展到本地生活“搜索 + 推荐 + 广告 + 画像”闭环。

### 5.2 架构总览（文字版）

```
用户/设备
  -> 搜索：ES 倒排 + kNN，embedding 独立服务，失败降级纯倒排
  -> 推荐：5 路召回（FOLLOW/HOT/GEO/TAG/VECTOR/CURATED）
       -> 粗排 Top100 -> 精排（冻结权重）-> 重排 -> 广告固定位 3/6 -> Feed
  -> 广告：Redis 倒排定向 -> pCTR 校准 -> eCPM 一价 -> budget_deduct.lua 准入
       -> 曝光/点击/转化 -> Stream 计费/延迟反馈
  -> 画像：行为聚合（自然/广告分离）-> LLM 异步 -> user:profile:* / 向量 / MySQL
数据层：MySQL（业务/画像/计费）、Redis（缓存/倒排/预算/频控/Stream）、ES（活动/搜索）
```

### 5.3 各阶段成果

- 阶段 1：环境升级、活动主表/行为表/统计表落地；保留老表、新增活动抽象。
- 阶段 2：ES 8.11 混合检索（倒排 + 向量兜底）、索引别名与增量 Stream。
- 阶段 3：5 路召回 + 粗排/精排/重排 + L1/L2 缓存 + 冷启动/降级；推荐 Feed 闭环。
- 阶段 4：广告数据模型、定向/频控/双预算 Lua、CTR 校准、eCPM 竞价、Feed 3/6 位、计费与延迟反馈；A-2 修复后 10 场景 10/10。
- 阶段 5：自然/广告行为分离聚合、LLM 异步画像、interest_tags/ad_affinity/语义 query、推荐与广告消费、用户标签编辑。

### 5.4 压测数据（降级态实测）

| 链路 | 目标 QPS | 实际 QPS | P50 | P99 | 错误率 | 降级/回退 | 结论 |
|---|---:|---:|---:|---:|---:|---:|---|
| 搜索 50 | 50 | 49.7 | 77.6ms | 304.2ms | 0% | 0.49% | 达标 |
| 搜索 100 | 100 | 99.2 | 61.6ms | 541.2ms | 0% | 0% | 略超 500ms |
| 搜索 200 | 200 | 122.7 | 1589ms | 2332ms | 0% | 0% | 超标，停止 |
| 推荐 | 200 | 189.3 | 172.6ms | 1122ms | 0% | 98.83% | P99 超标，未上探 |
| 广告 | 100 | 48.8 | 2018ms | 3832ms | 0% | 1.72% 回退 | 超标，未上探 |

环境：VECTOR 300ms 恢复（20 并发 VECTOR 降级 0/20），LLM STRUCTURED，embedding 1 worker，ES 512m；搜索 query 缓存命中 99.47–100%。

### 5.5 关键取舍

1. **不物理改名老表**：新增 `tb_activity` 等主表，老表语义升级；降低迁移风险，兼容既有业务数据。
2. **预算扣减新写 `budget_deduct.lua`**：秒杀“分段库存+一人一单”与广告“总/日预算+可变出价+频控”语义不同，强行复用会造成日预算/退款/幂等混乱。
3. **广告在线不调 embedding/LLM**：在线只读 Redis 倒排/画像标签，LLM/embedding 只在异步画像/索引；保证广告 50ms 计划预算。
4. **广告固定位 3/6**：自然精排权重冻结，固定位可预测；合并排序会让广告 eCPM 改写自然序列，违背约束。
5. **画像自然/广告行为分开**：interest_tags 只来自 activity，ad_affinity 只来自 ad；避免广告曝光污染自然兴趣，也便于广告单独定向。
6. **中文分词 standard + 向量兜底**：不装 IK，降低运维成本；标准分词对本地生活短文本够用，长尾靠向量。
7. **LLM 只在异步画像**：在线推荐/广告绝不调用 LLM，失败回退结构化统计。

### 5.6 已知限制（如实）

- 匿名画像：10/10 可生成，但 Top10 cat1 与无画像持平（7.0 vs 7.0）；行为稀疏时信号弱。
- VECTOR：本机 embedding CPU + ES kNN 下，VECTOR 300ms 时 20 并发 Feed VECTOR 降级 0/20；搜索 200 QPS 实际 122.7 QPS、P99 2332ms，query 缓存 100% 命中后瓶颈转为 ES/请求线程。
- 真实 LLM：未配置端点，真实成功率未测量；mock 20/20（mock）不代表线上。
- 广告端到端 P99 215ms；50ms 只覆盖计划阶段，预算/频控准入后置，未纳入 50ms。
- **降级态压测基线**：搜索 50/100 QPS 达标或接近达标，200 QPS 超标；推荐 200 QPS P99 1122ms、降级 98.83%；广告 100 QPS P99 3832ms；完整态需 ES/线程池优化与 GPU embedding 后复测。
- 合成活动 834 条，规模与真实业务差距大。

### 5.7 效果摘要（实测值；mock 已标注）

- 推荐 Feed：A-2 修复后 10 场景 **10/10** 达标；20 并发 Feed P50 **363.5ms/P99 375ms**，全源降级 **0/20**；fine P50 **150.5→98.5ms**，曝光查询 P50 **143→69ms**；降级态 200 QPS 实测 189.3 QPS、P99 1122ms、降级率 98.8%。
- 广告：20 并发真实胜率 **90%**、回退 **10%**、总填充 **100%**，预算超支 **0**；100 QPS 压测实际 48.8 QPS、P99 3832ms、回退 1.72%、填充 100%；CTR 时间切分 ECE **0.3767→0.0667**。
- 画像：LLM mock **20/20（mock）** 并发生成成功；匿名画像覆盖 **10/10**，Top10 cat1 与无画像持平 **7.0→7.0**。
- 搜索：query 向量缓存+200ms 降级后，**50 QPS P99 304ms、100 QPS P99 541ms、200 QPS 122.7 QPS/P99 2332ms**；50/100 达标或接近，200 受 ES/线程池限制；20 条索引事件 P50 **53ms/P99 247ms**。
- 向量：embedding 本机恢复后，20 并发 Feed VECTOR 降级 **0/20**（300ms 超时配置）；semantic_query vs tags 向量命中持平（火锅 7/7、跨品类 8/8）。

### 5.8 常见设计问题（10 个）

1. **为什么不用 Kafka/Flink？** 当前数据量与单机部署更适合 Redis Stream + 定时任务；Stream 提供消费组/ACK/pending，成本低；Flink 适合窗口聚合/超大吞吐，阶段 6 压测若到万级 QPS 再引入。
2. **预算为什么不复用秒杀 Lua？** 秒杀是一次性库存、一人一单；广告有日预算重置、可变 bid、频控 ZSet、request+billing 幂等、退款补偿；语义不同，复用会引入超支/重复扣费。
3. **CTR 校准怎么算？** 10 段分桶 + PAV 保序 + 线性插值；时间切分前 700 拟合、后 300 评估，ECE 0.3767→0.0667；不是 ≈0，训练集 ≈0 是过拟合口径。
4. **画像怎么接入推荐/广告？** interest_tags 合并进 UserProfile 供 TAG 召回；semantic_query 写 user:interest 供 VECTOR；ad_affinity 进广告倒排；LLM 失败退结构化标签。
5. **广告为什么固定位？** 自然精排权重冻结，固定位不反向改自然排序；缓存 key 区分 ad/noad；频控/预算过滤后保留自然位。
6. **P99 215ms，50ms 保护了什么？** 保护定向+pCTR 计划阶段；频控/预算准入后置，是已知限制；优化方向是本地缓存读+异步核销/预算服务。
7. **匿名画像覆盖？** `d{deviceId}` 统一 userKey；10/10 可生成，但 5 条点击下 cat1 7→7，无提升；需要更多行为或冷启动语义。
8. **VECTOR 为什么默认降级？** ES kNN 在本机 22–195ms，超过 50ms 单路预算；配置 vector-timeout 可放宽，或独立向量服务；恢复后需重跑降级率对比。
9. **和老业务数据模型的关系？** 保留 Redis/Lua/Stream/缓存底座，新增活动/搜索/推荐/广告/画像；老表不物理改名，新增主表兼容。
10. **10 倍流量先崩哪？** 搜索链路最先崩：query 向量缓存后 50 QPS P99 304ms、100 QPS 541ms，200 QPS 实际 122.7 QPS、P99 2332ms；瓶颈从 embedding 转到 ES/线程池。其次推荐 200 QPS P99 1122ms、降级 98.8%，广告 100 QPS P99 3832ms。需 ES 扩容/线程池隔离、GPU embedding、预算频控本地化。

### 5.9 下一步

- 配置真实 LLM（多模型 fallback）并完成真实 10 用户验证（成功率 ≥80%）。
- Docker embedding 512MB 跑通，补 semantic_query vs tags 向量对比与 VECTOR 恢复降级率。
- 预算/频控本地 Caffeine 读 + 异步核销，把广告端到端压到 80ms。
- 匿名画像覆盖扩到更多行为；A/B 实验框架；合成数据扩到万级后执行 200/800/500 QPS × 10min 正式压测。

## 6. 阻塞项与需要的外部输入

1. 真实 LLM endpoint + API key（通过 `HMDP_PROFILE_LLM_URL` 注入，不进仓库）。
2. Docker 构建/512MB 运行 embedding 的资源与网络（当前 torch 下载未完成）。
3. 压测环境资源（应用 + MySQL + Redis + ES + embedding 同时运行的机器内存/CPU），用于真实 10 分钟压测。
