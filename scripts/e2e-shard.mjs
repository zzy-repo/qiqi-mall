#!/usr/bin/env node
/**
 * 把 e2e spec 文件按「重量」均分到 N 个 shard，供 scripts/e2e-run.sh 用。
 *
 *   node scripts/e2e-shard.mjs --shards=2
 *   node scripts/e2e-shard.mjs --shards=4 --mode=count
 *
 * 为什么需要它：
 *   playwright 自带的 `--shard=i/N` 是**按文件数**均分（23 个文件 → 12/11），
 *   但文件的重量差一个数量级：`4-退货处理`、`1-管理员登录与验证码`、`5-图片上传` 里
 *   都是慢 UI 流程，而 `1-商品类型与属性` 之类几条就秒过。结果就是
 *   「shard a 9.2 分钟跑完空转，shard b 19.7 分钟」——墙钟由最慢的那半决定，
 *   2 并发只拿到 1.38× 而不是 2×。
 *
 * 算法：LPT（Longest Processing Time first）贪心装箱
 *   1) 算每个文件的重量；
 *   2) 按重量降序；
 *   3) 逐个放进「当前总重最小」的桶。
 *   这是多机调度里最经典的近似算法，负载不均衡的上界是 4/3·OPT；
 *   对「文件不能在 shard 间拆分」这种约束已经够好。
 *
 * 重量怎么来：
 *   - 优先用历史耗时：e2e/report/runs.jsonl 里每行 {file, durationMs}，取同文件的中位数；
 *   - 没有历史就退化用「用例数」当代理（grep 顶层 test( 的条数），
 *     再乘一个按场景的经验系数（含上传/退货/图表/验证码的重场景更贵）。
 *     系数只是让首次运行的分配别太离谱，跑过一次有了 runs.jsonl 就该走真实耗时。
 *
 * 输出（方便 bash 读）：
 *   a<TAB>tests/scenarios/x.spec.js<TAB>tests/scenarios/y.spec.js
 *   b<TAB>...
 */
import { readdirSync, readFileSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'

const REPO = new URL('..', import.meta.url).pathname.replace(/\/$/, '')
const E2E = join(REPO, 'e2e')
const SCEN = join(E2E, 'tests/scenarios')
const RUNS = join(E2E, 'report/runs.jsonl')

const arg = (name, dflt) => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.split('=').slice(1).join('=') : dflt
}
const SHARDS = Math.max(1, Number(arg('shards', '2')))
const MODE = arg('mode', 'auto') // auto | count | time

/** 递归收集 spec 文件（相对 e2e/ 的路径，和 playwright 的 testDir 口径一致） */
const walk = (dir) => {
  const out = []
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) out.push(...walk(p))
    else if (e.name.endsWith('.spec.js')) out.push(p)
  }
  return out
}

/** 用例数：只数顶层/嵌套的 test( 调用（排除 test.describe / test.beforeEach 之类） */
const countTests = (src) =>
  (src.match(/^\s*test\s*\(/gm) || []).length

/** 历史耗时中位数（runs.jsonl 是可选增强，没有就返回 null） */
const historyMedian = () => {
  if (!existsSync(RUNS)) return null
  const byFile = new Map()
  for (const line of readFileSync(RUNS, 'utf8').split('\n')) {
    if (!line.trim()) continue
    try {
      const { file, durationMs } = JSON.parse(line)
      if (!file || !durationMs) continue
      if (!byFile.has(file)) byFile.set(file, [])
      byFile.get(file).push(durationMs)
    } catch { /* 坏行忽略 */ }
  }
  const med = new Map()
  for (const [f, arr] of byFile) {
    arr.sort((x, y) => x - y)
    med.set(f, arr[Math.floor(arr.length / 2)])
  }
  return med
}

/**
 * 无历史耗时时的经验系数：按场景里最容易拖慢的东西加权
 * （文件上传/预览、退货与退款流水、数据概览图表、验证码重试、跨仓拆单）。
 */
const costHint = (rel) => {
  let k = 1
  if (/图片上传|预览/.test(rel)) k += 0.8
  if (/退货/.test(rel)) k += 0.6
  if (/跨仓拆单|发货单调整/.test(rel)) k += 0.4
  if (/数据概览|概览/.test(rel)) k += 0.5
  if (/验证码|管理员登录/.test(rel)) k += 0.9
  return k
}

const files = walk(SCEN).sort()
const med = MODE === 'count' ? null : historyMedian()

const items = files.map((abs) => {
  const rel = relative(E2E, abs) // tests/scenarios/xx.spec.js
  const src = readFileSync(abs, 'utf8')
  const tests = countTests(src)
  let weight
  if (med && med.has(rel)) weight = med.get(rel) / 1000 // 秒
  else weight = tests * costHint(rel)
  return { rel, tests, weight }
})

// LPT：重量降序，逐个塞进当前最轻的桶
const buckets = Array.from({ length: SHARDS }, () => ({ load: 0, files: [] }))
for (const it of [...items].sort((a, b) => b.weight - a.weight)) {
  const target = buckets.reduce((m, b) => (b.load < m.load ? b : m), buckets[0])
  target.load += it.weight
  target.files.push(it.rel)
}

const NAMES = 'abcdefgh'.split('')
buckets.forEach((b, i) => {
  // 每个桶内部按文件名排序，输出稳定可比对
  process.stdout.write([NAMES[i], ...b.files.sort()].join('\t') + '\n')
})

// 负载概况走 stderr，不污染 stdout 的映射（verbose 才打）
if (process.env.E2E_SHARD_VERBOSE === '1') {
  const total = buckets.reduce((s, b) => s + b.load, 0)
  buckets.forEach((b, i) =>
    process.stderr.write(
      `  shard ${NAMES[i]}: ${b.files.length} 文件 / 权重 ${b.load.toFixed(1)} ` +
      `(${((b.load / total) * 100).toFixed(1)}%) ` +
      `${b.files.reduce((s, f) => s + (items.find((x) => x.rel === f)?.tests || 0), 0)} 用例\n`
    )
  )
}
