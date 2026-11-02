#!/usr/bin/env python3
"""验证全量重建期间并发更新不丢增量（双写 search:rebuild:target）。"""
import json
import threading
import time
import urllib.error
import urllib.request

APP = "http://127.0.0.1:8081"
ADMIN = {"X-Admin-Token": "zs-admin-local", "Content-Type": "application/json"}
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
ACTIVITY_ID = 910400


def call(path, method="GET", body=None, timeout=120):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(APP + path, data=data, headers=ADMIN, method=method)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def search(query):
    req = urllib.request.Request(APP + "/search/activity",
                                 data=json.dumps({"query": query, "size": 20}, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with opener.open(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main():
    ts = int(time.time())
    status, detail = call("/admin/activity/%s" % ACTIVITY_ID)
    original = detail["data"]["title"]
    print("original title:", original)

    result = {}

    def reindex():
        result["resp"] = call("/admin/search/reindex", method="POST", timeout=300)

    thread = threading.Thread(target=reindex)
    thread.start()
    time.sleep(0.8)

    i = 0
    while thread.is_alive():
        call("/admin/activity", method="PUT", body={
            "id": ACTIVITY_ID, "title": "P2并发更新-%d-%d" % (ts, i), "status": 2, "activityType": 1
        })
        i += 1
        time.sleep(0.4)
    thread.join()
    print("reindex done, concurrent updates:", i, "reindex resp:", result.get("resp", [None])[0])

    final_title = "P2并发更新完成-%d" % ts
    call("/admin/activity", method="PUT", body={
        "id": ACTIVITY_ID, "title": final_title, "status": 2, "activityType": 1
    })
    time.sleep(1.5)

    resp = search(final_title)
    items = (resp.get("data") or {}).get("list") or []
    ok = any(item.get("id") == ACTIVITY_ID and item.get("title") == final_title for item in items)
    print("[PASS] rebuild conflict: final update visible" if ok
          else "[FAIL] rebuild conflict: final update missing")
    if not ok:
        print("items:", [(x.get("id"), x.get("title")) for x in items[:10]])

    # 恢复原状
    call("/admin/activity", method="PUT", body={
        "id": ACTIVITY_ID, "title": original, "status": 2, "activityType": 1
    })
    time.sleep(0.8)
    print("restored title:", original)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
