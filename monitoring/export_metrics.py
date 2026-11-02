#!/usr/bin/env python3
"""从 Actuator /actuator/prometheus 拉取关键指标，导出 CSV/HTML 趋势报表。"""
import csv
import datetime
import html
import re
import urllib.request

ENDPOINT = "http://127.0.0.1:8081/actuator/prometheus"
KEYS = [
    "http_server_requests_seconds",
    "recommend_feed",
    "recommend_degraded",
    "recommend_cache",
    "ad_auction",
    "ad_budget_reject",
    "ad_frequency_reject",
    "ad_fill",
    "seckill_requests_total",
    "executor_pool",
]


def scrape():
    text = urllib.request.urlopen(ENDPOINT, timeout=10).read().decode("utf-8")
    rows = []
    ts = datetime.datetime.now().isoformat(timespec="seconds")
    for line in text.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        name = line.split("{", 1)[0].split(" ", 1)[0]
        if not any(k in name for k in KEYS):
            continue
        m = re.match(r"(.+?)\s+([0-9.eE+-]+|NaN|Inf|\+Inf)$", line)
        if not m:
            continue
        rows.append({"time": ts, "metric": m.group(1), "value": m.group(2)})
    return rows


def main():
    rows = scrape()
    with open("monitoring/metrics.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["time", "metric", "value"])
        writer.writeheader()
        writer.writerows(rows)
    body = "\n".join(
        "<tr><td>{}</td><td>{}</td><td>{}</td></tr>".format(
            html.escape(r["time"]), html.escape(r["metric"]), html.escape(r["value"]))
        for r in rows
    )
    with open("monitoring/metrics.html", "w", encoding="utf-8") as f:
        f.write("<html><body><h2>HMD</h2><table border=1>"
                "<tr><th>time</th><th>metric</th><th>value</th></tr>"
                + body + "</table></body></html>")
    print("exported", len(rows), "metrics")


if __name__ == "__main__":
    main()
