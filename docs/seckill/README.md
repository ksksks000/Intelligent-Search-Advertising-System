# 秒杀模块设计说明

## 1. 目标架构

```
POST /voucher-order/seckill/{id}
  → RateLimitInterceptor（Redis Lua 令牌桶：user / ip / api）
  → VoucherOrderServiceImpl
      → SeckillStockService.trySeckill
          → seckill_segment.lua（同一原子脚本内完成）
              时间窗校验 → 售罄标记 → 一人一单 → 游标轮转选段
              → DECR 段库存 + DECR 总库存 + SADD 用户
              → XADD seckill:orders:stream（Stream 模式）
  → 立即返回 requestId（订单号，API 语义不变）
  ↓
StreamSeckillOrderConsumer（消费组 seckill:orders:group）
  → XREADGROUP → SeckillConsumerExecutor（独立有界动态线程池）
  → SeckillOrderConsumeService（同一事务）
       幂等表 tb_seckill_idempotent（request_id 唯一）
       订单 tb_voucher_order（uk_user_voucher 唯一）
       DB 库存条件扣减 stock = stock - 1 where stock > 0
  → 成功 XACK
  → 失败不 ACK，XPENDING/XCLAIM 超时重试
  → 超过 maxRetry → 死信流 seckill:orders:dead + tb_seckill_failure
```

## 2. 关键文件

| 模块 | 文件 |
|---|---|
| 资格判定/分段 | `resources/lua/seckill_segment.lua`、`service/SeckillStockService.java` |
| 初始化/重置/回滚 | `resources/lua/seckill_stock_init.lua`、`seckill_stock_reset.lua`、`seckill_stock_rollback.lua` |
| 入口限流 | `resources/lua/rate_limit.lua`、`service/RateLimiter.java`、`interceptor/RateLimitInterceptor.java` |
| 消息 | `mq/SeckillOrderMessage.java`、`mq/SeckillOrderPublisher.java`、`mq/StreamOrderPublisher.java`、`mq/RabbitOrderPublisher.java` |
| 消费 | `mq/StreamSeckillOrderConsumer.java`、`service/impl/SeckillOrderConsumeService.java` |
| 线程池 | `config/SeckillConsumerProperties.java`、`mq/SeckillConsumerExecutor.java` |
| 补偿/对账 | `service/impl/SeckillFailureService.java`、`service/impl/SeckillReconcileService.java` |
| 可观测 | `metrics/SeckillMetrics.java`、`filter/TraceIdFilter.java` |
| SQL | `resources/db/seckill_p3.sql`、`seckill_p6.sql` |

## 3. 配置

```yaml
hmdp:
  seckill:
    segment-count: 16
    init-on-startup: true
    meta-ttl-buffer-seconds: 86400
    mq: stream                 # stream | rabbit
    stream:
      stream-key: seckill:orders:stream
      group: seckill:orders:group
      dead-key: seckill:orders:dead
      concurrency: 4
      batch-size: 10
      block-millis: 2000
      pending-min-idle-seconds: 30
      pending-scan-interval-ms: 30000
      max-retry: 3
      max-len: 1000000
    consumer:
      core-pool-size: 8
      max-pool-size: 32
      queue-capacity: 2000
      keep-alive-seconds: 60
      dynamic-enabled: true
      adjust-interval-ms: 5000
      queue-high-watermark: 0.8
      queue-low-watermark: 0.2
      min-core-pool-size: 4
      max-pool-limit: 64
      backpressure-sleep-millis: 100
    reconcile:
      enabled: true
      interval-ms: 60000
  rate-limit:
    enabled: true
    ttl-seconds: 60
    user: { capacity: 10, refill-rate: 5 }
    ip:   { capacity: 50, refill-rate: 20 }
    api:  { capacity: 100, refill-rate: 50 }
```

## 4. 启动步骤

1. 执行 SQL（按顺序）：
   - `src/main/resources/db/seckill_p3.sql`
   - `src/main/resources/db/seckill_p6.sql`
2. 启动 MySQL、Redis（≥5，Stream 需要）。
3. Stream 模式无需 RabbitMQ；Rabbit 回滚模式才需要。
4. 启动应用，启动时会按 DB 库存初始化分段（`init-on-startup=true`）。
5. 新增秒杀券时，事务提交后自动初始化 Redis 分段。

## 5. 运行测试

纯单测（不需要中间件）：

```bash
mvn -Dtest=SeckillStockServiceTest,SeckillCodeTest,SeckillOrderMessageTest,SeckillOrderConsumeServiceTest,SeckillConsumerExecutorTest,SeckillFailureServiceTest,SeckillMetricsTest,TraceIdFilterTest,RateLimitInterceptorTest test
```

Redis 集成测试（`*IT` 默认不跑）：

```bash
mvn -Dtest=SeckillStockServiceIT,StreamSeckillOrderConsumerIT,RateLimiterIT test
# 如 Redis 非本机 6379，可用环境变量覆盖：
SPRING_REDIS_HOST=127.0.0.1 SPRING_REDIS_PORT=6379 mvn -Dtest=... test
```

## 6. 指标

Prometheus：`GET /actuator/prometheus`

| 指标 | 含义 |
|---|---|
| `seckill_requests_total{code}` | 秒杀请求数，按 Lua 结果码 |
| `seckill_ratelimit_rejected_total{dimension}` | 限流拒绝数（user/ip/api） |
| `seckill_consume_total{result}` | 消费结果（SUCCESS/DUPLICATE/RETRY/DEAD） |
| `seckill_dead_total` | 进入死信流的消息数 |
| `seckill_stock_rollback_total{result}` | 库存回滚成功/失败 |
| `seckill_pool_active` / `seckill_pool_queue` / `seckill_pool_size` | 消费线程池活跃/队列/线程数 |
| `seckill_pool_rejected_total` | 线程池拒绝数 |

日志格式已加入 `%X{traceId}`，消费端按 `req-{requestId}` 关联。

## 7. 已知边界

- 单节点 Redis；若迁移集群，需给所有秒杀 key 加 `{voucherId}` hash tag。
- 死信流未做 `MAXLEN` 裁剪，需定期归档；`tb_seckill_failure` 提供人工补偿查询。
- 对账只告警不自动修正，避免消费异步延迟时误改数据。
- 限流在 Redis 异常时 fail-open，由秒杀 Lua 统一失败兜底。
- Lua 脚本内 `XADD` 与扣减同一脚本原子执行，但 Redis 脚本出错时不回滚已写命令；已通过 `TYPE` 预检查覆盖类型错误，OOM 等极端情况依赖对账。

## 8. 阶段状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 库存分段 + 时间窗前移 + 初始化/重置/回滚 | 完成 |
| P2 | requestId + 失败枚举 + 消息体扩展 | 完成 |
| P3 | Redis Stream outbox + 消费组/ACK/pending/DLQ + 幂等表 + DB 唯一索引 | 完成 |
| P4 | 独立有界动态线程池 + 背压 + 池指标 | 完成 |
| P5 | Redis Lua 令牌桶限流 | 完成 |
| P6 | 死信补偿 + 失败表 + 对账 | 完成 |
| P7 | Micrometer/Prometheus + traceId | 完成 |
| P8 | 压测脚本 + 文档 + 回滚方案 | 完成 |
