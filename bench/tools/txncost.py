#!/usr/bin/env python3
"""txncost.py —— 「2.73 个事务/单」的拆解与对账（只读分析，不改任何东西）

两种模式：
  ./txncost.py <run_dir>          # 定速轮：snap_before/snap_after 紧邻前后差 + 订单数（来自 samples.csv）
  ./txncost.py --probe <dir>      # 探针：history_long 的「事务 → 语句」归因，逐事务拆 pre/语句/间隙/提交

为什么要「紧邻快照」而不是 CSV 采样：P_S 的 digest 聚合表会被回收/Q清空，首末行做差会算出
天文数字（上一轮实测出过 2800 万条语句）。只有窗口紧邻的前后两次快照相减才有意义。
"""
import sys, os, csv, json
from collections import defaultdict, Counter

PS = 1e9  # picoseconds per ms

# ------------------------------------------------------------------ 通用
def read_snap(path):
    txn = {}          # state -> [count, timer]
    stmt_total = None
    stat = {}
    waits = {}
    digest = {}
    epoch_ms = None
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if not line:
            continue
        if line.startswith('#epoch_ms='):
            epoch_ms = int(line.split('=')[1]); continue
        f = line.split('\t')
        if f[0] == 'TXN':
            txn[f[1]] = [int(f[2]), int(f[3])]
        elif f[0] == 'STMT_TOTAL':
            stmt_total = dict(count=int(f[1]), wait=int(f[2]), lock=int(f[3]),
                              aff=int(f[4]), exam=int(f[5]))
        elif f[0] == 'STAT':
            stat[f[1]] = int(f[2])
        elif f[0] == 'WAIT':
            waits[f[1]] = [int(f[2]), int(f[3])]
        elif f[0] == 'DIGEST':
            digest[f[1]] = dict(count=int(f[2]), wait=int(f[3]), lock=int(f[4]),
                                aff=int(f[5]), exam=int(f[6]), text=f[7] if len(f) > 7 else '')
    return dict(epoch_ms=epoch_ms, txn=txn, stmt=stmt_total, stat=stat, waits=waits, digest=digest)


NUMF = ('count', 'wait', 'lock', 'aff', 'exam')

def diff(a, b):
    """b - a，逐键；a 里有 b 没有的键按 0 处理（P_S 行被回收时宁可少算也不出负数）"""
    out = {}
    for k, v in (b or {}).items():
        av = (a or {}).get(k)
        if isinstance(v, list):
            out[k] = [v[i] - (av[i] if av else 0) for i in range(len(v))]
        elif isinstance(v, dict):
            out[k] = {f: (v[f] - ((av or {}).get(f) or 0)) for f in NUMF if f in v}
        else:
            out[k] = v - (av or 0)
    return out


def pct(vals, p):
    if not vals: return 0.0
    s = sorted(vals)
    k = max(0, min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1)))))
    return s[k]


def load_csv(path):
    with open(path, newline='') as f:
        return list(csv.DictReader(f))

# ------------------------------------------------------------------ 定速轮
def report_run(d):
    sb, sa = read_snap(os.path.join(d, 'snap_before.txt')), read_snap(os.path.join(d, 'snap_after.txt'))
    steps = load_csv(os.path.join(d, 'steps.csv'))
    t0 = int(float(steps[0]['epoch_start']))          # measureOrigin 的墙上时刻
    w0, w1 = sb['epoch_ms'], sa['epoch_ms']
    dur = (w1 - w0) / 1000.0
    samples = load_csv(os.path.join(d, 'samples.csv'))
    lat, ok = [], 0
    for r in samples:
        planned_abs = t0 + float(r['planned_ms'])
        if not (w0 <= planned_abs < w1):
            continue
        if r['ok'] == '1':
            ok += 1; lat.append(float(r['latency_ms']))
    if not ok:
        print('✗ 窗口内没有成功订单'); return
    oks = ok / dur

    txn = diff(sb['txn'], sa['txn'])
    txn_cnt = sum(v[0] for v in txn.values())
    txn_wait = sum(v[1] for v in txn.values()) / PS
    st = diff(sb['stmt'], sa['stmt'])
    if st is None or 'count' not in st:
        print('✗ 快照里没有 STMT_TOTAL（快照失败？）'); return
    stat = diff(sb['stat'], sa['stat'])
    waits = diff(sb['waits'], sa['waits'])

    per = lambda x: x / ok
    print(f"== 窗口 {dur:.1f}s  成功 {ok} 单（{oks:.1f} ok/s 窗口平均）==")
    print(f"  延迟（窗口内 ok）：p50={pct(lat,50):.1f} p95={pct(lat,95):.1f} p99={pct(lat,99):.1f} ms")
    print(f"  事务数/单      = {per(txn_cnt):.3f}   事务墙钟/单 = {per(txn_wait):.3f} ms")
    print(f"  语句数/单      = {per(st['count']):.3f}   SQL服务时间/单 = {per(st['wait'])/PS:.3f} ms"
          f"   语句内 Lock_time/单 = {per(st['lock'])/PS:.3f} ms")
    txn_wait_ps = txn_wait * PS
    print(f"  非SQL时间/单   = {per(txn_wait_ps - st['wait'])/PS:.3f} ms"
          f"（占 {100.0*(txn_wait_ps-st['wait'])/txn_wait_ps:.1f}%）")
    print(f"  Com_commit/单  = {per(stat.get('Com_commit',0)):.3f}  Com_begin/单 = {per(stat.get('Com_begin',0)):.3f}")
    print(f"  行锁等待/单    = {per(stat.get('Innodb_row_lock_time',0)):.3f} ms"
          f"（次数/单 {per(stat.get('Innodb_row_lock_waits',0)):.3f}）")
    print(f"  redo fsync/单  = {per(stat.get('Innodb_os_log_fsyncs',0)):.4f}"
          f"  data fsync/单 = {per(stat.get('Innodb_data_fsyncs',0)):.4f}"
          f"  fsync/s = {stat.get('Innodb_os_log_fsyncs',0)/dur:.0f}")
    print(f"  threads_running 末值={stat.get('Threads_running','?')}  created/单={per(stat.get('Threads_created',0)):.4f}")
    # I/O 等待（日志文件 write/flush/sync 的真实耗时）
    logf = {k: v for k, v in waits.items() if 'innodb_log_file' in k}
    if logf:
        tot = sum(v[1] for v in logf.values()) / PS
        print(f"  日志文件 I/O 等待/单 = {per(tot):.3f} ms（{dur:.0f}s 内共 {tot:.0f} ms）")
        for k, v in sorted(logf.items()):
            print(f"      {k:52s} 次数/单={per(v[0]):7.4f}  {per(v[1])/PS:8.4f} ms/单")
    # 每单语句构成
    dg = diff(sb['digest'], sa['digest'])
    texts = {k: v.get('text', '') for k, v in sa['digest'].items()}
    rows = sorted(((v['count'], v['wait'], texts.get(k, '')) for k, v in dg.items()), reverse=True)
    print('  -- 语句构成（条/单，ms/单）--')
    for c, w, t in rows[:16]:
        if c <= 0: continue
        print(f"     {per(c):7.3f} 条/单  {per(w)/PS:7.4f} ms/单  {t[:78]}")
    # mysql.csv：稳态段（窗口内）的平均 Threads_running / row lock
    try:
        my = load_csv(os.path.join(d, 'mysql.csv'))
        seg = [r for r in my if w0/1000 - 2 <= int(r['epoch']) <= w1/1000 + 2]
        if seg:
            avg = lambda k: sum(float(r[k]) for r in seg) / len(seg)
            print(f"  [窗口内采样] Threads_running 均={avg('threads_run'):.1f}  "
                  f"Threads_connected 均={avg('threads_conn'):.1f}")
    except Exception:
        pass
    # MySQL 自己的核数（cgroup usage_usec 增量）
    try:
        mc = load_csv(os.path.join(d, 'mycpu.csv'))
        seg = [r for r in mc if w0 <= int(r['epoch_ms']) <= w1]
        if len(seg) > 1:
            du = int(seg[-1]['usage_usec']) - int(seg[0]['usage_usec'])
            dt = (int(seg[-1]['epoch_ms']) - int(seg[0]['epoch_ms'])) / 1000.0
            print(f"  MySQL 核数（窗口内）={du/1e6/dt:.2f} 核")
    except Exception:
        pass
    json.dump(dict(dur=round(dur, 2), ok=ok, ok_s=round(oks, 2),
                   p50=round(pct(lat, 50), 1), p95=round(pct(lat, 95), 1), p99=round(pct(lat, 99), 1),
                   txn_per_order=round(per(txn_cnt), 3), txn_ms_per_order=round(per(txn_wait), 3),
                   stmt_per_order=round(per(st['count']), 3), sql_ms_per_order=round(per(st['wait'])/PS, 3),
                   nonsql_ms_per_order=round(per(txn_wait_ps - st['wait'])/PS, 3),
                   com_commit_per_order=round(per(stat.get('Com_commit', 0)), 3),
                   rowlock_ms_per_order=round(per(stat.get('Innodb_row_lock_time', 0)), 3),
                   logfsync_per_order=round(per(stat.get('Innodb_os_log_fsyncs', 0)), 4),
                   logio_ms_per_order=round(sum(v[1] for v in logf.values())/PS/ok, 3) if logf else None),
              open(os.path.join(d, 'summary.json'), 'w'), indent=1)

# ------------------------------------------------------------------ 探针
def read_tsv(path, tag, n):
    rows = []
    for line in open(path, encoding='utf-8', errors='replace'):
        f = line.rstrip('\n').split('\t')
        if f and f[0] == tag:
            rows.append(f[1:])
    return rows

def classify(stmts):
    txt = ' '.join(x['text'] for x in stmts)
    first = stmts[0]['text'] if stmts else ''
    if 'INSERT INTO `order`' in txt:
        return 'MAIN 下单事务'
    if len(stmts) == 1 and first.startswith('SELECT') and 'order_item' in first:
        return 'AUTOCOMMIT 明细回读'
    if all(x['text'].startswith('SET ') or x['text'].startswith('SELECT @@') for x in stmts):
        return 'AUTOCOMMIT 会话状态'
    if all(x['text'].startswith('SELECT') for x in stmts):
        return 'AUTOCOMMIT 只读查询'
    return 'AUTOCOMMIT 其它'

def report_probe(d):
    tx = read_tsv(os.path.join(d, 'txn.txt'), 'TXN', 10)
    st = read_tsv(os.path.join(d, 'stmt.txt'), 'STMT', 12)
    # txn 行：[thread, event_id, state, timer_wait, timer_start, timer_end, iso, access]
    #   （dump 的 SQL 只选这 8 列；txncost.sh 的 cmd_dump 里若加列，这里要同步）
    TX = {}
    for f in tx:
        TX[(f[0], f[1])] = dict(state=f[2], wait=int(f[3]), start=int(f[4]), end=int(f[5]),
                                iso=f[6], access=f[7] if len(f) > 7 else '-', n=None)
    # stmt 行：[thread, event_id, nest_id, nest_type, wait, start, end, lock, aff, sent, schema, text]
    S = defaultdict(list)
    for f in st:
        if f[10] not in ('qiqi_mall_test',):     # 只认业务库的语句（采样器自己的 SCHEMA 为 NULL）
            continue
        S[(f[0], f[2])].append(dict(wait=int(f[4]), start=int(f[5]), end=int(f[6]), lock=int(f[7]),
                                    aff=int(f[8]), sent=int(f[9]), schema=f[10], text=f[11]))
    shapes = defaultdict(lambda: dict(n_txn=0, dur=0, stmt=0, gap=0, pre=0, commit=0, n_stmt=0, samples=Counter()))
    orders = 0
    cands = []
    for k, t in TX.items():
        sl = sorted(S.get(k, []), key=lambda x: x['start'])
        if not sl:
            continue
        shape = classify(sl)
        g = shapes[shape]
        g['n_txn'] += 1
        ssum = sum(x['wait'] for x in sl)
        gap = sum(max(0, sl[i+1]['start'] - sl[i]['end']) for i in range(len(sl)-1))
        pre = max(0, sl[0]['start'] - t['start'])
        commit = max(0, t['end'] - sl[-1]['end'])
        g['dur'] += t['wait']; g['stmt'] += ssum; g['gap'] += gap; g['pre'] += pre
        g['commit'] += commit; g['n_stmt'] += len(sl)
        g['samples'][tuple(sorted({x['text'][:60] for x in sl}))[:3]] += 1
        if shape.startswith('MAIN'):
            orders += 1
            cands.append((t, sl, gap, pre, commit, ssum))
    if not orders:
        print('✗ 没找到主事务'); return
    print(f"== 探针：主事务（含 INSERT INTO `order`）{orders} 个 = 订单数；事务事件总数 {len(TX)} ==")
    print(f"   事务/单 = {len(TX)/orders:.3f}")
    print(f"   {'事务形状':22s} {'次/单':>7s} {'平均墙钟':>9s} {'语句和':>8s} {'语句间隙':>9s} {'开始前':>7s} {'提交':>7s} {'语句数':>6s}")
    tot = dict(dur=0, stmt=0, gap=0, pre=0, commit=0)
    for name, g in sorted(shapes.items(), key=lambda kv: -kv[1]['n_txn']):
        n = g['n_txn']
        print(f"   {name:22s} {n/orders:7.2f} {g['dur']/n/PS:8.3f}ms {g['stmt']/n/PS:7.3f}ms "
              f"{g['gap']/n/PS:8.3f}ms {g['pre']/n/PS:6.3f}ms {g['commit']/n/PS:6.3f}ms {g['n_stmt']/n:6.2f}")
        for k in tot: tot[k] += g[k]
    print(f"   {'合计（每单）':22s} {len(TX)/orders:7.2f} {tot['dur']/orders/PS:8.3f}ms "
          f"{tot['stmt']/orders/PS:7.3f}ms {tot['gap']/orders/PS:8.3f}ms "
          f"{tot['pre']/orders/PS:6.3f}ms {tot['commit']/orders/PS:6.3f}ms")
    print()
    print('== 主事务逐语句（取**中位墙钟**那条，避免冷启动样本）==')
    cands.sort(key=lambda c: c[0]['wait'])
    t, sl, gap, pre, commit, ssum = cands[len(cands)//2]
    print(f"   事务 T{sl[0]['start']} … 墙钟 {t['wait']/PS:.3f} ms，语句 {len(sl)} 条共 {ssum/PS:.3f} ms，"
          f"提交 {commit/PS:.3f} ms，事务开始到第一条语句 {pre/PS:.3f} ms")
    prev_end = None
    for i, x in enumerate(sl):
        g = '' if prev_end is None else f"   ← 间隙 {(x['start']-prev_end)/PS:6.3f} ms"
        print(f"   {i+1:2d}. {x['wait']/PS:7.3f} ms  lock={x['lock']/PS:5.3f} aff={x['aff']:2d}  {x['text'][:86]}{g}")
        prev_end = x['end']
    print(f"    (末条之后到事务结束 = 提交 {commit/PS:.3f} ms)")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    if sys.argv[1] == '--probe':
        report_probe(sys.argv[2])
    else:
        report_run(sys.argv[1])
