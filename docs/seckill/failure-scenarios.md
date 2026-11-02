# 秒杀失败场景与处理表

| # | 场景 | 触发点 | 表现 | 检测 | 处理/补偿 |
|---|---|---|---|---|---|
| 1 | Lua 资格失败 | `seckill_segment.lua` 返回 1/2/3/4/5/-1 | 接口快速返回明确原因，不产生消息 | `seckill_requests_total{code}` | 用户可重试（售罄除外）；时间窗/未初始化由运维检查 |
| 2 | Lua 扣减成功但 XADD 失败 | Stream key 类型错误/OOM | 脚本报错；Redis 不回滚已执行命令 | 应用错误日志、对账 | 脚本开头 `TYPE` 预检；极端 OOM 由对账 + 人工 reset |
| 3 | 消息丢失 | XADD 未成功 | Redis 已扣减但无消息 | `tb_seckill_idempotent` 无记录、对账差异 | 对账告警；按 requestId 人工补发或回滚库存 |
| 4 | 重复消费 | 消费者重启、XCLAIM、重复投递 | 同一 requestId 多次处理 | `seckill_consume_total{result="DUPLICATE"}` | 幂等表 request_id 唯一 + 订单主键，直接成功 |
| 5 | DB 唯一冲突 | 同一用户不同 requestId 绕过 Redis | `uk_user_voucher` 冲突 | 日志“同一用户重复下单” | 返回 DUPLICATE，回滚该请求库存（`removeUser=false`），ACK |
| 6 | DB 库存扣减失败 | DB 库存与 Redis 不一致/已售罄 | 条件更新返回 0 | `tb_seckill_failure`、日志 | 抛异常回滚事务，消息留 pending 重试；超限进死信 |
| 7 | 消费超时/实例宕机 | 消费者处理中崩溃 | 消息停留在 pending | `XPENDING`/`XINFO GROUPS` | 空闲超过 `pending-min-idle-seconds` 后 XCLAIM 重试 |
| 8 | 消费持续失败 | 业务/DB 异常 | 多次重试仍失败 | `deliveryCount > maxRetry` | 写死信流 + `tb_seckill_failure`，人工处理 |
| 9 | Redis 不可用 | 限流/资格/Stream 访问失败 | 秒杀不可用 | 应用错误日志、健康检查 | 限流 fail-open；资格返回“秒杀暂未开放”；消费重连；对账 |
| 10 | RabbitMQ 不可用（rabbit 模式） | confirm 失败 | 消息未确认 | `seckill:order:pending` 长度 | Redis List 待补偿任务重发；消费端幂等 |
| 11 | 线程池满 | 消费高峰 | 提交被拒绝 | `seckill_pool_rejected_total` | 不 ACK，留 pending；读取线程背压休眠；动态扩容 |
| 12 | 死信积压 | 长期失败 | dead stream/失败表增长 | `XLEN seckill:orders:dead`、`tb_seckill_failure` | 人工修复后重放/忽略；定期归档 |
| 13 | 重复回滚 | 补偿任务重复执行 | 库存被多加 | 对账 | `seckill:rollback:{id}` requestId 防重；段容量上限防超卖 |
| 14 | Redis/DB 漂移 | 异步延迟、故障 | 库存/订单数不一致 | `SeckillReconcileService` 告警 | 只告警；人工 reset/rollback，禁止自动改库存 |
| 15 | 元数据缺失 | 未初始化/Redis 被清 | 返回 -1 | `seckill_requests_total{code="NOT_INITIALIZED"}` | 启动预热；新增券初始化；reset 脚本 |

## 人工补偿 SQL

```sql
-- 查看待处理失败单
SELECT * FROM tb_seckill_failure WHERE status = 0 ORDER BY create_time;

-- 查看处理中超过 5 分钟的幂等记录
SELECT * FROM tb_seckill_idempotent
WHERE status = 0 AND update_time < NOW() - INTERVAL 5 MINUTE;

-- 标记已人工处理
UPDATE tb_seckill_failure SET status = 1 WHERE request_id = '...';
```
