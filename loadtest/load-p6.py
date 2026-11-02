#!/usr/bin/env python3
"""阶段 6 压测脚本（真实执行；默认 10 分钟，可 --seconds 缩短做冒烟）。

用法：
  python loadtest/load-p6.py --mode search --qps 200 --seconds 600
  python loadtest/load-p6.py --mode recommend --qps 800 --seconds 600
  python loadtest/load-p6.py --mode ad --qps 500 --seconds 600
"""
import argparse
import json
import random
import statistics
import threading
import time
import urllib.request

SEARCH_QUERIES = [
    {"query": "火锅", "city": "杭州", "size": 10},
    {"query": "KTV", "city": "杭州", "size": 10},
    {"query": "静安寺附近适合约会的日料", "city": "上海", "size": 10},
    {"query": "不存在的长尾词zzz", "city": "杭州", "size": 10},
    {"query": "火锅", "city": "杭州", "categoryId": 1, "size": 10},
]


def request(args, seq):
    headers = {"Content-Type": "application/json"}
    if args.mode == "search":
        body = json.dumps(random.choice(SEARCH_QUERIES)).encode()
        req = urllib.request.Request(args.url + "/search/activity", data=body,
                                     method="POST", headers=headers)
    elif args.mode == "recommend":
        device = "load-%d" % (seq % 200)
        req = urllib.request.Request(
            args.url + "/recommend/feed?city=%E6%9D%AD%E5%B7%9E&size=10&ad=true",
            headers={"X-Device-Id": device, "X-Device-Type": "IOS",
                     "X-Forwarded-For": "10.200.0.%d" % (seq % 250)})
    else:
        body = json.dumps({"city": "杭州", "slotCount": 2}).encode()
        headers.update({"X-Device-Id": "adload-%d" % (seq % 200),
                        "X-Device-Type": "IOS",
                        "X-Forwarded-For": "10.201.0.%d" % (seq % 250)})
        req = urllib.request.Request(args.url + "/ad/auction", data=body,
                                     method="POST", headers=headers)
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=args.timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        latency = (time.time() - start) * 1000
        data = payload.get("data") if isinstance(payload, dict) else None
        return latency, None, data or {}
    except Exception as e:
        return (time.time() - start) * 1000, str(e), {}


def percentile(values, p):
    if not values:
        return 0
    values = sorted(values)
    return values[min(len(values) - 1, int(round((len(values) - 1) * p)))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8081")
    parser.add_argument("--mode", choices=["search", "recommend", "ad"], required=True)
    parser.add_argument("--qps", type=int, default=100)
    parser.add_argument("--seconds", type=int, default=600)
    parser.add_argument("--threads", type=int, default=100)
    parser.add_argument("--timeout", type=float, default=10)
    args = parser.parse_args()

    lock = threading.Lock()
    next_at = [time.time()]
    latencies, errors = [], []
    degraded = 0
    vector_degraded = 0
    query_cache_hit = 0
    cache_levels = {}
    total = 0
    stop = time.time() + args.seconds

    def worker():
        nonlocal degraded, vector_degraded, query_cache_hit, total
        while time.time() < stop:
            with lock:
                now = time.time()
                wait = next_at[0] - now
                if wait > 0:
                    time.sleep(wait)
                next_at[0] = max(next_at[0], now) + 1.0 / max(1, args.qps)
                seq = total
                total += 1
            latency, error, data = request(args, seq)
            latencies.append(latency)
            if error:
                errors.append(error)
            if isinstance(data, dict):
                if data.get("degraded"):
                    degraded += 1
                if data.get("vectorDegraded"):
                    vector_degraded += 1
                if data.get("queryVectorCacheHit"):
                    query_cache_hit += 1
                level = data.get("cacheLevel")
                if level:
                    cache_levels[level] = cache_levels.get(level, 0) + 1

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(args.threads)]
    start = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - start
    summary = {
        "mode": args.mode,
        "target_qps": args.qps,
        "seconds": round(elapsed, 1),
        "requests": len(latencies),
        "achieved_qps": round(len(latencies) / max(elapsed, 0.001), 1),
        "error_rate": round(len(errors) / max(1, len(latencies)), 4),
        "p50_ms": round(statistics.median(latencies), 1) if latencies else 0,
        "p99_ms": round(percentile(latencies, 0.99), 1),
        "degraded_rate": round(degraded / max(1, len(latencies)), 4),
        "vector_degraded_rate": round(vector_degraded / max(1, len(latencies)), 4),
        "query_vector_cache_hit_rate": round(query_cache_hit / max(1, len(latencies)), 4),
        "cache_levels": cache_levels,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
