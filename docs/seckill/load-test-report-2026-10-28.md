# 秒杀 JMeter 压测报告（2026-10-28）

> 本报告数据来自真实执行，未做任何估算或编造。

## 1. 环境

| 项 | 值 |
|---|---|
| 日期 | 2026-10-28 |
| 应用 | Spring Boot 2.3.12，单实例，端口 8081 |
| JVM | Java 17（Maven 运行），Tomcat 默认线程池 |
| MySQL | 8.4.8（Windows 本机 3306） |
| Redis | 7.0.15（WSL，临时实例 16379） |
| 消息模式 | Redis Stream（`hmdp.seckill.mq=stream`） |
| 压测工具 | Apache JMeter 5.6.3 |
| 测试券 | voucher_id=16，初始库存 1000 |
| token | 1000 个真实登录用户（CSV Data Set） |
| 线程 | 200 |
| 爬坡 | 10s |
| 持续 | 30s |
| 限流 | 本次关闭（用于测 Lua/Stream/DB 主链路容量） |

## 2. JMeter 汇总

JMeter `statistics.json`：

| 指标 | 值 |
|---|---|
| 样本数 | 30,115 |
| 错误数 / 错误率 | 0 / 0.00% |
| 吞吐量 | **1007.73 req/s**（summary 996/s，含爬坡） |
| 平均响应时间 | 165.97 ms |
| 中位数 | 183 ms |
| 最小 / 最大 | 2 / 461 ms |
| P90 | **346 ms** |
| P95 | **370 ms** |
| P99 | **405 ms** |
| 接收速率 | 263.31 KB/s |

JTL 复核（Python 计算）：

| 指标 | 值 |
|---|---|
| 样本数 | 30,115 |
| HTTP 状态码 | 全部 200 |
| P50 | 151 ms |
| P90 | 334 ms |
| P95 | 365 ms |
| P99 | 404 ms |
| Max | 461 ms |

> `statistics.json` 与 JTL 的分位值有 1–30ms 差异，属于 JMeter 分位插值口径差异；报告以 `statistics.json` 为准，JTL 作为交叉校验。

## 3. 业务正确性

| 校验项 | 结果 |
|---|---|
| DB 订单数（voucher 16） | **1000** |
| DB 库存 | **0** |
| 超卖 | **0**（订单数 = 初始库存） |
| 一人多单违规 | **0** |
| 幂等成功记录 | **1000** |
| 失败表 `tb_seckill_failure` | 0 |
| Redis 总库存 | 0 |
| Redis 段库存和 | 0 |
| Redis order set | 1000 |
| Stream 长度 | 1000 |
| Stream pending | **0** |
| Stream lag | 0 |
| 死信流 | 0 |
| 消费成功指标 | 1000 |
| 线程池拒绝 | 0 |
| 线程池活跃 / 队列 | 0 / 0 |
| 对账（运行期间每 60s） | 未发现不一致 |

## 4. 业务成功率说明

- JMeter HTTP 成功率：**100%**（全部 200）。
- 业务成功数：**1000 / 30115 = 3.32%**。
- 剩余 29127 次为 `DUPLICATE`：token 池只有 1000 个用户，且一人一单；1000 人全部下单后，后续请求都是同一批用户重复请求。
- 本次没有出现 `SOLD_OUT` 指标，原因是脚本先判断“一人一单”再判断售罄：所有请求用户都已在 order set 中，因此返回 `DUPLICATE`。若出现新用户，会命中 `SOLD_OUT`/`SOLD_OUT_FLAG`。
- 指标：`seckill_requests_total{code="SUCCESS"}=1000`，`{code="DUPLICATE"}=29127`，`seckill_consume_total{result="SUCCESS"}=1000`。

## 5. 限流验证（独立 curl 测试）

本次 JMeter 关闭了限流以测主链路容量。限流能力在另一轮真实请求中验证：

- 配置：user 10 容量 / 5 per second。
- 20 次快速请求：`14 × 200 + 6 × 429`。
- 429 响应：`Retry-After: 1`，`X-Trace-Id`，`{"success":false,"errorMsg":"请求过于频繁，请稍后重试"}`。
- 指标：`seckill_ratelimit_rejected_total{dimension="user"}=7`。

## 6. 结论

在单实例、WSL Redis、MySQL 8.4.8 环境下：

- 约 **1000 QPS**，P99 **405ms**，HTTP 错误率 0%。
- 1000 库存并发下**不超卖**、**一人一单**、**消息不丢**（pending=0）。
- 消费端线程池无拒绝，指标与对账正常。

## 7. 注意事项

- 本次为单机压测，JMeter 与应用同机，结果包含本机资源竞争；生产评估需独立压测机与多实例。
- 若开启限流且所有请求来自同一 IP，IP 维度会成为主要瓶颈；容量测试建议关闭限流或使用分布式来源 IP。
- 未执行 RabbitMQ 模式压测；Rabbit 模式保留为回滚路径。
