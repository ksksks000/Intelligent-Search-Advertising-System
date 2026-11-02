# 监控

## Prometheus

```bash
docker run --rm -p 9090:9090 -v "$PWD/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml" prom/prometheus
```

应用 `application.yaml` 已暴露 `health,info,metrics,prometheus`。

## 轻量报表

```bash
python monitoring/export_metrics.py
# 输出 monitoring/metrics.csv / metrics.html
```

报表覆盖：Feed 耗时/降级/缓存、广告竞价耗时/降级/预算拒绝/频控拒绝/填充、秒杀请求。

## Grafana（可选）

将 `prometheus.yml` 作为数据源；推荐面板：
- `histogram_quantile(0.99, rate(recommend_feed_seconds_bucket[5m]))`
- `rate(recommend_degraded_total[5m])`
- `rate(ad_auction_seconds_count[5m])`
- `ad_budget_reject_total`
- `ad_fill_filled_total / ad_fill_requested_total`
