#!/usr/bin/env python3
"""auditcost.py —— 「审计写入成本」的量化：条/单、耗时占比、端到端延迟

    python3 auditcost.py <rundir>            # 定速下单档（gen.mjs + sqldigest + 事务聚合前后差）
    python3 auditcost.py --step <idx> <rundir>  # 多档跑（steps.csv 有多行）时只看第 idx 档；auditcost.sh 默认传 STEP_INDEX/0
    python3 auditcost.py --ship <rundir>     # 后台发货路径微基准（ship_s.txt，串行单次操作墙钟）

口径（不编数字，缺哪份就少哪一列）：
  · 只算 SCHEMA_NAME='qiqi_mall_test'，并剔掉采样器自己的查询（digest_text 含 events_statements_summary）
  · DIGEST 表是「自 MySQL 启动以来」的累计值 → 用窗口**前后两次快照做差**
  · 「条/单」= 该 DIGEST 在窗口内的执行次数 ÷ 窗口内 ok 单数（失败单不摊进分母）
  · 「耗时占比」有两个分母，两个都报，因为它们回答的问题不同：
        sql_share   = 审计 INSERT 的 SUM_TIMER_WAIT ÷ 窗口内**所有**语句的 SUM_TIMER_WAIT
                      （= 审计在「服务器花在 SQL 上的时间」里占多少）
        txn_share   = 审计 INSERT 的 SUM_TIMER_WAIT ÷ 窗口内**事务**的总时长
                      （= 审计在「每笔事务墙钟」里占多少 —— 任务书判据用的就是这个口径，
                       但注意事务时长含锁等待/网络，所以它比 sql_share 只会更小）
"""
import csv
import json
import os
import statistics
import sys

AUDIT_MARK = "INSERT INTO admin_audit_log"
SELF_MARK = "events_statements_summary"


def fnum(v, d=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


def rd(p):
    if not os.path.exists(p):
        return []
    with open(p, errors="replace") as f:
        return list(csv.DictReader(f))


def pct(sorted_vals, q):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * q / 100.0
    lo, hi = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def digests(d, schema="qiqi_mall_test"):
    """→ {digest: [(epoch_ms, count, timer_ms, text)]} 按时间升序"""
    out = {}
    for r in rd(os.path.join(d, "sqldigest.csv")):
        if (r.get("schema") or "").strip() != schema:
            continue
        txt = r.get("digest_text") or ""
        if SELF_MARK in txt:
            continue
        out.setdefault(r["digest"], []).append(
            (fnum(r["epoch_ms"]), fnum(r["count_star"]), fnum(r["sum_timer_wait_ms"]), txt))
    for v in out.values():
        v.sort()
    return out


def value_at(series, epoch_ms):
    """该 digest 在 epoch_ms 时（含之前）的累计值"""
    val = (0.0, 0.0)
    for (e, c, t, _) in series:
        if e <= epoch_ms:
            val = (c, t)
        else:
            break
    return val


def window_stats(d, start_ms, end_ms):
    dd = digests(d)
    tot_c = tot_t = 0.0
    audit_c = audit_t = 0.0
    audit_text = ""
    per = []
    for dig, series in dd.items():
        c0, t0 = value_at(series, start_ms)
        c1, t1 = value_at(series, end_ms)
        c, t = c1 - c0, t1 - t0
        if c <= 0:
            continue
        txt = series[0][3]
        per.append((t, c, txt))
        tot_c += c
        tot_t += t
        if AUDIT_MARK.upper() in txt.upper().replace("`", ""):
            audit_c += c
            audit_t += t
            audit_text = txt
    per.sort(reverse=True)
    return dict(tot_c=tot_c, tot_t=tot_t, audit_c=audit_c, audit_t=audit_t,
                audit_text=audit_text, top=per[:12])


def txn_delta(d):
    def rd2(p):
        try:
            a = open(os.path.join(d, p)).read().strip().split(",")
            return fnum(a[0]), fnum(a[1]) / 1e12      # COUNT_STAR, SUM_TIMER_WAIT(ps)->s
        except Exception:
            return 0.0, 0.0
    c0, t0 = rd2("txn_before.txt")
    c1, t1 = rd2("txn_after.txt")
    return c1 - c0, t1 - t0


def load_run(d, idx=0):
    steps = rd(os.path.join(d, "steps.csv"))
    if not steps:
        return None
    s = steps[idx] if idx < len(steps) else steps[-1]
    return dict(start_ep=fnum(s["epoch_start"]), end_ep=fnum(s["epoch_end"]),
                s0=fnum(s["start_ms"]), s1=fnum(s["end_ms"]),
                dur=fnum(s["duration_ms"]) / 1000.0, target=fnum(s["target_rps"]))


def samples_window(d, st):
    ok = 0
    lat = []
    fail = 0
    for s in rd(os.path.join(d, "samples.csv")):
        p = fnum(s.get("planned_ms"), -1)
        if not (st["s0"] <= p <= st["s1"]):
            continue
        if int(fnum(s.get("ok"))) == 1:
            ok += 1
            lat.append(fnum(s.get("latency_ms")))
        else:
            fail += 1
    lat.sort()
    return ok, fail, lat


def report_run(d, idx=0):
    st = load_run(d, idx)
    ev = open(os.path.join(d, "evidence.txt")).read().strip().splitlines() if os.path.exists(
        os.path.join(d, "evidence.txt")) else []
    tag = os.path.basename(d.rstrip("/"))
    ledger = next((l.split("=", 1)[1] for l in ev if l.startswith("ledger_line=")), "?")
    audit_line = next((l for l in ev if l.startswith("audit_rows_")), "?")
    out = {"label": tag, "target_rps": st["target"], "ledger": ledger, "audit_rows": audit_line}
    ok, fail, lat = samples_window(d, st)
    out.update(ok=ok, fail=fail, okps=ok / st["dur"] if st["dur"] else 0,
               p50=pct(lat, 50), p95=pct(lat, 95), p99=pct(lat, 99),
               avg=statistics.fmean(lat) if lat else 0, mx=lat[-1] if lat else 0)
    ws = window_stats(d, st["start_ep"], st["end_ep"])
    tc, tt = txn_delta(d)
    # 事务聚合的前后差覆盖的是**整段采样窗口**（含预热档/第一档），所以分母要用整段跑完的 ok 数，
    # 不能用「测量档」的 ok —— 否则每单事务时长会被放大成 2~3 倍（本轮踩过）。
    run_ok = ok
    try:
        run_ok = json.load(open(os.path.join(d, "run.json")))["ok"] or ok
    except Exception:
        pass
    out.update(sql_stmts=ws["tot_c"], sql_ms=ws["tot_t"],
               sql_per_order=ws["tot_c"] / ok if ok else 0,
               audit_stmts=ws["audit_c"], audit_ms=ws["audit_t"],
               audit_per_order=ws["audit_c"] / ok if ok else 0,
               audit_ms_per_order=ws["audit_t"] / ok if ok else 0,
               sql_share=100 * ws["audit_t"] / ws["tot_t"] if ws["tot_t"] else 0,
               txn_count=tc, txn_s=tt,
               txn_ms_per_txn=1000 * tt / tc if tc else 0,
               txn_ms_per_order=1000 * tt / run_ok if run_ok else 0,
               run_ok=run_ok,
               txn_share=100 * (ws["audit_t"] / 1000) / tt if tt else 0)
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return out


def report_ship(d):
    tag = os.path.basename(d.rstrip("/"))
    vals = [fnum(x) * 1000 for x in open(os.path.join(d, "ship_s.txt")).read().split() if x.strip()]
    vals.sort()
    ev = open(os.path.join(d, "evidence.txt")).read().strip().splitlines()
    ledger = next((l.split("=", 1)[1] for l in ev if l.startswith("ledger_line=")), "?")
    rows = next((l for l in ev if l.startswith("audit_rows_")), "?")
    out = {"label": tag, "n": len(vals), "ledger": ledger, "audit_rows": rows}

    def two(p):
        try:
            a = open(os.path.join(d, p)).read().strip().split(",")
            return fnum(a[0]), fnum(a[1])      # COUNT_STAR, SUM_TIMER_WAIT(ms)
        except Exception:
            return None
    ab, aa = two("ps_audit_before.txt"), two("ps_audit_after.txt")
    tb, ta = two("ps_all_before.txt"), two("ps_all_after.txt")
    if ab and aa and tb and ta:
        a_c, a_ms = aa[0] - ab[0], aa[1] - ab[1]
        t_c, t_ms = ta[0] - tb[0], ta[1] - tb[1]
        out.update(op_sql_stmts=t_c, op_sql_ms=t_ms,
                   op_sql_per_op=t_c / len(vals) if vals else 0,
                   audit_stmts=a_c, audit_ms=a_ms,
                   audit_per_op=a_c / len(vals) if vals else 0,
                   audit_ms_per_op=a_ms / len(vals) if vals else 0,
                   sql_share=100 * a_ms / t_ms if t_ms else 0)
    out.update(op_p50=pct(vals, 50), op_p95=pct(vals, 95), op_p99=pct(vals, 99),
               op_avg=statistics.fmean(vals) if vals else 0, op_max=vals[-1] if vals else 0)
    if out.get("op_avg"):
        out["txn_share"] = 100 * out.get("audit_ms_per_op", 0) / out["op_avg"]
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return out


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    if sys.argv[1] == "--ship":
        report_ship(sys.argv[2])
    elif sys.argv[1] == "--step":
        report_run(sys.argv[3], int(sys.argv[2]))
    else:
        report_run(sys.argv[1])
