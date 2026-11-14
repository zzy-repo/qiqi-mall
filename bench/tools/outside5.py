#!/usr/bin/env python3
"""outside5.py —— 「事务之外那 5 ms」四份拆解的取数工具。

两个子命令：

  samples <run目录> <warmup_s> <dur_s>
     从 gen.mjs 的 samples.csv 直接算 p50/p95/p99（**不筛 ok**）——
     框架探针（items=[] 提前返回）在服务端是 200+code=1，gen.mjs 记成 ok=0，
     txncost.py 只统计 ok，所以探针臂必须由这里算延迟。

  nginx <startByte> <endByte>
     从网关 qiqi-access.log 的字节切片里读 rt=$request_time / urt=$upstream_response_time，
     只取 POST /portal/orders；输出 rt、urt、rt-urt 的 p50（= 网关自身处理 + 回写客户端的开销）。
     口径：urt = nginx 等上游的时间；rt = nginx 从收到首字节到发完响应的总时间。
"""
import csv
import sys
from statistics import median


def pct(xs, p):
    if not xs:
        return float('nan')
    xs = sorted(xs)
    k = (len(xs) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def cmd_samples(d, warmup_s, dur_s):
    # gen.mjs 的 planned_ms 以**测量档起点**为零点（预热段不计入 samples），
    # 所以窗口是 [settle_ms, dur_s*1000)；warmup_s 这里复用为「先丢掉多少秒」的稳定期。
    lo, hi = float(warmup_s) * 1000.0, float(dur_s) * 1000.0
    lat, okc, badc = [], 0, 0
    with open(f'{d}/samples.csv', newline='') as f:
        for row in csv.DictReader(f):
            t = float(row['planned_ms'])
            if t < lo or t >= hi:
                continue
            lat.append(float(row['latency_ms']))
            if row['ok'] == '1':
                okc += 1
            else:
                badc += 1
    print(f'  窗口 [{lo:.0f},{hi:.0f})ms  样本 {len(lat)}  ok={okc} notok={badc}')
    print(f'  p50={median(lat):.2f} p90={pct(lat,90):.2f} p95={pct(lat,95):.2f} '
          f'p99={pct(lat,99):.2f} mean={sum(lat)/len(lat):.2f} ms')


def cmd_nginx(path):
    rt, urt, gap = [], [], []
    with open(path, errors='replace') as f:
        for line in f:
            if 'POST /portal/orders HTTP' not in line or 'rt=' not in line:
                continue
            try:
                r = float(line.rsplit('rt=', 1)[1].split()[0])
                u = float(line.rsplit('urt=', 1)[1].split()[0])
            except (IndexError, ValueError):
                continue
            rt.append(r * 1000.0)
            urt.append(u * 1000.0)
            gap.append((r - u) * 1000.0)
    print(f'  样本 {len(rt)}')
    for name, xs in (('rt  (nginx 侧总时间)', rt), ('urt (等上游)', urt), ('rt-urt (网关自身+回写)', gap)):
        print(f'  {name:<24} p50={median(xs):7.3f}  p10={pct(xs,10):7.3f}  p90={pct(xs,90):7.3f}  mean={sum(xs)/len(xs):7.3f} ms')


if __name__ == '__main__':
    if sys.argv[1] == 'samples':
        cmd_samples(sys.argv[2], sys.argv[3], sys.argv[4])
    elif sys.argv[1] == 'nginx':
        cmd_nginx(sys.argv[2])
    else:
        sys.exit('usage: outside5.py samples <dir> <warmup_s> <dur_s> | nginx <logfile>')
