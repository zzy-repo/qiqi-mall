#!/usr/bin/env python3
# analyze.py —— 开环阶梯压测 → 每档一行汇总 + 环境列 + 稳态判定 + 图
#
#   python3 analyze.py <rundir>
#
# 输入：steps.csv(含 epoch 边界)、samples.csv、persecond.csv、run.json、env.csv(可选)
# 输出：summary.csv、steady.csv、curve.png
#
# 口径（重要）：
#   · TPS 一律用 **ok/s**（有效吞吐），不用 requests/s —— 失败请求不算吞吐。
#   · 每档 p50/p95/p99 从 samples.csv 原始样本切（不用"每秒分位数再平均"，会低估 p99）。
#   · Little's Law：L ≈ ok_rps × W；L 取该档 inflight 的时间加权均值（gen.mjs 精确积分）。
#   · 环境列：该档窗口内 load1 的 min/中位/max 与 MemAvailable 最小值；
#     某档 load1 峰值 > 本轮各档峰值中位数 × 1.5 ⇒ disturbed=1（受扰，数字要打折看）。

import csv
import json
import os
import sys

try:
    from PIL import Image, ImageDraw
    HAVE_PIL = True
except Exception:
    HAVE_PIL = False


def rd(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def pct(sv, p):
    n = len(sv)
    if n == 0:
        return 0.0
    if n == 1:
        return sv[0]
    idx = (p / 100.0) * (n - 1)
    lo, hi = int(idx), min(int(idx) + 1, n - 1)
    return sv[lo] if lo == hi else sv[lo] + (sv[hi] - sv[lo]) * (idx - lo)


def med(v):
    if not v:
        return 0.0
    s = sorted(v)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def cv(vals):
    n = len(vals)
    if n < 2:
        return 0.0
    m = sum(vals) / n
    if m == 0:
        return 0.0
    var = sum((v - m) ** 2 for v in vals) / (n - 1)
    return (var ** 0.5) / m * 100.0


def main():
    rundir = sys.argv[1]
    steps = rd(os.path.join(rundir, 'steps.csv'))
    ps = rd(os.path.join(rundir, 'persecond.csv'))
    run = json.load(open(os.path.join(rundir, 'run.json')))
    samples = rd(os.path.join(rundir, 'samples.csv'))
    env = rd(os.path.join(rundir, 'env.csv'))
    rds = []
    _rp = os.path.join(rundir, 'redis.csv')
    if os.path.exists(_rp):
        for line in open(_rp):
            f = line.strip().split(',')
            if len(f) == 2:
                try:
                    rds.append({'epoch': f[0], 'dbsize': f[1]})
                except Exception:
                    pass

    # 1) 原始样本按「每档实际计划窗口」切（不假设等长：饱和档超时后下一档起点会整体后移）
    per_step = [[] for _ in steps]
    win = [(float(x['start_ms']), float(x['end_ms'])) for x in steps]
    for s in samples:
        p = float(s['planned_ms'])
        if p < 0:
            continue
        for j, (a, b) in enumerate(win):
            if a <= p < b:
                per_step[j].append((float(s['latency_ms']), int(s['ok'])))
                break

    rows = []
    for i, st in enumerate(steps):
        target = float(st['target_rps'])
        dur = float(st.get('duration_ms') or (float(st['end_ms']) - float(st['start_ms']))) / 1000.0
        prow = [r for r in ps if int(r['step']) == i]
        lat = sorted(x[0] for x in per_step[i])
        ok = sum(x[1] for x in per_step[i])
        fail = len(per_step[i]) - ok
        done = len(per_step[i])
        ok_rps = ok / dur if dur else 0.0
        req_rps = done / dur if dur else 0.0
        avg = (sum(lat) / len(lat)) if lat else 0.0
        ifmean = sum(float(r['inflight_mean']) for r in prow) / len(prow) if prow else 0.0
        blmean = sum(float(r.get('backlog_mean') or 0) for r in prow) / len(prow) if prow else 0.0
        ltotal = ifmean + blmean
        ifpeak = max((int(r['inflight_peak']) for r in prow), default=0)
        cap = int(prow[0]['inflight_cap']) if prow else 0
        dropped = sum(int(r['dropped']) for r in prow)
        offered = sum(int(r['offered']) for r in prow)
        ach_ratio = 100.0 * req_rps / target if target else 0.0
        ok_ratio = 100.0 * ok_rps / target if target else 0.0
        l_pred = ok_rps * (avg / 1000.0)
        # Little's Law：L 必须把「生成器入口积压」也算进来（饱和时排队发生在那层，不在 inflight）
        l_err = 100.0 * (ltotal - l_pred) / l_pred if l_pred > 0 else 0.0

        # 环境列
        rv = []
        if rds and st.get('epoch_start'):
            a, b = float(st['epoch_start']) / 1000.0, float(st['epoch_end']) / 1000.0
            for e in rds:
                try:
                    t = float(e['epoch'])
                except Exception:
                    continue
                if a <= t <= b and e.get('dbsize') not in (None, ''):
                    rv.append(float(e['dbsize']))
        l1, mem = [], []
        if env and st.get('epoch_start'):
            a, b = float(st['epoch_start']) / 1000.0, float(st['epoch_end']) / 1000.0
            for e in env:
                t = float(e['epoch'])
                if a <= t <= b:
                    l1.append(float(e['load1']))
                    mem.append(float(e['MemAvailable_kB']))
        # 10s 滑动窗 CV<5% ⇒ 进稳态的秒数
        tps_series = [float(r['achieved_rps']) for r in prow]
        steady_t, steady_cv = None, None
        W = 10
        for t in range(W - 1, len(tps_series)):
            if cv(tps_series[t - W + 1:t + 1]) < 5.0:
                steady_t, steady_cv = t + 1, cv(tps_series[t - W + 1:t + 1])
                break

        rows.append(dict(
            step=i, target_rps=int(target),
            ok_rps=round(ok_rps, 1), req_rps=round(req_rps, 1),   # ← TPS 用 ok_rps
            achieve_ratio_pct=round(ach_ratio, 1), ok_ratio_pct=round(ok_ratio, 1),
            offered=offered, ok=ok, fail=fail, dropped=dropped,
            p50=round(pct(lat, 50), 1), p95=round(pct(lat, 95), 1),
            p99=round(pct(lat, 99), 1), max=round(lat[-1], 1) if lat else 0.0,
            avg=round(avg, 1),
            inflight_mean=round(ifmean, 2), inflight_peak=ifpeak, backlog_mean=round(blmean, 2),
            l_total=round(ltotal, 2), inflight_cap=cap,
            little_l_pred=round(l_pred, 2),
            little_err_pct=round(l_err, 1) if dropped == 0 else '',
            saturated=1 if (dropped > 0 or ach_ratio < 95.0) else 0,
            steady_t_s=steady_t if steady_t else 0,
            steady_cv_pct=round(steady_cv, 2) if steady_cv is not None else '',
            load1_min=round(min(l1), 2) if l1 else '',
            load1_med=round(med(l1), 2) if l1 else '',
            load1_max=round(max(l1), 2) if l1 else '',
            mem_min_MB=round(min(mem) / 1024.0, 0) if mem else '',
            redis_min=int(min(rv)) if rv else '', redis_max=int(max(rv)) if rv else '',
        ))

    # 受扰判定：某档 load1 峰值 > 各档峰值中位数 × 1.5
    peaks = [r['load1_max'] for r in rows if r['load1_max'] != '']
    mpeak = med(peaks) if peaks else 0
    for r in rows:
        r['disturbed'] = 1 if (peaks and r['load1_max'] != '' and r['load1_max'] > 1.5 * mpeak) else 0
    rows_all = rows
    cols = list(rows_all[0].keys())
    with open(os.path.join(rundir, 'summary.csv'), 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for r in rows_all:
            w.writerow(r)

    # steady.csv
    with open(os.path.join(rundir, 'steady.csv'), 'w', newline='') as f:
        f.write('step,target_rps,win_end_s,cv_pct\n')
        for i, st in enumerate(steps):
            tps = [float(r['achieved_rps']) for r in ps if int(r['step']) == i]
            for t in range(9, len(tps)):
                f.write(f"{i},{st['target_rps']},{t+1},{round(cv(tps[t-9:t+1]),2)}\n")

    # 文本表
    hdr = (f"{'step':>4} {'target':>7} {'ok/s':>7} {'req/s':>8} {'ok%':>6} {'ok':>7} {'fail':>5} {'drop':>5} "
           f"{'p50':>7} {'p95':>7} {'p99':>8} {'max':>8} {'infl':>6} {'backlog':>7} {'L_pred':>7} {'Lerr%':>6} "
           f"{'sat':>4} {'dstrb':>5} {'load1(min/med/max)':>22} {'steady':>10}")
    print(hdr)
    print('-' * len(hdr))
    for r in rows_all:
        st = f"{r['steady_t_s']}s/{r['steady_cv_pct']}%" if r['steady_t_s'] else '-'
        l1 = f"{r['load1_min']}/{r['load1_med']}/{r['load1_max']}" if r['load1_max'] != '' else '-'
        print(f"{r['step']:>4} {r['target_rps']:>7} {r['ok_rps']:>7.1f} {r['req_rps']:>8.1f} {r['ok_ratio_pct']:>6.1f} "
              f"{r['ok']:>7} {r['fail']:>5} {r['dropped']:>5} {r['p50']:>7.1f} {r['p95']:>7.1f} {r['p99']:>8.1f} "
              f"{r['max']:>8.1f} {r['inflight_mean']:>6.2f} {r['backlog_mean']:>7.2f} {r['little_l_pred']:>7.2f} "
              f"{(f"{float(r['little_err_pct']):>6.1f}" if r['little_err_pct'] != '' else '   n/a'):>6} "
              f"{r['saturated']:>4} {r['disturbed']:>5} {l1:>22} {st:>10}")

    sat = [r for r in rows_all if r['ok_ratio_pct'] < 95.0]
    print()
    if sat:
        print(f"饱和点：target {sat[0]['target_rps']} rps 时 ok/s 达成率 {sat[0]['ok_ratio_pct']:.1f}%"
              f"（ok/s={sat[0]['ok_rps']}，丢弃 {sat[0]['dropped']}）")
    else:
        print("饱和点：本次阶梯未触达（所有档 ok/s 达成率 ≥ 95%）")
    lerrs = [(r['step'], abs(float(r['little_err_pct']))) for r in rows_all
             if r['little_err_pct'] != '' and r['little_l_pred'] > 0]
    sk = [r['step'] for r in rows_all if r['little_err_pct'] == '']
    if lerrs:
        mx = max(x[1] for x in lerrs)
        print(f"Little's Law 自校验：{len(lerrs)} 档（L_total = inflight + 调度积压），最大偏差 {mx:.1f}%，"
              f"是否全部 ≤10%：{'是' if mx <= 10 else '否'}")
        print('   逐档: ' + ' '.join(f"step{s}={e:.1f}%" for s, e in lerrs))
    if sk:
        print(f"   另有档 {sk} 含丢弃请求，未参与判定")
    st = [(r['step'], r['steady_t_s'], r['steady_cv_pct']) for r in rows_all if r['steady_t_s']]
    if st:
        print(f"CV(TPS) 稳态判定：全部档都在 {min(x[1] for x in st)}s 就'判定稳态'——"
              f"但开环下达成 TPS 由到达率决定，这个判据测的是我们发得稳不稳，不是服务器稳不稳（见报告）")

    if HAVE_PIL:
        draw_curve(os.path.join(rundir, 'curve.png'), rows_all,
                   [float(r['achieved_rps']) for r in ps], [float(r['p99_ms']) for r in ps],
                   [float(r['p95_ms']) for r in ps], run.get('step_duration_s', 45))
        print(f"图: {os.path.join(rundir,'curve.png')}")


def draw_curve(out, rows, tps, p99, p95, stepdur):
    W, H = 1200, 700
    L, R, T, B = 90, 90, 60, 90
    pw, ph = W - L - R, H - T - B
    img = Image.new('RGB', (W, H), 'white')
    d = ImageDraw.Draw(img)
    tmax = max(1, len(tps))
    ymax_rps = max(50.0, max(tps) * 1.25, max(r['target_rps'] for r in rows) * 1.15)
    ymax_lat = max(10.0, max(p99) * 1.3)

    def X(i): return L + pw * i / max(1, tmax - 1)
    def Y1(v): return T + ph * (1 - v / ymax_rps)
    def Y2(v): return T + ph * (1 - v / ymax_lat)

    for k in range(6):
        y = T + ph * k / 5
        d.line([(L, y), (L + pw, y)], fill=(235, 235, 235))
        d.text((8, y - 6), f"{ymax_rps*(1-k/5):.0f}", fill=(90, 90, 90))
        d.text((L + pw + 8, y - 6), f"{ymax_lat*(1-k/5):.0f}", fill=(180, 90, 90))
    d.line([(L, T), (L, T + ph)], fill='black')
    d.line([(L, T + ph), (L + pw, T + ph)], fill='black')
    d.line([(L + pw, T), (L + pw, T + ph)], fill=(180, 90, 90))

    for r in rows:
        x0 = X(r['step'] * stepdur)
        x1 = X(min(tmax - 1, (r['step'] + 1) * stepdur))
        if r['step'] > 0:
            d.line([(x0, T), (x0, T + ph)], fill=(210, 210, 230))
        ty = Y1(r['target_rps'])
        for xx in range(int(x0), int(x1), 12):
            d.line([(xx, ty), (min(xx + 6, x1), ty)], fill=(120, 160, 220), width=2)
        d.text((x0 + 6, T + 6), f"{r['target_rps']}", fill=(70, 110, 190))

    d.line([(X(i), Y1(v)) for i, v in enumerate(tps)], fill=(40, 90, 200), width=2)
    d.line([(X(i), Y2(v)) for i, v in enumerate(p99)], fill=(200, 40, 40), width=2)
    d.line([(X(i), Y2(v)) for i, v in enumerate(p95)], fill=(230, 150, 40), width=1)
    d.text((L, 24), "open-loop step run: achieved TPS (blue) / target (dash) / p99 (red) / p95 (orange)", fill='black')
    d.text((L, H - 50), "time (s)", fill='black')
    d.text((10, T - 26), "TPS", fill=(40, 90, 200))
    d.text((L + pw + 8, T - 26), "ms", fill=(200, 40, 40))
    d.text((L + pw - 260, 24), "blue=achieved  red=p99  orange=p95", fill=(90, 90, 90))
    img.save(out)


if __name__ == '__main__':
    main()
