# 秒杀改造回滚方案

## 1. 开关一览

| 开关 | 回滚值 | 作用 |
|---|---|---|
| `hmdp.seckill.mq` | `rabbit` | 关闭 Stream outbox，恢复 RabbitMQ 发送/消费 |
| `hmdp.rate-limit.enabled` | `false` | 关闭入口限流 |
| `hmdp.seckill.consumer.dynamic-enabled` | `false` | 关闭线程池动态调整 |
| `hmdp.seckill.reconcile.enabled` | `false` | 关闭定时对账 |
| `hmdp.seckill.init-on-startup` | `false` | 启动时不再初始化分段（谨慎） |

## 2. 回滚步骤（推荐顺序）

1. **冻结入口**
   - 网关/负载均衡摘除秒杀接口，或临时把 `hmdp.rate-limit.user.capacity` 调到极小值。
2. **排空 Stream**
   ```bash
   redis-cli XLEN seckill:orders:stream
   redis-cli XPENDING seckill:orders:group - + 100
   ```
   - 等待 `XLEN` 与 pending 归零；
   - 若无法等待，先停止消费者，记录未处理 ID，回滚后人工重放。
3. **切换消息模式**
   - 将 `hmdp.seckill.mq` 改为 `rabbit`；
   - 确认 RabbitMQ 与 `QA/QD` 队列已创建；
   - 重启应用。`QueueConfig`、`ReliableMqSender`、`SeckillVoucherListener`、`PendingOrderCompensator` 会按条件重新生效。
4. **确认订单/库存一致性**
   ```sql
   SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?;
   SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?;
   ```
   ```bash
   redis-cli GET seckill:stock:{VOUCHER_ID}:total
   redis-cli SCARD seckill:order:{VOUCHER_ID}
   ```
   - 如 Redis 与 DB 漂移，执行 `SeckillStockService.reset(voucher)`（管理入口/临时脚本）按 DB 库存重建；
   - 注意 reset 会保留 order set。
5. **SQL 回滚（仅在完整回退 P3/P6 时）**
   ```sql
   -- 先确认没有依赖幂等表的代码在运行
   DROP TABLE IF EXISTS tb_seckill_failure;
   DROP TABLE IF EXISTS tb_seckill_idempotent;
   ALTER TABLE tb_voucher_order DROP INDEX uk_user_voucher;
   ```
   - 保留 `tb_seckill_failure` / 死信流用于审计，确认后再删。
6. **Redis 清理**
   - 秒杀相关 key 均带 TTL（`meta-ttl-buffer-seconds`），活动结束后自动过期；
   - 如需立即清理：
     ```bash
     redis-cli DEL seckill:meta:{ID} seckill:stock:{ID}:total \
       seckill:soldout:{ID} seckill:seg:cursor:{ID} seckill:rollback:{ID}
     for i in $(seq 1 N); do redis-cli DEL seckill:stock:{ID}:s:$i; done
     ```
   - **不要**随意删除 `seckill:order:{ID}`，否则会丢失一人一单标记。

## 3. 代码回滚

- 本改造未提交前，可直接 `git checkout` 相关文件；
- 已提交后，优先使用开关回滚，其次 `git revert` 本次提交；
- 回滚后重新执行完整单测与 Redis 集成测试。

## 4. 数据修复建议

| 问题 | 修复 |
|---|---|
| Redis 已扣、DB 无订单 | 按 `requestId` 查 `tb_seckill_failure`；确认后回滚库存或补单 |
| DB 有订单、Redis 未扣 | 以 DB 为准，reset Redis 分段 |
| 一人多单 | 按 `uk_user_voucher` 找出重复，人工退款/取消多余订单 |
| 死信积压 | 从 `seckill:orders:dead` 读取，修复后重新 XADD 到主 stream |

## 5. 回滚后验证

```bash
mvn -Dtest=... test          # 单测 + IT
curl -X POST http://127.0.0.1:8081/voucher-order/seckill/1 -H "authorization: <token>"
redis-cli XLEN seckill:orders:stream
redis-cli XPENDING seckill:orders:group - + 10
```
