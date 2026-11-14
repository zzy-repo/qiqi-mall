#!/usr/bin/env python3
"""outside5_agg.py —— 把 results/<round>/run.log 的关键指标聚成一张表（多轮取中位）。"""
import re, sys, os
from statistics import median

FIELDS = [
    ('p50',   r'p50=([\d.]+) p95'),
    ('p95',   r'p95=([\d.]+) p99'),
    ('p99',   r'p99=([\d.]+) ms'),
    ('txn',   r'事务数/单\s+=\s*([\d.]+)'),
    ('txwall',r'事务墙钟/单 = ([\d.]+)'),
    ('stmt',  r'语句数/单\s+=\s*([\d.]+)'),
    ('sql',   r'SQL服务时间/单 = ([\d.]+)'),
    ('lock',  r'行锁等待/单\s+=\s*([\d.]+)'),
    ('nonsql',r'非SQL时间/单\s+=\s*([\d.]+)'),
    ('fsync', r'redo fsync/单\s+=\s*([\d.]+)'),
]

def read(path):
    try:
        txt = open(path, errors='replace').read()
    except FileNotFoundError:
        return None
    out = {}
    for k, pat in FIELDS:
        m = re.search(pat, txt)
        out[k] = float(m.group(1)) if m else None
    return out if out.get('p50') else None

def main(root, labels):
    rows = {}
    for lab in labels:
        rs = []
        for d in sorted(os.listdir(root)):
            # 目录名以 lab- 开头才算（base-300-r2 / after-300-r2 / after-300-r2-18080 分开）
            if not re.match(re.escape(lab) + r'-(r\d|r\d-r\d)', d) and not d.startswith(lab + '-'):
                continue
            m = read(os.path.join(root, d, 'run.log'))
            if m:
                rs.append((d, m))
        if not rs:
            continue
        rows[lab] = rs
        print(f'\n=== {lab} ===')
        print(f'{"run":<26}' + ''.join(f'{k:>9}' for k, _ in FIELDS))
        for d, m in rs:
            print(f'{d:<26}' + ''.join(f'{m[k]:>9.2f}' if m[k] is not None else f'{"-":>9}' for k, _ in FIELDS))
        med = {k: median([m[k] for _, m in rs if m[k] is not None]) for k, _ in FIELDS}
        print(f'{"中位":<24}' + ''.join(f'{med[k]:>9.2f}' for k, _ in FIELDS))
        rows[lab] = med
    if len(labels) >= 2:
        a, b = labels[0], labels[1]
        if a in rows and b in rows and isinstance(rows[a], dict):
            print(f'\n=== Δ（{b} − {a}）===')
            for k, _ in FIELDS:
                d = rows[b][k] - rows[a][k]
                pct = (d / rows[a][k] * 100) if rows[a][k] else 0
                print(f'  {k:<8} {rows[a][k]:8.2f} → {rows[b][k]:8.2f}   Δ={d:+7.2f}  ({pct:+6.1f}%)')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
