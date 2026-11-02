#!/usr/bin/env python3
"""阶段 2 搜索模块测试：ES / embedding / 索引同步 / 混合检索 / 热词 / 联动 / GEO。

前置：
  - 应用 127.0.0.1:8081
  - ES 127.0.0.1:9201
  - embedding 127.0.0.1:8090
  - Redis 容器 zs-redis
  - MySQL root/123456
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

APP = "http://127.0.0.1:8081"
ES = "http://127.0.0.1:9201"
EMB = "http://127.0.0.1:8090"
ADMIN_TOKEN = os.environ.get("HMDP_ADMIN_TOKEN", "zs-admin-local")
MYSQL = r"D:\mysql-8.0\mysql-8.4.8-winx64\bin\mysql.exe"

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
passed = 0
failed = 0
failures = []


def call(url, method="GET", body=None, token=None, admin=False, timeout=60):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["authorization"] = token
    if admin:
        headers["X-Admin-Token"] = ADMIN_TOKEN
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            if not raw:
                return resp.status, None
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return e.code, raw


def check(name, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print("[PASS] " + name)
    else:
        failed += 1
        failures.append(name)
        print("[FAIL] " + name + (" | " + str(detail)[:300] if detail else ""))


def mysql_scalar(sql):
    out = subprocess.check_output(
        [MYSQL, "-h127.0.0.1", "-uroot", "-p123456", "-N", "-e", "USE hmdp; " + sql],
        stderr=subprocess.DEVNULL)
    return out.decode("utf-8", errors="replace").strip()


def redis(*args):
    out = subprocess.check_output(
        ["docker", "exec", "zs-redis", "redis-cli"] + list(args),
        stderr=subprocess.DEVNULL)
    return out.decode("utf-8", errors="replace").strip()


def login(phone):
    call(APP + "/user/code?phone=" + phone, method="POST")
    code = redis("get", "login:code:" + phone)
    status, resp = call(APP + "/user/login", method="POST",
                        body={"phone": phone, "code": code})
    if status != 200 or not resp or not resp.get("success"):
        raise RuntimeError("login failed " + phone + " " + str(resp))
    return resp["data"]


def encode(texts, typ="document"):
    status, resp = call(EMB + "/encode", method="POST", body={"texts": texts, "type": typ})
    if status != 200 or not resp:
        raise RuntimeError("encode failed " + str(resp))
    return resp["vectors"], resp["dim"]


def search(body, timeout=30):
    return call(APP + "/search/activity", method="POST", body=body, timeout=timeout)


def main():
    ts = str(int(time.time()))
    print("===== Stage 2 search smoke test =====")

    # ---------- 1. environment ----------
    status, es = call(ES + "/_cluster/health", timeout=5)
    check("ES health", status == 200 and es.get("status") in ("green", "yellow"), es)
    status, health = call(EMB + "/health", timeout=10)
    check("embedding health", status == 200 and health.get("status") == "UP"
          and health.get("dim") == 512, health)
    status, h = call(APP + "/actuator/health", timeout=10)
    check("app health UP", status == 200 and h.get("status") == "UP", h)

    # ---------- 2. embedding semantics ----------
    vectors, dim = encode(["火锅双人餐", "KTV欢唱四小时", "日料Omakase"])
    check("embedding batch 3 x 512", len(vectors) == 3 and dim == 512
          and all(len(v) == 512 for v in vectors), (len(vectors), dim))
    qvec, _ = encode(["火锅"], "query")
    dot_food = sum(a * b for a, b in zip(qvec[0], vectors[0]))
    dot_ktv = sum(a * b for a, b in zip(qvec[0], vectors[1]))
    check("semantic: 火锅 closer to 火锅 than KTV", dot_food > dot_ktv + 0.05,
          "food=%.4f ktv=%.4f" % (dot_food, dot_ktv))
    batch, _ = encode(["批量编码测试%d" % i for i in range(33)])
    check("embedding batch 33", len(batch) == 33)

    # ---------- 3. index rebuild ----------
    status, resp = call(APP + "/admin/search/reindex", method="POST", admin=True, timeout=300)
    reindex = resp.get("data") if resp else None
    check("reindex", status == 200 and resp.get("success") and reindex
          and reindex.get("docCount", 0) >= 800, resp)
    online_count = int(mysql_scalar("SELECT COUNT(*) FROM tb_activity WHERE status=2;"))
    status, count_resp = call(ES + "/activity_index/_count", timeout=10)
    es_count = count_resp.get("count") if isinstance(count_resp, dict) else -1
    check("index doc count == DB online count", es_count == online_count,
          "es=%s db=%s" % (es_count, online_count))
    status, stats = call(APP + "/admin/search/stats", admin=True)
    check("admin search stats", status == 200 and stats.get("success")
          and stats["data"]["docCount"] == online_count, stats)

    # ---------- 4. direct ES query checks ----------
    status, mp = call(ES + "/activity_index/_search", method="POST", body={
        "size": 3,
        "query": {"bool": {
            "filter": [{"term": {"status": 2}}],
            "should": [{"match_phrase": {"title": "火锅"}}],
            "minimum_should_match": 1
        }}
    })
    total_mp = mp["hits"]["total"]["value"] if status == 200 and isinstance(mp, dict) else 0
    check("ES match_phrase 火锅", status == 200 and total_mp > 0, total_mp)

    knn_q = qvec[0]
    status, knn = call(ES + "/activity_index/_search", method="POST", body={
        "size": 3,
        "_source": ["id", "title"],
        "knn": {"field": "embedding", "query_vector": knn_q, "k": 3, "num_candidates": 20}
    })
    knn_hits = knn["hits"]["hits"] if status == 200 and isinstance(knn, dict) else []
    check("ES knn query", len(knn_hits) == 3, len(knn_hits))

    # ---------- 5. search API cases ----------
    cases = [
        ("火锅", {"query": "火锅", "size": 10}),
        ("KTV", {"query": "KTV", "size": 10}),
        ("日料", {"query": "日料", "size": 10}),
        ("亲子乐园", {"query": "亲子乐园", "size": 10}),
        ("美甲", {"query": "美甲", "size": 10}),
        ("精酿啤酒", {"query": "精酿啤酒", "size": 10}),
        ("SPA", {"query": "SPA", "size": 10}),
        ("唱歌(近义)", {"query": "唱歌", "size": 10}),
        ("长尾", {"query": "静安寺附近适合约会的日料", "size": 10}),
        ("西湖", {"query": "西湖", "size": 10}),
    ]
    for name, body in cases:
        status, resp = search(body)
        data = resp.get("data") if resp else None
        ok = status == 200 and resp.get("success") and data and data.get("list") is not None
        check("search " + name, ok, resp)

    status, resp = search({"query": "火锅", "size": 5, "city": "上海"})
    items = resp["data"]["list"] if status == 200 and resp.get("success") else []
    check("search filter city=上海", len(items) > 0 and all(i.get("city") == "上海" for i in items),
          [(i.get("city"), i.get("title")) for i in items])

    status, resp = search({"query": "欢唱", "size": 5, "categoryId": 2})
    items = resp["data"]["list"] if status == 200 and resp.get("success") else []
    check("search filter categoryId=2", len(items) > 0
          and all(i.get("categoryId") == 2 for i in items), [i.get("categoryId") for i in items])

    status, resp = search({"query": "套餐", "size": 5, "maxPayValue": 5000})
    items = resp["data"]["list"] if status == 200 and resp.get("success") else []
    check("search price range", len(items) > 0
          and all((i.get("payValue") or 0) <= 5000 for i in items),
          [i.get("payValue") for i in items])

    status, resp = search({"query": "火锅", "size": 5, "city": "不存在城市"})
    data = resp.get("data") if resp else {}
    check("empty result fallback", status == 200 and resp.get("success")
          and data.get("fallback") is True and len(data.get("list") or []) > 0, data)

    status, resp = search({"query": "", "size": 5})
    data = resp.get("data") if resp else {}
    check("blank query fallback", status == 200 and resp.get("success")
          and data.get("fallback") is True and len(data.get("list") or []) > 0, data)

    # highlight
    status, resp = search({"query": "火锅", "size": 5})
    items = resp["data"]["list"] if status == 200 and resp.get("success") else []
    check("highlight <em>", any("<em>" in (i.get("highlightTitle") or "") for i in items),
          [i.get("highlightTitle") for i in items[:3]])

    # semantic KTV
    status, resp = search({"query": "唱歌", "size": 10})
    items = resp["data"]["list"] if status == 200 and resp.get("success") else []
    ktv_hits = [i for i in items if i.get("categoryId") == 2
                or "KTV" in (i.get("title") or "") or "欢唱" in (i.get("title") or "")]
    check("semantic 唱歌 -> KTV/欢唱", len(ktv_hits) > 0,
          [(i.get("categoryId"), i.get("title")) for i in items[:5]])

    # ---------- 6. hot words ----------
    for _ in range(3):
        search({"query": "火锅", "size": 3})
    status, hot = call(APP + "/search/hot?limit=20")
    words = {item["word"]: item["score"] for item in (hot.get("data") or [])} if hot else {}
    check("hot word recorded", status == 200 and "火锅" in words and words["火锅"] > 0, words)
    before = words.get("火锅", 0)
    status, decayed = call(APP + "/admin/search/hot/decay", method="POST", admin=True)
    words2 = {item["word"]: item["score"] for item in (decayed.get("data") or [])} if decayed else {}
    after = words2.get("火锅", 0)
    check("hot word decay", status == 200 and after < before, "before=%s after=%s" % (before, after))

    # ---------- 7. incremental index ----------
    unique = "P2增量测试" + ts
    activity = {
        "shopId": 1, "title": unique, "subTitle": "增量索引", "description": unique + " 描述",
        "images": "/imgs/blogs/blog1.jpg", "activityType": 1, "categoryId": 1,
        "tags": "增量,搜索", "city": "杭州", "area": "西湖", "x": 120.15, "y": 30.27,
        "originalPrice": 10000, "payValue": 5000, "actualValue": 5000, "stock": 10,
        "beginTime": "2026-10-01T00:00:00", "endTime": "2026-12-31T23:59:59", "status": 2
    }
    status, resp = call(APP + "/admin/activity", method="POST", admin=True, body=activity)
    activity_id = resp.get("data") if resp else None
    check("incremental: create activity", status == 200 and resp.get("success") and activity_id, resp)
    found = False
    for _ in range(20):
        _, r = search({"query": unique, "size": 5})
        ids = [i["id"] for i in (r.get("data", {}).get("list") or [])] if r else []
        if activity_id in ids:
            found = True
            break
        time.sleep(0.5)
    check("incremental upsert visible <10s", found, activity_id)

    new_title = unique + "更新"
    status, resp = call(APP + "/admin/activity", method="PUT", admin=True,
                        body={"id": activity_id, "title": new_title, "status": 2, "activityType": 1})
    check("incremental: update activity", status == 200 and resp.get("success"), resp)
    found = False
    for _ in range(20):
        _, r = search({"query": new_title, "size": 5})
        ids = [i["id"] for i in (r.get("data", {}).get("list") or [])] if r else []
        if activity_id in ids:
            found = True
            break
        time.sleep(0.5)
    check("incremental update visible <10s", found, activity_id)

    status, resp = call(APP + "/admin/activity/%s" % activity_id, method="DELETE", admin=True)
    check("incremental: offline activity", status == 200 and resp.get("success"), resp)
    gone = False
    for _ in range(20):
        _, r = search({"query": new_title, "size": 20})
        ids = [i["id"] for i in (r.get("data", {}).get("list") or [])] if r else []
        if activity_id not in ids:
            gone = True
            break
        time.sleep(0.5)
    check("incremental delete visible <10s", gone, activity_id)

    # ---------- 8. activity-voucher linkage ----------
    act = dict(activity)
    act["title"] = "P2券联动" + ts
    act["description"] = act["title"]
    status, resp = call(APP + "/admin/activity", method="POST", admin=True, body=act)
    act_id = resp.get("data") if resp else None
    check("linkage: create activity", status == 200 and act_id, resp)
    voucher = {
        "shopId": 1, "activityId": act_id, "title": "P2联动券" + ts, "subTitle": "联动",
        "rules": "联动测试", "payValue": 1000, "actualValue": 2000, "stock": 5,
        "beginTime": "2026-10-01T00:00:00", "endTime": "2026-12-31T23:59:59"
    }
    status, resp = call(APP + "/voucher/seckill", method="POST", body=voucher)
    voucher_id = resp.get("data") if resp else None
    check("linkage: create voucher", status == 200 and voucher_id, resp)
    token1 = login("13800000001")
    token2 = login("13800000002")
    status, resp = call(APP + "/voucher-order/seckill/%s" % voucher_id, method="POST", token=token1)
    check("linkage: seckill before offline", status == 200 and resp.get("success"), resp)
    status, resp = call(APP + "/admin/activity/%s" % act_id, method="DELETE", admin=True)
    check("linkage: offline activity", status == 200 and resp.get("success"), resp)
    status, resp = call(APP + "/voucher-order/seckill/%s" % voucher_id, method="POST", token=token2)
    check("linkage: seckill rejected after offline",
          status == 200 and resp and not resp.get("success")
          and "活动已下线" in (resp.get("errorMsg") or ""), resp)
    status, resp = call(APP + "/voucher/list/1")
    ids = [v["id"] for v in (resp.get("data") or [])] if resp else []
    check("linkage: offline activity voucher hidden", voucher_id not in ids, ids)

    # ---------- 9. GEO per city ----------
    status, resp = call(APP + "/admin/geo/rebuild", method="POST", admin=True, timeout=60)
    geo = resp.get("data") if resp else {}
    check("geo rebuild", status == 200 and resp.get("success")
          and geo.get("cityCount") == 5 and geo.get("activityCount", 0) >= 800, geo)
    for city in ["杭州", "上海", "北京", "深圳", "成都"]:
        check("geo key " + city, int(redis("zcard", "act:geo:" + city) or 0) > 0)
    check("legacy act:geo removed", redis("exists", "act:geo") == "0")

    print("\n===== RESULT: %d passed, %d failed =====" % (passed, failed))
    if failures:
        print("FAILED: " + ", ".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
