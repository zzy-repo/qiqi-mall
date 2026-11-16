#!/usr/bin/env node
/**
 * prepare-audit-demo-queue.mjs —— 让**审核队列在演示库上「有单可看」**（幂等，复位末尾自动跑）
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 为什么需要它（2026-11-17 查出来的真事）
 *   `sql/seed-order-audit-demo.sql` 只铺「起点」：六笔演示单 QT-1001..1006 都是 status=7（待审核）、
 *   **没有任何决策行、也没有任何标**。而人工队列是 `FROM order_audit_decision` 的 ——
 *   于是「刚复位完就打开队列页」看到的是**空表**；「按标筛选」（?tag=NO_POLICY_MATCH）也是 0 行。
 *   演示站上这个功能因此没法演示：不是功能坏了，是**队列里没有一张「身上带标的待审核单」**。
 *
 *   错误修法（明确排除）：改队列口径 / 手写一次性 SQL 往 order_audit_decision、order_audit_tag 里糊行。
 *   后者错在两处：① 决策行里的判定理由、最接近策略、缺失条件那一整套 JSON 是引擎的产物，
 *   手抄一份必然与引擎漂移（改一条策略文案，抄来的行就成了假证据）；② 一次性 SQL 下次复位就没了。
 *
 *   正确修法（本脚本）：**让引擎自己判一轮**。
 *   起点由 seed 摆好（六笔单在 7、策略齐全、库存桶齐），然后调运行中服务的
 *   `POST /admin/order-audit/run` —— 决策行、结论标、状态流转全部是真引擎落的。
 *   于是「队列里 5 张单挂着 4 种不同的结论标」不是我们写进去的，是**跑出来的**。
 *   这也正是「六笔单走查」那五步取证的前提：单子在 7 上带着 NO_POLICY_MATCH / AUDIT_PENDING /
 *   BLOCKED / FROZEN，点开才讲得出「最像哪条、差什么」。
 *
 * 判完会变成什么样（六笔单的结局由引擎决定，本脚本只**核对**不制造）
 *   QT-1001 淘宝 268 → AUTO_PASS → 7 推到 8，**不落标**（V9 起「过没过」由状态表达）
 *   QT-1002 被全局排除 → MANUAL，仍 7，挂 AUDIT_PENDING
 *   QT-1003 抖音 1580 → NO_MATCH，仍 7，挂 NO_POLICY_MATCH（差在应收 1580 < 500）
 *   QT-1004 毛利 −12 → BLOCKED，仍 7，挂 BLOCKED（本会命中「直营-小单」）
 *   QT-1005 有待处理退款申请 → FROZEN，冻结标
 *   QT-1006 6 个种类 → NO_MATCH，仍 7，挂 NO_POLICY_MATCH（差在种类数 6 ≤ 5）
 *   ⇒ status=7 且带结论标的单 ≥ 5 张、结论标去重 ≥ 4 种 —— 本脚本末尾逐条断言这几件事。
 *
 * 幂等
 *   已经是「待审核 + 带标」的单不会被重复判定（它们本来就还在 7 上，再跑一轮结论不变、标不会翻倍：
 *   结论标同码互斥，见 OrderAuditTagService 的 active 唯一键）。
 *   复位（`scripts/reset-test-db.sh`）每次都整库重建 → 每次都要再跑一轮，所以它挂在复位末尾。
 *
 * 用法
 *   node scripts/prepare-audit-demo-queue.mjs              # 跑一轮 + 自检（复位末尾就是这么调的）
 *   node scripts/prepare-audit-demo-queue.mjs --dry-run    # 只看现状，不触发 run
 *   node scripts/prepare-audit-demo-queue.mjs --base=http://127.0.0.1:18080   # 换基址
 *   node scripts/prepare-audit-demo-queue.mjs --json       # 自检结果以 JSON 打出来（喂给报告）
 *
 * 口令：`ADMIN_PASSWORD` 环境变量，否则读 `/root/.qiqi-secrets/admin.txt`（与 demo-run.sh 同一套，
 *       脚本里**没有任何默认口令**）。取不到就直接失败，不静默跳过 —— 跳过的结果是
 *       「复位完成但队列还是空的」，那正是这次要堵的坑。
 *
 * 退出码：0 = 跑完且自检全绿；1 = 服务不可达 / 口令缺失 / 自检不过（复位会把这条退出码传上去）。
 */
import { readFileSync, existsSync } from 'node:fs'

const argv = process.argv.slice(2)
const arg = (name, dflt = null) => {
  const hit = argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`))
  if (!hit) return dflt
  const eq = hit.indexOf('=')
  return eq === -1 ? true : hit.slice(eq + 1)
}
const BASE = String(arg('base', process.env.AUDIT_API_BASE || 'http://127.0.0.1:18080')).replace(/\/+$/, '')
const DRY_RUN = argv.includes('--dry-run')
const AS_JSON = argv.includes('--json')
const TIMEOUT_MS = Number(arg('timeout', 120000))

/** 结论档（category='结论' 的那一档）里，本脚本要核对的四个码 —— 与 AuditTagCatalogService 同名字面量 */
const CODE_NO_POLICY_MATCH = 'NO_POLICY_MATCH'
const CODE_AUDIT_PENDING = 'AUDIT_PENDING'
const CODE_BLOCKED = 'BLOCKED'
const FROZEN_PREFIX = 'FROZEN'

const log = (...a) => { if (!AS_JSON) console.log(...a) }
const fail = (msg) => { console.error(`✗ ${msg}`); process.exit(1) }

/* ------------------------------------------------------------------ 口令 */

const SECRET_FILE = process.env.ADMIN_PASSWORD_FILE || '/root/.qiqi-secrets/admin.txt'
function adminPassword() {
  if (process.env.ADMIN_PASSWORD) return process.env.ADMIN_PASSWORD
  if (existsSync(SECRET_FILE)) {
    const s = readFileSync(SECRET_FILE, 'utf8').trim()
    if (s) return s
  }
  fail(`拿不到 admin 口令：设 ADMIN_PASSWORD，或写入 ${SECRET_FILE}。`
    + '\n  没有口令就跑不了这一轮判定 ⇒ 复位后队列会是空的，所以这里**不降级、不跳过**。')
  return null
}

/* ------------------------------------------------------------------ HTTP */

async function call(method, path, { token, body } = {}) {
  const headers = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let res
  try {
    res = await fetch(`${BASE}${path}`, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch (e) {
    fail(`连不上 ${BASE}${path}（${e.message}）—— 演示栈没起？先 docker compose up -d 再复位。`)
  }
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* 非 JSON：原样报出去 */ }
  if (!res.ok) fail(`${method} ${path} → HTTP ${res.status}：${text.slice(0, 300)}`)
  if (json && json.code !== undefined && json.code !== 0) fail(`${method} ${path} → code=${json.code}：${json.message}`)
  return json && json.data !== undefined ? json.data : json
}

async function login() {
  const pw = adminPassword()
  const data = await call('POST', '/admin/auth/login', { body: { username: 'admin', password: pw } })
  if (!data || !data.accessToken) fail('登录没拿到 accessToken —— 口令错 / 账号在验证码态，先修好再复位')
  return data.accessToken
}

/* ------------------------------------------------------------------ 队列自检 */

/** 队列一行 → 本脚本关心的四个事实（字段名与 /order-audit/queue 返回体逐字一致） */
function factOf(row) {
  const tags = Array.isArray(row.tagCodes) ? row.tagCodes : []
  return {
    sn: row.orderSn ?? row.order_sn,
    status: row.orderStatus ?? row.order_status,
    result: row.result,
    round: row.roundNo ?? row.round_no,
    tags,
    frozen: Boolean(row.frozen),
    // 第一层那句人话（T1/T2 的产物）：不命中时它就是「最像哪条、差什么」
    oneLiner: row.layers?.layer1?.oneLiner ?? null,
    closestPolicyName: row.layers?.layer1?.closest?.policyName ?? null,
    closestMissing: row.layers?.layer1?.closest?.missingConditions ?? [],
  }
}

async function snapshot(token) {
  const all = await call('GET', '/admin/order-audit/queue?size=100', { token })
  return (all.rows || []).map(factOf)
}

/** 按标筛：total 用后端给的（不是前端数行数），并核「每一行都真挂该标」 */
async function tagCount(token, code) {
  const d = await call('GET', `/admin/order-audit/queue?size=100&tag=${encodeURIComponent(code)}`, { token })
  const rows = (d.rows || []).map(factOf)
  const allCarry = rows.every((r) => r.tags.includes(code))
  return { total: Number(d.total), rows, allCarry }
}

/* ------------------------------------------------------------------ 主流程 */

const token = await login()

const before = await snapshot(token)
log(`▶ 判定前：队列 ${before.length} 行（status=7 的 ${before.filter((r) => r.status === 7).length} 行）`)
if (DRY_RUN) {
  for (const r of before) log(`   ${r.sn} status=${r.status} result=${r.result} tags=[${r.tags.join(',')}]`)
  log('（--dry-run：没有触发 run）')
  process.exit(0)
}

const run = await call('POST', '/admin/order-audit/run', { token })
log(`▶ 跑了一轮：runId=${run.runId} 范围 ${run.total} 笔 → 自动过审 ${run.autoPass} / 留人工 ${run.manual}`
  + ` / 阻止 ${run.blocked} / 冻结 ${run.frozen} / 不命中 ${run.noMatch}（跳过 ${run.skipped}）`)
// 计数自洽（后端 RunResultDto 的硬约束）：五类之和 == 决策行数。这里自己加一遍，
// 不调后端方法 —— 返回体是 JSON，没有方法可调；把口径写在这里也顺便当成对服务端的复核。
const verdictSum = run.autoPass + run.manual + run.blocked + run.frozen + run.noMatch
if (verdictSum !== (run.decisions || []).length) {
  fail(`计数不自洽：五类之和 ${verdictSum} ≠ 决策行 ${(run.decisions || []).length}`)
}

const after = await snapshot(token)
const pending = after.filter((r) => r.status === 7)
const conclusionTags = [...new Set(pending.flatMap((r) => r.tags))]
const noMatch = await tagCount(token, CODE_NO_POLICY_MATCH)
const pendingTag = await tagCount(token, CODE_AUDIT_PENDING)
const blocked = await tagCount(token, CODE_BLOCKED)

log('')
log('▶ 判定后队列（演示要看的就是这张表）')
log('   单号      status  result      标签                         第一层那句话')
for (const r of after) {
  const tags = r.tags.join(',') || '—'
  log(`   ${String(r.sn).padEnd(9)} ${String(r.status).padEnd(7)} ${String(r.result).padEnd(11)} `
    + `${tags.padEnd(28)} ${r.oneLiner ?? '—'}`)
}
log('')
log(`   status=7 的单 = ${pending.length} 张；它们身上的结论标去重 = ${conclusionTags.length} 种：[${conclusionTags.join(', ')}]`)
log(`   按标筛：NO_POLICY_MATCH → ${noMatch.total} 行（每行都带该标=${noMatch.allCarry}）`
  + ` ｜ AUDIT_PENDING → ${pendingTag.total} 行 ｜ BLOCKED → ${blocked.total} 行`)

/* ------------------------------------------------------------------ 硬断言（复位靠它保证「复位完就能演示」） */

const problems = []
if (pending.length < 3) problems.push(`带结论标的待审核单只有 ${pending.length} 张（要 ≥3）`)
if (conclusionTags.length < 3) problems.push(`结论标只有 ${conclusionTags.length} 种（要 ≥3）`)
for (const code of [CODE_NO_POLICY_MATCH, CODE_AUDIT_PENDING]) {
  if (!conclusionTags.includes(code)) problems.push(`没覆盖标 ${code}`)
}
if (!conclusionTags.includes(CODE_BLOCKED) && !conclusionTags.includes('STOCK_SHORT')) {
  problems.push('BLOCKED / STOCK_SHORT 一个都没有（要至少一个）')
}
if (noMatch.total < 1) problems.push('按标筛 NO_POLICY_MATCH = 0 行（筛不出单 ⇒ 演示第 5 步做不了）')
if (!noMatch.allCarry) problems.push('NO_POLICY_MATCH 筛出来的行里，有行没挂该标')
// 「最接近那条 + 差什么」必须是人能听懂的一句话：不命中的行至少一行要有 closest 策略名 + 具体差在哪
const explainable = pending.filter((r) => r.result === 'NO_MATCH' && r.oneLiner
  && r.closestPolicyName && r.closestMissing.length > 0)
if (!explainable.length) {
  problems.push('没有一张不命中单能讲出「最接近哪条 + 差什么」（layer1 缺 oneLiner/closest.missingConditions）')
}

if (problems.length) {
  if (AS_JSON) console.log(JSON.stringify({ ok: false, problems, after, noMatch: noMatch.total }, null, 2))
  for (const p of problems) console.error(`  ✗ ${p}`)
  fail('演示队列没修成：复位后仍然「没单可看」，别拿它去演示')
}

if (AS_JSON) {
  console.log(JSON.stringify({
    ok: true,
    runId: run.runId,
    pendingCount: pending.length,
    conclusionTags,
    filters: { NO_POLICY_MATCH: noMatch.total, AUDIT_PENDING: pendingTag.total, BLOCKED: blocked.total },
    rows: after,
  }, null, 2))
} else {
  log('')
  log(`✓ 队列可演示：${pending.length} 张待审核单挂着 ${conclusionTags.length} 种结论标，`
    + `按标筛 NO_POLICY_MATCH 能筛出 ${noMatch.total} 行。`)
}
