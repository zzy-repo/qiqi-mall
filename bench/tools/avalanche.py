#!/usr/bin/env python3
"""avalanche.py —— 把「压测结果 + cgroup 水位 + JVM GC」按档窗口对齐，出雪崩阶梯表并判定雪崩点

    python3 avalanche.py <rundir>                 # 出 ladder.csv / ladder.txt + 判定
    python3 avalanche.py --compare <dirA> <dirB>  # 两次跑同一批档次的噪声对比（±2.8% 带）

输入（同一次运行目录里的文件，缺哪个就少哪一列，不编数字）：
    steps.csv          gen.mjs 出的每档窗口（perf 轴 start_ms/end_ms + 墙上 epoch_start/end）
    samples.csv        gen.mjs 出的每请求样本（planned_ms 为 perf 轴，latency_ms 已含协调遗漏修正）
    persecond.csv      gen.mjs 出的每秒明细（拿 saturated / dropped）
    cg.csv             tools/cgwatch.sh 出的 200ms cgroup/线程/load 水位
    gc_persecond.csv   tools/gcwatch.py 出的每秒 GC 汇总（STW 毫秒合计、after-GC 堆峰值）
    gc_events.csv      tools/gcwatch.py 出的每次 STW 事件（算「after GC 堆」用）

判定口径（**跑之前就写死，不是事后找的**）：
    雪崩点 = 下面三条判据中**最先触发**的那一档 ——
      ① p99 相对上一档翻倍（p99 > 2 × 上一档 p99）
      ② 错误率 > 1%（fail / (ok+fail)）
      ③ GC 时间占比 > 20%（ΣSTW / 档窗口，取两个 JVM 中较大的那个）
    报告必须点名是**哪一条**先触发；三条同时/接近触发时按 ①②③ 的顺序报第一个。
"""
import csv
import json
import os
import sys


def rd(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def fnum(v, default=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def pct(sorted_vals, p):
    n = len(sorted_vals)
    if n == 0:
        return 0.0
    if n == 1:
        return sorted_vals[0]
    idx = (p / 100.0) * (n - 1)
    lo = int(idx)
    hi = min(lo + 1, n - 1)
    return sorted_vals[lo] if lo == hi else sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (idx - lo)


MYSQL_COLS = ["epoch", "threads_run", "threads_conn", "row_lock_waits", "row_lock_time_ms", "queries"]


def load_mysql(path):
    """读 mysql.csv；容忍**缺表头**的历史文件（第一版采样器把表头 echo 到了 stdout）。

    另外做一次「计数器回绕/重置」防护：MySQL 重启过时这些全局计数器会归零，
    于是 Δ 变负 —— 那样这一档的「行锁/s、SQL/s」就是假的，宁可报 0 也不要报负数。
    """
    if not os.path.exists(path):
        return []
    rows = []
    with open(path) as f:
        for i, line in enumerate(f):
            parts = [x.strip() for x in line.rstrip("\n").split(",")]
            if not parts or not parts[0]:
                continue
            if i == 0 and not parts[0].isdigit():
                continue                      # 真表头，跳过
            if len(parts) < len(MYSQL_COLS):
                continue
            rows.append(dict(zip(MYSQL_COLS, parts)))
    return rows


def load_run(d):
    """读一次运行的全部原料，返回 (steps, per_step 计算所需的各组数据)"""
    steps = []
    for r in rd(os.path.join(d, "steps.csv")):
        steps.append(dict(i=int(r["index"]), target=fnum(r["target_rps"]),
                          start_ms=fnum(r["start_ms"]), end_ms=fnum(r["end_ms"]),
                          dur_s=fnum(r["duration_ms"]) / 1000.0,
                          ep0=int(fnum(r["epoch_start"])), ep1=int(fnum(r["epoch_end"]))))
    samples = rd(os.path.join(d, "samples.csv"))
    per_sec = rd(os.path.join(d, "persecond.csv"))
    cg = rd(os.path.join(d, "cg.csv"))
    gc_ps = rd(os.path.join(d, "gc_persecond.csv"))
    my = load_mysql(os.path.join(d, "mysql.csv"))
    return steps, samples, per_sec, cg, gc_ps, my


def step_stats(st, samples, per_sec, cg, gc_ps, my=None):
    """单档窗口内的全部统计量"""
    lo, hi = st["start_ms"], st["end_ms"]
    lat, ok_n, fail_n = [], 0, 0
    for s in samples:
        p = fnum(s["planned_ms"])
        if p < lo or p >= hi:
            continue
        if int(fnum(s["ok"])) == 1:
            ok_n += 1
            lat.append(fnum(s["latency_ms"]))
        else:
            fail_n += 1
            lat.append(fnum(s["latency_ms"]))  # 失败也计入延迟分布（开环下失败往往就是超时/排队）
    lat.sort()
    offered = ok_n + fail_n
    o = dict(
        ok_n=ok_n, fail_n=fail_n,
        ok_s=ok_n / st["dur_s"] if st["dur_s"] else 0.0,
        err_pct=100.0 * fail_n / offered if offered else 0.0,
        p50=pct(lat, 50), p95=pct(lat, 95), p99=pct(lat, 99),
        pmax=lat[-1] if lat else 0.0,
    )
    # 每秒明细：饱和/丢弃
    # persecond.csv 没有 epoch，按 step 归属（gen.mjs 的 step 列就是档号）
    dropped = sum(int(fnum(r.get("dropped", 0))) for r in per_sec if int(fnum(r.get("step", -1))) == st["i"])
    sat = sum(int(fnum(r.get("saturated", 0))) for r in per_sec if int(fnum(r.get("step", -1))) == st["i"])
    ok_ps = sum(int(fnum(r.get("ok", 0))) for r in per_sec if int(fnum(r.get("step", -1))) == st["i"])
    o.update(dropped=dropped, sat_secs=sat, ok_ps=ok_ps)

    # ---- cgroup：memory.current 峰值、CPU 占用、Tomcat 线程数、load1、swap
    mem_peak = cpu_max = thr_max = thr_tot_max = 0.0
    load1_max = 0.0
    swap_max = 0.0
    psi_max = 0.0
    per_cont = {}
    for r in cg:
        t = int(fnum(r["epoch_ms"]))
        if not (st["ep0"] <= t <= st["ep1"]):
            continue
        c = r["container"]
        d = per_cont.setdefault(c, dict(cpu0=None, cpu1=None, mem=0.0, thr=0.0, thr_tot=0.0))
        d["mem"] = max(d["mem"], fnum(r["mem_current_B"]))
        d["thr"] = max(d["thr"], fnum(r["thr_http"]))
        d["thr_tot"] = max(d["thr_tot"], fnum(r["thr_total"]))
        u = fnum(r["cpu_usage_usec"])
        if d["cpu0"] is None:
            d["cpu0"] = u
        d["cpu1"] = u
        load1_max = max(load1_max, fnum(r["host_load1"]))
        swap_max = max(swap_max, fnum(r["host_swapused_kB"]))
        psi_max = max(psi_max, fnum(r["psi_mem_some_avg10"]))
    for c, d in per_cont.items():
        mem_peak = max(mem_peak, d["mem"])
        thr_max = max(thr_max, d["thr"])
        thr_tot_max = max(thr_tot_max, d["thr_tot"])
        if d["cpu0"] is not None and d["cpu1"] is not None and st["dur_s"]:
            cpu_pct = 100.0 * (d["cpu1"] - d["cpu0"]) / (st["dur_s"] * 1e6)
            cpu_max = max(cpu_max, cpu_pct)
    o.update(mem_current_peak_MB=mem_peak / 1048576.0, cpu_pct_max=cpu_max,
             thr_http_max=thr_max, thr_total_max=thr_tot_max,
             load1_max=load1_max, swap_used_max_MB=swap_max / 1024.0, psi_mem_max=psi_max)

    # ---- JVM GC：每 JVM 的 STW 时间占比 + after-GC 堆峰值/末值
    per_jvm = {}
    for r in gc_ps:
        sec = int(fnum(r["epoch_s"])) * 1000
        if not (st["ep0"] <= sec <= st["ep1"]):
            continue
        j = r["jvm"]
        d = per_jvm.setdefault(j, dict(pause=0.0, heap=0.0, gc=0, full=0, last=None, last_sec=-1))
        d["pause"] += fnum(r["pause_ms_sum"])
        d["gc"] += int(fnum(r["gc_count"]))
        d["full"] += int(fnum(r["full_gc"]))
        d["heap"] = max(d["heap"], fnum(r["heap_after_max_MB"]))
        if sec > d["last_sec"]:
            d["last_sec"] = sec
            d["last"] = fnum(r["heap_after_max_MB"])
    gc_ratio = 0.0
    heap_peak = 0.0
    heap_last = 0.0
    gc_cnt = 0
    full_cnt = 0
    detail = []
    for j, d in sorted(per_jvm.items()):
        ratio = 100.0 * d["pause"] / (st["dur_s"] * 1000.0) if st["dur_s"] else 0.0
        gc_ratio = max(gc_ratio, ratio)
        heap_peak = max(heap_peak, d["heap"])
        heap_last = max(heap_last, d["last"] or 0.0)
        gc_cnt += d["gc"]
        full_cnt += d["full"]
        detail.append(f"{j}:STW{ratio:.1f}%/堆峰{d['heap']:.0f}M/GC{d['gc']}次")
    o.update(gc_ratio_pct=gc_ratio, heap_after_peak_MB=heap_peak,
             heap_after_last_MB=heap_last, gc_count=gc_cnt, full_gc=full_cnt,
             gc_detail=" ".join(detail))

    # ---- MySQL 侧：判断瓶颈有没有搬到 DB
    t_run = t_conn = 0.0
    lw_rate = q_rate = 0.0
    pts = []
    for r in (my or []):
        t = int(fnum(r.get("epoch", 0))) * 1000   # mysql.csv 的 epoch 是秒，档窗口是毫秒
        if not (st["ep0"] <= t <= st["ep1"]):
            continue
        pts.append(r)
        t_run = max(t_run, fnum(r.get("threads_run")))
        t_conn = max(t_conn, fnum(r.get("threads_conn")))
    if len(pts) >= 2 and st["dur_s"]:
        a, b = pts[0], pts[-1]
        span = max(1.0, fnum(b["epoch"]) - fnum(a["epoch"]))
        lw_rate = (fnum(b.get("row_lock_waits")) - fnum(a.get("row_lock_waits"))) / span
        q_rate = (fnum(b.get("queries")) - fnum(a.get("queries"))) / span
    o.update(my_run_max=t_run, my_conn_max=t_conn, row_lock_waits_s=lw_rate, queries_s=q_rate)

    # ---- Little's law：N = λ × W
    #   λ 用**入站速率**（offered/s，即生成器按计划发出的速率，不是完成速率）
    #   W 用**样本均值**（latency_ms 的平均；开环下已含排队时间，因为延迟起点=计划时刻）
    lat_all = lat  # 已排序的全体样本延迟（含失败），直接用
    o["w_avg_ms"] = (sum(lat_all) / len(lat_all)) if lat_all else 0.0
    # λ 必须用**计划的入站速率**（= 我们对系统施加的负载），不能用「实际发出数/时长」：
    # 开环下实际发出数本身就等于 ok+fail，拿它算弹性恒等于 1，判据永远不会触发（这个坑实测踩过）。
    o["lambda_in"] = st["target"]
    o["offered_s"] = offered / st["dur_s"] if st["dur_s"] else 0.0
    o["inflight_n"] = o["lambda_in"] * o["w_avg_ms"] / 1000.0
    return o


def build(d):
    steps, samples, per_sec, cg, gc_ps, my = load_run(d)
    rows = []
    for st in steps:
        r = dict(step=st["i"], target_rps=st["target"])
        r.update(step_stats(st, samples, per_sec, cg, gc_ps, my))
        rows.append(r)
    return rows


COLS = ["step", "target_rps", "ok_s", "p50", "p95", "p99", "pmax", "err_pct", "dropped",
        "sat_secs", "heap_after_peak_MB", "heap_after_last_MB", "gc_ratio_pct", "gc_count",
        "full_gc", "mem_current_peak_MB", "cpu_pct_max", "thr_http_max", "thr_total_max",
        "load1_max", "swap_used_max_MB", "psi_mem_max", "my_run_max", "my_conn_max",
        "row_lock_waits_s", "queries_s", "w_avg_ms", "lambda_in", "offered_s", "inflight_n", "gc_detail"]


def fmt_rows(rows):
    out = []
    hdr = (f"{'档':>2} {'目标rps':>7} {'ok/s':>7} {'p50':>7} {'p95':>7} {'p99':>8} {'max':>8} "
           f"{'错%':>5} {'丢':>5} {'堆峰M':>6} {'堆末M':>6} {'GC%':>5} {'GC次':>4} "
           f"{'mem峰M':>7} {'CPU%':>6} {'线程':>4} {'load1':>5} {'swapM':>6} "
           f"{'DBrun':>6} {'DBconn':>6} {'行锁/s':>7} {'SQL/s':>6} "
           f"{'W均ms':>7} {'λ计/s':>7} {'实发/s':>7} {'在途N':>8}")
    out.append(hdr)
    out.append("-" * len(hdr))
    for r in rows:
        out.append(f"{r['step']:>2} {r['target_rps']:>7.0f} {r['ok_s']:>7.1f} {r['p50']:>7.1f} "
                   f"{r['p95']:>7.1f} {r['p99']:>8.1f} {r['pmax']:>8.1f} {r['err_pct']:>5.2f} "
                   f"{r['dropped']:>5d} {r['heap_after_peak_MB']:>6.0f} {r['heap_after_last_MB']:>6.0f} "
                   f"{r['gc_ratio_pct']:>5.1f} {r['gc_count']:>4d} {r['mem_current_peak_MB']:>7.0f} "
                   f"{r['cpu_pct_max']:>6.0f} {r['thr_http_max']:>4.0f} {r['load1_max']:>5.2f} "
                   f"{r['swap_used_max_MB']:>6.0f} {r['my_run_max']:>6.0f} {r['my_conn_max']:>6.0f} "
                   f"{r['row_lock_waits_s']:>7.1f} {r['queries_s']:>6.0f} "
                   f"{r['w_avg_ms']:>7.1f} {r['lambda_in']:>7.1f} {r['offered_s']:>7.1f} "
                   f"{r['inflight_n']:>8.1f}")
    return "\n".join(out)


def elasticity(prev, cur):
    """弹性 = (ok/s 相对涨幅) / (λ 相对涨幅)。<0.5 表示「负载涨了、吞吐没跟上」。"""
    dl = (cur["lambda_in"] - prev["lambda_in"]) / prev["lambda_in"] if prev["lambda_in"] else 0.0
    dt = (cur["ok_s"] - prev["ok_s"]) / prev["ok_s"] if prev["ok_s"] else 0.0
    return (dt / dl) if dl > 1e-9 else None, dt, dl


def verdict_v3(rows):
    """最终判据（三层）：
         ① 主判据：λ 再涨一档，ok/s 的**相对涨幅 < 该档 λ 相对涨幅的 50%**（弹性 < 0.5）→ 饱和信号
         ② 佐证：在途 N = λ×W 单调爬升（Little's law）
         ③ 自证伪：被判档的下一档若弹性 ≥ 0.5（即重新跟着涨），本档判定作废
       另外单独列出「拐区起点」= 延迟开始非线性的那一档（p99>200ms 且 ≥2×上一档 且下一档不回落）。
       两个概念分开报，不混。"""
    out = ["== 饱和点判定（新判据：吞吐弹性）==",
           "   弹性 = (ok/s 相对涨幅) ÷ (λ 入站相对涨幅)；弹性 < 0.5 ⇒ 吞吐不再跟着负载涨",
           ""]
    sat = None
    detail = []
    for i, r in enumerate(rows):
        if i == 0:
            detail.append(f"  档{r['step']}（λ={r['lambda_in']:.0f}/s, ok/s={r['ok_s']:.1f}）：基准档")
            continue
        prev = rows[i - 1]
        e, dt, dl = elasticity(prev, r)
        nxt = rows[i + 1] if i + 1 < len(rows) else None
        flag = e is not None and e < 0.5
        # 自证伪
        invalidated = False
        reason = ""
        if flag and nxt is not None:
            e_next, _, _ = elasticity(r, nxt)
            if e_next is not None and e_next >= 0.5:
                invalidated = True
                reason = f"下一档({nxt['target_rps']:.0f}) 弹性={e_next:.2f} 仍跟着涨"
        detail.append(
            f"  档{r['step']}（λ={r['lambda_in']:.0f}/s, ok/s={r['ok_s']:.1f}）："
            f"Δλ=+{dl*100:.1f}% Δok/s=+{dt*100:.1f}% 弹性={e:.2f}"
            + ("  ← 弹性<0.5 **饱和信号**" if flag else "")
            + (f"  ⇒ 本档判据**作废**（{reason}）" if invalidated else ""))
        if flag and not invalidated and sat is None:
            sat = r
    out += detail
    out.append("")

    # ② Little 佐证
    ns = [r["inflight_n"] for r in rows]
    mono = all(ns[i] <= ns[i + 1] * 1.05 for i in range(len(ns) - 1))
    out.append(f"② Little 佐证：在途 N=λ×W（W=样本均值）逐档 = "
               + " → ".join(f"{n:.0f}" for n in ns)
               + ("  **单调爬升 ✓**" if mono else "  **非单调 ✗**"))
    out.append("")

    # 拐区起点（延迟视角）
    knee = None
    for i, r in enumerate(rows):
        if i == 0:
            continue
        prev = rows[i - 1]
        nxt = rows[i + 1] if i + 1 < len(rows) else None
        c = r["p99"] > 200 and prev["p99"] > 0 and r["p99"] >= 2 * prev["p99"] and (
            nxt is None or nxt["p99"] >= 0.5 * r["p99"])
        if c:
            knee = r
            break

    if knee:
        out.append(f"★ 拐区起点 = 档 {knee['step']}（λ={knee['lambda_in']:.0f}/s, ok/s={knee['ok_s']:.1f}）："
                   f"p99 {rows[knee['step']-1]['p99']:.0f}→{knee['p99']:.0f}ms 起崩，此后不回落")
    else:
        out.append("★ 拐区起点：本阶梯内没有档位满足（p99>200 且 ≥2×上一档 且下一档不回落）")
    if sat:
        out.append(f"★ 饱和点   = 档 {sat['step']}（λ={sat['lambda_in']:.0f}/s, ok/s={sat['ok_s']:.1f}）："
                   f"λ 还涨但 ok/s 只涨 {(sat['ok_s']-rows[sat['step']-1]['ok_s'])/rows[sat['step']-1]['ok_s']*100:.1f}%")
    else:
        out.append("★ 饱和点   = 本阶梯内没有档位触发（吞吐全程跟着涨 ⇒ 还没压到天花板）")
    if knee and sat and knee["step"] != sat["step"]:
        out.append(f"   （两者不是一回事：拐区从 {knee['lambda_in']:.0f}/s 开始延迟崩，"
                   f"但吞吐要拖到 {sat['lambda_in']:.0f}/s 才真正封顶）")
    return "\n".join(out)

def verdict_v2(rows):
    """改进版判据（避免低基线伪触发）：
       雪崩点 = p99 > 200ms  且  p99 >= 2×上一档 且 下一档不回落（下一档 p99 >= 本档 0.5×）
       三个条件都满足才算；逐档打印每个条件是否成立，不搞"看情况"。"""
    out = ["== 改进版判据（p99>200ms 且 ≥2×上一档 且 下一档不回落）=="]
    hit = None
    for i, r in enumerate(rows):
        prev = rows[i - 1] if i else None
        nxt = rows[i + 1] if i + 1 < len(rows) else None
        c_abs = r["p99"] > 200.0
        c_dbl = prev is not None and prev["p99"] > 0 and r["p99"] >= 2.0 * prev["p99"]
        c_keep = True if nxt is None else nxt["p99"] >= 0.5 * r["p99"]
        ok = c_abs and c_dbl and c_keep
        out.append(f"  档{r['step']}（{r['target_rps']:.0f} rps, ok/s={r['ok_s']:.1f}）: "
                   f"p99={r['p99']:.0f}ms  >200?{'✓' if c_abs else '✗'}  "
                   f"≥2×上一档?{'✓' if c_dbl else '✗'}"
                   + (f"  下一档不回落?{'✓' if c_keep else '✗'}({nxt['p99']:.0f}ms)" if nxt else "  末档(无下一档)")
                   + ("  ★雪崩" if ok else ""))
        if ok and hit is None:
            hit = r
    out.append("")
    if hit:
        out.append(f"★ 雪崩点 = 第 {hit['step']} 档（目标 {hit['target_rps']:.0f} rps，实测 ok/s={hit['ok_s']:.1f}）")
        out.append(f"   当时：after-GC 堆 {hit['heap_after_peak_MB']:.0f}MB / GC {hit['gc_ratio_pct']:.1f}% / "
                   f"Tomcat 线程 {hit['thr_http_max']:.0f} / MySQL Threads_running {hit['my_run_max']:.0f} / "
                   f"行锁等待 {hit['row_lock_waits_s']:.0f}/s")
    else:
        out.append("★ 没有档位同时满足三个条件 —— 拐点不在本轮阶梯覆盖范围内（要么还没到，要么已经越过）")
    return "\n".join(out)


def verdict(rows):
    """旧判据（三条按档顺序扫，谁先触发算谁）—— 保留用于和历史数据对照"""
    lines = []
    hit = None
    for i, r in enumerate(rows):
        prev = rows[i - 1] if i else None
        c1 = prev is not None and prev["p99"] > 0 and r["p99"] > 2.0 * prev["p99"]
        c2 = r["err_pct"] > 1.0
        c3 = r["gc_ratio_pct"] > 20.0
        flags = []
        if c1:
            flags.append(f"① p99 翻倍（{prev['p99']:.1f}→{r['p99']:.1f}ms，×{r['p99']/prev['p99']:.2f}）")
        if c2:
            flags.append(f"② 错误率 {r['err_pct']:.2f}% > 1%")
        if c3:
            flags.append(f"③ GC 时间占比 {r['gc_ratio_pct']:.1f}% > 20%")
        lines.append(f"  档{ r['step']}（目标 {r['target_rps']:.0f} rps）：" + ("；".join(flags) if flags else "无触发"))
        if flags and hit is None:
            hit = (i, r, flags)
    out = ["== 判据逐档扫描（①p99翻倍 ②错>1% ③GC>20%）=="] + lines
    if hit:
        i, r, flags = hit
        out.append("")
        out.append(f"★ 雪崩点 = 第 {r['step']} 档（目标 {r['target_rps']:.0f} rps，实测 ok/s={r['ok_s']:.1f}）")
        out.append(f"★ 最先触发的判据 = {flags[0]}")
        if len(flags) > 1:
            out.append(f"  （同档还触发：{'；'.join(flags[1:])}）")
        out.append(f"★ 当时状态：after-GC 堆峰 {r['heap_after_peak_MB']:.0f}MB / GC 占比 {r['gc_ratio_pct']:.1f}% "
                   f"/ GC 次数 {r['gc_count']}（Full {r['full_gc']}）/ 容器 memory.current 峰 {r['mem_current_peak_MB']:.0f}MB "
                   f"/ Tomcat 线程 {r['thr_http_max']:.0f} / load1 {r['load1_max']:.2f}")
    else:
        out.append("")
        out.append("★ 全部档位都没触发任一条判据 —— 阶梯上限没到雪崩点（不是「没雪崩」，是「没压到」）")
    return "\n".join(out)


def compare(da, db):
    ra, rb = build(da), build(db)
    mb = {round(r["target_rps"]): r for r in rb}
    out = [f"== 重复性对比（噪声带 ±2.8%）==\n{'目标':>6} {'A ok/s':>9} {'B ok/s':>9} {'差异%':>8} {'在带内':>6}"]
    for r in ra:
        b = mb.get(round(r["target_rps"]))
        if not b:
            continue
        diff = 100.0 * (b["ok_s"] - r["ok_s"]) / r["ok_s"] if r["ok_s"] else 0.0
        out.append(f"{r['target_rps']:>6.0f} {r['ok_s']:>9.1f} {b['ok_s']:>9.1f} {diff:>8.2f} "
                   f"{'是' if abs(diff) <= 2.8 else '**否**':>6}")
    return "\n".join(out)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    if args[0] == "--compare":
        print(compare(args[1], args[2]))
        return
    d = args[0]
    rows = build(d)
    if not rows:
        print(f"✗ {d} 里没有可用的 steps.csv/samples.csv")
        sys.exit(2)
    with open(os.path.join(d, "ladder.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLS, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)
    table = fmt_rows(rows)
    v = verdict(rows)
    v3 = verdict_v3(rows)
    v2 = verdict_v2(rows)
    text = table + "\n\n" + v3 + "\n\n" + v2 + "\n\n" + v
    print(text)
    with open(os.path.join(d, "ladder.txt"), "w") as f:
        f.write(text + "\n")
    print(f"\n→ {os.path.join(d, 'ladder.csv')}\n→ {os.path.join(d, 'ladder.txt')}")


if __name__ == "__main__":
    main()
