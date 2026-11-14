#!/usr/bin/env node
/**
 * check-sidebar-continuity.mjs —— 侧边栏「顺序与清单一致」门禁（零依赖：node 内置 + 全局 fetch）
 *
 * ── 2026-11-14 口径变更（原来是「编号连续性」）──────────────────────────────
 * 编号已经从**文件名 / URL / 目录名**里整个去掉了：场景的顺序只由 build-material.mjs 的
 * SCENE_BUCKETS（四桶 → 子分类 → 场景）书写顺序决定。于是旧的判据（URL 里 1..N 连续）失效：
 *   · 没有编号可数，`7→9` 这种断档再也不会出现在 URL 上；
 *   · 而且旧判据只看得见「编号断档」，看不见「顺序被改了」「同名出现两次」。
 * 新判据只有三句话，全部对着**构建自己落的顺序清单**比：
 *   ① 侧边栏里的场景页序列 == 清单里的场景页序列（顺序逐条相同）；
 *   ② 无重复（同一个 page 出现两次 = 两条链接抢同一个页面）；
 *   ③ 无遗漏（清单里有、侧边栏里没有 = 少了一个场景；侧边栏里有、清单里没有 = 多了一个野页面）。
 *
 * ── 清单从哪来 ─────────────────────────────────────────────────────────────
 * build-material.mjs 每次构建写 `material/_nav-manifest.json`：
 *   { version, scenes: [{bucket, sub, label, page}], diagrams: [{group, label, page}] }
 * 顺序 = SCENE_BUCKETS / DIAGRAM_GROUPS 的书写顺序（构建期物化，不是按文件名排）。
 * **为什么让构建落清单、而不是本脚本去解析 build-material.mjs 源码**：解析源码等于用正则猜代码，
 * 改一行注释、换个写法就误判；而清单是构建产物，与侧边栏同源同批，判「生成物 == 清单」才有意义。
 *
 * ── 用法 ───────────────────────────────────────────────────────────────────
 *   node scripts/check-sidebar-continuity.mjs --root=/root/docsify
 *   node scripts/check-sidebar-continuity.mjs --root=/root/docsify/dist-prod
 *   node scripts/check-sidebar-continuity.mjs --base=http://100.113.135.55:3027   # 线上（清单随站点发布）
 *
 *   --base=<url>   抓 <url>/_sidebar.md 与 <url>/material/_nav-manifest.json
 *   --root=<dir>   读 <dir>/_sidebar.md 与 <dir>/material/_nav-manifest.json
 *   --manifest=<p> 显式指定清单文件（Rspress 产物等清单不在默认位置时用）
 *   两个取源都给时以 --base 为准（线上即真相）。
 *
 * ── 退出码（可直接当门禁）───────────────────────────────────────────────────
 *   0  顺序一致、无重复、无遗漏
 *   1  不一致 / 有重复 / 有遗漏；或侧边栏一个场景链接都没扫到（源指错/产物被清空 —— 不许空跑即通过）
 *   2  参数错 / 取源失败 / 清单缺失（清单缺失 = 没法判，也判 2，不能当成通过）
 *
 * 只读：不改库、不改站、不写任何文件。
 */

import { readFileSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** `- [文案](url)` —— 只认这种侧边栏行；标题行（`- 分组名`）靠缩进栈归组 */
const LINK_RE = /^-\s*\[([^\]]*)\]\(([^)]*)\)/

/** 场景页 / 图页链接（形如 material/scenarios/<桶>/<子分类>/<场景>.md）—— 与清单里的 page 同一种写法 */
const SCENE_URL_RE = /^material\/scenarios\/.+\.md$/
const DIAGRAM_URL_RE = /^material\/diagrams\/.+\.md$/

const KIND_LABEL = { scenes: '场景', diagrams: '图页' }

const decodeURIComponentSafe = (s) => {
  try { return decodeURIComponent(s) } catch { return s }
}

/**
 * 解析 _sidebar.md → 按出现顺序拍平的链接清单（分组标题只用于报错定位，不参与判定）。
 *
 * 用「缩进栈」还原父子关系：markdown 列表靠缩进表达层级，遇到链接行时，
 * 栈里缩进比自己小的最近一行就是它的父标题（= 侧边栏上的分组名）。不按固定层级数写死。
 *
 * @returns {{order: Array<{kind:'scenes'|'diagrams', url:string, path:string, title:string, group:string}>, total:number}}
 */
export function parseSidebar(md) {
  const stack = []
  const order = []
  for (const rawLine of String(md).split('\n')) {
    if (!rawLine.trim()) continue
    const indent = rawLine.length - rawLine.trimStart().length
    const body = rawLine.trim()
    const link = LINK_RE.exec(body)

    if (!link) {
      while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop()
      stack.push({ indent, text: body.replace(/^-\s*/, '').replace(/\*\*/g, '').trim() })
      continue
    }

    const [, title, rawUrl] = link
    const url = decodeURIComponentSafe(rawUrl).trim()
    const scene = SCENE_URL_RE.exec(url)
    const diagram = DIAGRAM_URL_RE.exec(url)
    if (!scene && !diagram) continue          // 首页 / 手工文档 / 外链：不参与顺序判定
    const group = [...stack].reverse().find((s) => s.indent < indent)
    order.push({
      kind: scene ? 'scenes' : 'diagrams',
      url,
      // 与清单里的 page 同一种写法：站点相对路径（material/scenarios/… / material/diagrams/…）
      path: url,
      title,
      group: group ? group.text : '(无分组)',
    })
  }
  return { order, total: order.length }
}

/**
 * 判「侧边栏顺序 == 清单顺序」：逐条比对 + 查重复 + 查遗漏。
 *
 * @param {Array} sidebarOrder  parseSidebar().order
 * @param {{scenes:Array<{page:string,label:string}>, diagrams:Array<{page:string,label:string}>}} manifest
 * @returns {{results:Array<object>, bad:Array<object>, lines:string[]}}
 */
export function checkAgainstManifest(sidebarOrder, manifest) {
  const results = []
  for (const kind of ['scenes', 'diagrams']) {
    const expected = (manifest?.[kind] ?? []).map((x) => x.page)
    const actual = sidebarOrder.filter((x) => x.kind === kind)
    const actualPages = actual.map((x) => x.path)

    const seen = new Set()
    const dup = []
    for (const p of actualPages) {
      if (seen.has(p)) dup.push(p)      // 同一个页面被两条链接抢：点哪条都一样，等于少一个场景
      seen.add(p)
    }

    const expectedSet = new Set(expected)
    const actualSet = new Set(actualPages)
    const missing = expected.filter((p) => !actualSet.has(p))    // 清单有、侧边栏没有
    const extra = actualPages.filter((p) => !expectedSet.has(p)) // 侧边栏有、清单没有

    // 顺序：把两边的交集按各自顺序取出来，逐条比 —— 「少一条」不该掩盖「顺序也被换了」
    const common = expected.filter((p) => actualSet.has(p))
    const actualCommon = actualPages.filter((p) => expectedSet.has(p))
    const orderBad = []
    const n = Math.min(common.length, actualCommon.length)
    for (let i = 0; i < n; i++) {
      if (common[i] !== actualCommon[i]) { orderBad.push({ at: i + 1, want: common[i], got: actualCommon[i] }); break }
    }

    const ok = !dup.length && !missing.length && !extra.length && !orderBad.length && actualPages.length === expected.length
    results.push({ kind, expected, actual: actualPages, dup, missing, extra, orderBad, ok })

    const tag = `[${KIND_LABEL[kind]}]`
    if (ok) {
      results[results.length - 1].line = `✅ ${tag} ${actualPages.length} 条，顺序与清单逐条相同（无重复、无遗漏）`
      continue
    }
    const why = []
    if (orderBad.length) why.push(`顺序在第 ${orderBad[0].at} 条就分叉：清单是 ${orderBad[0].want}，侧边栏是 ${orderBad[0].got}`)
    if (missing.length) why.push(`少 ${missing.length} 条 [${missing.join(', ')}]`)
    if (extra.length) why.push(`多 ${extra.length} 条（清单里没有）[${extra.join(', ')}]`)
    if (dup.length) why.push(`重复 [${dup.join(', ')}]`)
    if (!why.length && actualPages.length !== expected.length) why.push(`条数 ${actualPages.length} ≠ 清单 ${expected.length}`)
    results[results.length - 1].line = `❌ ${tag} 侧边栏 ${actualPages.length} / 清单 ${expected.length} 条 → ${why.join(' / ')}`
  }
  const bad = results.filter((r) => !r.ok)
  return { results, bad, lines: results.map((r) => r.line) }
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

/**
 * 取顺序清单：--manifest 优先 → <root>/material/_nav-manifest.json → <root>/_nav-manifest.json
 * （--base 时走线上 <base>/material/_nav-manifest.json）。
 * 找不到就抛错（清单缺失 = 判不了，绝不能当通过）。
 */
export async function loadManifest({ base, root, manifest }) {
  if (manifest) {
    const file = resolve(manifest)
    if (!existsSync(file)) throw new Error(`找不到清单文件 ${file}`)
    return { json: JSON.parse(readFileSync(file, 'utf8')), source: file }
  }
  if (base) {
    const url = `${String(base).replace(/\/+$/, '')}/material/_nav-manifest.json`
    let res
    try {
      res = await fetch(url)
    } catch (e) {
      throw new Error(`抓取清单失败（网络/域名）：${url} —— ${e.message}`)
    }
    if (!res.ok) throw new Error(`抓取清单失败：${url} → HTTP ${res.status}（重建站点后才会生成）`)
    return { json: await res.json(), source: url }
  }
  for (const rel of ['material/_nav-manifest.json', '_nav-manifest.json']) {
    const file = join(resolve(root), rel)
    if (existsSync(file)) return { json: JSON.parse(readFileSync(file, 'utf8')), source: file }
  }
  throw new Error(`找不到顺序清单：${join(resolve(root), 'material/_nav-manifest.json')}（先跑 build-material.mjs 重建站点）`)
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
  const manifest = opt('--manifest')

  if (!base && !root) {
    console.error('✗ 需要 --base=<url>（抓线上 _sidebar.md）或 --root=<站点产物目录>（读本地 _sidebar.md）')
    console.error('  例：node scripts/check-sidebar-continuity.mjs --root=/root/docsify')
    console.error('      node scripts/check-sidebar-continuity.mjs --base=http://100.113.135.55:3027')
    process.exit(2)
  }

  let loaded, man
  try {
    loaded = await loadSidebar({ base, root })
    man = await loadManifest({ base, root, manifest })
  } catch (e) {
    console.error(`✗ ${e.message}`)
    process.exit(2)
  }

  console.log(`侧边栏：${loaded.source}${base && root ? '（--base 优先，--root 已忽略）' : ''}`)
  console.log(`顺序清单：${man.source}`)

  const { order, total } = parseSidebar(loaded.text)
  const { results, bad, lines } = checkAgainstManifest(order, man.json)
  for (const line of lines) console.log(line)

  // fail-closed：扫不到任何场景/图页链接 = 源指错了，不许当「一致」放行
  if (!total) {
    console.log('')
    console.log('✗ 侧边栏里一个场景页/图页链接都没扫到（0 条）—— 源指错了或产物是空的，不能判通过')
    process.exit(1)
  }

  const expectedTotal = (man.json.scenes?.length ?? 0) + (man.json.diagrams?.length ?? 0)
  console.log('')
  console.log(`侧边栏 ${total} 条 / 清单 ${expectedTotal} 条；不一致 ${bad.length} 组`)

  if (bad.length) {
    console.log('')
    console.log(`✗ 明细：${results.filter((r) => !r.ok).map((r) => `${r.kind}(少 ${r.missing.length} 多 ${r.extra.length} 重 ${r.dup.length}${r.orderBad.length ? ' 顺序错' : ''})`).join('；')}`)
  }
  process.exit(bad.length ? 1 : 0)
}
