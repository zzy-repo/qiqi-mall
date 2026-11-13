#!/usr/bin/env python3
"""tdcount.py —— 从 SIGQUIT 抓到的 thread dump 里数「有多少线程停在借连接」

    python3 tdcount.py <rundir>            # 读 <rundir>/threaddump-*.txt

为什么需要它：容器里是 JRE（没有 jstack/jcmd），但 `kill -3`（SIGQUIT）会让 JVM 自己把
thread dump 打到 stdout，`docker logs --since <epoch>` 就能取回来。于是「连接池是不是闸门」
不需要靠猜 —— 直接数**停在 `HikariPool.getConnection` 的 http-nio 线程数**：

    pool=10 时：~190/202 线程在排队借连接，只有个位数线程真在跑 SQL
    pool=30 时：这个数应该显著下降（借得到连接了），"跑 SQL 的线程数"应上升到 10→30 量级

输出：
  · 每个 dump 文件一行汇总（含状态分布、等连接数、跑 SQL 数）
  · 明细 CSV：<rundir>/threaddump-summary.csv
"""
import collections
import csv
import glob
import os
import re
import sys

HIKARI = ("HikariPool.getConnection", "ConcurrentBag.borrow")
SQLWAIT = ("socketRead0", "SocketDispatcher.read", "NioSocketImpl.read")
REDIS = ("RedisKvStore", "lettuce")


def classify(joined):
    if any(k in joined for k in HIKARI):
        return "等连接(HikariPool.getConnection)"
    if any(k in joined for k in SQLWAIT):
        return "跑SQL(socketRead)"
    if any(k in joined for k in REDIS):
        return "等Redis"
    return "其它"


def parse_file(path):
    txt = open(path, errors="replace").read()
    if "Full thread dump" not in txt:
        return None
    txt = txt.split("Full thread dump", 1)[1]
    blocks = re.split(r'\n(?="[^"]+")', txt)
    state = collections.Counter()
    kind = collections.Counter()
    http_total = 0
    stack_names = collections.Counter()
    for b in blocks:
        if "java.lang.Thread.State:" not in b:
            continue
        name = b.split('"')[1] if '"' in b else ""
        st = re.search(r"java\.lang\.Thread\.State: (\w+)", b)
        state[st.group(1) if st else "?"] += 1
        if not name.startswith("http-nio"):
            continue
        http_total += 1
        frames = [l.strip()[3:] for l in b.splitlines() if l.strip().startswith("at ")]
        k = classify("\n".join(frames))
        kind[k] += 1
        # 记下 http 线程名尾部的 exec-N，用来看池子规模（可选）
        m = re.search(r"-exec-(\d+)", name)
        if m:
            stack_names["exec"] += 1
    return dict(http_total=http_total, state=dict(state), kind=dict(kind))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    rundir = sys.argv[1]
    files = sorted(glob.glob(os.path.join(rundir, "threaddump-*.txt")))
    if not files:
        print(f"（{rundir} 里没有 threaddump-*.txt，跳过）")
        return
    rows = []
    print("== SIGQUIT thread dump 计数（代理指标：停在 HikariPool.getConnection 的线程数）==")
    for f in files:
        r = parse_file(f)
        name = os.path.basename(f).replace("threaddump-", "").replace(".txt", "")
        if not r:
            print(f"  {name}: 没有 thread dump（SIGQUIT 没打出来？）")
            continue
        wait = r["kind"].get("等连接(HikariPool.getConnection)", 0)
        sql = r["kind"].get("跑SQL(socketRead)", 0)
        rows.append(dict(container=name, http_threads=r["http_total"], wait_connection=wait,
                         running_sql=sql,
                         pct_waiting=round(100.0 * wait / r["http_total"], 1) if r["http_total"] else 0,
                         other=r["http_total"] - wait - sql))
        print(f"  {name}: http-nio 线程 {r['http_total']} 个 → "
              f"**等连接 {wait}**（{rows[-1]['pct_waiting']}%）/ 跑 SQL {sql} / 其它 {rows[-1]['other']}")
        print(f"     全 JVM 状态分布: {r['state']}")
    if rows:
        out = os.path.join(rundir, "threaddump-summary.csv")
        with open(out, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print(f"  → {out}")
        tot = sum(r["wait_connection"] for r in rows)
        n = sum(r["http_threads"] for r in rows)
        if n:
            print(f"  合计：{tot}/{n} 个 http-nio 线程停在借连接（{100.0*tot/n:.1f}%）")


if __name__ == "__main__":
    main()
