#!/usr/bin/env python3
"""localsess.py —— useLocalSessionState 对照实验的判读（两臂三档中位数 + 探针条数 + 预测对撞）

    python3 localsess.py --a <armA 轮目录...> --b <armB 轮目录...> [--ref <上一轮参照轮...>] [--qps-cap 8215]

输出：
  ① 每臂每档：ok/s 的**逐轮值 + 中位数**、p50/p95/p99 中位数、每单语句数中位数、SQL/s、
     MySQL 核数、行锁/s、fsync/s、堆峰、GC%
  ② **探针条数**：`SELECT @@SESSION.transaction_read_only` 与 `SET autocommit = ?` 的条/单
     —— 这是「改动是否生效」的语句级硬证据（吞吐会被 JIT/GC/热机污染，探针条数不会）
  ③ 预测 vs 实测：ok/s_pred = QPS_cap ÷ 每单语句数，逐档给误差百分比
  ④ 冷启动门禁：该档 SQL/s < 7500 ⇒ 标 ⚠冷，该档数据作废（口径沿用上一轮 §13.8）

为什么分母用 ladder.csv 的 ok_s×时长：sqlprofile.csv 的 count 是服务端语句计数，
它的窗口边界来自 steps.csv（±1 个采样间隔），用同一窗口里的 ok 单数做分母才是「每单」。
"""
import csv
import os
import sys
import statistics as st

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import avalanche as AV  # noqa: E402

PROBE = "transaction_read_only"
AUTOCOMMIT = "autocommit"


def fnum(v, d=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


def med(vals):
    vals = [v for v in vals if v is not None]
    return st.median(vals) if vals else None


def fmt(v, n=1, suf=""):
    return "n/a" if v is None else f"{v:.{n}f}{suf}"


def digest_delta(d):
    """sqlprofile.csv → {target: {'total':n, 'probe':n, 'autocommit':n}}"""
    p = os.path.join(d, "sqlprofile.csv")
    if not os.path.exists(p):
        return {}
    out = {}
    for r in csv.DictReader(open(p)):
        t = int(fnum(r["target"]))
        c = int(fnum(r["count"]))
        rec = out.setdefault(t, {"total": 0, "probe": 0, "autocommit": 0})
        rec["total"] += c
        txt = (r["digest_text"] or "").lower()
        if PROBE in txt:
            rec["probe"] += c
        if AUTOCOMMIT in txt and txt.strip().startswith("set"):
            rec["autocommit"] += c
    return out


def mysql_rates(d):
    """mysql.csv → 每档 fsync/s、行锁/s（用档窗口做差，与 avalanche.py 同口径）"""
    p = os.path.join(d, "mysql.csv")
    if not os.path.exists(p):
        return {}
    rows = list(csv.reader(open(p)))
    if not rows:
        return {}
    hdr = rows[0] if not rows[0][0].replace(".", "").isdigit() else None
    if hdr is None:
        hdr = ["epoch", "threads_run", "threads_conn", "row_lock_waits", "row_lock_time_ms", "queries",
               "data_fsyncs", "log_fsyncs", "log_waits", "bp_wait_free", "rows_updated", "rows_inserted"]
        rows = [hdr] + rows
    idx = {name: i for i, name in enumerate(hdr)}
    data = []
    for r in rows[1:]:
        if len(r) < len(hdr):
            continue
        data.append(r)
    out = {}
    for step in AV.rd(os.path.join(d, "steps.csv")):
        t = int(fnum(step["target_rps"]))
        ep0, ep1 = int(fnum(step["epoch_start"])), int(fnum(step["epoch_end"]))
        # mysql.csv 的 epoch 是**秒**，档窗口是毫秒（avalanche.py 里同样的换算）
        seg = [r for r in data if ep0 <= int(fnum(r[idx["epoch"]])) * 1000 <= ep1]
        if len(seg) < 2:
            continue
        a, b = seg[0], seg[-1]
        span = fnum(b[idx["epoch"]]) - fnum(a[idx["epoch"]])
        if span <= 0:
            continue
        get = lambda row, k: fnum(row[idx[k]]) if k in idx else 0.0
        rl_w = get(b, "row_lock_waits") - get(a, "row_lock_waits")
        rl_t = get(b, "row_lock_time_ms") - get(a, "row_lock_time_ms")
        out[t] = dict(
            data_fsync_s=(get(b, "data_fsyncs") - get(a, "data_fsyncs")) / span,
            log_fsync_s=(get(b, "log_fsyncs") - get(a, "log_fsyncs")) / span,
            row_lock_s=rl_w / span,
            row_lock_wait_ms=(rl_t / rl_w) if rl_w > 0 else 0.0,
            queries_s=(get(b, "queries") - get(a, "queries")) / span,
        )
    return out


def round_metrics(d):
    """一轮 → {target: metrics}"""
    lad = {}
    for r in AV.rd(os.path.join(d, "ladder.csv")):
        lad[int(fnum(r["target_rps"]))] = r
    steps = {int(fnum(s["target_rps"])): fnum(s["duration_ms"]) / 1000.0
             for s in AV.rd(os.path.join(d, "steps.csv"))}
    dg = digest_delta(d)
    my = mysql_rates(d)
    out = {}
    for t, r in lad.items():
        ok = fnum(r["ok_s"])
        dur = steps.get(t, 75.0)
        dgt = dg.get(t, {})
        n_ok = ok * dur
        rec = dict(
            ok_s=ok, p50=fnum(r["p50"]), p95=fnum(r["p95"]), p99=fnum(r["p99"]),
            dropped=fnum(r["dropped"]), err_pct=fnum(r["err_pct"]),
            queries_s=fnum(r["queries_s"]), row_lock_s=fnum(r["row_lock_waits_s"]),
            heap_peak=fnum(r["heap_after_peak_MB"]), gc_pct=fnum(r["gc_ratio_pct"]),
            thr_http=fnum(r["thr_http_max"]), cpu_pct=fnum(r["cpu_pct_max"]),
            per_order=(dgt.get("total", 0) / n_ok) if n_ok else None,
            probe_per_order=(dgt.get("probe", 0) / n_ok) if n_ok else None,
            ac_per_order=(dgt.get("autocommit", 0) / n_ok) if n_ok else None,
        )
        if t in my:
            rec.update(my[t])
        out[t] = rec
    return out


def arm(dirs):
    rounds = [round_metrics(d) for d in dirs]
    rounds = [r for r in rounds if r]
    targets = sorted({t for r in rounds for t in r})
    out = {}
    for t in targets:
        rs = [r[t] for r in rounds if t in r]
        rec = dict(n=len(rs), vals=[r["ok_s"] for r in rs])
        for k in ("ok_s", "p50", "p95", "p99", "queries_s", "row_lock_s", "row_lock_wait_ms",
                  "data_fsync_s", "log_fsync_s", "heap_peak", "gc_pct", "per_order",
                  "probe_per_order", "ac_per_order", "dropped", "thr_http", "cpu_pct"):
            rec[k] = med([r.get(k) for r in rs])
        out[t] = rec
    return out


def line_arm(name, A):
    print(f"\n=== {name} ===")
    hdr = (f"{'λ':>4} {'ok/s 逐轮':>26} {'中位':>7} {'p50':>7} {'p95':>8} {'p99':>8} "
           f"{'语句/单':>8} {'探针/单':>8} {'SQL/s':>7} {'行锁/s':>7} {'锁等ms':>7} {'达成%':>6} {'丢':>6} "
           f"{'核':>5} {'堆峰M':>6} {'GC%':>5}")
    print(hdr)
    print("-" * len(hdr))
    med_q = med([r["queries_s"] for r in A.values() if r["queries_s"]])
    for t in sorted(A):
        r = A[t]
        vals = "/".join(f"{v:.1f}" for v in sorted(r["vals"]))
        # 冷启动判据（本轮改法，理由见报告）：
        #   ① 该档 SQL/s < 本臂各档 SQL/s 中位数 × 0.85  ⇒ 语句率还没爬到本臂的墙
        #   ② 或 没达成目标（<99%）且有丢弃 = 请求在客户端排队溢出
        # 绝对阈值 7500 是上一轮按「19~20 条/单」标定的，本轮 B 臂只有 12.4 条/单，
        # 它的墙本来就在 5.7~6.3k，套绝对阈值会把每一档都误判成冷（见报告 §3.3）。
        cold = ""
        if (r["queries_s"] or 0) < 0.85 * (med_q or 0):
            cold = " ⚠冷(SQL/s)"
        elif (r["dropped"] or 0) > 0 and r["ok_s"] < 0.99 * t:
            # 不是"冷"，是**已到天花板**（到达率 > 服务率 ⇒ 客户端排队溢出）。这是本实验要找的饱和档。
            cold = f" 饱和(丢{r['dropped']:.0f})"
        print(f"{t:>4} {vals:>26} {fmt(r['ok_s']):>7} {fmt(r['p50'],0):>7} {fmt(r['p95'],0):>8} "
              f"{fmt(r['p99'],0):>8} {fmt(r['per_order'],2):>8} {fmt(r['probe_per_order'],2):>8} "
              f"{fmt(r['queries_s'],0):>7} {fmt(r['row_lock_s'],0):>7} {fmt(r['row_lock_wait_ms'],0):>7} "
              f"{fmt(100.0*r['ok_s']/t,1):>6} {fmt(r['dropped'],0):>6} "
              f"{fmt(r['cpu_pct'],0):>5} {fmt(r['heap_peak'],0):>6} {fmt(r['gc_pct'],2):>5}{cold}")


def predict(A, B, cap, label):
    print(f"\n=== 预测 vs 实测（{label}：ok/s_pred = {cap:.0f} ÷ 语句/单）===")
    print(f"{'λ':>4} {'A 语句/单':>9} {'A 预测':>8} {'A 实测':>8} {'A 误差':>8} | "
          f"{'B 语句/单':>9} {'B 预测':>8} {'B 实测':>8} {'B 误差':>8}")
    for t in sorted(set(A) & set(B)):
        ra, rb = A[t], B[t]
        cells = []
        for r in (ra, rb):
            po = r["per_order"]
            if not po:
                cells += ["n/a", "n/a", "n/a"]
                continue
            pred = cap / po
            obs = r["ok_s"]
            cells += [f"{po:.2f}", f"{pred:.0f}", f"{obs:.1f}", f"{100*(obs/pred-1):+.1f}%"]
        print(f"{t:>4} {cells[0]:>9} {cells[1]:>8} {cells[2]:>8} {cells[3]:>8} | "
              f"{cells[4]:>9} {cells[5]:>8} {cells[6]:>8} {cells[7]:>8}")


def main(argv):
    a, b, ref, cap = [], [], [], 8215.0
    cur = None
    i = 0
    while i < len(argv):
        x = argv[i]
        if x == "--a":
            cur = a
        elif x == "--b":
            cur = b
        elif x == "--ref":
            cur = ref
        elif x == "--qps-cap":
            i += 1
            cap = float(argv[i])
        else:
            (cur if cur is not None else a).append(x)
        i += 1
    if not a or not b:
        print(__doc__)
        return 2
    A, B = arm(a), arm(b)
    print(f"A 臂（useLocalSessionState 缺省=false）= {len(a)} 轮: {[os.path.basename(x) for x in a]}")
    print(f"B 臂（useLocalSessionState=true）    = {len(b)} 轮: {[os.path.basename(x) for x in b]}")
    if ref:
        print(f"参照（上一轮最好状态）= {len(ref)} 轮: {[os.path.basename(x) for x in ref]}")
    print("\n⚠冷 = 该档 SQL/s < 7500（上一轮 §13.8 的冷启动门禁），该档数据作废")
    line_arm("A 臂（18.77 条/单那一版 jar，URL 不带参数）", A)
    line_arm("B 臂（同 jar，URL 加 useLocalSessionState=true）", B)
    if ref:
        line_arm("参照 R（上一轮 sqlfix 最好轮，供连续性对照）", arm(ref))
    print("\n=== 并排对照（λ 逐档）===")
    hdr = (f"{'λ':>4} | {'A ok/s':>7} {'B ok/s':>7} {'Δ':>7} | {'A 语句/单':>9} {'B 语句/单':>9} "
           f"| {'B 探针/单':>9} | {'A p99':>7} {'B p99':>7} | {'A SQL/s':>8} {'B SQL/s':>8}")
    print(hdr)
    print("-" * len(hdr))
    for t in sorted(set(A) | set(B)):
        if t not in A or t not in B:
            continue
        ra, rb = A[t], B[t]
        d = 100 * (rb["ok_s"] / ra["ok_s"] - 1) if ra["ok_s"] else 0
        print(f"{t:>4} | {ra['ok_s']:>7.1f} {rb['ok_s']:>7.1f} {d:+6.1f}% | "
              f"{fmt(ra['per_order'],2):>9} {fmt(rb['per_order'],2):>9} | {fmt(rb['probe_per_order'],2):>9} | "
              f"{fmt(ra['p99'],0):>7} {fmt(rb['p99'],0):>7} | {fmt(ra['queries_s'],0):>8} {fmt(rb['queries_s'],0):>8}")
    predict(A, B, cap, f"QPS_cap={cap:.0f}")
    print("\n=== 不变式自证：每臂「ok/s × 语句/单」= 语句率常量 ===")
    for name, X in (("A", A), ("B", B)):
        for t in sorted(X):
            r = X[t]
            if r["per_order"]:
                print(f"  {name} λ={t:>4}: {r['ok_s']:>6.1f} × {r['per_order']:>5.2f} = "
                      f"{r['ok_s']*r['per_order']:>8.0f} 语句/s"
                      )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
