# C. 搜索索引事件消费耗时（embedding 开启）

- 应用与 embedding 8090 同时运行；`search:index:stream` 一次性发布 20 个 UPSERT 事件（activityId 900002~900021，重复消费同一批 ID 以验证幂等）。
- 4 个 activityId 分区并行消费；`vector=true`。

## 消费耗时（ms）

| 指标 | P50 | P99 | Min | Max | Avg |
|---|---:|---:|---:|---:|---:|
| db（查活动） | 2 | 3 | 1 | 3 | 1.8 |
| index（upsert 全流程） | 49 | 245 | 42 | 245 | 86.5 |
| ack | 2 | 2 | 1 | 2 | 1.8 |
| total | 53 | 247 | 46 | 247 | 90.2 |
| embedding | 32 | 231 | 23 | 231 | 68.0 |
| es | 17 | 28 | 13 | 28 | 18.2 |

## 说明

- 20 条事件在约 **340ms** 内全部消费并 ACK，吞吐约 **58 条/s**。
- 前 4 条跨分区同时到达，embedding 冷启动/并发争用为 203/205/231/223ms，是 P99=245ms 的来源；之后 embedding 回落到 23~61ms（P50=32ms）。
- DB 查询稳定 1~3ms，ACK 1~2ms；瓶颈完全在 embedding，ES upsert 13~28ms。
- 幂等：第二次消费同一批 activityId 全部成功 upsert 并 ACK，无重复文档（索引仍 834 条）。
- 阶段 4 广告在线链路不得调用 embedding；索引侧建议为 embedding 做批量/预热或独立限流，避免 20 条突发时 P99 到 245ms。
