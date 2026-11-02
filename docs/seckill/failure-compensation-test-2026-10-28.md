# 秒杀失败补偿链路实测（2026-10-28）

> 本报告为真实执行结果，用于验证“Redis 扣减成功但 DB 落单/扣库存失败”时的重试、死信、失败表和库存回滚。

## 1. 模拟方式

| 项 | 值 |
|---|---|
| 测试券 | voucher_id=17，Redis 初始库存 1 |
| DB 库存 | 手工置为 0（模拟 DB 侧库存不足/不一致） |
| 请求用户 | user_id=1（对 voucher 17 无历史订单） |
| 请求结果 | Lua 资格通过，返回 requestId=`653747237096027568`，segment=1 |
| 消息 | Redis Stream 正常写入 |

## 2. 时间线

| 时间点 | 事件 | 结果 |
|---|---|---|
| T0 | 首次消费 | 幂等插入/订单插入后，DB `stock = stock - 1 where stock > 0` 返回 0 → 抛异常 → 事务回滚 → 不 ACK |
| T+30s | XPENDING/XCLAIM 重试 1 | 同样失败，保持 pending |
| T+60s | 重试 2 | 同样失败，保持 pending |
| T+90s | 重试 3 | 同样失败，保持 pending |
| T+120s | 达到最大重试（deliveryCount=4） | 进入死信流，调用 `SeckillFailureService` |
| 死信处理 | 查询订单不存在 → 回滚 Redis 库存（removeUser=true） | 库存恢复，用户标记移除，失败表 status=1 |

> 说明：`max-retry=3` 表示首次投递后最多 3 次重试；第 5 次投递（deliveryCount=4）判定为超过重试上限并进入死信。

## 3. 最终状态

| 校验项 | 结果 |
|---|---|
| `tb_voucher_order`（voucher 17） | **0**（事务已回滚） |
| `tb_seckill_idempotent`（voucher 17） | **0**（事务已回滚） |
| `tb_seckill_failure` | **1 行，status=1（已自动补偿）** |
| 失败原因 | `超过最大重试次数: 4` |
| Redis 总库存 | **1**（回滚成功） |
| Redis 段库存和 | **1** |
| Redis order set | **0**（用户标记已移除） |
| Redis soldout 标记 | 0 |
| Redis rollback 标记 | 1（requestId 防重生效） |
| Stream pending | **0** |
| 死信流 | **1** |
| `seckill_consume_total{result="RETRY"}` | 4 |
| `seckill_dead_total` | 1 |
| `seckill_stock_rollback_total{result="success"}` | 1 |

## 4. 结论

- 消费失败会保留 pending，按 `pending-min-idle-seconds` 由 `XCLAIM` 重试。
- 超过 `max-retry` 后进入死信流，并写入 `tb_seckill_failure`，可人工查询/处理。
- 订单事务失败会整体回滚，不会留下半成品订单或幂等记录。
- 死信处理会先确认 DB 订单不存在，再回滚 Redis 库存；requestId 防重避免重复回滚，段容量上限避免超卖。
- 本测试中 DB 库存被人为置 0，因此对账会显示 DB/Redis 差异，这是模拟故障的预期结果；测试券与失败记录已在验证后清理。
