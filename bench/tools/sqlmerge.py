#!/usr/bin/env python3
"""sqlmerge.py —— 「压 SQL 条数能不能换吞吐」的 A/B 判读（改前手臂 vs 改后手臂）

    python3 sqlmerge.py --a <dir> [<dir> ...] --b <dir> [<dir> ...]

每个手臂可以给多轮（>=2 轮用来卡 ±2.8% 噪声带）。输出：
  ① 阶梯并列表：λ × (ok/s, p50/p95/p99, 每单语句数, SQL/s, MySQL 核数, 行锁/s)
  ② 每臂的「拐区起点 / 饱和点」（判据沿用 avalanche.py：弹性 = Δok/s ÷ Δλ < 0.5）
  ③ **因果检验**：实测 ok/s 涨幅 vs「每单语句数下降比例」预测的涨幅
       预测 = S_A / S_B  （语句少了 → 同样 SQL/s 能跑更多单）
       实测 = ok/s_B / ok/s_A
       二者比值 0.8~1.2 → 不变式被证实；远小于 1 → 闸门不在这里
  ④ MySQL CPU 与节流（第三步要的因果证据）：mycpu.csv 按档窗口求增量
"""
import csv
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import avalanche as AV  # noqa: E402  复用同一套档窗口/分位数口径


def fnum(v, d=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


def load_ladder(d):
    rows = {}
    for r in AV.rd(os.path.join(d, "ladder.csv")):
        rows[int(fnum(r["target_rps"]))] = r
    return rows


def per_order_sql(d):
    """从 sqlprofile.csv 读每档「每单语句数」"""
    out = {}
    p = os.path.join(d, "sqlprofile.csv")
    if not os.path.exists(p):
        return out
    acc = {}
    for r in csv.DictReader(open(p)):
        t = int(fnum(r["target"]))
        c = int(fnum(r["count"]))
        acc[t] = acc.get(t, 0) + c
    # 分母用 ladder.csv 的 ok_s × 档时长
    lad = load_ladder(d)
    steps = {int(fnum(x["target_rps"])): fnum(x["duration_ms"]) / 1000.0
             for x in AV.rd(os.path.join(d, "steps.csv"))}
    for t, n in acc.items():
        lad_ok = fnum(lad.get(t, {}).get("ok_s"))
        dur = steps.get(t, 75.0)
        if lad_ok and dur:
            out[t] = n / (lad_ok * dur)
    return out


def mysql_cpu(d):
    """mycpu.csv → 每档 MySQL 用了几个核 + 节流 + 宿主机压力"""
    out = {}
    p = os.path.join(d, "mycpu.csv")
    if not os.path.exists(p):
        return out
    rows = list(csv.DictReader(open(p)))
    steps = AV.rd(os.path.join(d, "steps.csv"))
    for st in steps:
        ep0, ep1 = int(fnum(st["epoch_start"])), int(fnum(st["epoch_end"]))
        seg = [r for r in rows if ep0 <= int(fnum(r["epoch_ms"])) <= ep1]
        if len(seg) < 2:
            continue
        a, b = seg[0], seg[-1]
        span_s = (fnum(b["epoch_ms"]) - fnum(a["epoch_ms"])) / 1000.0
        if span_s <= 0:
            continue
        cores = (fnum(b["usage_usec"]) - fnum(a["usage_usec"])) / (span_s * 1e6)
        out[int(fnum(st["target_rps"]))] = dict(
            cores=cores,
            throttled=int(fnum(b["nr_throttled"])) - int(fnum(a["nr_throttled"])),
            periods=int(fnum(b["nr_periods"])) - int(fnum(a["nr_periods"])),
            throttled_us=(fnum(b["throttled_usec"]) - fnum(a["throttled_usec"])) / 1000.0,
            cpu_max=(b.get("cpu_max") or "?").strip(),
            psi_full=max(fnum(r["host_cpu_psi_full10"]) for r in seg),
            psi_some=max(fnum(r["host_cpu_psi_some10"]) for r in seg),
            host_cores=max(fnum(r["host_cores_busy"]) for r in seg),
            mem_peak_mb=max(fnum(r["mem_peak_B"]) for r in seg) / 1048576.0,
        )
    return out


def elasticity(prev_ok, prev_lam, ok, lam):
    dl = (lam - prev_lam) / prev_lam if prev_lam else 0
    dt = (ok - prev_ok) / prev_ok if prev_ok else 0
    return (dt / dl) if dl > 1e-9 else None


def verdicts(lad):
    """拐区起点 / 饱和点（与 avalanche.py 同一判据）"""
    ts = sorted(lad)
    knee = None
    sat = None
    for i, t in enumerate(ts):
        r = lad[t]
        if i == 0:
            continue
        p = lad[ts[i - 1]]
        e = elasticity(fnum(p["ok_s"]), fnum(ts[i - 1]), fnum(r["ok_s"]), fnum(t))
        nxt = lad.get(ts[i + 1]) if i + 1 < len(ts) else None
        if knee is None and fnum(r["p99"]) > 200 and fnum(r["p99"]) >= 2 * fnum(p["p99"]):
            if nxt is None or fnum(nxt["p99"]) >= fnum(r["p99"]):
                knee = t
        if sat is None and e is not None and e < 0.5:
            if nxt is None:
                sat = t
            else:
                e2 = elasticity(fnum(r["ok_s"]), fnum(t),
                                fnum(nxt["ok_s"]), fnum(ts[i + 1]))
                if e2 is None or e2 < 0.5:
                    sat = t
    return knee, sat


def arm_table(dirs):
    """一个手臂（多轮）→ 每档的均值行"""
    per = [load_ladder(d) for d in dirs]
    per = [p for p in per if p]
    targets = sorted({t for p in per for t in p})
    oq = [per_order_sql(d) for d in dirs]
    mc = [mysql_cpu(d) for d in dirs]
    out = {}
    for t in targets:
        rows = [p[t] for p in per if t in p]
        n = len(rows)
        rec = dict(rounds=n)
        for k in ("ok_s", "p50", "p95", "p99", "queries_s", "row_lock_waits_s",
                  "my_run_max", "cpu_pct_max", "thr_http_max", "gc_ratio_pct", "dropped"):
            rec[k] = sum(fnum(r.get(k)) for r in rows) / n if n else 0.0
        vals = [o.get(t) for o in oq if o.get(t)]
        rec["sql_per_order"] = sum(vals) / len(vals) if vals else None
        cs = [c.get(t) for c in mc if c.get(t)]
        if cs:
            rec["my_cores"] = sum(c["cores"] for c in cs) / len(cs)
            rec["throttled"] = sum(c["throttled"] for c in cs)
            rec["cpu_max"] = cs[0]["cpu_max"]
            rec["psi_full"] = max(c["psi_full"] for c in cs)
            rec["host_cores"] = max(c["host_cores"] for c in cs)
        out[t] = rec
    return out


def main(argv):
    a, b = [], []
    cur = None
    for x in argv:
        if x == "--a":
            cur = a
        elif x == "--b":
            cur = b
        else:
            (cur if cur is not None else a).append(x)
    if not a or not b:
        print(__doc__)
        return 2
    A, B = arm_table(a), arm_table(b)
    print(f"A（改前）= {len(a)} 轮: {[os.path.basename(x) for x in a]}")
    print(f"B（改后）= {len(b)} 轮: {[os.path.basename(x) for x in b]}")
    print()
    hdr = (f"{'λ':>5} | {'A ok/s':>8} {'B ok/s':>8} {'Δok/s':>7} | {'A 语句/单':>9} {'B 语句/单':>9} {'Δ语句':>7} "
           f"| {'A p99':>8} {'B p99':>8} | {'A SQL/s':>8} {'B SQL/s':>8} | {'A 核':>5} {'B 核':>5}")
    print(hdr)
    print("-" * len(hdr))
    for t in sorted(set(A) | set(B)):
        ra, rb = A.get(t), B.get(t)
        if not ra or not rb:
            continue
        dps = ""
        if ra["sql_per_order"] and rb["sql_per_order"]:
            dps = f"{100*(rb['sql_per_order']/ra['sql_per_order']-1):+6.1f}%"
        sqa = f"{ra['sql_per_order']:.2f}" if ra["sql_per_order"] else "n/a"
        sqb = f"{rb['sql_per_order']:.2f}" if rb["sql_per_order"] else "n/a"
        print(f"{t:>5} | {ra['ok_s']:>8.1f} {rb['ok_s']:>8.1f} {100*(rb['ok_s']/ra['ok_s']-1):+6.1f}% "
              f"| {sqa:>9} {sqb:>9} {dps:>7} "
              f"| {ra['p99']:>8.0f} {rb['p99']:>8.0f} "
              f"| {ra['queries_s']:>8.0f} {rb['queries_s']:>8.0f} "
              f"| {ra.get('my_cores', 0):>5.2f} {rb.get('my_cores', 0):>5.2f}")
    print()
    for name, arm in (("A 改前", A), ("B 改后", B)):
        knee, sat = verdicts(arm)
        print(f"{name}: 拐区起点 = {knee if knee else '未触发'} rps ｜ 饱和点 = {sat if sat else '未触发'} rps")
    print()
    print("== 因果检验（不变式：ok/s ≈ SQL/s ÷ 每单语句数）==")
    for t in sorted(set(A) & set(B)):
        ra, rb = A[t], B[t]
        if not (ra["sql_per_order"] and rb["sql_per_order"]):
            continue
        pred = ra["sql_per_order"] / rb["sql_per_order"]
        obs = rb["ok_s"] / ra["ok_s"]
        print(f"  λ={t:>4}: 语句/单 {ra['sql_per_order']:.2f} → {rb['sql_per_order']:.2f}"
              f"（预测 ok/s ×{pred:.3f}）  实测 ×{obs:.3f}  比值 {obs/pred:.2f}"
              + ("  ⇒ 与不变式一致" if 0.8 <= obs / pred <= 1.25 else "  ⇒ **与不变式不符**"))
    print()
    print("== MySQL CPU / 节流（饱和档）==")
    for name, arm in (("A 改前", A), ("B 改后", B)):
        for t in sorted(arm):
            r = arm[t]
            if "my_cores" not in r:
                continue
            print(f"  {name} λ={t:>4}: MySQL {r['my_cores']:.2f} 核（cpu.max={r['cpu_max']}）"
                  f"  节流次数={r['throttled']:.0f}  宿主机忙={r['host_cores']:.2f}/4 核"
                  f"  cpu.pressure full={r['psi_full']:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
