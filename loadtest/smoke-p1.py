#!/usr/bin/env python3
"""阶段 1 冒烟测试：Boot 2.7 回归 + 活动领域接口 + GEO 预热 + 管理端。

运行前提：
  - 应用监听 127.0.0.1:8081
  - MySQL 127.0.0.1:3306 (root/123456, db=hmdp)
  - Redis 127.0.0.1:6379（本机用 WSL redis-server）
  - 管理端 token 使用 application.yaml 默认值 zs-admin-local
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://127.0.0.1:8081"
ADMIN_TOKEN = os.environ.get("HMDP_ADMIN_TOKEN", "zs-admin-local")
MYSQL = os.environ.get("MYSQL_BIN", "mysql")
UPLOAD_DIR = os.environ.get("HMDP_IMAGE_UPLOAD_DIR", "uploads")

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

passed = 0
failed = 0
failures = []


def call(method, path, token=None, body=None, admin=False, timeout=8):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["authorization"] = token
    if admin:
        headers["X-Admin-Token"] = ADMIN_TOKEN
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            if not raw:
                return resp.status, None
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                # /actuator/prometheus 等文本接口
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
        print("[FAIL] " + name + (" | " + str(detail) if detail else ""))


def login(phone):
    _, _ = call("POST", "/user/code?phone=" + phone)
    code = redis("get", "login:code:" + phone)
    status, resp = call("POST", "/user/login", body={"phone": phone, "code": code})
    if status != 200 or not resp or not resp.get("success"):
        raise RuntimeError("login failed for %s: %s %s" % (phone, status, resp))
    return resp["data"]


def mysql_scalar(sql):
    out = subprocess.check_output(
        [MYSQL, "-h127.0.0.1", "-uroot", "-p123456", "-N", "-e", "USE hmdp; " + sql],
        stderr=subprocess.DEVNULL)
    return out.decode("utf-8", errors="replace").strip()


def redis(*args):
    return subprocess.check_output(
        ["docker", "exec", "zs-redis", "redis-cli"] + list(args),
        stderr=subprocess.DEVNULL).decode().strip()


def ensure_follow(token, target_id):
    # Redis 可能因重启丢失 follows:* Set，先取关再关注，确保 DB 与 Redis 同步
    call("PUT", "/follow/%s/false" % target_id, token=token)
    status, resp = call("PUT", "/follow/%s/true" % target_id, token=token)
    return status == 200 and bool(resp and resp.get("success"))


def main():
    ts = str(int(time.time()))
    print("===== Stage 1 smoke test =====")

    # 0. health
    status, resp = call("GET", "/actuator/health")
    check("health UP", status == 200 and resp.get("status") == "UP", resp)

    # 1. login three users
    token1 = login("13800000001")   # user 1
    token2 = login("13800000002")   # user 2
    token3 = login("13800000004")   # user 4
    check("login user1", bool(token1))
    check("login user2", bool(token2))
    check("login user3", bool(token3))

    # 2. legacy shop / category / voucher endpoints
    status, resp = call("GET", "/shop/1")
    check("GET /shop/1", status == 200 and resp.get("success") and resp["data"]["id"] == 1, resp)
    status, resp = call("GET", "/shop-type/list")
    check("GET /shop-type/list", status == 200 and resp.get("success") and len(resp["data"]) == 10,
          len(resp.get("data") or []))
    status, resp = call("GET", "/shop/of/name?name=%E8%8C%B6")
    check("GET /shop/of/name (deprecated but kept)", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/voucher/list/1")
    check("GET /voucher/list/1", status == 200 and resp.get("success"), resp)

    # 2.1 legacy write endpoints
    shop_body = {
        "name": "P1 smoke shop " + ts, "typeId": 1, "images": "/imgs/blogs/blog1.jpg",
        "area": "大关", "address": "smoke address", "x": 120.1501, "y": 30.3162,
        "avgPrice": 50, "sold": 0, "comments": 0, "score": 45, "openHours": "10:00-22:00"
    }
    status, resp = call("POST", "/shop", body=shop_body)
    shop_id = resp.get("data") if resp else None
    check("POST /shop", status == 200 and resp.get("success") and shop_id, resp)
    if shop_id:
        status, resp = call("PUT", "/shop", body={"id": shop_id, "name": "P1 smoke shop updated " + ts})
        check("PUT /shop", status == 200 and resp.get("success"), resp)
    status, resp = call("POST", "/voucher", body={
        "shopId": 1, "title": "P1 normal voucher " + ts, "subTitle": "smoke",
        "rules": "smoke", "payValue": 100, "actualValue": 200, "type": 0, "status": 1
    })
    check("POST /voucher", status == 200 and resp.get("success") and resp.get("data"), resp)

    # 3. activity public endpoints
    status, resp = call("GET", "/activity/900001")
    check("GET /activity/900001", status == 200 and resp.get("success")
          and resp["data"]["id"] == 900001, resp)
    status, resp = call("GET", "/activity/list?size=5")
    check("GET /activity/list", status == 200 and resp.get("success")
          and resp.get("total", 0) >= 30 and len(resp["data"]) == 5, resp.get("total"))
    status, resp = call("GET", "/activity/of/shop/1")
    check("GET /activity/of/shop/1", status == 200 and resp.get("success")
          and len(resp["data"]) > 0, resp)
    status, resp = call("GET", "/category/list")
    check("GET /category/list", status == 200 and resp.get("success")
          and len(resp["data"]) == 10 and resp["data"][0].get("level") == 1, resp)

    # 4. geo
    check("redis act:geo:杭州 non-empty", int(redis("zcard", "act:geo:杭州") or 0) > 0)
    check("redis shop:geo:1 non-empty", int(redis("zcard", "shop:geo:1") or 0) > 0)
    status, resp = call("GET", "/shop/of/type?typeId=1&current=1&x=120.15&y=30.31")
    ok = status == 200 and resp.get("success") and len(resp.get("data") or []) > 0
    ok = ok and resp["data"][0].get("distance") is not None
    check("GET /shop/of/type with geo distance", ok, resp if not ok else "")

    # 5. admin auth + CRUD
    status, resp = call("GET", "/admin/activity/900001")
    check("admin without token -> 401", status == 401, status)
    body = {
        "shopId": 1, "title": "P1 smoke activity " + ts, "subTitle": "smoke",
        "description": "stage1 smoke", "images": "/imgs/blogs/blog1.jpg",
        "activityType": 2, "categoryId": 1, "tags": "smoke",
        "city": "杭州", "area": "大关", "x": 120.1492, "y": 30.3161,
        "originalPrice": 10000, "payValue": 8000, "actualValue": 2000, "stock": 20,
        "beginTime": "2026-10-01T00:00:00", "endTime": "2026-12-31T23:59:59", "status": 2
    }
    status, resp = call("POST", "/admin/activity", admin=True, body=body)
    activity_id = resp.get("data") if resp else None
    check("admin create activity", status == 200 and resp.get("success") and activity_id, resp)
    if activity_id:
        status, resp = call("GET", "/activity/%s" % activity_id)
        check("public detail of new activity", status == 200 and resp.get("success"), resp)
        body2 = {"id": activity_id, "title": "P1 smoke updated " + ts, "status": 2,
                 "activityType": 2}
        status, resp = call("PUT", "/admin/activity", admin=True, body=body2)
        check("admin update activity", status == 200 and resp.get("success"), resp)
        status, resp = call("GET", "/activity/%s" % activity_id)
        check("cache evicted after update", status == 200 and resp.get("success")
              and resp["data"]["title"] == "P1 smoke updated " + ts, resp)
        status, resp = call("DELETE", "/admin/activity/%s" % activity_id, admin=True)
        check("admin offline activity", status == 200 and resp.get("success"), resp)
        status, resp = call("GET", "/activity/%s" % activity_id)
        check("offline activity not public", status == 200 and not resp.get("success"), resp)

    status, resp = call("POST", "/admin/geo/rebuild", admin=True)
    check("admin geo rebuild", status == 200 and resp.get("success")
          and resp["data"]["activityCount"] > 0, resp)

    # 6. seckill end-to-end (create voucher -> lua -> stream consumer -> DB)
    voucher = {
        "shopId": 1, "title": "P1 smoke seckill " + ts, "subTitle": "smoke",
        "rules": "smoke", "payValue": 1000, "actualValue": 2000, "stock": 5,
        "beginTime": "2026-10-01T00:00:00", "endTime": "2026-12-31T23:59:59"
    }
    status, resp = call("POST", "/voucher/seckill", body=voucher)
    voucher_id = resp.get("data") if resp else None
    check("create seckill voucher", status == 200 and resp.get("success") and voucher_id, resp)
    if voucher_id:
        status, resp = call("POST", "/voucher-order/seckill/%s" % voucher_id, token=token3)
        request_id = resp.get("data") if resp else None
        check("seckill accepted", status == 200 and resp.get("success") and request_id, resp)
        order_count = "0"
        for _ in range(20):
            order_count = mysql_scalar(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE id = %s;" % request_id)
            if order_count == "1":
                break
            time.sleep(0.3)
        check("seckill order persisted by Stream consumer", order_count == "1", order_count)
        stock = mysql_scalar(
            "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = %s;" % voucher_id)
        check("seckill DB stock deducted", stock == "4", stock)
        idem = mysql_scalar(
            "SELECT status FROM tb_seckill_idempotent WHERE request_id = '%s';" % request_id)
        check("idempotent record SUCCESS", idem == "1", idem)

    # 7. like / follow / feed / sign
    check("follow user2", ensure_follow(token1, 2))
    status, resp = call("GET", "/follow/or/not/2", token=token1)
    check("isFollow user2", status == 200 and resp.get("data") is True, resp)
    # 共同关注：user1 与 user2 都关注 user4
    check("follow user4 by user1", ensure_follow(token1, 4))
    check("follow user4 by user2", ensure_follow(token2, 4))
    status, resp = call("GET", "/follow/common/2", token=token1)
    common_ids = [u["id"] for u in (resp.get("data") or [])] if resp else []
    check("GET /follow/common/2", status == 200 and resp.get("success") and 4 in common_ids,
          common_ids)

    blog_body = {"shopId": 1, "title": "P1 smoke blog " + ts,
                 "images": "/imgs/blogs/blog1.jpg", "content": "stage1 smoke"}
    status, resp = call("POST", "/blog", token=token2, body=blog_body)
    blog_id = resp.get("data") if resp else None
    check("create blog as user2", status == 200 and resp.get("success") and blog_id, resp)
    if blog_id:
        now_ms = int(time.time() * 1000) + 60000
        status, resp = call("GET", "/blog/of/follow?lastId=%s&offset=0" % now_ms, token=token1)
        ids = [b["id"] for b in (resp.get("data") or {}).get("list", [])] if resp else []
        check("feed contains followed user's blog", blog_id in ids, ids)
        status, resp = call("PUT", "/blog/like/%s" % blog_id, token=token1)
        check("like blog", status == 200 and resp.get("success"), resp)
        status, resp = call("GET", "/blog/likes/%s" % blog_id, token=token1)
        user_ids = [u["id"] for u in (resp.get("data") or [])] if resp else []
        check("blog likes contains user1", 1 in user_ids, user_ids)

    status, resp = call("POST", "/user/sign", token=token1)
    check("sign", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/user/sign/count", token=token1)
    check("sign count >= 1", status == 200 and resp.get("success")
          and isinstance(resp.get("data"), int) and resp["data"] >= 1, resp)

    # 8. remaining legacy endpoints
    status, resp = call("GET", "/blog/hot")
    check("GET /blog/hot", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/blog/4", token=token1)
    check("GET /blog/4", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/blog/of/me", token=token1)
    check("GET /blog/of/me", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/blog/of/user?id=2", token=token1)
    check("GET /blog/of/user", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/user/me", token=token1)
    check("GET /user/me", status == 200 and resp.get("data", {}).get("id") == 1, resp)
    status, resp = call("GET", "/user/1", token=token1)
    check("GET /user/1", status == 200 and resp.get("success"), resp)
    status, resp = call("GET", "/user/info/1", token=token1)
    check("GET /user/info/1", status == 200 and resp.get("success"), resp)
    status, resp = call("POST", "/user/logout", token=token1)
    check("POST /user/logout", status == 200 and resp.get("success"), resp)

    # 9. upload (local disk path from SystemConstants)
    try:
        os.makedirs(UPLOAD_DIR, exist_ok=True)
        tmp = tempfile.NamedTemporaryFile(suffix=".jpg", delete=False)
        tmp.write(b"stage1-smoke")
        tmp.close()
        out = subprocess.check_output(
            ["curl", "-s", "--noproxy", "*", "-F", "file=@" + tmp.name,
             BASE + "/upload/blog"], stderr=subprocess.DEVNULL).decode("utf-8")
        up = json.loads(out) if out else {}
        file_name = up.get("data")
        check("POST /upload/blog", up.get("success") and file_name, up)
        if file_name:
            out = subprocess.check_output(
                ["curl", "-s", "--noproxy", "*", "-X", "DELETE",
                 BASE + "/upload/blog/delete?name=" + quote(file_name)],
                stderr=subprocess.DEVNULL).decode("utf-8")
            check("DELETE /upload/blog/delete", json.loads(out).get("success"), out)
        os.unlink(tmp.name)
    except Exception as e:
        check("upload endpoints", False, e)

    # 10. prometheus
    status, resp = call("GET", "/actuator/prometheus", timeout=5)
    body_text = resp if isinstance(resp, str) else json.dumps(resp)
    check("actuator/prometheus", status == 200 and "jvm_memory" in body_text, status)

    print("\n===== RESULT: %d passed, %d failed =====" % (passed, failed))
    if failures:
        print("FAILED: " + ", ".join(failures))
        sys.exit(1)


if __name__ == "__main__":
    main()
