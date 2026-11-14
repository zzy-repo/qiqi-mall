#!/usr/bin/env python3
"""txncost_agg.py —— 把多轮 summary.json 汇总成中位数对照表（±噪声带）

用法： ./txncost_agg.py <root> prefix1 prefix2 ...
例：   ./txncost_agg.py bench/results/2026-11-14-txn-count base- after-

输出：每个前缀 × 每个 rps 档的轮次明细、中位数、极差(%), 以及两臂对比的差值。
为什么要中位而不是均值：这台机器噪声是**单边**的（偶发一轮 p50 翻 3 倍），均值会被那一轮带走。
"""
import sys, os, json, glob, statistics

root = sys.argv[1]
prefixes = sys.argv[2:]
KEYS = [
    ('ok_s', 'ok/s', '{:.1f}'),
    ('p50', 'p50', '{:.1f}'),
    ('p95', 'p95', '{:.1f}'),
    ('p99', 'p99', '{:.1f}'),
    ('txn_per_order', '事务/单', '{:.3f}'),
    ('txn_ms_per_order', '事务墙钟/单', '{:.2f}'),
    ('sql_ms_per_order', 'SQL/单', '{:.2f}'),
    ('nonsql_ms_per_order', '非SQL/单', '{:.2f}'),
    ('rowlock_ms_per_order', '行锁等待/单', '{:.2f}'),
    ('logio_ms_per_order', '日志I/O/单', '{:.2f}'),
]

groups = {}
for p in prefixes:
    for f in sorted(glob.glob(os.path.join(root, p + '*/summary.json'))):
        d = json.load(open(f))
        label = os.path.basename(os.path.dirname(f))
        rps = label.split('-')[-2] if label.split('-')[-2].isdigit() else '?'
        groups.setdefault((p, rps), []).append((label, d))

print(f"{'臂':10s} {'档':>4s} {'轮':>2s}  " + '  '.join(f'{n:>10s}' for _, n, _ in KEYS))
agg = {}
for (p, rps), items in sorted(groups.items()):
    for label, d in items:
        print(f"{p:10s} {rps:>4s} {'·':>2s}  " + '  '.join(
            f"{fmt.format(d.get(k, 0) or 0):>10s}" for k, _, fmt in KEYS))
    med = {}
    rng = {}
    for k, _, _ in KEYS:
        vals = [d.get(k, 0) or 0 for _, d in items]
        med[k] = statistics.median(vals)
        rng[k] = (max(vals) - min(vals)) / med[k] * 100 if med[k] else 0
    agg[(p, rps)] = (med, len(items))
    print(f"{p:10s} {rps:>4s} {'M':>2s}  " + '  '.join(f"{fmt.format(med[k]):>10s}" for k, _, fmt in KEYS))
    print(f"{'':10s} {'':>4s} {'±%':>2s}  " + '  '.join(f"{rng[k]:>10.1f}" for k, _, _ in KEYS))

# 两臂对比（同档）
if len(prefixes) == 2:
    a, b = prefixes
    print()
    print(f"{'档':>4s}  " + '  '.join(f'{n:>9s}{"Δ%":>8s}' for _, n, _ in KEYS))
    for (p, rps) in sorted(groups):
        if p != a: continue
        if (b, rps) not in agg: continue
        ma, na = agg[(a, rps)]
        mb, nb = agg[(b, rps)]
        cells = []
        for k, _, fmt in KEYS:
            va, vb = ma[k], mb[k]
            delta = (vb - va) / va * 100 if va else 0
            cells.append(f"{fmt.format(vb):>9s}{delta:+8.1f}")
        print(f"{rps:>4s}  " + '  '.join(cells))
