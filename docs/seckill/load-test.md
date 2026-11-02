# 秒杀压测说明

> 本文只给出脚本、指标口径和验证方法，不包含任何编造的压测结果。

## 1. 前置准备

1. 启动 MySQL、Redis（Stream 模式）。
2. 执行 `db/seckill_p3.sql`、`db/seckill_p6.sql`。
3. 准备一个秒杀券，记录 `VOUCHER_ID`，库存设置为压测目标值（如 1000）。
4. 生成登录 token：
   - 运行 `VoucherOrderControllerTest#login`，会在 `src/main/resources/tokens.txt` 生成 1000 个 token。
   - 将 tokens 文件复制到压测机，或与 wrk/JMeter 同目录。
5. 确认应用启动、Redis 分段已初始化：
   ```bash
   redis-cli HGETALL seckill:meta:{VOUCHER_ID}
   redis-cli GET seckill:stock:{VOUCHER_ID}:total
   ```

## 2. wrk 脚本

文件：`loadtest/seckill.lua`

```bash
cd loadtest
cp /path/to/tokens.txt .
# 修改脚本中的 VOUCHER_ID 或通过环境变量替换
wrk -t8 -c500 -d60s --latency -s seckill.lua http://127.0.0.1:8081
```

说明：
- 每个虚拟用户随机取一个 token，适合**超卖/QPS**验证。
- 一人一单验证建议用 JMeter 的 CSV Data Set（每个线程固定一个 token），或让 token 数量 ≥ 并发数。
- 若 token 数少于并发数，会出现大量“不能重复下单”，这是预期，不是超卖。

## 3. JMeter 脚本

文件：`loadtest/seckill.jmx`

```bash
jmeter -n -t loadtest/seckill.jmx \
  -JHOST=127.0.0.1 -JPORT=8081 -JVOUCHER_ID=1 \
  -JTHREADS=500 -JRAMP=10 -JDURATION=60 \
  -l result.jtl -e -o report
```

- `tokens.txt` 放在 JMeter 工作目录，或在 Test Plan 中修改 `TOKENS_FILE`。
- `Thread Group` 使用调度器，按 `DURATION` 秒持续压测。
- 结果用 `-l result.jtl -e -o report` 生成 HTML 报告。

## 4. 指标口径

| 指标 | 口径 | 采集方式 |
|---|---|---|
| 请求总数 | 压测期间成功/失败请求总数 | wrk/JMeter 汇总 |
| QPS | 总请求数 / 压测时长 | wrk/JMeter |
| 成功率 | HTTP 200 且 `success=true` / 总请求 | 响应断言/JMeter |
| P50/P95/P99 | 请求延迟分位 | wrk `--latency` / JMeter HTML |
| 超卖数 | `DB 订单数 - 初始库存`（应为 0） | SQL |
| 一人多单数 | 同一 `(user_id, voucher_id)` 订单数 > 1 的组数（应为 0） | SQL |
| 消息积压 | `XLEN seckill:orders:stream` | Redis |
| pending 数 | `XPENDING seckill:orders:group` | Redis |
| 消费成功/失败/重试 | 指标 | `/actuator/prometheus` |
| 死信数 | `XLEN seckill:orders:dead` | Redis |
| 线程池活跃/队列/拒绝 | 指标 | `/actuator/prometheus` |
| 限流拒绝数 | 指标 | `/actuator/prometheus` |

## 5. 验证 SQL / Redis

```sql
-- 订单总数应与售出数一致，且不超过库存
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?;

-- 一人多单（应为空）
SELECT user_id, voucher_id, COUNT(*) cnt
FROM tb_voucher_order
WHERE voucher_id = ?
GROUP BY user_id, voucher_id
HAVING cnt > 1;

-- 失败单
SELECT status, COUNT(*) FROM tb_seckill_failure GROUP BY status;

-- 幂等表状态
SELECT status, COUNT(*) FROM tb_seckill_idempotent GROUP BY status;
```

```bash
redis-cli XLEN seckill:orders:stream
redis-cli XPENDING seckill:orders:group - + 10
redis-cli XLEN seckill:orders:dead
redis-cli GET seckill:stock:{VOUCHER_ID}:total
# 段库存求和
for i in $(seq 1 16); do redis-cli GET seckill:stock:{VOUCHER_ID}:s:$i; done | awk '{s+=$1} END{print s}'
```

## 6. 对比方法

同一环境、同一数据量、同一并发下分别跑：

1. 改造前：`hmdp.seckill.mq=rabbit` 或旧版本；
2. 改造后：`hmdp.seckill.mq=stream` + 分段 + 限流。

记录 `load-test-report-template.md` 中的指标，至少重复 3 轮取中位数，避免单轮抖动。
