#!/usr/bin/env node
/**
 * check-docs-site.mjs —— 文档站重建后的「一次体检」（零依赖：node 内置 + 全局 fetch）
 *
 * 为什么要有它：站点每次重建要验 12 项（页面数、图/视频能否取到、图与 mermaid 源码是否配对、
 * 业务场景是否「图+视频」齐全（无界面场景仅查图，见 NO_UI_SCENES）、内网信息有没有混进产物、
 * SVG 硬指标、图内禁词、侧边栏死链、**私人文稿零泄漏**（口语稿 + build-material.mjs 的 PRIVATE_DOCS
 * 那 12 篇私人笔记，产物里一个字节都不许有，见第 ⑩ 项）、**产物洁净度**（私密文件名/原始录屏/
 * 绝对路径与主机名，见第 ⑪ 项）、**侧边栏编号连续性**（显示名不带编号，少一个场景肉眼看不出，
 * 但 URL 会露馅 7→9，见第 ⑫ 项）），
 * 人工 curl + 数数必漏，所以要一条命令给出 PASS/FAIL + 具体数字 + 可行动的失败清单。
 *
 * 用法：
 *   # dev 站（内网专用）：首页 README.md 就是内网索引页，本来就列着内网 IP/端口/测试账号，
 *   # 那是有意为之、不是泄露，所以 dev 站用 --internal-ok 让第 ⑥ 项豁免 README.md 这一个文件。
 *   # dev 站还要加 --private-ok（口语稿/私人笔记/_deploy/_evidence 在 dev 都是刻意的，第 ⑩⑪ 项对 dev 豁免）。
 *   node scripts/check-docs-site.mjs --root /root/docsify --base http://100.113.135.55:3027 --internal-ok --private-ok
 *   # prod 站（公网）：**绝对不要加 --internal-ok**，README.md 也必须过内网信息扫描。
 *   node scripts/check-docs-site.mjs --root /root/docsify/dist-prod --base https://docs.rose7.cn
 *   node scripts/check-docs-site.mjs --root /root/docsify --skip-http --internal-ok --private-ok   # 只做静态检查
 *   # 一句话：dev 站 = --internal-ok --private-ok；prod 站 = 不带任何豁免开关（带了就是把问题藏起来）。
 *
 * 参数：--root 站点产物目录（必填） / --base 站点基址（除非 --skip-http） / --skip-http 只查本地文件
 *       --internal-ok 仅 dev 站用：第 ⑥ 项豁免首页文件（默认 README.md，见 --internal-ok-files），其余文件照扫
 *       --internal-ok-files=a,b  dev 站豁免的内网扫描文件名列表（default README.md）。Rspress 站传
 *                     index.html,index.md —— 它的首页不叫 README.md
 *       --page-ext=md|html  产物里「页面」的扩展名（default md）。Rspress 静态产物是 .html
 *       --sidebar=<path>    侧边栏文件（default <root>/_sidebar.md）。Rspress 站根不放它，
 *                     指到 /root/docs-rspress/_sidebar.md（与 config 侧边栏同源同序）
 *       --private-ok 仅 dev 站用：第 ⑩ 项（私人文稿零泄漏）+ 第 ⑪ 项（产物洁净度）整体豁免 ——
 *       dev 站刻意含口语稿/私人笔记，也刻意保留 _deploy/_evidence（nginx deny 的内网材料）
 *       --private-src 指定 build-material.mjs（第 ⑩ 项的 PRIVATE_DOCS 名单来源；默认自动在
 *       --root 及其上一级找，找不到就降级成关键词扫描并判 FAIL）
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
 *   所以十二项检查之后加一节「下限断言」：每个维度的实测数量必须 ≥ FLOOR 里的下限，
 *   不达标就整条 FAIL、退出码 1 —— 宁可因为「扫到的东西太少」拦下来，也不要因为「问题数为 0」放过去。
 *   第 ⑩⑪ 项同理：产物里连一个 .md/.html 都没扫到（--root 指错/产物被清空）时不许判 ✅，
 *   所以它们也有 privacyScanned / privateDocs / cleanScanned 三条下限。
 *   阈值集中在下面唯一的 FLOOR_DEFAULTS / FLOOR 常量里，判定逻辑只读它，别处不许重复写数字。
 */
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs'
import { join, dirname, resolve, posix } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseSidebar, checkContinuity } from './check-sidebar-continuity.mjs'

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
// 豁免哪些「首页文件」不做内网信息扫描。docsify 的首页是 README.md；Rspress 的首页是 index.html
// （还带一份 index.md 的 llms 镜像）→ 用列表表达，默认仍是 README.md，docsify 侧行为逐字节不变。
// 支持简单通配 *（按站点相对路径整串匹配）。为什么需要：Rspress 站把首页内容同时 SSR 进
// index.html 和它那条 route-<hash>.js 分片里，只豁免 html 会让「首页同一段内容」从 JS 侧照旧命中。
const INTERNAL_OK_FILES = (opt('--internal-ok-files') ?? 'README.md')
  .split(',').map((x) => x.trim()).filter(Boolean)
const globToRe = (g) => new RegExp(`^${g.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*')}$`)
const INTERNAL_OK_RES = INTERNAL_OK_FILES.map(globToRe)
// dev 站专用开关（第 ⑩⑪ 项）：dev 站是内网「完整版」，口语稿、那 12 篇私人笔记、_deploy/_evidence
// 都是刻意保留的；prod 站（dist-prod → docs.rose7.cn）绝不许带。
const PRIVATE_OK = argv.includes('--private-ok')
const DOCS = resolve(opt('--docs') ?? join(REPO, 'docs'))
const VIDEOS = resolve(opt('--videos') ?? join(REPO, 'e2e', 'report', 'videos'))

// 产物里「一个页面」长什么样：docsify 站点页面就是 .md 源文件；Rspress 静态产物是 .html
// （Rspress 还会顺手生成同名 .md 的 llms 镜像，但那份把 <video> 组件吃掉了，所以引用扫描必须看 .html）。
// 只影响 ①页面数 / ②③引用扫描 / ⑤「场景有没有对应页面」，默认 md —— 原有判定完全不变。
const PAGE_EXT = (opt('--page-ext') ?? 'md').replace(/^\./, '')
if (!['md', 'html'].includes(PAGE_EXT)) {
  console.error(`✗ --page-ext=${PAGE_EXT} 不支持（只支持 md / html）`)
  process.exit(2)
}
// 侧边栏文件：docsify 在站点根（<root>/_sidebar.md）；Rspress 站根不放它，
// 用 --sidebar=<path> 指到生成物 /root/docs-rspress/_sidebar.md（与 config 侧边栏同源同序）。
const SIDEBAR = opt('--sidebar') ? resolve(opt('--sidebar')) : null

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
  privacyScanned: 30, // ⑩ 被扫的产物文本文件数（md/html/txt/json，实测 57）：扫到 0 个 = 根本没查
  privateDocs: 12,   // ⑩ 从 build-material.mjs 解析到的 PRIVATE_DOCS 条数（实测 12）：解析不到 = 无法证明没泄漏
  cleanScanned: 100, // ⑪ 被扫的产物路径条目数（文件+目录，实测 245）：扫到 0 个 = 根目录指错/产物空
}
const FLOOR_LABEL = {
  scenes: '场景数', pages: '页面数', images: '图片引用',
  videos: '视频引用', svgChecked: '检查的 svg', sidebarLinks: '侧边栏链接',
  privacyScanned: '私密扫描文本', privateDocs: '私人文档名单', cleanScanned: '洁净度扫描路径',
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
  // 纯概念页：内容是「平台同步日志」与「仓库交换台账」逐行对照，没有专属界面；
  // 证据已由 09/1 的视频覆盖（同步日志页）。按「无界面场景免视频」处理。
  '09-多平台接入/3-与内部系统的边界',
]
const NO_UI_SET = new Set(NO_UI_SCENES)

/* ------------------------------------------------- 待补视频清单（第 ⑤ 项用） */

// 「待补视频」= 有界面、该录、但**确实还没录**的场景（spec 仍 fixme）。
// 与 NO_UI_SCENES 的区别：那是「视频不适用」，这是「视频欠着」—— 技术债必须显式登记，不许悄悄绿。
// 规则（每条都由第 ⑤ 项强制校验，不是注释）：
//   · 命中清单 → 该项降级为 ⚠ 并计入「待补 N 项」，⑤ 仍算通过；
//   · 不在清单里的缺视频 → 照旧判红（门禁没有放松）；
//   · 每条必须同时写 reason（为什么缺）与 plan（何时/怎么补），缺一即 FAIL；
//   · 清单长度 ≤ PENDING_VIDEO_MAX（防止长成垃圾桶）；
//   · 清单里的 path 必须真的存在（场景改名/删了却没清清单 → FAIL）；
//   · 清单里的场景**已经有视频了** → FAIL（提示「可从未补清单移除」），防清单长期不清。
// 空数组是**合法状态**（当前即如此：13/15 已补录完成）：⑤ 只按「缺件 0」判定，不假定清单非空。
const PENDING_VIDEO_MAX = 3
const PENDING_VIDEO = []
const PENDING_VIDEO_MAP = new Map(PENDING_VIDEO.map((p) => [p.path, p]))

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
// 页面文件：PAGE_EXT=md（docsify）取 .md；PAGE_EXT=html（Rspress 产物）取 .html
const pageFiles = allFiles.filter((f) => f.endsWith(`.${PAGE_EXT}`)).sort(zhSort)
const countPage = (prefix) => pageFiles.filter((f) => f.startsWith(prefix)).length
const scenarioPages = countPage('material/scenarios/')
const diagramPages = countPage('material/diagrams/')

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
  const pageSet = new Set(pageFiles)
  const noPage = sceneList.filter((s) => !s.missing && !pageSet.has(`material/scenarios/${s.path}.${PAGE_EXT}`))
  const lines = []
  if (noPage.length) {
    lines.push(`提示：源侧图+视频齐全场景 ${completeScenes} 个，其中 ${noPage.length} 个在产物里没有页面（重建后应补齐）`)
    for (const s of noPage) lines.push(`  material/scenarios/${s.path}.${PAGE_EXT} 缺失`)
  }
  push('①', '页面数', scenarioPages > 0 && diagramPages > 0,
    `场景页 ${scenarioPages} / 图页 ${diagramPages}（源侧齐全场景 ${completeScenes}）`, lines)
}

/* ============================================================== ②③ 图 / 视频引用完整性 */

const imgRefs = []
const vidRefs = []
for (const rel of pageFiles) {
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

// ⑤ 分三类：被待补清单登记的（⚠ 降级）、没登记的（❌ 照旧判红）、无界面白名单（免视频）。
// 清单自身的腐烂（超长 / 路径不存在 / 条目重复 / 缺 reason|plan / 已经有视频）同样判红 —— 门禁只管是否「登记过」，不管是否「合规矩」就等于没人管。
const pendingHits = sceneGaps.filter((g) => PENDING_VIDEO_MAP.has(g.path))
const hardGaps = sceneGaps.filter((g) => !PENDING_VIDEO_MAP.has(g.path))
const pendingProblems = []
const pendingSeen = new Set()
if (PENDING_VIDEO.length > PENDING_VIDEO_MAX) {
  pendingProblems.push(`待补清单 ${PENDING_VIDEO.length} 条 > 上限 ${PENDING_VIDEO_MAX} 条（清单正在变成垃圾桶）`)
}
for (const p of PENDING_VIDEO) {
  if (!p || !p.path) { pendingProblems.push(`待补清单存在没有 path 的条目：${JSON.stringify(p)}`); continue }
  if (!String(p.reason || '').trim()) pendingProblems.push(`待补清单 ${p.path} 缺 reason（为什么缺）`)
  if (!String(p.plan || '').trim()) pendingProblems.push(`待补清单 ${p.path} 缺 plan（何时/怎么补）`)
  if (pendingSeen.has(p.path)) pendingProblems.push(`待补清单 ${p.path} 重复登记`)
  pendingSeen.add(p.path)
  const hit = sceneList.find((s) => s.path === p.path)
  if (!hit) {
    pendingProblems.push(`待补清单里的 ${p.path} 未匹配到任何场景目录（场景改名/删除后没清清单？）`)
  } else if (hit.vids > 0) {
    pendingProblems.push(`待补清单里的 ${p.path} 已经有 ${hit.vids} 段视频了 → 可从待补清单移除（防清单长期不清）`)
  }
}

push('⑤', '场景图+视频齐全', hardGaps.length === 0 && pendingProblems.length === 0,
  `场景 ${sceneList.length} 个，缺件 ${sceneGaps.length}`
  + (hardGaps.length
    ? `（其中 ${hardGaps.length} 项未登记进「待补视频」清单 → 判红）`
    : (pendingHits.length ? '（均在「待补视频」清单内，已登记原因与计划）' : ''))
  + (noUiList.length ? `（其中 ${noUiList.length} 个为无界面场景，仅校验图）` : ''),
  [
    // 未登记的缺件：真红，逐条点名
    ...hardGaps.map((g) => `${g.path}  缺 ${g.missing.join(' + ')}（未在待补清单登记 → 判红）`),
    // 已登记的缺件：⚠ 降级，但原因与计划必须现场打出来（不靠去翻源码）
    ...pendingHits.map((g) => {
      const p = PENDING_VIDEO_MAP.get(g.path)
      return `⚠ ${g.path}  缺 ${g.missing.join(' + ')} —— 原因：${p.reason} / 计划：${p.plan}`
    }),
    ...(pendingHits.length || PENDING_VIDEO.length
      ? [`（待补 ${pendingHits.length} 项 / 清单上限 ${PENDING_VIDEO_MAX} 项）`]
      : ['（待补清单为空：无待补视频）']),
    ...pendingProblems.map((x) => `待补清单自检不通过：${x}`),
    // 被豁免的场景逐个打印出来，不悄悄跳过
    ...noUiList.map((s) => `无界面场景免视频：${s.path}（图 ${s.imgs} 个，已在⑦⑧校验）`),
    ...noUiStale.map((n) => `提示：NO_UI_SCENES 里的 ${n} 未匹配到任何场景目录（名单已过期？）`),
  ])
const pendingCount = pendingHits.length

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
const isLeakExemptByName = (f) => INTERNAL_OK && INTERNAL_OK_RES.some((re) => re.test(f))
// 被豁免的首页 HTML **引用的** route-*.js 分片一并豁免：那是同一页内容的 SSR 载荷，
// 换个文件形态还是同一段内容（Rspress 把首页正文同时写进 index.html 和它的 route 分片）。
// 只跟被豁免的 HTML 走，不会波及别的页面的分片 —— 不是「JS 一律不看」。
const leakDerived = new Set()
if (INTERNAL_OK) {
  for (const rel of allFiles.filter((f) => f.endsWith('.html') && isLeakExemptByName(f))) {
    for (const m of readText(join(ROOT, rel)).matchAll(/["']([^"']*route-[^"']+?\.js)["']/g)) {
      leakDerived.add(m[1].replace(/^\.?\//, ''))
    }
  }
}
const leakSkipIsReadme = (f) => isLeakExemptByName(f) || leakDerived.has(f)
const leakExempted = INTERNAL_OK ? allFiles.filter((f) => /\.(md|html|js)$/i.test(f) && leakSkipIsReadme(f)) : []
const leakExemptLabel = INTERNAL_OK
  ? [...INTERNAL_OK_FILES, ...(leakDerived.size ? [`${[...leakDerived].join(', ')}（首页 HTML 引用的分片，自动跟随）`] : [])].join(', ')
  : ''
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
// docsify 侧（缺省名单 = README.md）保持原来的措辞与输出逐字节不变；只有传了自定义名单才展开细节
const IS_LEGACY_INTERNAL_OK = INTERNAL_OK_FILES.length === 1 && INTERNAL_OK_FILES[0] === 'README.md'
push('⑥',
  INTERNAL_OK
    ? (IS_LEGACY_INTERNAL_OK
      ? '内网信息泄露（已豁免 README.md：dev 站索引页，内网专用）'
      : `内网信息泄露（已豁免 ${leakExemptLabel}：dev 站首页及其 SSR 分片，内网专用）`)
    : '内网信息泄露',
  leakHits.length === 0,
  `扫描 ${leakTargets.length} 个文件，命中 ${leakHits.length}（prod 环境不要加 --internal-ok）`
  + (!INTERNAL_OK || IS_LEGACY_INTERNAL_OK
    ? ''
    : `；豁免 ${leakExempted.length} 个：${leakExempted.slice(0, 6).join(', ')}${leakExempted.length > 6 ? ' …' : ''}`),
  leakHits)

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

const sidebarPath = SIDEBAR ?? join(ROOT, '_sidebar.md')
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
    // 目录链接（首页 = /）落到 README.md / index.html。
    // 其余必须命中**真实产物页**：侧边栏按 docsify 约定写 .md，Rspress 产物页是同路径的 .html，
    // 所以按 PAGE_EXT 换后缀再试一次（不放松判定：三种候选都不在，照样判死链）。
    const relLink = p.replace(/^\.?\//, '')
    const candidates = p === '/' || p === './'
      ? ['README.md', 'index.html']
      : [relLink, relLink.replace(/\.md$/, `.${PAGE_EXT}`), relLink.replace(/\.md$/, '/index.html')]
    const target = candidates.map((c) => join(ROOT, c)).find((abs) => isFile(abs))
    if (!target) deadLinks.push(`${p}  →  ${join(ROOT, candidates[0]).replace(ROOT + '/', '')} 不存在`)
  }
}
push('⑨', '侧边栏一致性', deadLinks.length === 0, `链接 ${sidebarLinks} 个，死链 ${deadLinks.length}`, deadLinks)

/* ============================================================== ⑩ 私人文稿零泄漏 */

// 为什么单列这一项：口语稿（作者口述用的私人材料）和 build-material.mjs 里 PRIVATE_DOCS 那 12 篇
// 业务笔记，都**只该在 dev 站出现**。prod 侧现在靠 build-material.mjs 的实现细节保证干净
// （`if (MODE !== 'dev') return null` 不读口语稿、prod 分支不拷那 12 篇），但那是代码层约定、
// 没有任何外部门禁：有人改那个 if、加一个新素材目录、或手工往 dist-prod 塞个文件，没人会拦。
// 而一旦这些字节上了 OSS + 刷了 CDN，就是 30 天缓存留在公网（见 publish.sh 头部【六】），撤不干净。
// 所以这里按「产物字节」判，不看代码怎么写的：
//   a) 文件名：任何 口语稿* / *spoken* 文件 → 泄漏
//   b) 内容：产物里的 .md/.html/.txt/.json grep 口语稿特征词 → 泄漏
//   c) 私人文档：PRIVATE_DOCS 名单（唯一来源 build-material.mjs，不在这里另抄一份）里的
//      原文件 / 页面链接 / 页面标题出现在产物里 → 泄漏；名单读不到就降级关键词扫描并判 FAIL（fail-closed）
const SPOKEN_FILE_RE = /口语稿|(?:^|[-_./])spoken(?:[-_./]|$)/i
// 长词在前：命中「一分钟口语稿」就不再重复报子串「口语稿」
const SPOKEN_CONTENT_WORDS = ['一分钟口语稿', '\u9762\u8bd5可直接念', 'spoken-script', '口语稿']
const PRI_TEXT_RE = /\.(?:md|html?|txt|json)$/i
const priTextFiles = allFiles.filter((f) => PRI_TEXT_RE.test(f)).sort(zhSort)

/** 取命中点附近一段（压平成一行、最多 120 字）：失败输出要能一眼看出「泄了什么」 */
function excerpt(text, needle, width = 120) {
  const i = text.indexOf(needle)
  if (i < 0) return ''
  const start = Math.max(0, i - 24)
  const body = text.slice(start, start + width).replace(/\s+/g, ' ').trim()
  return `${start > 0 ? '…' : ''}${body}${start + width < text.length ? '…' : ''}`
}

/** PRIVATE_DOCS 名单的唯一来源 = build-material.mjs；读不到就返回空名单（调用方降级 + 判 FAIL） */
function readPrivateDocs() {
  const explicit = opt('--private-src')
  const candidates = [
    ...(explicit ? [resolve(explicit)] : []),
    join(ROOT, 'build-material.mjs'),          // dev 站：构建脚本就躺在 ROOT 里
    join(ROOT, '..', 'build-material.mjs'),    // prod 站：ROOT=dist-prod，脚本在上一级
  ]
  const file = candidates.find((p) => isFile(p))
  if (!file) return { file: null, names: [] }
  const m = /const\s+PRIVATE_DOCS\s*=\s*\[([\s\S]*?)\]/.exec(readText(file))
  const names = m ? [...m[1].matchAll(/'([^']+)'|"([^"]+)"/g)].map((x) => x[1] ?? x[2]) : []
  return { file, names }
}

const PRI = readPrivateDocs()
const priNameHits = []
const priContentHits = []
const priPrivateHits = []
let priScanned = 0

if (!PRIVATE_OK) {
  // a) 文件名
  for (const rel of allFiles) {
    const m = SPOKEN_FILE_RE.exec(rel)
    if (m) priNameHits.push(`${rel}  ⊃ 文件名命中「${m[0]}」`)
  }
  // b) 内容
  for (const rel of priTextFiles) {
    const text = readText(join(ROOT, rel))
    priScanned++
    for (const w of SPOKEN_CONTENT_WORDS) {
      if (!text.includes(w)) continue
      priContentHits.push(`${rel}  ⊃ 「${w}」×${text.split(w).length - 1}  「${excerpt(text, w)}」`)
      break
    }
  }
  // c) 私人文档
  if (PRI.names.length) {
    const priSet = new Set(PRI.names)
    for (const rel of allFiles) {
      const b = posix.basename(rel)
      if (priSet.has(b)) priPrivateHits.push(`${rel}  ⊃ PRIVATE_DOCS 原文件（${b}）`)
    }
    const pages = priTextFiles.filter((f) => /\.(?:md|html?)$/i.test(f))
    const texts = pages.map((rel) => [rel, readText(join(ROOT, rel))])
    for (const name of PRI.names) {
      const stem = name.replace(/\.md$/, '')
      const srcAbs = join(dirname(PRI.file), name)
      const title = isFile(srcAbs) ? ((readText(srcAbs).match(/^#{1,6}\s+(.+?)\s*$/m) ?? [])[1] ?? '') : ''
      for (const [rel, text] of texts) {
        if (text.includes(stem)) priPrivateHits.push(`${rel}  ⊃ 引用私人文档「${stem}」  「${excerpt(text, stem)}」`)
        else if (title.length >= 4 && text.includes(title)) priPrivateHits.push(`${rel}  ⊃ 私人文档标题「${title}」  「${excerpt(text, title)}」`)
      }
    }
  } else {
    // 名单读不到 → 退化成关键词扫描，但照样判 FAIL：门禁不能因为「规则找不到」就放行
    // （priScanned 已在上面 b) 的循环里统计过，这里不重复计数）
    for (const rel of priTextFiles) {
      const text = readText(join(ROOT, rel))
      const m = /私人文档|私人笔记|PRIVATE_DOCS|PRIVATE/.exec(text)
      if (m) priPrivateHits.push(`${rel}  ⊃ 「${m[0]}」  「${excerpt(text, m[0])}」`)
    }
  }
}
const priHits = [...priNameHits, ...priContentHits, ...priPrivateHits]
const priSource = PRI.file
  ? `${PRI.file}${PRI.names.length ? `（${PRI.names.length} 篇）` : '（未解析到 PRIVATE_DOCS，已降级关键词扫描）'}`
  : '未找到 build-material.mjs（已降级关键词扫描）'
push('⑩',
  PRIVATE_OK ? '私人文稿零泄漏（dev 站豁免：内网完整版，刻意含口语稿/私人笔记）' : '私人文稿零泄漏',
  PRIVATE_OK ? true : priHits.length === 0 && PRI.names.length > 0,
  PRIVATE_OK
    ? '已豁免（--private-ok）：本项不参与判定；prod 发布绝不允许带这个开关'
    : `文件名扫 ${allFiles.length} 个 / 文本扫 ${priScanned} 个，私人名单 ${priSource}，命中 ${priHits.length}`,
  PRIVATE_OK
    ? ['⚠ dev 站豁免：口语稿 + 私人笔记是内网完整版的一部分；prod 产物里出现它们的任何字节都算泄漏']
    : priHits)

/* ============================================================== ⑪ 产物洁净度（私密文件名 / 原始录屏 / 绝对路径） */

// 和 ⑩ 的分工：⑩ 管「已经登记过的私人文稿」（口语稿 + PRIVATE_DOCS 12 篇）不许漏进产物；
// ⑪ 管「根本不该出现在产物里的东西」——**不需要逐篇登记**，靠名字/正文特征就能判死。
// 为什么必须有这一项：下面这些现在全靠 build-material「不拷」这个约定（纯代码层），没有门禁；
// 而 PRIVATE_DOCS 是逐篇登记的，新加一篇私人笔记忘了登记就会漏 —— ⑪ 是那条路的兜底。
// 每条规则自带标签，失败输出必须指出**是哪条规则**命中的（规则表是唯一数据源，别在别处另写正则）。
const CLEAN_NAME_RULES = [
  { label: '私密周边文件', re: /简历|\u9762\u8bd5|复盘|口述|-笔记|\bJD\b/ },
  { label: '凭据/配置', re: /\.env\b|credentials|\.pem$|id_rsa|\.git\/|\.DS_Store$|~$|\.bak/ },
  { label: '录屏/垃圾', re: /node_modules\/|\.webm$|trace\.zip$|playwright-report/ },
  { label: '内部目录', re: /(?:^|\/)_(?:deploy|evidence)\// },
]
// 正文规则：口径同 build-material 的 PROD_NEUTRAL（它把 /root//tmp/ 换成 /），但那只是构建期替换、
// **没有独立门禁验证「替换后 0 命中」** —— 替换规则漏一个词就漏一个，所以这里独立扫一遍产物正文。
const CLEAN_TEXT_RULES = [
  { label: '内网 IP', re: /100\.113\.135\.55/ },
  { label: '绝对路径', re: /\/root\/|\/tmp\// },
  { label: '容器名', re: /qiqi-server-[12]|qiqi-mysql|qiqi-gateway/ },
  { label: '编排命令', re: /docker\s+compose/i },
]

// 路径扫描必须**不复用 walk()**：walk() 正好把 _deploy/_evidence/.git/node_modules 这些 skip 掉了，
// 而它们正是本项要抓的东西。命中目录时记一条就剪枝，避免 node_modules/.git 那种几千个文件刷屏。
const cleanPathHits = []
let cleanPaths = 0
if (!PRIVATE_OK) {
  ;(function scanClean(rel = '') {
    let entries
    try { entries = readdirSync(join(ROOT, rel), { withFileTypes: true }) } catch { return }
    for (const e of entries) {
      const childRel = rel ? `${rel}/${e.name}` : e.name
      if (childRel === 'dist-prod' && ROOT_BASE !== 'dist-prod') continue   // 同 walk()：两个独立待验目标
      cleanPaths++
      const hit = CLEAN_NAME_RULES.find((r) => r.re.test(childRel))
      if (hit) {
        cleanPathHits.push(`[${hit.label}] ${childRel}${e.isDirectory() ? '/' : ''}  ⊃ 名称命中 ${hit.re}`)
        if (e.isDirectory()) continue    // 目录命中即剪枝，不往里刷
      }
      if (e.isDirectory()) scanClean(childRel)
    }
  })()
}

// 正文扫描：只用 .md/.html（含 .htm），与「公网能直接读到的那两种正文」口径一致
const cleanTextHits = []
const cleanTextFiles = priTextFiles.filter((f) => /\.(?:md|html?)$/i.test(f))
if (!PRIVATE_OK) {
  for (const rel of cleanTextFiles) {
    const text = readText(join(ROOT, rel))
    for (const r of CLEAN_TEXT_RULES) {
      const m = r.re.exec(text)
      if (m) cleanTextHits.push(`[${r.label}] ${rel}  ⊃ 「${m[0]}」×${text.split(m[0]).length - 1}  「${excerpt(text, m[0])}」`)
    }
  }
}
const cleanHits = [...cleanPathHits, ...cleanTextHits]
const cleanCounts = [...CLEAN_NAME_RULES, ...CLEAN_TEXT_RULES]
  .map((r) => `${r.label} ${cleanHits.filter((h) => h.startsWith(`[${r.label}]`)).length}`)
  .join(' / ')
push('⑪',
  PRIVATE_OK ? '产物洁净度（dev 站豁免：内网完整版，刻意保留 _deploy/_evidence）' : '产物洁净度（私密名/原始录屏/绝对路径）',
  PRIVATE_OK ? true : cleanHits.length === 0,
  PRIVATE_OK
    ? '已豁免（--private-ok）：本项不参与判定；prod 发布绝不允许带这个开关'
    : `路径扫 ${cleanPaths} 个 / 正文扫 ${cleanTextFiles.length} 个，命中 ${cleanHits.length}（${cleanCounts}）`,
  PRIVATE_OK
    ? ['⚠ dev 站豁免：dev 站的 _deploy/_evidence 是内网材料（nginx deny），prod 产物里出现即泄漏']
    : cleanHits)

/* ============================================================== ⑫ 侧边栏编号连续性 */

// 为什么单列一项（而不塞进 ⑨）：⑨ 判的是「链接指向的文件在不在」，断档时那些链接**全都在**，
// ⑨ 一定是绿的。而侧边栏显示名不带编号（见 build-material.mjs 顶部约定），少一个场景肉眼也看不出，
// 只有 URL 里的编号会露馅：……/7-收货地址.md 后面直接跟 ……/9-确认收货并发.md。
// 2026-11-14 就断过这么一次（「8-支付并发冲突」测过、素材落盘，但 SCENE_GROUPS 没登记 →
// 不生成页面 → 侧边栏 7→9），当时 ①页面数/⑤缺件/⑨死链 全绿，因为缺的那项压根不在清单里。
// 既不并进 ⑨ 的 detail 字符串（那会改动 ⑨ 的既有口径），也放在 ⑪ 之后输出，前 11 项的
// 判定口径与显示顺序完全不变。解析逻辑与门禁脚本共用一份（parseSidebar），不在这里另抄。
const CONTINUITY_MIN_GROUPS = 10   // 分组数下限：目前 14 组。扫到 0 组 = _sidebar.md 指错/产物空，不许判通过
const CONTINUITY_MIN_LINKS = 30    // 带编号条目下限：目前 74 条（场景 50 + 图页 24）
const numbering = (() => {
  if (!existsSync(sidebarPath)) return { groups: [], numbered: 0, lines: ['_sidebar.md 不存在，无法校验编号连续性'] }
  const { groups, numbered } = parseSidebar(readText(sidebarPath))
  const { lines } = checkContinuity(groups)
  return { groups, numbered, lines }
})()
const numberingBad = numbering.groups.length
  ? numbering.lines.filter((l) => l.startsWith('❌'))
  : []
const numberingVacuous = numbering.groups.length < CONTINUITY_MIN_GROUPS || numbering.numbered < CONTINUITY_MIN_LINKS
push('⑫',
  '侧边栏编号连续性',
  numberingBad.length === 0 && !numberingVacuous,
  numberingVacuous
    ? `分组 ${numbering.groups.length} 个（下限 ${CONTINUITY_MIN_GROUPS}）/ 编号条目 ${numbering.numbered} 条（下限 ${CONTINUITY_MIN_LINKS}）—— 扫到的太少，不能判通过`
    : `分组 ${numbering.groups.length} 个，编号条目 ${numbering.numbered} 条，不连续 ${numberingBad.length} 个`,
  numberingVacuous
    ? [`❌ 不是「全连续」而是「根本没扫到东西」：检查 _sidebar.md 是否指对、产物是否为空`]
    : numberingBad.map((l) => l.replace(/^❌ /, '断档 ')))

/* ============================================================== 下限断言（防止「扫不到东西 → 判通过」） */

// 十二项检查各自只看「扫到的东西对不对」，扫到 0 个就是 0 个问题 = 0 个失败 → 空跑即通过（fail-open）。
// 这一节只看数量：每个维度的实测值必须 ≥ FLOOR 里的下限，否则整体 FAIL、退出码 1。
const floorActual = {
  scenes: sceneList.length,
  pages: scenarioPages + diagramPages,
  images: imgStat.total,
  videos: vidStat.total,
  svgChecked: svgTargets.length,
  sidebarLinks,
  privacyScanned: priScanned,
  privateDocs: PRI.names.length,
  cleanScanned: cleanPaths,
}
const floorItems = FLOOR_KEYS.map((key) => {
  // dev 站整体豁免第 ⑩ 项（--private-ok），它那两条下限也随之不参与判定，但要显式打出来
  if (PRIVATE_OK && (key === 'privacyScanned' || key === 'privateDocs' || key === 'cleanScanned')) {
    return { key, label: FLOOR_LABEL[key], actual: '—', floor: FLOOR[key], ok: true, skipped: true }
  }
  return {
    key,
    label: FLOOR_LABEL[key],
    actual: floorActual[key],
    floor: FLOOR[key],
    ok: floorActual[key] >= FLOOR[key],
  }
})
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
    ? `✅ ${f.label} ${f.actual} ≥ ${f.floor}（${f.key}）${f.skipped ? '  ← dev 站豁免第 ⑩⑪ 项，不参与' : ''}`
    : `❌ ${f.label} ${f.actual} < ${f.floor}（${f.key}，差 ${f.floor - f.actual}）`)
}

const failed = results.filter((r) => !r.ok)
const imgMode = SKIP_HTTP ? '本地存在' : '200'
const vidMode = SKIP_HTTP ? '本地存在' : '200'
console.log('')
console.log(`页面 ${scenarioPages + diagramPages}（场景 ${scenarioPages} / 图页 ${diagramPages}）  `
  + `图片引用 ${imgStat.total}（${imgMode}: ${imgStat.ok}，失败 ${imgStat.fail}）  `
  + `视频引用 ${vidStat.total}（${vidMode}: ${vidStat.ok}，失败 ${vidStat.fail}）`)
console.log(`孤儿图 ${mmdOnly.length + svgOnly.length}  场景缺件 ${sceneGaps.length}`
  + (pendingCount ? `（待补 ${pendingCount}）` : '') + '  '
  + `泄露命中 ${leakHits.length}  SVG 不合规 ${svgBad.length}  死链 ${deadLinks.length}  `
  + `私密命中 ${PRIVATE_OK ? 'n/a（dev 豁免）' : priHits.length}`
  + `  洁净命中 ${PRIVATE_OK ? 'n/a（dev 豁免）' : cleanHits.length}`)
// 失败原因先写下限（它解释的是「为什么上面的 ✅ 不算数」），再写常规项
const reasons = []
if (floorFailed.length) reasons.push(`下限断言：${floorFailed.map((f) => `${f.label} ${f.actual} < ${f.floor}`).join('、')}`)
if (failed.length) reasons.push(failed.map((r) => r.id + r.title).join('、'))
const pass = reasons.length === 0
console.log(`结论：${pass ? 'PASS' : 'FAIL'}`
  + (pass
    ? (pendingCount ? `（含 ${pendingCount} 项已登记的待补视频）` : '')
    : `（${reasons.join('；')}）`))
process.exit(pass ? 0 : 1)
