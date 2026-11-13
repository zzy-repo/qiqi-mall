#!/usr/bin/env python3
# compare.py —— 新旧两法对比：实验 A（墙上时间）+ 实验 B（同负载水平可信度）
#
#   python3 compare.py <bench>/oldout/timings.csv <bench>/out/summary.csv
#
# 已知缺陷（v1，2026-11-13 首轮 A/B 时暴露）：饱和档 summary.csv 的 little_err_pct 为空，
# 旧版直接 float('') 在逐档配对阶段抛出 ValueError，整张对比表只输出到第 5 档。
# 本版对空值做了 n/a 降级处理，其余逻辑保持原样。
#
# 口径：TPS 一律用 ok/s（有效吞吐）。旧法 p99 来自 LoadGen 的测量窗口；新法 p99 来自原始样本切片。

import csv
import os
import re
import sys


OLD_CSV = sys.argv[1] if len(sys.argv) > 1 else 'oldout/timings.csv'


def rd(p):
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return list(csv.DictReader(f))


def env_series(D=None):
    D = D or os.path.dirname(os.path.abspath(OLD_CSV))
    for f in ('env2.csv', 'env.csv'):
        rows = rd(f'{D}/oldout/{f}')
        out = []
        for r in rows:
            try:
                out.append((int(r['epoch']), float(r['load1'])))
            except Exception:
                pass
        if out:
            return out
    return []


def fnum(x, d=None):
    try:
        return float(x)
    except Exception:
        return None


def l1disp(r, ser=None):
    ser = ser if ser is not None else env_series()
    v = r.get('load1_max')
    if fnum(v) is not None:
        return v
    p = [x[1] for x in ser if int(r['t_start']) <= x[0] <= int(r['t_end'])]
    return f"{max(p):.2f}" if p else "n/a"


def main():
    oldraw = rd(sys.argv[1])
    new = [r for r in rd(sys.argv[2]) if r.get('ok_rps')]
    old = []
    for r in oldraw:
        if r.get('tier', '').startswith('TOTAL'):
            continue
        v = r.get('verdict', '')
        if v not in ('OK', 'DATURBED'):
            continue
        if fnum(r.get('ok_rps')) is None:
            continue
        old.append(r)
    # 同一档多次（重跑）只留最后一次采用
    best = {}
    for r in old:
        best[r['tier']] = r
    old = [best[k] for k in sorted(best, key=lambda t: int(re.sub(r'\D', '', t) or 0))]

    print("== 实验 A：旧法（闭环，每档独立一场）真实打点 ==")
    print(f"{'tier':>5} {'conc':>5} {'wall_s':>7} {'reset':>6} {'seed':>5} {'jvm+run':>8} {'ok/s':>7} {'req/s':>7} "
          f"{'p50':>7} {'p95':>8} {'p99':>9} {'load1(max)':>10} {'verdict':>10}")
    tot_wall = 0
    for r in old:
        tot_wall += int(float(r['wall_s']))
        print(f"{r['tier']:>5} {r['concurrency']:>5} {r['wall_s']:>7} {r['reset_s']:>6} {r['seed_s']:>5} "
              f"{r['jvm_start_s']:>8} {float(r['ok_rps']):>7.1f} {float(r['req_rps']):>7.1f} "
              f"{float(r['p50_ms']):>7.1f} {float(r['p95_ms']):>8.1f} {float(r['p99_ms']):>9.1f} "
              f"{l1disp(r):>10} {r['verdict']:>10}")
    if old:
        print(f"旧法 {len(old)} 档墙上时间合计 = {tot_wall}s = {tot_wall/60:.1f} min；平均每档 {tot_wall/len(old):.0f}s")

    print()
    print("== 实验 B：新法（开环阶梯，一个进程跑完）==")
    print(f"{'step':>5} {'target':>7} {'ok/s':>7} {'达成%':>6} {'p50':>7} {'p95':>8} {'p99':>9} {'infl':>6} "
          f"{'L_err%':>7} {'sat':>4} {'dstrb':>5} {'load1(max)':>10}")
    for r in new:
        le = f"{float(r['little_err_pct']):7.1f}" if r['little_err_pct'] != '' else "n/a(drop)"
        print(f"{r['step']:>5} {float(r['target_rps']):>7.0f} {float(r['ok_rps']):>7.1f} "
              f"{float(r['ok_ratio_pct']):>6.1f} {float(r['p50']):>7.1f} {float(r['p95']):>8.1f} "
              f"{float(r['p99']):>9.1f} {float(r['inflight_mean']):>6.2f} "
              f"{le:>9} {r['saturated']:>4} {r.get('disturbed','-'):>5} {r.get('load1_max','-'):>10}")

    print()
    print("== 逐档配对（新法 target ≙ 旧法该档 ok/s）==")
    print(f"{'old_tier':>8} {'old_ok/s':>9} {'new_step':>8} {'new_target':>10} {'new_ok/s':>9} {'TPS差%':>7} "
          f"{'old_p99':>8} {'new_p99':>8} {'p99差%':>7}  判读")
    for r in old:
        o = float(r['ok_rps'])
        best_r = min(new, key=lambda n: abs(float(n['target_rps']) - o))
        nt = float(best_r['ok_rps'])
        dtps = 100.0 * (nt - o) / o
        op99, np99 = fnum(r.get('p99_ms')), fnum(best_r.get('p99'))
        if op99 is None or np99 is None or op99 == 0:
            print(f"{r['tier']:>8} {o:>9.1f} {best_r['step']:>8} {float(best_r['target_rps']):>10.0f} {nt:>9.1f} "
                  f"{dtps:>+7.1f} {'n/a':>8} {'n/a':>8} {'n/a':>7}  该档 p99 缺失（饱和档无稳态分位），跳过 p99 对比")
            continue
        dp99 = 100.0 * (np99 - op99) / op99
        if abs(dtps) <= 5.0:
            verdict = "同负载可比(±5%)→" + ("新法 p99 ≥ 旧法 ✅" if dp99 >= 0 else "⚠新法 p99 更低(计时起点可疑)")
        else:
            verdict = f"负载水平不同(差 {dtps:+.1f}%)"
        print(f"{r['tier']:>8} {o:>9.1f} {best_r['step']:>8} {float(best_r['target_rps']):>10.0f} {nt:>9.1f} "
              f"{dtps:>+7.1f} {op99:>8.1f} {np99:>8.1f} {dp99:>+7.1f}  {verdict}")

    # ---- 墙上时间对比（读 bench.sh 的 timings.txt）+ lock 窗口
    tpath = os.path.join(os.path.dirname(sys.argv[2]), 'timings.txt')
    new_wall = None
    if os.path.exists(tpath):
        txt = open(tpath).read()
        m = re.search(r'T_total_wall\s*=\s*(\d+)s', txt)
        if m:
            new_wall = int(m.group(1))
        m2 = re.search(r'T_bench_start\s*=\s*(\d+)', txt)
        m3 = re.search(r'T_bench_end\s*=\s*(\d+)', txt)
        if m2 and m3:
            new_bench = int(m3.group(1)) - int(m2.group(1))
        else:
            new_bench = None
    print()
    print("== 实验 A 结论：墙上时间 ==")
    if old:
        print(f"旧法 7 档合计（含 reset/seed/JVM 冷启动/预热/计时/收尾）= {tot_wall}s = {tot_wall/60:.1f} min")
    if new_wall is not None:
        print(f"新法整段（含唯一一次 reset + 唯一一次预热 + 阶梯 + 清理 + 聚合）= {new_wall}s = {new_wall/60:.1f} min")
        if new_bench:
            mw = re.search(r'其中预热\s*(\d+)\s*s', txt or '')
            warm = int(mw.group(1)) if mw else 60
            print(f"  其中压测段（预热+阶梯）= {new_bench}s = {new_bench/60:.1f} min；纯测量段（阶梯，不含 {warm}s 预热）= {new_bench-warm}s")
        if old:
            r_all = tot_wall / new_wall if new_wall else 0
            r_bench = tot_wall / new_bench if new_bench else 0
            print(f"整段提效 {r_all:.2f}× ；按压测段口径提效 {r_bench:.2f}× ；"
                  f"省 {tot_wall-new_wall}s = {(tot_wall-new_wall)/60:.1f} min")
            print(f"判据（新法 ≤ 旧法 1/2 才算提效）：{'✅ 达标' if r_all >= 2 else '❌ 未达标'}")


if __name__ == '__main__':
    main()
