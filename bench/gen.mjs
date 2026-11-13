#!/usr/bin/env node
// gen.mjs —— 开环定速压测生成器（open loop / fixed arrival rate）
//
// 与闭环压测器（LoadGen.java，固定并发数）的根本区别：
//   1) 按**计划到达时刻**发压：在 T 时刻该发第 N 个请求就发，不等上一个请求返回。
//   2) 延迟统计起点 = **计划发出时刻**（不是实际发出时刻）。
//      → 修 coordinated omission：服务器卡住时"本该发出"的请求，其排队时间也被计入延迟。
//   3) 阶梯升压：--steps 100,178,207,340,364,400,500 --step-duration 45
//      每级固定 RPS 跑 N 秒，同一进程内自动切下一级（无 JVM 冷启动、无 reset、无重灌令牌）。
//   4) 每秒一行明细（第二层 CSV）：t,step,step_t,target_rps,achieved_rps,ok,fail,dropped,
//      p50,p95,p99,max,avg,inflight_mean,inflight_peak,inflight_cap,saturated,lag_max
//   5) 达不到目标 RPS 时（在飞请求顶到 VU 上限 / 调度落后超过 --max-lag-ms）明确标 saturated=1。
//
// 用法（所有路径都由命令行传入，脚本内不硬编码任何机器路径）：
//   node gen.mjs --url http://127.0.0.1:18080/portal/orders --method POST \
//     --steps 100,178,207,340,364,400,500 --step-duration 45 --warmup 60 --warmup-rps 200 \
//     --token-file "$LG/tokens.txt" --auth-file "$LG/jwts.txt" \
//     --body-file "$LG/order.json" --sku-hot 1 --sku-ratio 0.8 \
//     --sku-cold-file "$LG/skus_cold.txt" --outdir ./out --label t1
//
//   注：$LG 是「数据工作目录」（令牌池 / JWT / 下单模板 / 冷门 SKU 清单），见 README「怎么跑」。
//       令牌与 JWT 是**运行时产物**，用 bench/tools/prepare.sh 生成，不入库。
//
// 输出：<outdir>/persecond.csv、<outdir>/samples.csv、<outdir>/steps.csv、<outdir>/run.json
// 退出码：0 跑完；2 参数/令牌池问题。

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const perf = () => performance.now();
const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));

// ------------------------------------------------------------------ 参数
function parseArgs(argv) {
  const a = {
    url: null, method: 'POST', steps: '100,200,300', stepDuration: 45,
    warmup: 60, warmupRps: 200, timeoutMs: 15000, tokenFile: null, authFile: null,
    bodyFile: null, skuHot: -1, skuRatio: 0.8, skuColdFile: null,
    outdir: process.env.BENCH_OUT || 'out', label: 'run',
    maxInflight: 0,        // 0 = 每级自动 = clamp(2*target_rps, 32, 4096)
    maxLagMs: 5000,        // 调度落后超过这个值 → 该计划请求丢弃并标 saturated
    spinUs: 250,           // 每次发送前最多忙等多少微秒（亚毫秒精度，其余靠 timer）
    header: [], samples: true,
  };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    const v = argv[i + 1];
    switch (k) {
      case '--url': a.url = v; i++; break;
      case '--method': a.method = v; i++; break;
      case '--steps': a.steps = v; i++; break;
      case '--step-duration': a.stepDuration = Number(v); i++; break;
      case '--warmup': a.warmup = Number(v); i++; break;
      case '--warmup-rps': a.warmupRps = Number(v); i++; break;
      case '--timeout-ms': a.timeoutMs = Number(v); i++; break;
      case '--token-file': a.tokenFile = v; i++; break;
      case '--auth-file': a.authFile = v; i++; break;
      case '--body-file': a.bodyFile = v; i++; break;
      case '--sku-hot': a.skuHot = Number(v); i++; break;
      case '--sku-ratio': a.skuRatio = Number(v); i++; break;
      case '--sku-cold-file': a.skuColdFile = v; i++; break;
      case '--outdir': a.outdir = v; i++; break;
      case '--label': a.label = v; i++; break;
      case '--max-inflight': a.maxInflight = Number(v); i++; break;
      case '--max-lag-ms': a.maxLagMs = Number(v); i++; break;
      case '--spin-us': a.spinUs = Number(v); i++; break;
      case '--header': a.header.push(v); i++; break;
      case '--no-samples': a.samples = false; break;
      default:
        if (k.startsWith('--')) { console.error('未知参数: ' + k); process.exit(2); }
    }
  }
  if (!a.url) { console.error('缺少 --url'); process.exit(2); }
  return a;
}

const A = parseArgs(process.argv.slice(2));
const STEPS = A.steps.split(',').map((s) => Number(s.trim())).filter((n) => n > 0);
if (!STEPS.length) { console.error('--steps 为空'); process.exit(2); }
const STEP_MS = A.stepDuration * 1000;
const TOKEN_TTL_LIMIT = 200000;

function readLines(f) {
  if (!f) return [];
  try {
    return fs.readFileSync(f, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean);
  } catch (e) { return []; }
}

const tokens = readLines(A.tokenFile);
const jwts = readLines(A.authFile);
const coldSkus = readLines(A.skuColdFile).map(Number).filter((n) => n > 0);
const bodyTemplate = A.bodyFile ? fs.readFileSync(A.bodyFile, 'utf8') : null;

// ------------------------------------------------------------------ 状态
let tokenIdx = 0, jwtIdx = 0, seq = 0;
let inflight = 0, inflightCap = 32;
const slotWaiters = [];
let tokenExhausted = false;
let measureOrigin = 0;               // perf() 时间轴上的测量起点
const stepStartsMs = [];             // 每级在测量时间轴上的起点
const stepEpoch = [];                // 每级计划窗口（perf 轴相对 measureOrigin，ms）
const stEpochAbs = [];               // 每级真实墙上 epoch 边界 [start,end]（ms）
let currentStep = 0;
let curStartAt = 0, curInterval = 1000, curK = 0, curRunning = false;
let dropped = 0, fired = 0, completed = 0;

// 每秒桶：延迟/成功数按"计划秒"归集；inflight 按"墙上秒"归集（稳态下两者一致）
const buckets = new Map();
function bucket(sec) {
  let b = buckets.get(sec);
  if (!b) {
    b = { lat: [], ok: 0, fail: 0, dropped: 0, offered: 0, lagMax: 0,
          ifSum: 0, ifTime: 0, ifPeak: 0, pSum: 0, pPeak: 0 };
    buckets.set(sec, b);
  }
  return b;
}

const errGroups = new Map();
const bump = (k) => errGroups.set(k, (errGroups.get(k) || 0) + 1);
const samples = [];       // [plannedMs, lagMs, latMs, ok(0/1), status]

function pickSku() {
  if (A.skuHot < 0 || !coldSkus.length) return A.skuHot >= 0 ? A.skuHot : -1;
  return Math.random() < A.skuRatio ? A.skuHot : coldSkus[(Math.random() * coldSkus.length) | 0];
}

// 连接池：open loop 的在飞量会远超闭环，maxSockets 必须给够
const u = new URL(A.url);
const agent = new http.Agent({
  keepAlive: true, maxSockets: 4096, maxFreeSockets: 256, keepAliveMsecs: 3000, scheduling: 'lifo',
});
const baseHeaders = { 'Content-Length': 0 };
for (const h of A.header) {
  const c = h.indexOf(':');
  if (c > 0) baseHeaders[h.slice(0, c).trim()] = h.slice(c + 1).trim();
}

function waitSlot(timeoutMs) {
  if (inflight < inflightCap) return null;          // 有空位，不用等
  if (timeoutMs <= 0) return Promise.resolve(false);
  // 超时也要能醒：到点仍未拿到空位 → 返回 false，由调用方丢弃该计划请求
  return Promise.race([
    new Promise((res) => slotWaiters.push(() => res(true))),
    sleep(timeoutMs).then(() => false),
  ]);
}
function releaseSlot() {
  inflight--; noteInflight();
  // 单调度线程 ⇒ 队列里最多一个等待者，醒来后由它自己 inflight++
  if (slotWaiters.length && inflight < inflightCap) slotWaiters.shift()();
}

function record(plannedMs, lagMs, latMs, ok, status, isWarmup) {
  completed++;
  if (isWarmup) return;
  const sec = Math.floor((plannedMs - measureOrigin) / 1000);
  if (sec < 0) return;
  const b = bucket(sec);
  b.lat.push(latMs);
  if (ok) b.ok++; else b.fail++;
  if (lagMs > b.lagMax) b.lagMax = lagMs;
  if (A.samples) samples.push([plannedMs - measureOrigin, lagMs, latMs, ok ? 1 : 0, status]);
}

function classify(status, bodyText) {
  let code = null, msg = null;
  const i = bodyText.indexOf('"code"');
  if (i >= 0) {
    const m = bodyText.slice(i + 6).match(/^\s*:?\s*(-?\d+)/);
    if (m) code = Number(m[1]);
  }
  const j = bodyText.indexOf('"message"');
  if (j >= 0) {
    const m = bodyText.slice(j + 9).match(/^\s*:?\s*"([^"]{0,80})"/);
    if (m) msg = m[1];
  }
  const ok = status >= 200 && status < 300 && (code === null || code === 0);
  if (!ok) {
    const key = `HTTP${status} code=${code === null ? '?' : code} msg=${msg === null ? '-' : msg}`;
    bump(key);
  }
  return ok;
}

function fire(plannedMs, isWarmup, stepIdx) {
  if (tokens.length) {
    if (tokenIdx >= tokens.length) { tokenExhausted = true; return false; }
  }
  const token = tokens.length ? tokens[tokenIdx++] : null;
  const jwt = jwts.length ? jwts[jwtIdx++ % jwts.length] : null;
  const sku = pickSku();
  const t = seq++;

  let target = A.url;
  let body = bodyTemplate;
  if (token) {
    target = target.split('{{TOKEN}}').join(token);
    if (body) body = body.split('{{TOKEN}}').join(token);
  }
  if (jwt) {
    target = target.split('{{JWT}}').join(jwt);
    if (body) body = body.split('{{JWT}}').join(jwt);
  }
  const skuStr = String(sku);
  if (body && sku >= 0) body = body.split('{{SKU}}').join(skuStr);
  if (body) body = body.split('{{SEQ}}').join(String(t));

  const headers = Object.assign({}, baseHeaders);
  if (jwt) headers['Authorization'] = 'Bearer ' + jwt;
  if (body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  const payload = body === null ? null : Buffer.from(body, 'utf8');
  if (payload) headers['Content-Length'] = payload.length;

  fired++;
  const sendAt = perf();
  const lagMs = sendAt - plannedMs;

  const req = http.request({
    protocol: u.protocol, hostname: u.hostname, port: u.port,
    path: (u.pathname || '/') + (u.search || ''), method: A.method,
    headers, agent,
  }, (res) => {
    let text = '';
    res.setEncoding('utf8');
    res.on('data', (c) => { if (text.length < 4096) text += c; });
    res.on('end', () => {
      // ★ 延迟起点 = 计划时刻（含调度滞后 + 排队），不是实际发出时刻
      const latMs = perf() - plannedMs;
      releaseSlot();
      const ok = classify(res.statusCode, text);
      record(plannedMs, lagMs, latMs, ok, res.statusCode, isWarmup);
    });
  });
  req.setTimeout(A.timeoutMs, () => req.destroy(new Error('timeout')));
  req.on('error', (e) => {
    const latMs = perf() - plannedMs;
    releaseSlot();
    if (!isWarmup) bump('TRANSPORT ' + e.message);
    record(plannedMs, lagMs, latMs, false, 0, isWarmup);
  });
  req.end(payload || undefined);
  return true;
}

// ------------------------------------------------------------------ 定速调度
// 关键：按绝对计划时刻发压；调度落后 / 在飞顶到上限 → 丢请求并标饱和，绝不"少发还装作没事"
async function runRate(rps, durMs, isWarmup, stepIdx) {
  const intervalMs = 1000 / rps;
  const plannedStartAbs = isWarmup ? perf() : measureOrigin + stepIdx * STEP_MS;
  // 若上一档超时（饱和），现在已晚于计划起点 → 本档从"现在"起，避免把上一档的拖欠算到本档头上
  const startAt = isWarmup ? plannedStartAbs : Math.max(plannedStartAbs, perf());
  const endAt = startAt + durMs;
  inflightCap = A.maxInflight > 0 ? A.maxInflight : Math.min(4096, Math.max(32, Math.round(2 * rps)));
  const spinMs = A.spinUs / 1000;
  let k = 0, droppedHere = 0;
  // 暴露给积压积分：本档起点/间隔/进度
  curStartAt = startAt; curInterval = intervalMs; curRunning = true;
  while (true) {
    const planned = startAt + k * intervalMs;
    if (planned >= endAt) break;

    // (a) 等到计划时刻（timer 粗等 + 最后 spinMs 忙等，保证到达率不塌）
    let now = perf();
    if (now < planned) {
      const remain = planned - now;
      if (remain > spinMs) await sleep(remain - spinMs);
      while ((now = perf()) < planned) { /* spin */ }
    }

    // (b) 已经落后超过上限 → 直接丢弃这条"本该发出"的请求，且**不等槽位**
    //     （否则每丢一条都要等 200ms 空位，会把本档拖长，档时长就不可比了）
    if (perf() - planned > A.maxLagMs) {
      dropped++; droppedHere++;
      if (!isWarmup) {
        const sec = Math.floor((planned - measureOrigin) / 1000);
        if (sec >= 0) bucket(sec).dropped++;
      }
      k++; curK = k;
      continue;
    }

    // (c) 在飞顶到 VU 上限 → 等空位（排队时间会算进后续请求的延迟，这正是开环要暴露的）
    //     但等待预算受 maxLag 约束：等超时就不等了，直接丢弃，避免档时长被拖长
    const budget = A.maxLagMs - Math.max(0, perf() - planned);
    const w = waitSlot(budget);
    if (w) { const got = await w; if (!got) { dropped++; droppedHere++; if (!isWarmup) { const sec = Math.floor((planned - measureOrigin) / 1000); if (sec >= 0) bucket(sec).dropped++; } k++; curK = k; continue; } }

    // (d) 等完槽位再判一次滞后
    if (perf() - planned > A.maxLagMs) {
      dropped++; droppedHere++;
      if (!isWarmup) {
        const sec = Math.floor((planned - measureOrigin) / 1000);
        if (sec >= 0) bucket(sec).dropped++;
      }
      k++; curK = k;
      continue;
    }

    if (!isWarmup) bucket(Math.floor((planned - measureOrigin) / 1000)).offered++;
    inflight++; noteInflight();
    const okFire = fire(planned, isWarmup, stepIdx);   // 完成/出错时由 releaseSlot() 归还名额
    if (!okFire) { inflight--; noteInflight(); break; }   // 令牌池耗尽
    k++; curK = k;
  }
  curRunning = false;
  if (droppedHere) console.log(`  ⚠ step ${stepIdx} 丢弃 ${droppedHere} 条（顶不住 ${rps} rps）`);
  return k;
}

// ---------------------------------------------------- inflight 精确时间加权积分
// 手段：inflight 每次变化就把 [上次变化, 现在) 这段时间按墙上秒记账（跨越秒边界则切分）。
// 这样 Little's Law 里的 L 是精确的时间平均并发，不是采样近似。
let accCursor = 0, lastVal = 0;
function accInflight(now) {
  if (!measureOrigin) { accCursor = now; return; }
  let t = accCursor;
  let guard = 0;
  while (t < now - 1e-9 && guard++ < 64) {
    const secIdx = Math.floor((t - measureOrigin) / 1000);
    const secEnd = measureOrigin + (secIdx + 1) * 1000;
    const seg = Math.min(now, secEnd);
    const dt = seg - t;
    if (secIdx >= 0 && dt > 0) {
      const b = bucket(secIdx);
      b.ifSum += lastVal * dt;
      b.ifTime += dt;
      if (lastVal > b.ifPeak) b.ifPeak = lastVal;
      const bl = backlog();
      b.pSum += bl * dt;
      if (bl > b.pPeak) b.pPeak = bl;
    }
    t = seg;
  }
  accCursor = now;
}
function noteInflight() { const n = perf(); accInflight(n); lastVal = inflight; }
// 调度积压：已到期但还没发出的请求数 = 该发多少 - 已发多少（不含丢弃）
function backlog() {
  if (!curRunning || curInterval <= 0) return 0;
  const due = Math.floor((perf() - curStartAt) / curInterval) + 1;
  // -1：正在派发中的那一条不算积压（否则低负载时会把调度抖动记成 1 个队列长度）
  return Math.max(0, due - curK - 1);
}
function startInflightSampler() {
  accCursor = perf(); lastVal = inflight;
  const timer = setInterval(() => accInflight(perf()), 100);   // 兜底：空闲段也记账
  timer.unref();
  return timer;
}

// ------------------------------------------------------------------ 分位数
function pct(sorted, p) {
  const n = sorted.length;
  if (!n) return 0;
  if (n === 1) return sorted[0];
  const idx = (p / 100) * (n - 1);
  const lo = Math.floor(idx), hi = Math.ceil(idx);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
}
const f = (x, d = 3) => Number.isFinite(x) ? Number(x.toFixed(d)) : 0;

// ------------------------------------------------------------------ main
async function main() {
  fs.mkdirSync(A.outdir, { recursive: true });
  console.log(`gen.mjs open-loop: steps=[${STEPS.join(',')}] step=${A.stepDuration}s warmup=${A.warmup}s@${A.warmupRps}rps`);
  console.log(`token pool=${tokens.length} jwt pool=${jwts.length} cold skus=${coldSkus.length} url=${A.url}`);
  if (tokens.length && tokens.length < TOKEN_TTL_LIMIT) console.log(`⚠ 令牌池 ${tokens.length} < ${TOKEN_TTL_LIMIT}`);
  const targetTotal = STEPS.reduce((s, r) => s + r * A.stepDuration, 0) + A.warmup * A.warmupRps;
  console.log(`预估计划请求数上限=${Math.round(targetTotal)}（令牌池 ${tokens.length}，够=${targetTotal <= tokens.length}）`);

  const cpuStart = process.cpuUsage();
  const startEpochMs = Date.now();

  if (A.warmup > 0) {
    console.log(`warmup ${A.warmup}s @ ${A.warmupRps} rps ...`);
    await runRate(A.warmupRps, A.warmup * 1000, true, -1);
    // 预热收尾：等在飞清空，避免污染测量窗
    while (inflight > 0) await sleep(10);
    console.log('warmup 完成，进入测量');
  }

  measureOrigin = perf();
  accCursor = measureOrigin;
  const t0Epoch = Date.now();
  const sampler = startInflightSampler();

  for (let i = 0; i < STEPS.length; i++) {
    currentStep = i;
    const s = perf();
    const eStart = Date.now();
    console.log(`[step ${i}] ${STEPS[i]} rps × ${A.stepDuration}s ...`);
    await runRate(STEPS[i], STEP_MS, false, i);
    const sStart = Math.max(measureOrigin + i * STEP_MS, s);   // 与 runRate 内的起点一致
    stepEpoch.push([sStart, sStart + STEP_MS]);                 // 计划窗口（不含超时溢出）
    stEpochAbs.push([eStart, Date.now()]);
    console.log(`[step ${i}] 实际窗口 ${((sStart - measureOrigin) / 1000).toFixed(1)}s+${A.stepDuration}s，` +
                `实耗 ${((perf() - s) / 1000).toFixed(1)}s`);
    const realMs = perf() - s;
    console.log(`[step ${i}] 计划 ${A.stepDuration}s 实耗 ${(realMs / 1000).toFixed(1)}s fired=${fired} dropped=${dropped}`);
    if (tokenExhausted) { console.error('令牌池耗尽，提前结束'); break; }
  }

  // 收尾：等在飞自然跑完（最多 25s）
  const drainDeadline = perf() + 25000;
  while (inflight > 0 && perf() < drainDeadline) await sleep(20);
  clearInterval(sampler);

  const cpu = process.cpuUsage(cpuStart);
  const wallMs = perf() - measureOrigin;
  const endEpochMs = Date.now();

  // ---- steps.csv
  const stepRows = ['index,target_rps,start_ms,end_ms,duration_ms,epoch_start,epoch_end'];
  STEPS.forEach((r, i) => {
    const [st, en] = stepEpoch[i];
    stepRows.push(`${i},${r},${f(st - measureOrigin, 1)},${f(st - measureOrigin + STEP_MS, 1)},${STEP_MS},` +
                  `${Math.round(stEpochAbs[i][0])},${Math.round(stEpochAbs[i][1])}`);
  });
  fs.writeFileSync(path.join(A.outdir, 'steps.csv'), stepRows.join('\n') + '\n');

  // ---- persecond.csv（第二层数据）
  const maxSec = Math.ceil(wallMs / 1000);
  const pc = ['t,step,step_t,target_rps,achieved_rps,offered,ok,fail,dropped,p50_ms,p95_ms,p99_ms,max_ms,avg_ms,inflight_mean,inflight_peak,backlog_mean,inflight_cap,saturated,lag_max_ms'];
  let totOk = 0, totFail = 0;
  for (let sec = 0; sec < maxSec; sec++) {
    const b = buckets.get(sec) || { lat: [], ok: 0, fail: 0, dropped: 0, offered: 0, lagMax: 0, ifSum: 0, ifTime: 0, ifPeak: 0 };
    const stepIdx = Math.min(STEPS.length - 1, Math.floor(sec / A.stepDuration));
    const stepT = sec - stepIdx * A.stepDuration;
    const target = STEPS[stepIdx];
    const s = b.lat.slice().sort((x, y) => x - y);
    const done = b.ok + b.fail;
    const sum = s.reduce((x, y) => x + y, 0);
    const achieved = done;               // 该计划秒完成（含失败）的请求数 = 达成 RPS
    const ifMean = b.ifTime > 0 ? b.ifSum / b.ifTime : 0;
    const blMean = b.ifTime > 0 ? b.pSum / b.ifTime : 0;
    const saturated = (b.dropped > 0 || achieved < target * 0.95) ? 1 : 0;
    totOk += b.ok; totFail += b.fail;
    pc.push([
      sec, stepIdx, stepT, target, achieved, b.offered, b.ok, b.fail, b.dropped,
      f(pct(s, 50)), f(pct(s, 95)), f(pct(s, 99)), f(s.length ? s[s.length - 1] : 0),
      f(done ? sum / done : 0), f(ifMean, 2), b.ifPeak, f(blMean, 2),
      A.maxInflight > 0 ? A.maxInflight : Math.min(4096, Math.max(32, Math.round(2 * target))),
      saturated, f(b.lagMax, 1),
    ].join(','));
  }
  fs.writeFileSync(path.join(A.outdir, 'persecond.csv'), pc.join('\n') + '\n');

  // ---- samples.csv（精确切档用）
  if (A.samples) {
    const lines = ['planned_ms,lag_ms,latency_ms,ok,status'];
    for (const r of samples) lines.push([f(r[0]), f(r[1]), f(r[2]), r[3], r[4]].join(','));
    fs.writeFileSync(path.join(A.outdir, 'samples.csv'), lines.join('\n') + '\n');
  }

  // ---- run.json
  const errs = [...errGroups.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12);
  const runInfo = {
    label: A.label, url: A.url, method: A.method,
    mode: 'open-loop-fixed-rate',
    steps: STEPS, step_duration_s: A.stepDuration,
    warmup_s: A.warmup, warmup_rps: A.warmupRps,
    start_iso: new Date(startEpochMs).toISOString(), end_iso: new Date(endEpochMs).toISOString(),
    measure_wall_s: f(wallMs / 1000, 3),
    fired, completed, dropped, ok: totOk, fail: totFail,
    token_pool: tokens.length, token_exhausted: tokenExhausted,
    client_cpu_pct: f(100 * (cpu.user + cpu.system) / 1000 / Math.max(1, wallMs), 2),
    loadavg_1m: Number(fs.readFileSync('/proc/loadavg', 'utf8').split(' ')[0]),
    errors: Object.fromEntries(errs),
  };
  fs.writeFileSync(path.join(A.outdir, 'run.json'), JSON.stringify(runInfo, null, 2));
  console.log(`DONE ok=${totOk} fail=${totFail} dropped=${dropped} wall=${(wallMs / 1000).toFixed(1)}s ` +
    `client_cpu=${runInfo.client_cpu_pct}% out=${A.outdir}`);
  if (tokenExhausted) process.exit(2);
}

process.on('SIGINT', () => {
  console.error('收到 SIGINT，尽力收尾...');
  process.exit(130);
});

main().catch((e) => { console.error('FATAL', e); process.exit(1); });
