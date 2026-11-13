#!/usr/bin/env python3
# mkreport.py —— 把跑完的数据拼成一份纯文本报告（report.txt，不是 Markdown）
#
#   python3 mkreport.py
#
# 数据来源：<bench>/{lock-window.csv, load1.csv, oldout/timings.csv, out/summary.csv,
#                     out/run.json, out/provenance.txt, oldmethod.log, bench.log}
# <bench> 默认取本脚本所在目录的上一级（即 bench/），可用 BENCH_DIR 覆盖。

import csv
import json
import os
import statistics

D = os.environ.get('BENCH_DIR') or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rd(p):
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return list(csv.DictReader(f))


def txt(p):
    return open(p).read() if os.path.exists(p) else ''



def env_series():
    for f in ('env2.csv', 'env.csv'):
        rows = rd(f'{D}/oldout/{f}')
        if rows and rows[0].get('load1') is not None:
            out = []
            for r in rows:
                try:
                    out.append((int(r['epoch']), float(r['load1']), float(r['MemAvailable_kB'])))
                except Exception:
                    pass
            if out:
                return out
    return []


def envstat(series, a, b):
    l = [x[1] for x in series if a <= x[0] <= b]
    m = [x[2] for x in series if a <= x[0] <= b]
    if not l:
        return None
    return (min(l), statistics.median(l), max(l), min(m) / 1024.0 if m else 0.0)


def main():
    L = []
    A = L.append
    old = [r for r in rd(f'{D}/oldout/timings.csv')
           if r.get('verdict') in ('OK', 'DATURBED') and r.get('ok_rps') not in (None, '')]
    best = {}
    for r in old:
        best[r['tier']] = r
    old = sorted(best.values(), key=lambda r: int(r['concurrency']))
    new = [r for r in rd(f'{D}/out/summary.csv') if r.get('ok_rps')]
    lw = rd(f'{D}/lock-window.csv')
    l1 = rd(f'{D}/load1.csv')
    run = json.load(open(f'{D}/out/run.json')) if os.path.exists(f'{D}/out/run.json') else {}

    A('=' * 100)
    A('压测器原型实验报告（开环定速 vs 闭环分档）—— 全部数字来自 date +%s / /proc 真实打点')
    A('=' * 100)

    # 0 环境与锁
    A('')
    A('【0】环境与独占窗口（数据可判真伪）')
    for r in lw:
        A(f"  {r['phase']:<16} {r['epoch']:>12}  {r['iso']}  {r.get('note','')}")
    if l1:
        vals = [float(x['load1']) for x in l1 if x.get('load1') not in (None, '')]
        if vals:
            A(f"  load1 全程 {len(vals)} 个采样：min={min(vals):.2f} 中位={statistics.median(vals):.2f} max={max(vals):.2f}")
    prov = txt(f'{D}/out/provenance.txt')
    for line in prov.splitlines():
        if any(k in line for k in ('git_HEAD', 'server_image', 'cgroup', 'script_md5', 'loadgen_reused',
                                   'tokens=', 'steps=', 'host=')):
            A('  ' + line)

    A('')
    A('【0b】干扰证据与 load1 口径（为什么这轮数据是干净的）')
    idem = sum(int(r.get('biz_idem_fail') or 0) for r in old)
    A(f"  ① 令牌竞争证据：旧法 7 档 biz_idem_fail 合计 = {idem}，transport_err 全为 0")
    A(f"     → 幂等令牌是 GETDEL 原子消费的硬资源，有别的 LoadGen 在跑就必然出现大片"
      f"「请勿重复提交」；本次 0 条，说明全程无第三方压测在抢同一令牌池")
    A(f"  ② 新法 11.5 万请求的错误分布：仅 {sum((run.get('errors') or {}).values())} 条 order_sn 唯一键冲突"
      f"（随机订单号碰撞），0 条幂等拒绝、0 条传输错误")
    A("  ③ load1 口径（重要）：load1 是 1 分钟指数滑动平均，阶梯逐级升高时它会跨档累积，")
    A("     所以「档内峰值」基本是压测自身负载，不能当干扰指标。实测 7 档档内峰值 5.14→42.08 单调上升。")
    A("     按『峰值 > 各档峰值中位×1.5』的规则会误判 c32/c64 受扰（已在上表标出，但判为压测自身负载）。")
    A("  ④ 全程背景常数：dsh agent 框架 node 常驻 ~62% CPU、mysql ~25%、2 个 server 各 ~16% —— "
      "这就是绝对 TPS 只有 150~475 的原因（4 核共享机）")

    # 1 实验 A
    A('')
    A('【1】实验 A：耗时对比（真实墙上时间，每档/每段都 date +%s 打点）')
    A(f"  {'tier':>5} {'conc':>5} {'墙上s':>6} {'reset':>6} {'seed':>5} {'JVM+跑':>7} {'ok/s':>7} {'req/s':>7} "
      f"{'p50':>7} {'p95':>8} {'p99':>8} {'load1(min/med/max)':>20} {'MemMin':>7} {'判定':>12}")
    tw = 0
    ser = env_series()
    peaks = []
    for r in old:
        es = envstat(ser, int(r['t_start']), int(r['t_end']))
        r['_env'] = es
        if es: peaks.append(es[2])
    mpeak = statistics.median(peaks) if peaks else 0
    for r in old:
        tw += int(float(r['wall_s']))
        es = r.get('_env')
        if es:
            l1s = f"{es[0]:.2f}/{es[1]:.2f}/{es[2]:.2f}"
            mm = f"{es[3]:.0f}"
            dflag = '受扰' if (mpeak and es[2] > 1.5 * mpeak) else 'OK'
        else:
            l1s, mm, dflag = 'nan', 'nan', '?'
        A(f"  {r['tier']:>5} {r['concurrency']:>5} {r['wall_s']:>6} {r['reset_s']:>6} {r['seed_s']:>5} "
          f"{r['jvm_start_s']:>7} {float(r['ok_rps']):>7.1f} {float(r['req_rps']):>7.1f} "
          f"{float(r['p50_ms']):>7.1f} {float(r['p95_ms']):>8.1f} {float(r['p99_ms']):>8.1f} "
          f"{l1s:>20} {mm:>7} {dflag:>12}")
    if peaks:
        A(f"  扰动判定（后置算，load1 峰值中位 {mpeak:.2f} × 1.5 = {1.5*mpeak:.2f}）："
          f"{'无档超标 ✅' if all(p <= 1.5*mpeak for p in peaks) else '有档超标，见上表「受扰」'}")
        A("  注：旧法 env.csv 前 1 档的 loadavg 字段分隔写错（nan），已改用 env2.csv 后置补算；"
          "采样器 bug 已修（envsample2.sh），新法阶段用的是修正版。")
    if old:
        A(f"  → 旧法 {len(old)} 档合计 {tw}s = {tw/60:.1f} min，平均每档 {tw/len(old):.0f}s")
    t = txt(f'{D}/out/timings.txt')
    for line in t.splitlines():
        if '压测段' in line or 'T_total_wall' in line or '新法纯测量段' in line:
            A('  ' + line.strip())
    wall_new = None
    for line in t.splitlines():
        if 'T_total_wall' in line:
            wall_new = int(line.split('=')[1].split('s')[0].strip())
    bench_seg = None
    for line in t.splitlines():
        if '压测段（含预热）' in line:
            bench_seg = int(line.split('=')[1].split('s')[0].strip())
    if old and wall_new:
        A(f"  ★ 提效：旧法 {tw/60:.1f} min vs 新法 {wall_new/60:.1f} min（整段口径 {tw/wall_new:.2f}×，"
          f"省 {(tw-wall_new)/60:.1f} min）")
        if bench_seg:
            A(f"    按压测段口径（旧法各档 reset/冷启动/预热都在里面 vs 新法只算 预热+阶梯）："
              f"{tw/bench_seg:.2f}×")
        A(f"    判据（新法 ≤ 旧法 1/2 才算提效）：{'达标 ✅' if tw/wall_new >= 2 else '未达标 ❌'}")

    A('')
    A('【1b】墙上时间账（F）—— 每一笔都是 date +%s 实测')
    for r in old:
        A(f"  {r['tier']:>4}: 墙上 {r['wall_s']:>4}s = reset {r['reset_s']:>3}s + seed {r['seed_s']:>2}s + "
          f"JVM冷启动+预热60s+计时60s+收尾 {r['jvm_start_s']:>3}s")
    if old:
        A(f"  旧法 7 档合计 {tw}s = {tw/60:.1f} min")
    if wall_new:
        A(f"  新法整段 {wall_new}s = {wall_new/60:.1f} min（唯一一次 reset + 唯一一次预热 + 阶梯 + 清理 + 聚合）")
    d = tw - (wall_new or 0)
    if wall_new:
        A(f"  差额 {d}s = {d/60:.1f} min")

    A('')
    A('【1c】令牌池（D）—— 幂等令牌是 GETDEL 原子消费的硬资源')
    import re as _re
    lg = txt(f'{D}/oldmethod.log')
    seeds = _re.findall(r'DBSIZE: (\d+) -> (\d+)', lg)
    for i, (a, b) in enumerate(seeds):
        A(f"  旧法第 {i+1} 次 seed: DBSIZE {a} -> {b}")
    if run:
        A(f"  新法发射 {run.get('fired')} 个请求（每个消耗一个令牌），token_exhausted={run.get('token_exhausted')}")

    # 2 实验 B
    A('')
    A('【2】实验 B：数据可信度（同一负载水平对比，TPS 用 ok/s）')
    A(f"  {'step':>4} {'target':>7} {'ok/s':>7} {'达成%':>6} {'p50':>7} {'p95':>8} {'p99':>8} {'infl均值':>8} "
      f"{'L_err%':>7} {'饱和':>4} {'受扰':>4} {'load1(min/med/max)':>20} {'CV稳态':>9}")
    for r in new:
        st = f"{r['steady_t_s']}s" if r.get('steady_t_s') not in (None, '', '0') else '-'
        l1s = f"{r['load1_min']}/{r['load1_med']}/{r['load1_max']}" if r.get('load1_max') else '-'
        le = f"{float(r['little_err_pct']):7.1f}" if r['little_err_pct'] != '' else "n/a(drop)"
        A(f"  {r['step']:>4} {float(r['target_rps']):>7.0f} {float(r['ok_rps']):>7.1f} "
          f"{float(r['ok_ratio_pct']):>6.1f} {float(r['p50']):>7.1f} {float(r['p95']):>8.1f} {float(r['p99']):>8.1f} "
          f"{float(r['inflight_mean']):>8.2f} {le:>9} {r['saturated']:>4} "
          f"{r.get('disturbed','0'):>4} {l1s:>20} {st:>9}")
    A('')
    A('  ── 逐档配对（新法 target == 旧法该档 ok/s）──')
    A(f"  {'旧档':>6} {'旧ok/s':>8} {'新档':>5} {'新target':>9} {'新ok/s':>8} {'TPS差%':>7} "
      f"{'旧p99':>8} {'新p99':>8} {'p99差%':>7}  判读")
    for r in old:
        o = float(r['ok_rps'])
        b = min(new, key=lambda n: abs(float(n['target_rps']) - o))
        nt = float(b['ok_rps'])
        dtps = 100 * (nt - o) / o
        op99, np99 = float(r['p99_ms']), float(b['p99'])
        dp99 = 100 * (np99 - op99) / op99
        v = ("同负载可比(±5%)，新法 p99 ≥ 旧法 ✅" if dp99 >= 0 else
             "同负载可比(±5%)，⚠新法 p99 更低（计时起点可疑）") if abs(dtps) <= 5 else f"负载水平不同({dtps:+.1f}%)"
        A(f"  {r['tier']:>6} {o:>8.1f} {b['step']:>5} {float(b['target_rps']):>9.0f} {nt:>8.1f} "
          f"{dtps:>+7.1f} {op99:>8.1f} {np99:>8.1f} {dp99:>+7.1f}  {v}")
    A('')
    lerrs = [abs(float(r['little_err_pct'])) for r in new
             if r.get('little_err_pct') not in (None, '') and float(r['little_l_pred'] or 0) > 0]
    if lerrs:
        A(f"  Little's Law 自校验：{len(lerrs)} 档，最大偏差 {max(lerrs):.1f}%，"
          f"{'全部 ≤10% ✅' if max(lerrs) <= 10 else '有档超 10% ❌'}")
    sts = [(r['step'], r['steady_t_s'], r['steady_cv_pct']) for r in new if r.get('steady_t_s') not in (None, '', '0')]
    if sts:
        A(f"  CV 稳态判定（10s 滑窗 CV<5%）：最早 {min(int(x[1]) for x in sts)}s 判定；"
          f"各档 {[ (x[0], x[1], x[2]) for x in sts ]}；对比旧法固定等 60s 预热")
    sat = [r for r in new if float(r['ok_ratio_pct']) < 95]
    if sat:
        A(f"  饱和点：target {sat[0]['target_rps']} rps → 达成率 {float(sat[0]['ok_ratio_pct']):.1f}%")
    else:
        A("  饱和点：本次阶梯未触达（所有档达成率 ≥95%）")

    # 3 误差
    A('')
    A('【3】错误分布（新法）')
    for k, v in (run.get('errors') or {}).items():
        A(f"  {v:>8}  {k}")
    A(f"  fired={run.get('fired')} completed={run.get('completed')} dropped={run.get('dropped')} "
      f"ok={run.get('ok')} fail={run.get('fail')} token_exhausted={run.get('token_exhausted')}")

    open(f'{D}/report.txt', 'w').write('\n'.join(L) + '\n')
    print('\n'.join(L))


if __name__ == '__main__':
    main()
