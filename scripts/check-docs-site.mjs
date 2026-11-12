#!/usr/bin/env node
/**
 * check-docs-site.mjs —— 文档站重建后的「一次体检」（零依赖：node 内置 + 全局 fetch）
 *
 * 为什么要有它：站点每次重建要验 9 项（页面数、图/视频能否取到、图与 mermaid 源码是否配对、
 * 业务场景是否「图+视频」齐全（无界面场景仅查图，见 NO_UI_SCENES）、内网信息有没有混进产物、
 * SVG 硬指标、图内禁词、侧边栏死链），
 * 人工 curl + 数数必漏，所以要一条命令给出 PASS/FAIL + 具体数字 + 可行动的失败清单。
 *
 * 用法：
 *   # dev 站（内网专用）：首页 README.md 就是内网索引页，本来就列着内网 IP/端口/测试账号，
 *   # 那是有意为之、不是泄露，所以 dev 站用 --internal-ok 让第 ⑥ 项豁免 README.md 这一个文件。
 *   node scripts/check-docs-site.mjs --root /root/docsify --base http://100.113.135.55:3027 --internal-ok
 *   # prod 站（公网）：**绝对不要加 --internal-ok**，README.md 也必须过内网信息扫描。
 *   node scripts/check-docs-site.mjs --root /root/docsify/dist-prod --base https://docs.rose7.cn
 *   node scripts/check-docs-site.mjs --root /root/docsify --skip-http     # 只做静态检查，不发请求
 *
 * 参数：--root 站点产物目录（必填） / --base 站点基址（除非 --skip-http） / --skip-http 只查本地文件
 *       --internal-ok 仅 dev 站用：第 ⑥ 项豁免根目录 README.md（dev 索引页），其余文件照扫
 *       --docs 图素材源目录（默认 <repo>/docs） / --videos 视频素材源目录（默认 <repo>/e2e/report/videos）
 *       dev 站的 /_assets/{img,video} 是 nginx alias 到这两个源目录的，prod 站则真拷进产物，故两者都要知道。
 *       --floor scenes=10[,pages=20] 覆盖下方 FLOOR 的下限阈值（可多次给，也可用环境变量
 *       CHECK_DOCS_FLOOR="scenes=10 pages=20" 或单项 CHECK_DOCS_FLOOR_SCENES=10）。
 * 退出码：全 PASS = 0，有 FAIL = 1（可直接接进 publish.sh 当门禁）；参数/阈值写错 = 2。
 *
 * 为什么需要「下限断言（floor）」：
 *   第 ⑤ 项（场景图+视频齐全）和第 ⑨ 项（侧边栏死链）都是「先扫到东西，再判断扫到的东西对不对」。
 *   一旦什么都没扫到，它们会说「场景 0 个，缺件 0」「链接 0 个，死链 0」并判 ✅ ——
 *   `--docs` 指错目录、docs/flows/02-业务场景 被改名、站点产物被清空时，门禁会输出
 *     ✅ ⑤  场景图+视频齐全  场景 0 个，缺件 0
 *     ✅ ⑨  侧边栏一致性  链接 0 个，死链 0
 *     结论：PASS      EXIT=0
 *   也就是**什么都没检查就放行**（fail-open）。publish.sh 用退出码当门禁，这种「空跑即通过」等于没有门禁。
 *   所以九项检查之后加一节「下限断言」：每个维度的实测数量必须 ≥ FLOOR 里的下限，
 *   不达标就整条 FAIL、退出码 1 —— 宁可因为「扫到的东西太少」拦下来，也不要因为「问题数为 0」放过去。
 *   阈值集中在下面唯一的 FLOOR_DEFAULTS / FLOOR 常量里，判定逻辑只读它，别处不许重复写数字。
 */
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs'
import { join, dirname, resolve, posix } from 'node:path'
import { fileURLToPath } from 'node:url'

/* ============================================================== 参数与路径 */

const argv = process.argv.slice(2)
function opt(name) {
  const hit = argv.find((a) => a.startsWith(`${name}=`))
  if (hit) return hit.slice(name.length + 1)
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : undefined
}
const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const ROOT = resolve(opt('--root') ?? '')
const BASE = (opt('--base') ?? '').replace(/\/+$/, '')
const SKIP_HTTP = argv.includes('--skip-http')
// dev 站专用开关：dev 首页 README.md 是内网索引页（本来就列着内网 IP/端口/测试账号），
// 第 ⑥ 项豁免它一个文件；prod 站不许带（prod 的 README.md 也必须过内网信息扫描）。
const INTERNAL_OK = argv.includes('--internal-ok')
const INTERNAL_OK_SKIP = 'README.md'
const DOCS = resolve(opt('--docs') ?? join(REPO, 'docs'))
const VIDEOS = resolve(opt('--videos') ?? join(REPO, 'e2e', 'report', 'videos'))

if (!opt('--root') || !existsSync(ROOT)) {
  console.error('✗ 缺少有效的 --root（站点产物目录）')
  process.exit(2)
}
if (!SKIP_HTTP && !BASE) {
  console.error('✗ 缺少 --base；只想做静态检查请加 --skip-http')
  process.exit(2)
}

/* ============================================================== 下限阈值（floor） */

// 唯一数据源：要调阈值只改这里（或从外部覆盖），判定逻辑只读 FLOOR[key]，不许在别处重复写数字。
// 取值口径 = 当前实测值往下留余量：正常内容小幅增减不该误报，但「扫到的东西明显太少/根本没扫到」必须拦住。
const FLOOR_DEFAULTS = {
  scenes: 20,        // ⑤ 扫到的业务场景目录数（实测 28）
  pages: 30,         // ① 产物页面数 = 场景页 + 图页（实测 52）
  images: 30,        // ② 图片引用总数（实测 54）
  videos: 25,        // ③ 视频引用总数（实测 41）
  svgChecked: 40,    // ⑦⑧ 被检查的 svg 数 = 源 + 产物（实测 109）
  sidebarLinks: 30,  // ⑨ 侧边栏站内链接数（实测 53）
}
const FLOOR_LABEL = {
  scenes: '场景数', pages: '页面数', images: '图片引用',
  videos: '视频引用', svgChecked: '检查的 svg', sidebarLinks: '侧边栏链接',
}
const FLOOR_KEYS = Object.keys(FLOOR_DEFAULTS)

/** "scenes=10 pages=20" / "scenes=10,pages=20" → { scenes:10, pages:20 }；名字写错直接报错，避免「以为放宽了其实没生效」 */
function parseFloorAssignments(chunk, origin) {
  const out = {}
  for (const kv of String(chunk).split(/[,\s]+/).filter(Boolean)) {
    const m = /^([A-Za-z]+)=(\d+)$/.exec(kv)
    if (!m) {
      console.error(`✗ ${origin} 里的 "${kv}" 不是 key=非负整数 形式（可用项：${FLOOR_KEYS.join('/')}）`)
      process.exit(2)
    }
    const [, key, val] = m
    if (!FLOOR_KEYS.includes(key)) {
      console.error(`✗ ${origin} 里的 "${key}" 不是已知下限项（可用项：${FLOOR_KEYS.join('/')}）`)
      process.exit(2)
    }
    out[key] = Number(val)
  }
  return out
}

// 外部覆盖优先级：FLOOR_DEFAULTS < 环境变量（整串 + 单项）< --floor 命令行
const FLOOR = { ...FLOOR_DEFAULTS }
for (const key of FLOOR_KEYS) {
  const single = process.env[`CHECK_DOCS_FLOOR_${key.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase()}`]
  if (single !== undefined && single !== '') Object.assign(FLOOR, parseFloorAssignments(`${key}=${single}`, `环境变量 CHECK_DOCS_FLOOR_${key.toUpperCase()}`))
}
if (process.env.CHECK_DOCS_FLOOR) Object.assign(FLOOR, parseFloorAssignments(process.env.CHECK_DOCS_FLOOR, '环境变量 CHECK_DOCS_FLOOR'))
for (let i = 0; i < argv.length; i++) {
  const a = argv[i]
  if (a.startsWith('--floor=')) Object.assign(FLOOR, parseFloorAssignments(a.slice('--floor='.length), '参数 --floor'))
  else if (a === '--floor') {
    const v = argv[i + 1]
    if (!v || v.startsWith('--')) {
      console.error(`✗ --floor 需要一个 key=非负整数 参数（可用项：${FLOOR_KEYS.join('/')}）`)
      process.exit(2)
    }
    Object.assign(FLOOR, parseFloorAssignments(v, '参数 --floor'))
    i++
  }
}

/* ============================================================== 无界面场景白名单（第 ⑤ 项用） */

// 无界面场景：产品本身没有 UI（MCP / AI 工具能力），
// 用户口径是"不做视频级验证"，验收方式 = mcp-server 测试套件断言。
// 豁免的只是"必须有视频"，图仍然必须有。
// 注意：只列「产品没有页面 UI」的场景。07-AI集成/4-令牌管理 有后台真实 UI 页面，**不豁免**。
// 唯一数据源：判定逻辑只查下面的 Set，不要在别处重复写场景名。
const NO_UI_SCENES = [
  '07-AI集成/1-MCP最小闭环',
  '07-AI集成/2-并发冲突（AI端）',
  '07-AI集成/3-工具能力矩阵',
]
const NO_UI_SET = new Set(NO_UI_SCENES)

/* ============================================================== 通用小工具 */

const results = []
/**
 * 遍历跳过清单：
 *  - node_modules/.git：无关；
 *  - _vendor：docsify 等第三方库，不是自产内容，扫禁词只会出噪声；
 *  - _deploy/_evidence：dev 站的内部分析材料，nginx 里 location 明确 deny（对外 404），不是泄露面；
 *  - dist-prod：dev 产物里嵌着 prod 产物，而两者是**两个独立待验目标**，
 *    扫 /root/docsify 时若连它一起算，页面数/引用数会翻倍（本脚本本来就按 --root 分别验）。
 */
const SKIP_DIRS = new Set(['node_modules', '.git', '_vendor', '_deploy', '_evidence'])
const ROOT_BASE = posix.basename(ROOT.replace(/\\/g, '/'))

function walk(rel = '', out = []) {
  let entries
  try { entries = readdirSync(join(ROOT, rel), { withFileTypes: true }) } catch { return out }
  for (const e of entries) {
    const childRel = rel ? `${rel}/${e.name}` : e.name
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue
      if (e.name === 'dist-prod' && ROOT_BASE !== 'dist-prod') continue
      walk(childRel, out)
    } else out.push(childRel)
  }
  return out
}
function readText(abs) { try { return readFileSync(abs, 'utf8') } catch { return '' } }
function isFile(abs) { try { return statSync(abs).isFile() } catch { return false } }
const zhSort = (a, b) => a.localeCompare(b, 'zh')

/** SVG 只取文字层（<text>/<tspan> 内容）：mermaid 产物是单行浮点坐标，裸扫必然撞出 5173/409 之类假命中 */
function svgText(svg) {
  const chunks = []
  for (const re of [/<text\b[^>]*>([\s\S]*?)<\/text>/g, /<tspan\b[^>]*>([\s\S]*?)<\/tspan>/g]) {
    for (const m of svg.matchAll(re)) chunks.push(m[1].replace(/<[^>]+>/g, ' '))
  }
  return chunks.join('\n')
}

/** md 里的相对引用 → 站点相对路径（POSIX）。外链 / data: / 越出站点根的一律返回 null 并说明原因 */
function toSiteRel(dir, ref) {
  if (/^(?:https?:)?\/\//i.test(ref) || /^(?:data|mailto):/i.test(ref)) return null
  let p = ref.split('#')[0].split('?')[0]
  try { p = decodeURIComponent(p) } catch { /* 保留原样 */ }
  if (!p) return null
  const norm = p.startsWith('/')
    ? posix.normalize(p.slice(1))
    : posix.normalize(dir === '.' ? p : posix.join(dir, p))
  return norm.startsWith('..') ? { escape: norm } : { rel: norm }
}

/** 站点相对路径 → 本地文件：dev 站 /_assets/* 是 nginx alias 到源素材（不落在产物目录下），prod 站是真拷贝，故先查产物再回落源目录 */
function localFile(rel) {
  const inRoot = join(ROOT, rel)
  if (existsSync(inRoot)) return inRoot
  if (rel.startsWith('_assets/img/')) return join(DOCS, rel.slice('_assets/img/'.length))
  if (rel.startsWith('_assets/video/')) return join(VIDEOS, rel.slice('_assets/video/'.length))
  return inRoot
}

/** 逐段百分号编码：中文目录名/文件名要编码才能进 URL，直接拼会被 undici 拒或服务端 400 */
function toUrl(rel) {
  if (/^https?:\/\//i.test(rel)) return rel
  const enc = rel.split('/').map((s) => encodeURIComponent(s)).join('/')
  return `${BASE}/${enc}`
}

/** 并发探测：Range: bytes=0-0 只取 1 字节，mp4 也不会被整包下载 */
async function probe(rels, concurrency = 10) {
  const map = new Map()
  const queue = [...rels]
  const one = async (rel) => {
    const url = toUrl(rel)
    try {
      const res = await fetch(url, { headers: { Range: 'bytes=0-0' }, signal: AbortSignal.timeout(20000) })
      try { await res.body?.cancel() } catch { /* 忽略 */ }
      return { status: res.status, ok: res.status >= 200 && res.status < 300 }
    } catch (e) {
      return { status: 0, ok: false, err: e.message ?? String(e) }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, queue.length || 1) }, async () => {
    while (queue.length) {
      const rel = queue.shift()
      map.set(rel, await one(rel))
    }
  }))
  return map
}

function push(id, title, ok, detail, lines = []) {
  results.push({ id, title, ok, detail, lines })
}

/* ============================================================== ① 页面数 */

const allFiles = walk()
const mdFiles = allFiles.filter((f) => f.endsWith('.md')).sort(zhSort)
const countMd = (prefix) => mdFiles.filter((f) => f.startsWith(prefix)).length
const scenarioPages = countMd('material/scenarios/')
const diagramPages = countMd('material/diagrams/')

/* ============================================================== ⑤ 场景素材齐全（先算，①要引用它的结论） */

const SCENES = join(DOCS, 'flows', '02-业务场景')
const sceneGaps = []
const sceneList = []
if (existsSync(SCENES)) {
  for (const cat of readdirSync(SCENES, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort(zhSort)) {
    const catDir = join(SCENES, cat)
    const scenes = readdirSync(catDir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort(zhSort)
    for (const scene of scenes) {
      // 图：<类>/<N-场景>/ 下的 .svg 或 .png；旧结构允许 <类>/<N-场景>.svg|png
      const scenePath = `${cat}/${scene}`
      const imgs = readdirSync(join(catDir, scene)).filter((f) => /\.(svg|png)$/i.test(f))
      for (const ext of ['.svg', '.png']) if (existsSync(join(catDir, scene + ext))) imgs.push(scene + ext)
      // 视频：<类>/<N-场景>.mp4 或 <类>/<N-场景>-<序号>-<分段名>.mp4（与 build-material 的匹配口径一致）
      let vids = []
      try {
        vids = readdirSync(join(VIDEOS, cat)).filter((f) => f.endsWith('.mp4')
          && (f === `${scene}.mp4` || f.startsWith(`${scene}-`)))
      } catch { /* 没有视频目录 = 0 个 */ }
      const noUi = NO_UI_SET.has(scenePath)
      const missing = []
      // 图：无论是否无界面场景都必须有（豁免不等于免检，缺图照旧 FAIL）
      if (imgs.length === 0) missing.push('图')
      // 视频：仅对白名单内的无界面场景跳过这一条，名单外缺视频照旧算缺件
      if (!noUi && vids.length === 0) missing.push('视频')
      sceneList.push({ path: scenePath, imgs: imgs.length, vids: vids.length, noUi })
      if (missing.length) sceneGaps.push({ path: scenePath, missing })
    }
  }
}
const completeScenes = sceneList.length - sceneGaps.length

{
  // ① 判「两类页面都产出了」。源素材比产物新（还没重建）时页数会少于齐全场景数，那是「等重建」不算坏，只提示
  const pageSet = new Set(mdFiles)
  const noPage = sceneList.filter((s) => !s.missing && !pageSet.has(`material/scenarios/${s.path}.md`))
  const lines = []
  if (noPage.length) {
    lines.push(`提示：源侧图+视频齐全场景 ${completeScenes} 个，其中 ${noPage.length} 个在产物里没有页面（重建后应补齐）`)
    for (const s of noPage) lines.push(`  material/scenarios/${s.path}.md 缺失`)
  }
  push('①', '页面数', scenarioPages > 0 && diagramPages > 0,
    `场景页 ${scenarioPages} / 图页 ${diagramPages}（源侧齐全场景 ${completeScenes}）`, lines)
}

/* ============================================================== ②③ 图 / 视频引用完整性 */

const imgRefs = []
const vidRefs = []
for (const rel of mdFiles) {
  const text = readText(join(ROOT, rel))
  const dir = posix.dirname(rel)
  for (const m of text.matchAll(/!\[[^\]]*\]\(\s*([^)\s]+)/g)) imgRefs.push({ from: rel, rel: toSiteRel(dir, m[1]) })
  for (const m of text.matchAll(/<img\b[^>]*\ssrc=["']([^"']+)["']/g)) imgRefs.push({ from: rel, rel: toSiteRel(dir, m[1]) })
  for (const m of text.matchAll(/<video\b[^>]*\ssrc=["']([^"']+)["']/g)) vidRefs.push({ from: rel, rel: toSiteRel(dir, m[1]) })
  for (const m of text.matchAll(/\]\(\s*([^)\s]+\.mp4)/g)) vidRefs.push({ from: rel, rel: toSiteRel(dir, m[1]) })
}

const uniqueRels = [...new Set([...imgRefs, ...vidRefs].map((r) => r.rel && r.rel.rel).filter(Boolean))]
const probed = SKIP_HTTP
  ? new Map(uniqueRels.map((rel) => [rel, { ok: isFile(localFile(rel)), status: 'local' }]))
  : await probe(uniqueRels)

function auditRefs(refs, label) {
  let ok = 0
  const bad = new Map()
  for (const r of refs) {
    if (!r.rel) continue
    if (r.rel.escape) { bad.set(r.rel.escape, { ...r.rel, from: r.from, status: '越出站点根' }); continue }
    const p = probed.get(r.rel.rel) ?? { ok: false, status: 'n/a' }
    if (p.ok) ok++
    else if (!bad.has(r.rel.rel)) bad.set(r.rel.rel, { rel: r.rel.rel, from: r.from, status: p.status })
  }
  const total = refs.filter((r) => r.rel).length
  const lines = [...bad.values()].map((b) =>
    `${b.rel ?? b.escape}  ← ${b.from}  [${b.status}]`)
  const mode = SKIP_HTTP ? '本地存在' : '200'
  push(label.id, label.title, bad.size === 0,
    `${mode}: ${ok} / 引用 ${total}，失败 ${bad.size}`, lines)
  return { total, ok, fail: bad.size }
}

const imgStat = auditRefs(imgRefs, { id: '②', title: '图片引用完整性' })
const vidStat = auditRefs(vidRefs, { id: '③', title: '视频引用完整性' })

/* ============================================================== ④ 图 ↔ mermaid 源码配对 */

const docFiles = []
;(function walkDocs(rel = '') {
  let entries
  try { entries = readdirSync(join(DOCS, rel), { withFileTypes: true }) } catch { return }
  for (const e of entries) {
    const childRel = rel ? `${rel}/${e.name}` : e.name
    if (e.isDirectory()) walkDocs(childRel)
    else docFiles.push(childRel)
  }
})()
const mmdSet = new Set(docFiles.filter((f) => f.endsWith('.mmd')).map((f) => f.slice(0, -4)))
const svgSet = new Set(docFiles.filter((f) => f.endsWith('.svg')).map((f) => f.slice(0, -4)))
const mmdOnly = [...mmdSet].filter((b) => !svgSet.has(b)).sort(zhSort)
const svgOnly = [...svgSet].filter((b) => !mmdSet.has(b)).sort(zhSort)
push('④', '图与源码配对', mmdOnly.length === 0 && svgOnly.length === 0,
  `mmd ${mmdSet.size} / svg ${svgSet.size}，孤儿 ${mmdOnly.length + svgOnly.length}`,
  [...mmdOnly.map((b) => `有 mmd 缺 svg：${b}.mmd`), ...svgOnly.map((b) => `有 svg 缺 mmd：${b}.svg`)])

/* ============================================================== ⑤ 场景「图 + 视频」齐全 */

// 无界面场景（白名单）只免「必须有视频」；图照旧必查，名单外的场景行为完全不变。
const noUiList = sceneList.filter((s) => s.noUi)
// 反向保护：白名单里写了但目录不存在 = 名单已过期/写错（不会放宽检查，只提示，避免悄悄失效）
const noUiStale = NO_UI_SCENES.filter((n) => !sceneList.some((s) => s.path === n))

push('⑤', '场景图+视频齐全', sceneGaps.length === 0,
  `场景 ${sceneList.length} 个，缺件 ${sceneGaps.length}`
  + (noUiList.length ? `（其中 ${noUiList.length} 个为无界面场景，仅校验图）` : ''),
  [
    ...sceneGaps.map((g) => `${g.path}  缺 ${g.missing.join(' + ')}`),
    // 被豁免的场景逐个打印出来，不悄悄跳过
    ...noUiList.map((s) => `无界面场景免视频：${s.path}（图 ${s.imgs} 个，已在⑦⑧校验）`),
    ...noUiStale.map((n) => `提示：NO_UI_SCENES 里的 ${n} 未匹配到任何场景目录（名单已过期？）`),
  ])

/* ============================================================== ⑦⑧ SVG 体检对象 */

// 两处都要验：源素材（下一版重建就发布它，是「事前」门禁）+ 产物里的 SVG 副本（prod 站真发布的字节）。
// dev 站的图靠 nginx alias 直出源目录，产物里没有副本，所以它只有源这一份。
const svgSrc = docFiles.filter((f) => f.endsWith('.svg')).sort(zhSort)
const svgArt = allFiles.filter((f) => f.endsWith('.svg')).sort(zhSort)
const svgTargets = [
  ...svgSrc.map((f) => ({ label: `源 ${f}`, abs: join(DOCS, f) })),
  ...svgArt.map((f) => ({ label: `产物 ${f}`, abs: join(ROOT, f) })),
]
const svgDetail = `检查 ${svgTargets.length} 个 svg（源 ${svgSrc.length} + 产物 ${svgArt.length}）`

/* ============================================================== ⑥ 内网信息泄露扫描 */

// 禁词表：内网 IP / 开发端口 / 网关端口 / 文档站端口 / 测试账号 / 调试入口 / 数据库口令；用正则因为「admin / 123456」常带空格
const LEAK_WORDS = [
  ['100.113.135.55', /100\.113\.135\.55/g], [':5173', /:5173\b/g], [':5176', /:5176\b/g], [':18080', /:18080\b/g], [':3027', /:3027\b/g],
  ['admin/123456', /admin\s*\/\s*123456/gi], ['MCP Inspector', /MCP\s?Inspector/gi], ['qiqi2026', /qiqi2026/gi],
].map(([label, re]) => ({ label, re }))
// 扫描对象 = 产物里的 md/html/js + 全部 SVG（源 svg 会被 dev 站 alias 直出，产物 svg 是 prod 真发布的字节）
// --internal-ok（仅 dev 站）：只豁免根目录 README.md 这一个文件（dev 索引页），其它文件照扫，不是把问题藏起来。
const leakSkipIsReadme = (f) => INTERNAL_OK && f === INTERNAL_OK_SKIP
const leakMdTargets = allFiles
  .filter((f) => /\.(md|html|js)$/i.test(f))
  .filter((f) => !leakSkipIsReadme(f))
  .map((f) => ({ label: f, abs: join(ROOT, f), svg: false }))
const leakTargets = [
  ...leakMdTargets,
  ...svgTargets.map((t) => ({ ...t, svg: true })),
]
const leakHits = []
for (const t of leakTargets) {
  const raw = readText(t.abs)
  const text = t.svg ? svgText(raw) : raw   // .svg 只取文字层，避免坐标误撞
  for (const w of LEAK_WORDS) {
    const n = (text.match(w.re) ?? []).length
    if (n) leakHits.push(`${t.label}  ⊃ ${w.label} ×${n}`)
  }
}
push('⑥',
  INTERNAL_OK
    ? '内网信息泄露（已豁免 README.md：dev 站索引页，内网专用）'
    : '内网信息泄露',
  leakHits.length === 0,
  `扫描 ${leakTargets.length} 个文件，命中 ${leakHits.length}（prod 环境不要加 --internal-ok）`, leakHits)

/* ============================================================== ⑦ SVG 硬指标 */

// foreignObject 必须 0（HTML 标签在 <img> 里会错位/空白）；根节点必须有 width/height（否则 <img> 无固有尺寸，缩放组件算成 0×0）
const svgBad = []
for (const { label, abs } of svgTargets) {
  const svg = readText(abs)
  const fo = (svg.match(/foreignObject/gi) ?? []).length
  const rootTag = (svg.match(/<svg\b[^>]*>/i) ?? [''])[0]
  const why = []
  if (fo > 0) why.push(`foreignObject×${fo}`)
  if (!/\swidth\s*=/.test(rootTag)) why.push('根节点缺 width')
  if (!/\sheight\s*=/.test(rootTag)) why.push('根节点缺 height')
  if (why.length) svgBad.push(`${label}  ${why.join(' / ')}`)
}
push('⑦', 'SVG 硬指标', svgBad.length === 0, `${svgDetail}，不合规 ${svgBad.length}`, svgBad)

/* ============================================================== ⑧ 图内禁词（文字层） */

// 图里写 HTTP 方法/后台路径/中间件名 = 把内部实现细节印在公网上；口径同 ⑥，只扫文字层
const DIAGRAM_WORDS = ['GET', 'POST', 'PUT', 'DELETE', '/admin', '/portal', 'MinIO', 'KvStore', 'status=']
const diagramHits = []
for (const { label, abs } of svgTargets) {
  const text = svgText(readText(abs))
  const hit = DIAGRAM_WORDS.filter((w) => text.includes(w))
  if (hit.length) diagramHits.push(`${label}  ⊃ ${hit.join(', ')}`)
}
push('⑧', '图内禁词（文字层）', diagramHits.length === 0, `${svgDetail}，命中 ${diagramHits.length}`, diagramHits)

/* ============================================================== ⑨ 侧边栏死链 */

const sidebarPath = join(ROOT, '_sidebar.md')
const deadLinks = []
let sidebarLinks = 0
if (!existsSync(sidebarPath)) {
  deadLinks.push(`${sidebarPath} 不存在（docsify loadSidebar: true 会整站失去导航）`)
} else {
  for (const m of readText(sidebarPath).matchAll(/\[[^\]]*\]\(\s*([^)\s]+)/g)) {
    const raw = m[1]
    if (/^(?:https?:|mailto:|tel:)/i.test(raw) || raw.startsWith('#')) continue // 外链/纯锚点不是站内文件
    let p = raw.split('#')[0].split('?')[0]
    try { p = decodeURIComponent(p) } catch { /* 保留原样 */ }
    if (!p) continue
    sidebarLinks++
    // 目录链接（首页 = /）落到 README.md / index.html，其余必须命中真实 .md
    const target = p === '/' || p === './'
      ? (isFile(join(ROOT, 'README.md')) ? join(ROOT, 'README.md') : join(ROOT, 'index.html'))
      : join(ROOT, p.replace(/^\.?\//, ''))
    if (!isFile(target)) deadLinks.push(`${p}  →  ${target.replace(ROOT + '/', '')} 不存在`)
  }
}
push('⑨', '侧边栏一致性', deadLinks.length === 0, `链接 ${sidebarLinks} 个，死链 ${deadLinks.length}`, deadLinks)

/* ============================================================== 下限断言（防止「扫不到东西 → 判通过」） */

// 九项检查各自只看「扫到的东西对不对」，扫到 0 个就是 0 个问题 = 0 个失败 → 空跑即通过（fail-open）。
// 这一节只看数量：每个维度的实测值必须 ≥ FLOOR 里的下限，否则整体 FAIL、退出码 1。
const floorActual = {
  scenes: sceneList.length,
  pages: scenarioPages + diagramPages,
  images: imgStat.total,
  videos: vidStat.total,
  svgChecked: svgTargets.length,
  sidebarLinks,
}
const floorItems = FLOOR_KEYS.map((key) => ({
  key,
  label: FLOOR_LABEL[key],
  actual: floorActual[key],
  floor: FLOOR[key],
  ok: floorActual[key] >= FLOOR[key],
}))
const floorFailed = floorItems.filter((f) => !f.ok)

/* ============================================================== 输出 */

for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'} ${r.id}  ${r.title.padEnd(0)}  ${r.detail}`)
  const shown = r.lines.slice(0, 60)
  for (const line of shown) console.log(`      ${line}`)
  if (r.lines.length > shown.length) console.log(`      … 其余 ${r.lines.length - shown.length} 条已省略`)
}

console.log('')
console.log('── 下限断言（防止空跑通过）──')
for (const f of floorItems) {
  console.log(f.ok
    ? `✅ ${f.label} ${f.actual} ≥ ${f.floor}（${f.key}）`
    : `❌ ${f.label} ${f.actual} < ${f.floor}（${f.key}，差 ${f.floor - f.actual}）`)
}

const failed = results.filter((r) => !r.ok)
const imgMode = SKIP_HTTP ? '本地存在' : '200'
const vidMode = SKIP_HTTP ? '本地存在' : '200'
console.log('')
console.log(`页面 ${scenarioPages + diagramPages}（场景 ${scenarioPages} / 图页 ${diagramPages}）  `
  + `图片引用 ${imgStat.total}（${imgMode}: ${imgStat.ok}，失败 ${imgStat.fail}）  `
  + `视频引用 ${vidStat.total}（${vidMode}: ${vidStat.ok}，失败 ${vidStat.fail}）`)
console.log(`孤儿图 ${mmdOnly.length + svgOnly.length}  场景缺件 ${sceneGaps.length}  `
  + `泄露命中 ${leakHits.length}  SVG 不合规 ${svgBad.length}  死链 ${deadLinks.length}`)
// 失败原因先写下限（它解释的是「为什么上面的 ✅ 不算数」），再写常规项
const reasons = []
if (floorFailed.length) reasons.push(`下限断言：${floorFailed.map((f) => `${f.label} ${f.actual} < ${f.floor}`).join('、')}`)
if (failed.length) reasons.push(failed.map((r) => r.id + r.title).join('、'))
const pass = reasons.length === 0
console.log(`结论：${pass ? 'PASS' : 'FAIL'}` + (pass ? '' : `（${reasons.join('；')}）`))
process.exit(pass ? 0 : 1)
