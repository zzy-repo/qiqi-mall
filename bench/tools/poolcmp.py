#!/usr/bin/env python3
"""poolcmp.py —— 多组（不同 Hikari maxPoolSize）阶梯并列对比 + 各自饱和点

    python3 poolcmp.py <rundir> [<rundir> ...]
    # 例：python3 poolcmp.py results/*-avalanche-pool10-r1 results/*-pool20-r1 results/*-pool30-r1

自动从每组的 provenance.txt 里读 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 作为该组的池大小标签，
从 ladder.csv 读每档指标，从 threaddump-summary.csv 读「停在 getConnection 的线程数」。

输出：
  ① 并列表：λ × (ok/s, p99, 丢) 每个池一列
  ② 每组自己的「拐区起点 / 饱和点」（用 avalanche.py 的同一套判据，不另立标准）
  ③ DB 侧对照：Threads_running / 行锁等待速率 / 平均每次等待时长 —— 看瓶颈有没有搬到 DB
  ④ 机制列：等连接的线程数 / 真在跑 SQL 的线程数
"""
import csv
import os
import sys
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import avalanche as AV


def pool_of(d):
    """从 provenance.txt 里读连接池大小（没有就返回 '?'）"""
    p = os.path.join(d, "provenance.txt")
    if not os.path.exists(p):
        return "?"
    for line in open(p, errors="replace"):
        if "MAXIMUM_POOL_SIZE=" in line:
            return line.strip().split("=")[-1]
    return "10(默认)"


def mysql_detail(d):
    """整段窗口的行锁等待：次数/速率/平均每次等待（机制证据）"""
    m = AV.load_mysql(os.path.join(d, "mysql.csv"))
    st = list(csv.DictReader(open(os.path.join(d, "steps.csv"))))[1:]
    if not m or not st:
        return None
    lo = int(st[0]["epoch_start"]) // 1000
    hi = int(st[-1]["epoch_end"]) // 1000
    seg = [r for r in m if lo <= int(r["epoch"]) <= hi]
    if len(seg) < 2:
        return None
    a, b = seg[0], seg[-1]
    dlw = int(b["row_lock_waits"]) - int(a["row_lock_waits"])
    dlwt = int(b["row_lock_time_ms"]) - int(a["row_lock_time_ms"])
    span = int(b["epoch"]) - int(a["epoch"])
    return dict(waits_per_s=dlw / span if span else 0,
                avg_wait_ms=(dlwt / dlw) if dlw else 0,
                total_wait_s=dlwt / 1000.0,
                run_peak=max(int(r["threads_run"]) for r in seg))


def td(d):
    p = os.path.join(d, "threaddump-summary.csv")
    if not os.path.exists(p):
        return None
    rows = list(csv.DictReader(open(p)))
    if not rows:
        return None
    http = sum(int(r["http_threads"]) for r in rows)
    wait = sum(int(r["wait_connection"]) for r in rows)
    sql = sum(int(r["running_sql"] for r in [])) if False else sum(int(r["running_sql"]) for r in rows)
    return dict(http=http, wait=wait, sql=sql,
                pct=100.0 * wait / http if http else 0)


def main():
    dirs = [a for a in sys.argv[1:] if os.path.isdir(a)]
    if not dirs:
        print(__doc__)
        sys.exit(2)
    groups = []
    for d in dirs:
        rows = AV.build(d)
        if not rows:
            print(f"⚠ {d} 没有可用数据，跳过")
            continue
        groups.append(dict(dir=d, pool=pool_of(d), rows=rows,
                           my=mysql_detail(d), td=td(d), v3=AV.verdict_v3(rows)))
    groups.sort(key=lambda g: (float(g["pool"]) if g["pool"].replace(".", "").isdigit() else 999))

    # ---------- ① 并列表
    print("== ① 并列阶梯（λ 是计划入站速率；ok/s 是有效吞吐）==")
    steps = sorted({int(r["step"]) for g in groups for r in g["rows"]})
    lam = {}
    for g in groups:
        for r in g["rows"]:
            lam[int(r["step"])] = r["target_rps"]
    hdr = f"{'λ':>5}"
    for g in groups:
        hdr += f" | {'pool' + g['pool']:>6} ok/s {'p99':>7} {'丢':>5}"
    print(hdr)
    print("-" * len(hdr))
    for s in steps:
        line = f"{lam[s]:>5.0f}"
        for g in groups:
            r = next((x for x in g["rows"] if int(x["step"]) == s), None)
            line += (f" | {'':>6} {r['ok_s']:>6.1f} {r['p99']:>7.0f} {r['dropped']:>5}"
                     if r else f" | {'-':>6} {'-':>6} {'-':>7} {'-':>5}")
        print(line)

    # ---------- ② 每组判定
    print("\n== ② 每组自己的拐区起点 / 饱和点（同一套判据）==")
    for g in groups:
        knee = sat = None
        rows = g["rows"]
        for i, r in enumerate(rows):
            if i == 0:
                continue
            prev, nxt = rows[i - 1], (rows[i + 1] if i + 1 < len(rows) else None)
            if knee is None and r["p99"] > 200 and prev["p99"] > 0 and r["p99"] >= 2 * prev["p99"] \
                    and (nxt is None or nxt["p99"] >= 0.5 * r["p99"]):
                knee = r
            e, dt, dl = AV.elasticity(prev, r)
            if sat is None and e is not None and e < 0.5:
                e_next = AV.elasticity(r, nxt)[0] if nxt else None
                if e_next is None or e_next < 0.5:
                    sat = r
        ks = f"{knee['target_rps']:.0f} rps(p99={knee['p99']:.0f}ms)" if knee else "未触发"
        ss = f"{sat['target_rps']:.0f} rps(ok/s={sat['ok_s']:.1f})" if sat else "未触发"
        peak_ok = max(r["ok_s"] for r in rows)
        print(f"  pool={g['pool']:>2}: 拐区起点={ks:<22} 饱和点={ss:<26} 实测吞吐峰={peak_ok:.1f} ok/s")

    # ---------- ③④ DB 侧 + 机制
    print("\n== ③ DB 侧对照（瓶颈有没有搬到数据库）==")
    print(f"{'pool':>4} {'DBrun峰':>8} {'行锁等待/s':>10} {'平均每次等待':>12} {'累计等待':>9}")
    for g in groups:
        m = g["my"]
        if m:
            print(f"{g['pool']:>4} {m['run_peak']:>8} {m['waits_per_s']:>10.0f} "
                  f"{m['avg_wait_ms']:>11.1f}ms {m['total_wait_s']:>8.0f}s")
        else:
            print(f"{g['pool']:>4} {'-':>8} {'-':>10} {'-':>12} {'-':>9}")
    print("\n== ④ 机制：最大档 SIGQUIT 线程栈（连接池有没有真的松开）==")
    print(f"{'pool':>4} {'http-nio':>8} {'等连接':>7} {'占比':>6} {'真跑SQL':>8}")
    for g in groups:
        t = g["td"]
        if t:
            print(f"{g['pool']:>4} {t['http']:>8} {t['wait']:>7} {t['pct']:>5.1f}% {t['sql']:>8}")
        else:
            print(f"{g['pool']:>4} {'-':>8} {'-':>7} {'-':>6} {'-':>8}")


if __name__ == "__main__":
    main()
