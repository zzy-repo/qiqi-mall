#!/usr/bin/env python3
"""sqlprofile.py —— SQL 画像：按档位把 performance_schema 的 DIGEST 聚合表**做差**，回答「每单那 N 条是哪几条」

    python3 sqlprofile.py <rundir> [<rundir> ...]

输入（都在 rundir 里，缺了就少一列，不编数字）：
    steps.csv        gen.mjs 的档窗口（含 epoch_start/epoch_end，毫秒）
    samples.csv      gen.mjs 的逐请求样本（用来数该档 ok 数 → 算「每单几条」）
    sqldigest.csv    tools/sqldigest.sh 的每 2s 快照（累计值）

口径：
  · 只统计 SCHEMA_NAME='qiqi_mall_test' 的行（P_S 的 digest 表按 schema 分行，别的库的行不能混进来）
  · 采样器自己的查询（digest_text 含 events_statements_summary / `select $$`）剔掉
  · 每档取「档开始前最后一个快照」与「档结束后第一个快照」相减 = 该档窗口内每条语句执行了多少次
  · 每单条数 = 该档语句总数 ÷ 该档 ok 单数（不是 ÷ 计划数：失败的单不该摊进分母）
  · 语句分三类，用来算「压 SQL 能压到哪」：
        driver   驱动/池自己的往返：SELECT @@... / SET ... / COMMIT
        read     业务只读：SELECT ... FROM 业务表
        write    业务写：INSERT / UPDATE / DELETE 业务表
    ⚠️ 关键事实：Connector/J 在**每条 DML 前**都会先发一句 `SELECT @@session.transaction_read_only`
       （useLocalSessionState=false 的默认行为），所以「删掉一条 DML」实际省掉的是 **2 条语句**。
"""
import csv
import os
import sys

DRIVER_PREFIX = ("SELECT @@", "SET ", "COMMIT", "ROLLBACK", "BEGIN", "select @@")


def fnum(v, d=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


def kind_of(txt):
    t = txt.strip()
    if t.startswith(DRIVER_PREFIX) or t.lower().startswith("select @@"):
        return "driver"
    u = t.upper()
    if u.startswith(("INSERT", "UPDATE", "DELETE", "REPLACE")):
        return "write"
    return "read"


def rd(p):
    if not os.path.exists(p):
        return []
    with open(p, errors="replace") as f:
        return list(csv.DictReader(f))


def load_steps(d):
    out = []
    for r in rd(os.path.join(d, "steps.csv")):
        out.append(dict(i=int(r["index"]), target=fnum(r["target_rps"]),
                        ep0=int(fnum(r["epoch_start"])), ep1=int(fnum(r["epoch_end"])),
                        # 样本的 planned_ms 是**压测内部轴**（从进程启动算），档窗口要用 start_ms/end_ms，
                        # 不能拿墙上 epoch 去比 —— 这两个轴混用会得到「ok=0」这种假数字（本轮踩过）
                        s0=fnum(r["start_ms"]), s1=fnum(r["end_ms"]),
                        dur=fnum(r["duration_ms"]) / 1000.0))
    return out


def ok_count(d, st):
    n = 0
    for s in rd(os.path.join(d, "samples.csv")):
        p = fnum(s.get("planned_ms"), -1)
        if st["s0"] <= p <= st["s1"] and int(fnum(s.get("ok"))) == 1:
            n += 1
    return n


def load_digest(d, schema="qiqi_mall_test"):
    """→ [(epoch_ms, digest, count, timer_ms, text)]，按时间升序"""
    rows = []
    for r in rd(os.path.join(d, "sqldigest.csv")):
        if (r.get("schema") or "").strip() != schema:
            continue
        txt = (r.get("digest_text") or "").strip()
        if "events_statements_summary" in txt or txt.startswith("select $"):
            continue
        rows.append((int(fnum(r["epoch_ms"])), r["digest"], int(fnum(r["count_star"])),
                     fnum(r["sum_timer_wait_ms"]), txt))
    rows.sort()
    return rows


def snapshot_at(rows, ts):
    """取 ts 时刻的累计快照（= ts 之前最后一个快照；没有就 None）"""
    best = None
    for e, dg, c, t, txt in rows:
        if e <= ts:
            best = (e, dg, c, t, txt)
        else:
            break
    return best


def tier_delta(rows, st):
    """该档窗口内的 per-digest 增量 → dict[digest] = [count, timer_ms, text]"""
    snaps = {}
    prev = None
    for e, dg, c, t, txt in rows:
        snaps.setdefault(e, {})[dg] = (c, t, txt)
        if prev is None:
            prev = e
    # 起点快照：<= ep0 的最后一个时间戳；终点快照：>= ep1 的第一个时间戳
    e0 = max([e for e in snaps if e <= st["ep0"]], default=None)
    e1 = min([e for e in snaps if e >= st["ep1"]], default=None)
    if e0 is None or e1 is None:
        return {}, None, None
    out = {}
    for dg, (c1, t1, txt) in snaps[e1].items():
        c0, t0, _ = snaps[e0].get(dg, (0, 0.0, txt))
        if c1 > c0:
            out[dg] = [c1 - c0, t1 - t0, txt]
    return out, e0, e1


def report(d):
    steps = load_steps(d)
    rows = load_digest(d)
    if not steps or not rows:
        print(f"[{os.path.basename(d)}] 缺 steps.csv 或 sqldigest.csv，跳过")
        return None
    print(f"\n########## {os.path.basename(d)} ##########")
    allrows = []
    summary = []
    for st in steps:
        delta, e0, e1 = tier_delta(rows, st)
        ok = ok_count(d, st)
        tot = sum(v[0] for v in delta.values())
        kinds = {"driver": 0, "read": 0, "write": 0}
        for dg, (c, t, txt) in delta.items():
            kinds[kind_of(txt)] += c
        per_order = (tot / ok) if ok else 0
        summary.append((st, ok, tot, per_order, kinds))
        print(f"\n=== 档 {st['i']}（λ={st['target']:.0f}/s, ok={ok}, 窗口 {e0}~{e1}）===")
        print(f"  语句总数 {tot} → **每单 {per_order:.2f} 条**"
              f"（driver {kinds['driver']} / read {kinds['read']} / write {kinds['write']}）")
        top = sorted(delta.items(), key=lambda kv: -kv[1][0])[:12]
        print(f"  {'/单':>6} {'占比':>6} {'次数':>8} {'总ms':>9} {'均ms':>6}  语句")
        for dg, (c, t, txt) in top:
            allrows.append(dict(tier=st["i"], target=st["target"], digest=dg, count=c,
                                per_order=c / ok if ok else 0, total_ms=t,
                                avg_ms=t / c if c else 0, share=c / tot * 100 if tot else 0,
                                kind=kind_of(txt), digest_text=txt))
            print(f"  {c/ok if ok else 0:6.2f} {100*c/tot:5.1f}% {c:8d} {t:9.1f} {t/c:6.3f}  {txt[:96]}")
    # 写 CSV（全量，不只是 top12）
    out_csv = os.path.join(d, "sqlprofile.csv")
    with open(out_csv, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["tier", "target", "digest", "count", "per_order",
                                          "total_ms", "avg_ms", "share", "kind", "digest_text"])
        w.writeheader()
        for st in steps:
            delta, _, _ = tier_delta(rows, st)
            ok = ok_count(d, st)
            tot = sum(v[0] for v in delta.values())
            for dg, (c, t, txt) in sorted(delta.items(), key=lambda kv: -kv[1][0]):
                w.writerow(dict(tier=st["i"], target=st["target"], digest=dg, count=c,
                                per_order=round(c / ok, 4) if ok else 0, total_ms=round(t, 3),
                                avg_ms=round(t / c, 4) if c else 0,
                                share=round(100 * c / tot, 2) if tot else 0,
                                kind=kind_of(txt), digest_text=txt))
    print(f"\n  → {out_csv}")
    print(f"\n  {'档':>3} {'λ':>6} {'ok':>6} {'语句/单':>8} {'driver':>7} {'read':>6} {'write':>6} {'SQL/s':>8}")
    for st, ok, tot, per_order, kinds in summary:
        print(f"  {st['i']:>3} {st['target']:>6.0f} {ok:>6d} {per_order:>8.2f} "
              f"{kinds['driver']/ok if ok else 0:>7.2f} {kinds['read']/ok if ok else 0:>6.2f} "
              f"{kinds['write']/ok if ok else 0:>6.2f} {tot/st['dur'] if st['dur'] else 0:>8.0f}")
    return summary


if __name__ == "__main__":
    dirs = sys.argv[1:]
    if not dirs:
        print(__doc__)
        sys.exit(2)
    for d in dirs:
        report(d)
