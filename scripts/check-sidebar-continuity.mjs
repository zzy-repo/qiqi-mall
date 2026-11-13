#!/usr/bin/env node
/**
 * check-sidebar-continuity.mjs —— 侧边栏「场景/图页编号连续性」门禁（零依赖：node 内置 + 全局 fetch）
 *
 * ── 为什么需要它 ───────────────────────────────────────────────────────────
 * 侧边栏的显示名**不带编号**（页面 H1 也不带，见 /root/docsify/build-material.mjs 顶部约定：
 * 「文件名保留 N- 前缀，但侧边栏文案与页面 H1 一律不显示数字」），所以少一个场景时，
 * 肉眼翻侧边栏是完全看不出来的 —— 少的那一项压根不在列表里，页数和缺件检查也照样是绿的。
 * 但 **URL 会露馅**：……/7-收货地址.md 的下一条直接是 ……/9-确认收货并发.md，7→9 就是断档。
 *
 * 2026-11-14 就发生过一次：「8-支付并发冲突」的 e2e 用例已测过、图/口语稿/视频也已落盘，
 * 但 build-material.mjs 的 SCENE_GROUPS 里没登记它 → 不生成页面 → 侧边栏从 7 直接跳到 9。
 * 当时 ①页面数 ⑤缺件 ⑨死链 全绿（缺的那项从来不在清单里，所有「按清单数」的检查都不会报），
 * 只有把 URL 里的编号按组排一遍、验证 1..N 连续，才看得见这个洞。
 *
 * 所以本脚本的判据是「URL 编号序列」，不看显示名、不看总数：
 *   对每个分组（01-买家侧 / 02-商家-商品管理 / 二、状态机 …）取其下所有
 *   material/{scenarios,diagrams}/<组目录>/<N>-<名字>.md 链接，要求
 *     ① 1..max 每个号都在（无缺号）
 *     ② 没有重复号（两页共用一个编号 = 同上）
 *     ③ 条数 == max（防「1,2,3,5」这种 max 对得上但中间缺的写法溜过去）
 *
 * ── 用法 ───────────────────────────────────────────────────────────────────
 *   # 取线上（最真实：验的就是用户点得到的那个 _sidebar.md）
 *   node scripts/check-sidebar-continuity.mjs --base=http://100.113.135.55:3027
 *   node scripts/check-sidebar-continuity.mjs --base https://docs.rose7.cn
 *   # 取本地产物（重建后、发布前、没网/没起站时用）
 *   node scripts/check-sidebar-continuity.mjs --root=/root/docsify
 *   node scripts/check-sidebar-continuity.mjs --root=/root/docsify/dist-prod
 *
 *   --base=<url>   抓 <url>/_sidebar.md（站点根；末尾斜杠可有可无）
 *   --root=<dir>   读 <dir>/_sidebar.md（站点产物目录）
 *   两个都给时以 --base 为准（线上即真相），并在输出里注明。
 *
 * ── 退出码（可直接当门禁）───────────────────────────────────────────────────
 *   0  所有分组编号连续
 *   1  有断档/重复；或一条带编号的条目都没扫到（源指错/产物被清空 —— 不许「空跑即通过」）
 *   2  参数错 / 取源失败（拿不到 _sidebar.md）
 *
 * 只读：不改库、不改站、不写任何文件。
 */

import { readFileSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** `- [文案](url)` —— 只认这种侧边栏行；标题行（`- 分组名`）靠缩进栈归组 */
const LINK_RE = /^-\s*\[([^\]]*)\]\(([^)]*)\)/

/** 带编号的页面链接：material/<scenarios|diagrams>/<组目录>/<N>-<名字>.md */
const URL_RE = /^material\/(scenarios|diagrams)\/([^/]+)\/(\d+)-(.+?)\.md$/

const KIND_LABEL = { scenarios: '场景', diagrams: '图页' }

/**
 * 解析 _sidebar.md → 分组列表。
 *
 * 用「缩进栈」还原父子关系：markdown 列表靠缩进表达层级，遇到链接行时，
 * 栈里缩进比自己小的最近一行就是它的父标题（= 侧边栏上的分组名）。
 * 不按固定层级数写死 —— 侧边栏调整过缩进/加过外层分组都不会读错。
 *
 * @returns {{groups: Array<{kind:string,dir:string,label:string,items:Array<{n:number,name:string,url:string}>}>, numbered:number}}
 */
export function parseSidebar(md) {
  const stack = []                 // [{ indent, text }]
  const groups = new Map()         // key → group（保持文档顺序）
  let numbered = 0

  for (const rawLine of String(md).split('\n')) {
    if (!rawLine.trim()) continue
    const indent = rawLine.length - rawLine.trimStart().length
    const body = rawLine.trim()
    const link = LINK_RE.exec(body)

    if (!link) {
      // 标题行：同级或更深的旧标题出栈，再把自己压入
      while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop()
      stack.push({ indent, text: body.replace(/^-\s*/, '').replace(/\*\*/g, '').trim() })
      continue
    }

    const [, title, url] = link
    const m = URL_RE.exec(decodeURIComponentSafe(url).trim())
    if (!m) continue               // 首页/手工文档/外链，不参与编号连续性
    numbered++

    const parent = [...stack].reverse().find((s) => s.indent < indent)
    const [, kind, dir, n, name] = m
    const label = parent ? parent.text : '(无分组)'
    const key = `${kind}\u0000${dir}\u0000${label}`
    if (!groups.has(key)) groups.set(key, { kind, dir, label, items: [] })
    groups.get(key).items.push({ n: Number(n), name, title, url })
  }

  return { groups: [...groups.values()], numbered }
}

function decodeURIComponentSafe(s) {
  try { return decodeURIComponent(s) } catch { return s }
}

/**
 * 逐组判连续性：1..max 无缺、无重、条数 == max。
 * @returns {{results:Array<{group:object,nums:number[],missing:number[],dup:number[],max:number,ok:boolean}>, bad:number, lines:string[]}}
 */
export function checkContinuity(groups) {
  const results = []
  for (const group of groups) {
    const nums = group.items.map((i) => i.n)
    const max = nums.length ? Math.max(...nums) : 0
    const seen = new Set()
    const dup = []
    for (const n of nums) {
      if (seen.has(n)) dup.push(n)   // 同一编号出现两次（含同号两页）
      seen.add(n)
    }
    const missing = []
    for (let i = 1; i <= max; i++) if (!seen.has(i)) missing.push(i)
    const ok = missing.length === 0 && dup.length === 0 && nums.length === max
    results.push({ group, nums, missing, dup, max, ok })
  }
  const bad = results.filter((r) => !r.ok)
  const lines = results.map((r) => {
    const tag = `[${KIND_LABEL[r.group.kind] || r.group.kind}] ${r.group.label} (${r.group.dir})`
    if (r.ok) {
      return `✅ ${tag}  n=${r.nums.length}  序列=${r.nums.join(',')}  → 1..${r.max} 连续无缺无重`
    }
    const why = []
    if (r.missing.length) why.push(`缺 [${r.missing.join(',')}]`)
    if (r.dup.length) why.push(`重复 [${r.dup.join(',')}]`)
    if (!r.missing.length && !r.dup.length) why.push(`条数 ${r.nums.length} ≠ max ${r.max}`)
    return `❌ ${tag}  n=${r.nums.length}  序列=${r.nums.join(',')}  → ${why.join(' / ')}`
  })
  return { results, bad, lines }
}

/** 取 _sidebar.md 文本：--base 优先（线上即真相），否则 --root */
export async function loadSidebar({ base, root }) {
  if (base) {
    const url = `${String(base).replace(/\/+$/, '')}/_sidebar.md`
    let res
    try {
      res = await fetch(url)
    } catch (e) {
      throw new Error(`抓取失败（网络/域名）：${url} —— ${e.message}`)
    }
    if (!res.ok) throw new Error(`抓取失败：${url} → HTTP ${res.status}`)
    return { text: await res.text(), source: url, from: 'base' }
  }
  const file = join(resolve(root), '_sidebar.md')
  if (!existsSync(file)) throw new Error(`找不到 ${file}（--root 指的目录里没有 _sidebar.md？）`)
  return { text: readFileSync(file, 'utf8'), source: file, from: 'root' }
}

/* ------------------------------------------------------------------ CLI */

const isMain = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))

if (isMain) {
  const argv = process.argv.slice(2)
  const opt = (name) => {
    const hit = argv.find((a) => a.startsWith(`${name}=`))
    if (hit) return hit.slice(name.length + 1)
    const i = argv.indexOf(name)
    return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : undefined
  }
  const base = opt('--base')
  const root = opt('--root')

  if (!base && !root) {
    console.error('✗ 需要 --base=<url>（抓线上 _sidebar.md）或 --root=<站点产物目录>（读本地 _sidebar.md）')
    console.error('  例：node scripts/check-sidebar-continuity.mjs --base=http://100.113.135.55:3027')
    console.error('      node scripts/check-sidebar-continuity.mjs --root=/root/docsify')
    process.exit(2)
  }

  let loaded
  try {
    loaded = await loadSidebar({ base, root })
  } catch (e) {
    console.error(`✗ ${e.message}`)
    process.exit(2)
  }

  console.log(`源：${loaded.source}${base && root ? '（--base 优先，--root 已忽略）' : ''}`)
  console.log('')

  const { groups, numbered } = parseSidebar(loaded.text)
  const { lines, bad } = checkContinuity(groups)
  for (const line of lines) console.log(line)

  // fail-closed：扫不到带编号的条目 = 源指错了，不许当「全连续」放行
  if (!groups.length || numbered === 0) {
    console.log('')
    console.log(`✗ 一条带编号的侧边栏条目都没扫到（分组 0 个 / 编号条目 0 条）—— 源指错了或产物是空的，不能判通过`)
    process.exit(1)
  }

  console.log('')
  console.log(`分组 ${groups.length} 个；不连续 ${bad.length} 个`)

  if (bad.length) {
    console.log('')
    console.log(`✗ 断档明细：${bad.map((r) => `${r.group.label}(缺 ${r.missing.join(',') || '—'}${r.dup.length ? ` 重 ${r.dup.join(',')}` : ''})`).join('；')}`)
  }
  process.exit(bad.length ? 1 : 0)
}
