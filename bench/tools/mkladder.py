#!/usr/bin/env python3
# mkladder.py —— 由旧法实测结果生成新法阶梯（保证"同一负载水平"可逐档对位）
#
#   python3 mkladder.py <bench>/oldout/timings.csv
#   → 100,127,126,130,161,167,183,250   （示例，实际取 ok/s）
#
# 规则：
#   ① 每档取 ok/s（有效吞吐，不看 requests/s）
#   ② 闭环在饱和区可能非单调 → 取「到该档为止的最大值」保证阶梯单调不减
#   ③ 相邻目标差 <2% 的合并（重复目标没有信息量）
#   ④ 末尾追加 1.35× 最高档的超载级 —— 这是闭环压测器**根本表达不出来**的负载水平，
#      专门用来暴露饱和点与 coordinated omission 掩盖的长尾

import csv
import math
import sys


def main():
    rows = [r for r in csv.DictReader(open(sys.argv[1]))
            if r.get('verdict') in ('OK', 'DATURBED') and r.get('ok_rps') not in (None, '')]
    best = {}
    for r in rows:
        best[r['tier']] = r
    rows = sorted(best.values(), key=lambda r: int(r['concurrency']))
    vals = [float(r['ok_rps']) for r in rows]
    mono = []
    for v in vals:
        mono.append(round(max(mono[-1], v) if mono else v))
    ded = []
    for v in mono:
        if not ded or v > ded[-1] * 1.02:
            ded.append(v)
    ded.append(int(math.ceil(ded[-1] * 1.35 / 10.0) * 10))
    print(','.join(str(x) for x in ded))


if __name__ == '__main__':
    main()
