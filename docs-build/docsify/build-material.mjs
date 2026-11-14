#!/usr/bin/env node
/**
 * build-material.mjs
 *
 * 扫描 /root/qiqi-mall 下的真实素材（图 / 视频），自动生成 /root/docsify/material/ 下的页面，
 * 并按同一份清单重写 /root/docsify/_sidebar.md。
 *
 * 约束：
 *   - 只写 /root/docsify/，对 /root/qiqi-mall/ 只读；素材文件不改名、不移动、不复制。
 *   - 幂等：内容无变化不落盘；素材增删后重跑自动补齐 / 清理。
 *   - 页面正文（标题之外）≤ 25 字：只有标题、每段视频自己的名字、video/img，禁止任何解释性段落。
 *     ⚠️ 2026-11-14 收窄：这 25 字只管**模板自己产出的那行摘要**。场景目录里的 `正文.md`（五段式正文）
 *     与 `实测数字.sql` 是**作者写的内容**，整段摘出计量（见 assertStrict 与 THESIS_FILE 的注释）。
 *     （唯一例外是「缺视频」那一行 blockquote 提示：固定文案、单行、不占 25 字额度，见 HINTS；
 *       另一个例外是场景页末尾的「一分钟口语稿」折叠块，见下面 SPOKEN 一段）
 *
 * 一分钟口语稿（场景页 + 图页，**只在 dev 展示**）：
 *   - 场景页：每个场景目录下 `口语稿.txt`（纯文本，不是 .md），一个场景一份，
 *     该场景的图和视频共用同一份稿，不按图/视频分别写。
 *   - 图页：图是**平铺文件**（没有「一张图一个目录」），所以稿件按
 *     `<图所在目录>/口语稿-<图文件名前缀>.txt` 放（如 `docs/flows/03-机制时序/口语稿-10-一件货的数量守恒.txt`）；
 *     同时也认「与图同名目录 + 口语稿.txt」的写法，详见 readDiagramSpoken。
 *   - dev 模式：若该场景目录下有这份稿，就在页面正文的图/视频**之后**插入一个
 *     `<details class="spoken-script">` 折叠块（`<details>` 默认收起，点开才展开）。
 *   - prod 模式：**完全不读、不插入** —— 生成产物里不含口语稿的任何字节。
 *   - 为什么只在 dev 展示：这份稿是作者本人面试口述用的**私人材料**（一分钟口语版业务讲解），
 *     和「业务逻辑笔记不上公网」（PRIVATE_DOCS 那 12 篇）是同一个原则 ——
 *     公网文档只留对外的业务场景说明，不把个人的讲解稿、逐字稿一起挂上去。
 *   - 缺稿不报错：渐进式，先有图的场景/图页照常出页，稿子补上后重跑即出现在末尾。
 *   - 出页门禁：**图是硬要求，视频不是**。有图就出页；缺视频时正文显式渲染一行提示，
 *     并在构建日志里汇总「哪些页面待补视频」——绝不像旧版那样静默吞掉页面。
 *   - 展示顺序 = 下面 DIAGRAM_GROUPS / SCENE_BUCKETS 的书写顺序（名字里没有编号可排）；
 *     文件名保留 `N-` 前缀（与素材目录同构），但侧边栏文案与页面 H1 一律不显示数字。
 *
 * 素材定位（严格按目录，不做跨目录模糊匹配）：
 *   场景图：docs/flows/02-业务场景/<类别>/<N-场景>/…png 或 <类别>/<N-场景>.png
 *           （02-业务场景/ 根目录下那批 2-场景N-*.png 属于旧结构，永不引用）
 *   图格式：同目录同主名同时存在 .svg 与 .png 时，页面一律引用 .svg；只有缺 .svg 才退回 .png
 *   场景视频：videos/<类别>/<N-场景>.mp4 或 <N-场景>-<序号>-<分段名>.mp4（分段按序号升序全部嵌入）
 *
 * 用法：
 *   node /root/docsify/build-material.mjs                  → dev 模式（默认），产物原地写 /root/docsify/material
 *   node /root/docsify/build-material.mjs --mode=prod      → 生产模式（脱敏 + 排除私人文档），产物写 /root/docsify/dist-prod/
 *   node /root/docsify/build-material.mjs --target=rspress --mode=dev|prod
 *                                                         → 输出到 Rspress 内容根 /root/docs-rspress/doc/
 *   （--only=关键词1,关键词2  仅用于冒烟/抽查：只生成匹配的页，并跳过过期页清理）
 *
 * 两种模式的差别只在「产物落点 + README 入口表 + 侧边栏（prod 无「业务逻辑」段）
 * + 素材拷贝（图一律用 SVG） + 脱敏终检」，
 * 页面清单、标题、顺序、图/视频引用关系完全一致（页面数不变）。
 *
 * ── --target=rspress 为什么不新写一个生成器 ──────────────────────────────
 * 分组/顺序的唯一真相源是下面 SCENE_BUCKETS / DIAGRAM_GROUPS 两张表（门禁 ⑫ 抓的就是它），
 * 「缺视频」两种语义（HINTS）要与 check-docs-site.mjs 的 NO_UI_SCENES 对齐、
 * 页面模板与 assertStrict（正文 ≤25 字）必须两边同口径 —— 复制一份解析逻辑必然漂移。
 * 所以这里只在**输出端**分叉：页面正文/素材定位/口语稿条件编译全部复用，
 * docsify 分支的代码路径与产物字节完全不变（--target 默认 docsify）。
 */
import {
  readdirSync, readFileSync, writeFileSync, mkdirSync, rmdirSync, rmSync, existsSync, chmodSync,
  statSync, copyFileSync, linkSync,
} from 'node:fs'
import { join, dirname } from 'node:path'

const SITE = '/root/docsify'
const SRC_DOCS = '/root/qiqi-mall/docs'                  // 图素材（er/ + flows/）
const SRC_VIDEO = '/root/qiqi-mall/e2e/report/videos'    // 视频素材

/* ------------------------------------------------------------------ 构建模式 */

const MODE_ARG = process.argv.slice(2).find((a) => a.startsWith('--mode='))
const MODE = MODE_ARG ? MODE_ARG.slice('--mode='.length) : 'dev'
if (MODE !== 'dev' && MODE !== 'prod') {
  console.error(`未知模式 --mode=${MODE}（只支持 dev / prod）`)
  process.exit(2)
}
// dev：原地写（内网站点 docsify 容器直接挂 /root/docsify）；
// prod：写 dist-prod/，一个可整目录丢给 OSS / Caddy file_server 的自包含站点
/* ------------------------------------------------------------------ 输出目标 */

/**
 * --target=docsify（默认，行为与本参数引入前逐字节一致）/ --target=rspress（写 Rspress 内容根）
 *
 * rspress 侧 dev 与 prod **共用同一个内容根** doc/：prod 重生成时口语稿块与那 12 篇私人笔记
 * 根本不会被写出来（不是写出来再删），并在生成后加一道 guard 扫产物字节兜底。
 * 不用「content/dev + content/prod 双根」的理由：内容存两份必然漂移，而双根解决的那个问题
 * （dev 内容漏进 prod 产物）用「生成期不产出 + 生成后字节扫描」已经能闭合。
 */
const TARGET_ARG = process.argv.slice(2).find((a) => a.startsWith('--target='))
const TARGET = TARGET_ARG ? TARGET_ARG.slice('--target='.length) : 'docsify'
if (TARGET !== 'docsify' && TARGET !== 'rspress') {
  console.error(`未知目标 --target=${TARGET}（只支持 docsify / rspress）`)
  process.exit(2)
}
/** --only=关键词,关键词 —— 冒烟/抽查用，不参与正式构建（会跳过过期页清理，避免误删） */
const ONLY = (process.argv.slice(2).find((a) => a.startsWith('--only=')) ?? '')
  .slice('--only='.length).split(',').map((s) => s.trim()).filter(Boolean)
const passOnly = (label) => !ONLY.length || ONLY.some((k) => label.includes(k))

const RSPRESS_SITE = '/root/docs-rspress'
const RSPRESS_DOC = join(RSPRESS_SITE, 'doc')
const OUT_ROOT = TARGET === 'rspress'
  ? RSPRESS_DOC
  : (MODE === 'prod' ? join(SITE, 'dist-prod') : SITE)


// 图片策略：一律引用同主名 .svg（矢量、清晰、体积最小），没有 .svg 才退回 .png。
// 旧版 prod 会把 SVG 里的浮点坐标误判成端口字面量（`151.8000030517578` ⊃ `5173`）而改引 PNG，
// 21 张图白丢矢量清晰度、体积 +3.6 MiB；现在端口改由「必须带上下文」的 PORT_PATTERNS 判定，
// 误撞消失，换图/光栅化那一整套逻辑已删除。
/**
 * 12 篇手工文档（/root/docsify 根目录）：作者面试私人笔记，**绝不进公网产物**。
 * 只影响 prod 的拷贝与侧边栏；dev 站是内网完整版，这 12 篇照常保留。
 */
const PRIVATE_DOCS = [
  'project-intro.md', 'project-architecture.md', 'architecture-decisions.md', 'evolution.md',
  'concurrency.md', 'mysql-tuning.md', 'multi-instance.md',
  'oms-relation.md', 'oms-pipeline.md', 'oms-gap.md', 'oms-split.md', 'oms-algorithm.md',
]
const OUT = join(OUT_ROOT, 'material')
const SCENE_IMG_ROOT = 'flows/02-业务场景'                // 场景配图区（相对 SRC_DOCS）
const FINISHED_VIDEO_EXT = '.mp4'   // 只收成品 mp4；.webm 是 Playwright 原始录制，一律忽略
const BODY_LIMIT = 25   // 正文（标题之外）字数上限

/* ------------------------------------------------------------------ 素材扫描 */

/** 递归列出目录下所有文件（返回相对 root 的 POSIX 路径） */
function walk(root) {
  const out = []
  if (!existsSync(root)) return out
  const stack = ['']
  while (stack.length) {
    const rel = stack.pop()
    for (const ent of readdirSync(join(root, rel), { withFileTypes: true })) {
      const childRel = rel ? `${rel}/${ent.name}` : ent.name
      if (ent.isDirectory()) stack.push(childRel)
      else if (ent.isFile()) out.push(childRel)
    }
  }
  return out.sort()
}

const hasExt = (p, ext) => p.toLowerCase().endsWith(ext)
const base = (p) => p.slice(p.lastIndexOf('/') + 1)

/* ------------------------------------------------------------------ 清单（同时就是展示顺序表） */

// 二~六组：图页。match 为相对 SRC_DOCS 的路径特征（**必须带目录**，
//   否则 `1-` 会命中 `10-`：字符串 includes 是无锚点的前缀匹配）。
//   n 只用于文件名，不参与排序。
//
// 2026-11 图集整体重画后的结构与源素材一一对应：
//   10 张机制图   docs/flows/03-机制时序/1..10-*.svg   （旧 02-关键机制 / 04-凭证与鉴权 / 03-状态机 的源图已删除）
//   2 张架构图    docs/flows/01-架构/1-系统架构图.svg
//                 docs/flows/02-业务场景/1-全流程总图.svg（这张全流程总图一直平铺在 02-业务场景 根下，
//                 不在 01-架构 目录里 —— match 必须指向它真实所在的位置，否则该页直接消失）
//   6 张 ER 图    docs/er/0..5-ER图-*.svg（平铺）
//
// 分组口径 = **图的体裁**，不是「业务话题」。三种体裁只有两种抽屉，第三种单列并说明理由：
//   状态机：讲「一个实体有哪几个状态、怎么迁移」，图上是状态/守卫条件，没有时间轴 → 二组
//   时序  ：讲「一次业务动作里谁跟谁、按什么顺序说话」，图上是参与者生命线 + 消息箭头 → 三组
//   既不是：全景地图是**入口索引**（读法是从它跳走，不是读它的流程），
//           一件货的数量守恒是**账本视图**（每笔加减后余额都是同一个数，没有先后关系）。
//           两张硬塞进任一体裁都是骗读者，所以第四组单列，组名里把理由写清楚。
// 编号 一~六 连续（一、功能场景是 buildNavTree() 里的一层硬编码，二~六 由本表顺序生成），不许断档。
const DIAGRAM_GROUPS = [
  {
    // 二组：状态机 2 张 —— 同一套「状态 + 迁移」体裁，所以独立成组而不是并进「全景与生命周期」
    dir: '01-状态机', label: '二、状态机',
    items: [
      { n: 1, name: '订单生命周期', match: ['03-机制时序/2-订单生命周期'] },
      { n: 2, name: '包裹生命周期', match: ['03-机制时序/3-包裹生命周期'] },
    ],
  },
  {
    // 三组：时序 6 张 —— 参与者生命线 + 消息顺序，按业务发生顺序排（买家付款 → 确认收货 → 退货 → 商家审核 → 下发回传）
    dir: '02-时序', label: '三、时序',
    items: [
      { n: 1, name: '下单与支付',         match: ['03-机制时序/4-下单与支付'] },
      { n: 2, name: '买家确认收货',       match: ['03-机制时序/5-买家确认收货'] },
      { n: 3, name: '退货与退款',         match: ['03-机制时序/6-退货与退款'] },
      { n: 4, name: '审核与跨仓拆单',     match: ['03-机制时序/7-审核与跨仓拆单'] },
      { n: 5, name: '下发与仓库回传',     match: ['03-机制时序/8-下发与仓库回传'] },
      { n: 6, name: '待下发包裹人工干预', match: ['03-机制时序/9-待下发包裹人工干预'] },
    ],
  },
  {
    // 四组：体裁上的「其他」。组名里直接写出为什么这两张没进二/三组，
    //   免得读者以为是分类漏了两张，也免得以后有人为了「整齐」把它们硬塞进时序。
    dir: '03-全景与数量守恒',
    label: '四、全景与数量守恒（这两张既不是状态机也不是时序）',
    items: [
      { n: 1, name: '全景地图',         match: ['03-机制时序/1-全景地图'] },
      { n: 2, name: '一件货的数量守恒', match: ['03-机制时序/10-一件货的数量守恒'] },
    ],
  },
  {
    dir: '04-整体架构', label: '五、整体架构',
    items: [
      { n: 1, name: '全流程总图', match: ['02-业务场景/1-全流程总图'] },
      { n: 2, name: '系统架构图', match: ['01-架构/1-系统架构图'] },
    ],
  },
  {
    dir: '05-数据模型', label: '六、数据模型（39 张表 / 10 个域 + 取证链）',
    items: [
      { n: 1,  name: '全库总览',       match: ['er/0-ER图-全库总览'] },
      { n: 2,  name: '商品域',         match: ['er/1-ER图-商品域'] },
      { n: 3,  name: '会员域',         match: ['er/2-ER图-会员域'] },
      { n: 4,  name: '订单交易域',     match: ['er/3-ER图-订单交易域'] },
      { n: 5,  name: '履约域',         match: ['er/4-ER图-履约域'] },
      { n: 6,  name: '库存域',         match: ['er/5-ER图-库存域'] },
      { n: 7,  name: '售后域',         match: ['er/6-ER图-售后域'] },
      { n: 8,  name: '权限域',         match: ['er/7-ER图-权限域'] },
      { n: 9,  name: '审计域',         match: ['er/8-ER图-审计域'] },
      { n: 10, name: '审核域',         match: ['er/9-ER图-审核域'] },
      { n: 11, name: '渠道接入域',     match: ['er/10-ER图-渠道接入域'] },
      { n: 12, name: '四条取证链',     match: ['er/11-证据链-四条取证链'] },
    ],
  },
]

// 一、功能场景：场景页 = 该场景的图（可多张，硬要求）+ 该场景的视频（可多段，有就嵌入）。
//   缺视频不再阻断出页（见 HINT_PENDING / HINT_NO_UI）；缺图仍不出页。
//   顺序即业务使用顺序（先基础数据、再登录、再履约），与文件名数字无关；
//   n 与 name 必须和素材目录 <N-场景> 对齐（三层同构）。
/**
 * 一、功能场景：**桶 →（子分类）→ 场景**。
 *
 * 2026-11-14 结构重构后的唯一形状（别再往回改）：
 *   · 顺序只由本表的书写顺序决定 —— **文件名/URL 里一个编号都没有**，所以不存在「编号连续性」这回事，
 *     少一个场景不会让 URL 露馅，只能靠「侧边栏顺序 == 本表顺序」的门禁去抓（见 scripts/check-sidebar-continuity.mjs）。
 *   · 目录三层同构（文档目录 / 视频目录 / spec 目录共用同一段 rel 路径）：
 *       文档  docs/flows/02-业务场景/<桶>/[<子分类>/]<场景名>/
 *       视频  e2e/report/videos/<桶>/[<子分类>/]<场景名>/<段序>-<段名>.mp4
 *       spec  e2e/tests/scenarios/<桶>/[<子分类>/]<场景名>.spec.js
 *   · 没有子分类的桶（压测与容量）直接写 items，rel 就少一层。
 *   · name 必须与真实目录名逐字相同（含空格与否），因为它是路径的一段，不再是「显示名」。
 */
const SCENE_BUCKETS = [
  { dir: '业务', label: '业务', subs: [
    { dir: '买家侧', label: '买家侧', items: [
      '注册与登录', '商品浏览与搜索', '购物车', '结算下单与支付',
      '我的订单与收货', '售后退货', '收货地址',
    ] },
    { dir: '商家-商品管理', label: '商家 · 商品管理', items: [
      '商品类型与属性', '商品分类', '商品维护', '图片上传与预览',
    ] },
    // 2026-11-14 挂上「后台建单」：管理端建单接口（OrderCreateRequest 的空明细路径）一直有，
    // 但没有场景页 —— 缺的是「后台建单 ≠ 买家侧结算下单」这条分界。
    { dir: '商家-履约', label: '商家 · 履约', items: [
      '订单审核与发货', '跨仓拆单', '发货单调整', '退货处理', '订单删除守卫', '后台建单',
    ] },
    { dir: '商家-基础配置', label: '商家 · 基础配置', items: [
      '仓库管理', '订单设置', '退货原因字典',
    ] },
    { dir: '账号与会员', label: '账号与会员', items: [
      '管理员登录与验证码', '管理员账号', '会员管理',
    ] },
    { dir: '数据概览', label: '数据概览', items: ['概览页'] },
    { dir: 'AI集成', label: 'AI 集成', items: [
      'MCP最小闭环', '工具能力矩阵', '令牌管理',
    ] },
    // 2026-11-14 挂上「审核策略与优先级」：策略页（/audit-policy）一直有页面和接口，
    // 但没有场景页 —— 缺的正是「优先级只在同类型内比较、跨类型固定 阻止>异常>自动」这条口径。
    { dir: '订单-自动审核', label: '订单 · 自动审核', items: [
      '判定链与四结局', '六笔单走查', '审核策略与优先级',
    ] },
    { dir: '渠道-多平台接入', label: '渠道 · 多平台接入', items: ['接入主链路'] },
  ] },
  // 库存没有子分类（3+1 个主题横向并列）。场景名里出现「「」」是因为它就是目录名 ——
  // 目录名 == 场景名 == spec 文件名 == 视频目录名，四处同构，改名必须四处一起改。
  // 不含全角冒号：「库存台账与对账」原来的目录名带「：三数相等」，全角冒号进路径既难打也易错，
  // 2026-11-14 收口成不带冒号的目录名（页面上的标题仍可写全）。
  // 2026-11-14 挂上「库存分桶」：图 + 口语稿 + 正文 + UI 测试（51 条断言）都在，
  // 缺的只有录屏 → 已登记进 check-docs-site.mjs 的 PENDING_VIDEO（不是无界面，是「能演未演」）。
  // 2026-11-14 挂上「库存对账」：/inventory-reconcile 页面与两个只读接口早就在，
  // 但没有场景页 —— 缺的正是「对账只报结论、不写证据」这条红线。
  { dir: '库存', label: '库存', items: [
    'SKU与规格',
    '一条SQL吃掉「读、判、写」',
    '库存分桶',
    '库存对账',
    '库存台账与对账',
  ] },
  { dir: '并发与竞态', label: '并发与竞态', subs: [
    { dir: '版本冲突', label: '版本冲突', items: [
      '商品编辑并发', 'SKU编辑并发', '会员资料编辑并发', 'AI端并发',
    ] },
    { dir: '状态竞态', label: '状态竞态', items: [
      '确认收货并发', '订单审核并发', '退货审核并发',
    ] },
    { dir: '重复提交与幂等', label: '重复提交与幂等', items: [
      '支付并发冲突', '平台重推与503重试',
    ] },
    { dir: '双向竞态', label: '双向竞态', items: ['发货单与仓库回传'] },
  ] },
  { dir: '台账与取证', label: '台账与取证', subs: [
    { dir: '台账七类', label: '台账七类', items: [
      '订单状态台账', '后台操作审计', '包裹状态台账',
      '售后台账', 'WMS交换台账', '支付流水',
    ] },
    { dir: '取证链与证据对齐', label: '取证链与证据对齐', items: [
      '状态与证据对齐', '与内部系统的边界',
    ] },
  ] },
  // ── 性能与容量：5 页全部落盘（图 + 口语稿 + 五段式正文）───────────────────────
  // 雪崩点400 / 每单19条SQL / 会话探针 / 开环与闭环压测 / 复合索引消filesort。
  // 「分页替代全量列表」**不单独成页**：原始接口耗时没留档，单独写就是空壳，
  // 它的机制部分（Page + 主键倒序）并入「复合索引消filesort」的第一节。
  // 这个桶的 5 条全是「先造出撑不住的环境再优化」来的，每条都带具体数字。
  // 这一桶**全都不录视频**：结论只能由曲线数据 + 一条复现命令证明，录一段压测终端证明不了，
  // 所以它们在体检那边走 NO_UI_SCENES（视频不适用），不在 PENDING_VIDEO 里占位。
  // ── 事故与修复：项目里真实发生过的事故，一页一起。**全都不录视频** ──────────────
  // 为什么没有视频、也不该有：这类事的现场是「白屏 + 一份已经挂在 CDN 上的坏副本」「一条查询要跑 25 秒」
  // 「自检那一行静默没打出来」—— 界面上只表现为转圈或空白，录一段屏幕什么也证明不了，
  // 硬录就是造假。验收 = 命令输出 + 数字 + 时间线（每页「凭什么证明好了」一节给可复跑的命令）。
  // 所以它们走 check-docs-site.mjs 的 NO_UI_SCENES（视频不适用），**不进** PENDING_VIDEO（待补是"该录没录"）。
  // 页面名一律用具体事实，不用「事故复盘 1」这类编号：编号记不住事，事实才记得住。
  // 顺序 = 事故的"由外到内"：先 CDN（用户看得见），再数据（台账孤儿），再灌库（种子），
  // 再并发（快照视野），再测试体系（假绿），最后是发布门禁（过期断言）。
  { dir: '事故与修复', label: '事故与修复', items: [
    'cdn-边缘缓存：不带-Origin-的预热把白屏写进了缓存',
    '台账孤儿-573-万行：reset-漏删三张表',
    '种子灌进真库：一句硬编码的-USE',
    '撞唯一键后的回查看不见对方提交',
    '删-78-处-sleep-会造假绿',
    '期望-401-实得-200：一条过期的冒烟断言',
  ] },
  { dir: '性能与容量', label: '性能与容量', items: [
    '雪崩点400',
    '每单19条SQL',
    '会话探针',
    '开环与闭环压测',
    // '分页替代全量列表',   // ← 不单独出页：原始接口耗时没留档（写了就是空壳），
    //                      //    它的「分页机制」部分并入「复合索引消filesort」的第一节
    '复合索引消filesort',
  ] },
]

/**
 * 场景表展平成有序清单（顺序 = SCENE_BUCKETS 的书写顺序，这是全站唯一的顺序真相源）：
 *   { bucket, bucketLabel, sub, subLabel, name, rel }
 * rel = 三层同构的那段路径（'业务/买家侧/注册与登录'、'压测与容量/雪崩点400'）。
 */
const SCENES = SCENE_BUCKETS.flatMap((b) => {
  const subs = b.subs ?? [{ dir: null, label: null, items: b.items ?? [] }]
  return subs.flatMap((s) => (s.items ?? []).map((name) => ({
    bucket: b.dir, bucketLabel: b.label,
    sub: s.dir, subLabel: s.label,
    name,
    rel: [b.dir, s.dir, name].filter(Boolean).join('/'),
  })))
})

/* ------------------------------------------------------------------ 素材解析 */

/**
 * 素材读权限归一：容器里 nginx worker 是 nginx(uid 101) 非 root，素材新生成时常见 600/700，
 * 这里只「加」读/进入位（o+rX），不改属主、不改内容；素材源其余方面仍只读。
 */
function ensureReadable(root) {
  if (!existsSync(root)) return 0
  let fixed = 0
  const stack = ['']
  while (stack.length) {
    const rel = stack.pop()
    const abs = join(root, rel)
    const mode = statSync(abs).mode & 0o777
    const isDir = statSync(abs).isDirectory()
    const want = mode | (isDir ? 0o555 : 0o444)
    if (want !== mode) { chmodSync(abs, want); fixed++ }
    if (isDir) for (const ent of readdirSync(abs, { withFileTypes: true })) {
      stack.push(rel ? `${rel}/${ent.name}` : ent.name)
    }
  }
  return fixed
}
const permFixed = ensureReadable(SRC_DOCS) + ensureReadable(SRC_VIDEO)

const DOC_FILES = walk(SRC_DOCS)      // 相对 SRC_DOCS
const VIDEO_FILES = walk(SRC_VIDEO)   // 相对 SRC_VIDEO
const isImage = (p) => /\.(png|jpe?g|webp|gif|svg)$/i.test(p)
const slug = (name) => name.replace(/\s+/g, '')

// 同一张图优先引用 .svg（矢量，体积小）；没有 .svg 时才退回 .png/.jpg…
// 只按「同目录同主名」判重，PNG 原图保留在仓库里做回滚，不参与页面引用。
const IMG_EXT_RE = /\.(png|jpe?g|webp|gif|svg)$/i
const imgStem = (p) => p.replace(IMG_EXT_RE, '')
const SVG_STEMS = new Set(DOC_FILES.filter((p) => hasExt(p, '.svg')).map(imgStem))
const preferSvg = (p) => hasExt(p, '.svg') || !SVG_STEMS.has(imgStem(p))

/* ------------------------------------------------------------------ 脱敏（仅 prod 模式生效） */

/**
 * 生产模式的敏感内容特征（内网 IP/端口、测试账号、调试入口、开发端口、e2e、内部路径）。
 * 用途：
 *   1) README 入口表：整行删除（不替换）；
 *   2) 页面正文：命中 → 整行替换成中性描述（正常应为 0 命中，见 build 报告）；
 *   3) 素材：见 PROD_GATE_PATTERNS / maskSvgText()。
 */
const PROD_REDACT = [
  /MCP ?Inspector/i, /MCP_?调试器/, /swagger/i, /100\.113\.135\.55/,
  /admin\s*\/\s*123456/, /ai_operator/, /tailcb871e/, /:30\d\d/, /:51\d\d/, /:808[0-9]/, /:18080/,
  /e2e/, /playwright/i, /qiqi_mall_e2e/, /\/root\//, /\/tmp\//,
]

/**
 * 验收终检口径（①）：端口必须**带上下文**，裸数字不算敏感词。
 *
 * 旧验收用裸端口字面量（'5173' / '5175' / '8081' …）当敏感词，而渲染出来的 SVG 是单行浮点坐标
 * （`151.8000030517578`），数字片段必然误撞 → 21 张图被迫换成 PNG，白丢矢量清晰度、体积 +3.6 MiB。
 * 现在 `:\d{4,5}` 要求**前面有冒号**，所以 `151.8000030517578` 永远不命中，
 * 而 `:5173`、`localhost:8081`、`100.113.135.55:5174` 都会命中。
 */
const PROD_GATE_PATTERNS = [
  /:\d{4,5}\b/g,                          // :5173 / :18080 这类带冒号的端口
  /\b(?:localhost|127\.0\.0\.1|0\.0\.0\.0):\d+/g,
  /\b100\.113\.135\.55\b/g,             // 内网 IP（这条是真敏感，保留）
  /\b\d{1,3}(?:\.\d{1,3}){3}:\d{4,5}\b/g, // IP:端口
]

// 非端口类的敏感字面量（没有浮点误撞风险，继续按字面量扫）
const PROD_GATE_TOKENS = [
  'admin / 123456', 'ai_operator', 'tailcb871e', 'swagger',
  'qiqi_mall_e2e', 'playwright', 'MCP Inspector',
]

/** 一段文本里所有敏感命中（用于报告里点名「哪个文件的哪段文字」） */
function scanSensitive(text, patterns = PROD_GATE_PATTERNS, tokens = PROD_GATE_TOKENS) {
  const hits = []
  for (const re of patterns) {
    for (const m of text.matchAll(new RegExp(re.source, re.flags))) hits.push({ what: String(re), text: m[0] })
  }
  for (const tok of tokens) {
    if (text.toLowerCase().includes(tok.toLowerCase())) hits.push({ what: tok, text: tok })
  }
  return hits
}

/** SVG 里可见文字节点的真实敏感命中（只扫 `>…<` 之间的文字，属性里的浮点坐标天然不参与） */
function scanSvgTextNodes(svg) {
  const hits = []
  for (const m of svg.matchAll(/>([^<]+)</g)) {
    for (const h of scanSensitive(m[1])) hits.push({ ...h, context: m[1].trim().slice(0, 120) })
  }
  return hits
}

/**
 * SVG 真命中的兜底掩码：只改**文字节点**里那段数字（端口尾两位 → `xx`，长度不变、不改版式），
 * 且只在 prod 拷贝时对**副本**动手 —— 源素材 /root/qiqi-mall/docs/*.svg 一个字节都不改。
 * 浮点坐标在属性里（`d="M151.8000030517578…"`），本函数碰不到，渲染完全不受影响。
 */
function maskSvgText(svg) {
  const hits = []
  // 只处理 <text> 元素里的文字内容：属性（浮点坐标）和 <style> 里的 CSS 值
  // （`stroke-dashoffset:900`、`max-width:200px`）都不该被当成端口。
  const text = svg.replace(/(<text\b[^>]*>)([\s\S]*?)(<\/text>)/g, (whole, open, inner, close) => {
    let out = inner
    if (out.includes('100.113.135.55')) {
      hits.push({ before: '100.113.135.55', after: '内部地址' })
      out = out.split('100.113.135.55').join('内部地址')
    }
    // 端口 4~5 位：`:8083` → `:80xx`、`:18080` → `:180xx`（字节数不变，版式不动）
    out = out.replace(/([:：])(\d{2,3})(\d{2})(?!\d)/g, (mm, sep, head, tail) => {
      hits.push({ before: `${sep}${head}${tail}`, after: `${sep}${head}xx` })
      return `${sep}${head}xx`
    })
    return out === inner ? whole : open + out + close
  })
  return { text, hits }
}

/** SVG 源文本缓存（只读，用于素材扫描；不改源素材） */
const docText = new Map()
function readDoc(rel) {
  if (!docText.has(rel)) docText.set(rel, readFileSync(join(SRC_DOCS, rel), 'utf8'))
  return docText.get(rel)
}

/**
 * 从候选图里挑一张：有 .svg 就用 .svg（矢量最清晰、体积最小）。
 * prod 也不再换 PNG —— 端口判定已带上下文，浮点坐标不再误撞。
 */
function pickArt(cands) {
  return cands.find(preferSvg)
}

/**
 * 场景素材全部按**目录**定位，不再拼 `N-` 前缀：
 *   图/口语稿/实测数字  docs/flows/02-业务场景/<rel>/…
 *   视频               e2e/report/videos/<rel>/<段序>-<段名>.mp4
 * rel 就是 SCENES 里那段（'业务/商家-履约/订单审核与发货'）。
 * 编号已在 2026-11-14 从文件名里去掉了：目录名就是身份，改名字只需改本脚本的清单与目录本身。
 */
const docDirOf = (scene) => `${SCENE_IMG_ROOT}/${scene.rel}`
const videoDirOf = (scene) => scene.rel

/** 场景目录（文档侧）的绝对路径 */
const sceneAbs = (scene) => join(SRC_DOCS, docDirOf(scene))
/** 场景目录（视频侧）的相对路径前缀，带结尾斜杠 */
const videoHead = (scene) => `${videoDirOf(scene)}/`

/**
 * 场景图：只看本场景目录下的图（`<rel>/1-库存台账图.svg`），别处的图永不命中。
 * 同主名同时有 .svg 与 .png 时只取 .svg（PNG 原图留在仓库做回滚，不进页面）。
 */
function sceneImages(scene) {
  const head = `${docDirOf(scene)}/`
  return DOC_FILES
    .filter((p) => p.startsWith(head) && isImage(p))   // 只看本场景目录这一层，不递归子目录
    .filter((p) => !p.slice(head.length).includes('/'))
    .filter(preferSvg)                                  // 有 .svg 就只取 .svg，PNG 不进页面
    .sort((a, b) => base(a).localeCompare(base(b), 'zh', { numeric: true }))
}

/**
 * 场景视频：`videos/<rel>/<段序>-<段名>.mp4`，同一场景内的多段按段序升序全部嵌入。
 * 段序是**这一页第几段**（1 起），不是场景编号 —— 场景在侧边栏里的位置由 SCENE_BUCKETS 决定，
 * 所以视频文件名里只有段序，没有场景编号可对。
 * 认不出的文件名（缺段序 / 不是 mp4）一律不猜、不静默当素材用：交给构建报告去数。
 */
function sceneVideos(scene) {
  const head = videoHead(scene)
  const out = []
  for (const p of VIDEO_FILES) {
    if (!p.startsWith(head) || !hasExt(p, FINISHED_VIDEO_EXT)) continue
    const rel = p.slice(head.length)
    if (rel.includes('/')) continue                       // 场景目录下不该再有子目录
    const m = rel.match(/^(\d+)-(.*)\.mp4$/i)
    if (!m) continue
    out.push({ path: p, part: Number(m[1]), label: (m[2] || '').trim() || null })
  }
  return out.sort((a, b) => a.part - b.part)
}

/* ------------------------------------------------------------------ 一分钟口语稿 */

/**
 * 口语稿：场景目录下的纯文本 `口语稿.txt`（一个场景一份，图与视频共用）。
 * **只有 dev 模式会读**——prod 产物里不出现口语稿的任何内容，
 * 因为它是作者面试口述用的私人材料，与 PRIVATE_DOCS 那 12 篇私人笔记同一个原则。
 * 缺文件返回 null（不报错，渐进式补稿）。
 */
const SPOKEN_FILE = '口语稿.txt'
const SPOKEN_SUMMARY = '🎤 一分钟口语稿（面试可直接念）'

function readSpokenScript(scene) {
  if (MODE !== 'dev') return null            // prod：不读、不插
  const abs = join(sceneAbs(scene), SPOKEN_FILE)
  if (!existsSync(abs)) return null
  const text = readFileSync(abs, 'utf8').trim().replace(/\s*\n+\s*/g, ' ')
  return text || null
}

/**
 * 图页口语稿的落盘规则（图是**平铺文件**，没有「一张图一个目录」可放）：
 *
 *   1) 首选「与图同名的目录」写法：`<图所在目录>/<图文件名前缀>/口语稿.txt`
 *      —— 与场景页 `<类别>/<N-场景>/口语稿.txt` 完全同构，将来把某张图收进目录就能直接用；
 *   2) 平铺目录的兜底写法：`<图所在目录>/口语稿-<图文件名前缀>.txt`
 *      —— 现状（03-机制时序 10 张、01-架构 1 张、02-业务场景 根下全流程总图、
 *         docs/er 6 张）全是平铺的 `.svg`，所以实际用的是这一条。
 *
 * 前缀取**图文件名去掉扩展名**（如 `口语稿-10-一件货的数量守恒.txt`），
 * 与图一一对应、不会和 `1-` / `10-` 混淆；多一张图就要多一份同前缀的稿，天然唯一。
 */
const DIAGRAM_SPOKEN_PREFIX = '口语稿-'

/** 图页口语稿的候选相对路径（相对 SRC_DOCS），按优先级排列 */
function diagramSpokenCandidates(imageRel) {
  const dir = dirname(imageRel)
  const stem = base(imgStem(imageRel))       // 图文件名前缀，如 `10-一件货的数量守恒`
  return [`${dir}/${stem}/${SPOKEN_FILE}`, `${dir}/${DIAGRAM_SPOKEN_PREFIX}${stem}.txt`]
}

/** 读图页口语稿：dev 才读；候选都不存在返回 null（不报错，渐进式补稿） */
function readDiagramSpoken(imageRel) {
  if (MODE !== 'dev') return null            // prod：不读、不插
  for (const rel of diagramSpokenCandidates(imageRel)) {
    const abs = join(SRC_DOCS, rel)
    if (!existsSync(abs)) continue
    const text = readFileSync(abs, 'utf8').trim().replace(/\s*\n+\s*/g, ' ')
    return text || null
  }
  return null
}

/** 折叠块原文（`<details>` 默认收起；docsify 会把 markdown 里的这段 HTML 正常渲染） */
function spokenBlock(text) {
  return [
    '<details class="spoken-script">',
    `<summary>${SPOKEN_SUMMARY}</summary>`,
    '',
    `<p>${text}</p>`,
    '',
    '</details>',
  ].join('\n')
}

/* ------------------------------------------------------------------ 实测数字（数据类场景的证据） */

/**
 * 数据类内容（台账 / 对账口径 / 表结构与索引 / 矩阵）的证据形式是「图 + SQL + 数字」，**不录视频**：
 * 能鼠标记账的东西才值得录屏，一张表的两端对不对平只有 SQL 说得清。
 *
 * 每页的数字写在场景目录下的 `实测数字.sql` —— 同一个文件两种用法：
 *   1) 人跑：把整个文件丢给 mysql 客户端就能重跑（纯 select，只读）；
 *   2) 构建期解析成页面上的「实测数字」清单（下面 ①②③ 三段，素材之后、口语稿之前）。
 * 语法（只有这四条，别自创）：
 *   `-- 库: …` / 其它文件头注释  → 页面顶部那行出处（哪套库、什么时候实测的）
 *   `-- @@ 断言一句话`           → 一条实测的标题
 *   （中间是 SQL，可多行，只能是 select / with / show / explain）
 *   `-- => 实测输出`             → 该条 SQL 的真实输出（多行就写多行）
 * 只读是硬约束：SQL 段里出现写语句直接让构建失败 —— 一份「实测数字」绝不能顺手改库。
 */
const MEASURED_FILE = '实测数字.sql'
/**
 * 场景正文：**场景目录下的一份 markdown，一等输入**（2026-11-14 起）。
 *
 * 为什么它必须是一等输入：用户要的是「用真实场景把代码和项目摊开给人看」，不是「一张图 + 一段视频」。
 * 早先模板把页面正文卡在 ≤25 字，本意只是「模板是生成的，别让人在生成物里手写解释文字」——
 * 现在正文有了正经的家（场景目录里的 正文.md，与图/口语稿同级），那个目的就不再靠限字数实现。
 *
 * 约定（全部 fail-closed，写错就构建失败，不静默半渲染）：
 *   ① **不要写 H1**：H1 由页面标题提供，正文从「一句话 / 场景」那节开始（写了 H1 会重复两次）；
 *   ② **不要在正文里放图/视频**：素材由模板在正文**之前**统一渲染一次，
 *      正文里再写 ![](...) 会得到一条相对路径（material/scenarios/… 下找不到那张图）→ 直接报错；
 *   ③ 原文照排（markdown 直出），不加工、不改标题层级。
 * 没有这份文件时，页面与旧版**逐字节一致**（渐进式：50 页既有场景不受影响）。
 */
const THESIS_FILE = '正文.md'
const THESIS_START = '<!-- thesis:start -->'
const THESIS_END = '<!-- thesis:end -->'
const MEASURED_START = '<!-- measured:start -->'
const MEASURED_END = '<!-- measured:end -->'
const MEASURED_TITLE = '📊 实测数字（只读 SQL）'
const MEASURED_WRITE_RE = /\b(insert|update|delete|drop|truncate|alter|create|replace|grant|set\s+\w+\s*=)\b/i
const MEASURED_ALLOW_RE = /^\s*(select|with|show|explain|describe|desc)\b/i

/** 解析 `实测数字.sql`：{ head:[…], cases:[{ claim, sql:[…], out:[…] }] } */
function parseMeasured(text) {
  const head = []
  const cases = []
  let cur = null
  let mode = 'head'                     // head | sql | out
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\s+$/, '')
    if (line.startsWith('-- @@')) {
      cur = { claim: line.slice('-- @@'.length).trim(), sql: [], out: [] }
      cases.push(cur)
      mode = 'sql'
      continue
    }
    if (line.startsWith('-- =>')) {
      if (!cur) throw new Error(`${MEASURED_FILE}: 「-- =>」出现在任何「-- @@」之前`)
      cur.out.push(line.slice('-- =>'.length).trim())
      mode = 'out'
      continue
    }
    if (mode === 'out') continue          // 实测输出区之后的内容只是注释，不参与渲染
    if (mode === 'head') {
      if (line.startsWith('--')) {
        const t = line.replace(/^--\s?/, '').trim()
        if (t) head.push(t)
      }
      continue
    }
    cur.sql.push(raw)
  }
  return { head, cases: cases.filter((c) => c.sql.join('').trim()) }
}

/** 读场景目录下的 `实测数字.sql`；缺文件返回 null（渐进式：先有图/视频的页照出） */
function readMeasured(scene) {
  const abs = join(sceneAbs(scene), MEASURED_FILE)
  if (!existsSync(abs)) return null                 // 缺文件 = 这页还没补实测数字，不渲染这一块
  const parsed = parseMeasured(readFileSync(abs, 'utf8'))
  // 只读硬约束：任何一条 SQL 段不是 select/with/show/explain，构建直接失败（不静默跳过）
  for (const c of parsed.cases) {
    const sql = c.sql.join('\n')
    if (MEASURED_WRITE_RE.test(sql) || !MEASURED_ALLOW_RE.test(sql)) {
      throw new Error(`${MEASURED_FILE}（${scene.rel}）「${c.claim}」的 SQL 不是只读语句——实测数字只许 select`)
    }
  }
  return parsed.cases.length ? parsed : null
}

/** 读场景目录下的 `正文.md`（五段式正文）；缺文件返回 null —— 页面与旧版逐字节一致 */
function readThesis(scene) {
  const abs = join(sceneAbs(scene), THESIS_FILE)
  if (!existsSync(abs)) return null
  let raw = readFileSync(abs, 'utf8').replace(/\r\n/g, '\n').trimEnd()
  if (!raw.trim()) throw new Error(`${THESIS_FILE}（${scene.rel}）是空的 —— 要么把正文写完，要么删掉这个文件`)
  // ① 正文开头写了 H1：**剥掉 + warn**，不 fail。
  //    正文里写标题是最自然的写法，不该因为写了标题就让整次构建失败；
  //    而页面的 H1 来自场景目录名（目录名 == 场景名 == 侧边栏文案，四处同构），
  //    所以正文里那个 H1 一定是重复的 —— 剥掉它，并让作者在构建日志里看见自己多写了一行。
  //    只剥「开头连续的 H1 行」，正文中间出现的 `# 注释`（代码块里）一概不动。
  const headLines = raw.split('\n')
  const strippedH1 = []
  while (headLines.length && /^#\s+\S/.test(headLines[0].trim())) strippedH1.push(headLines.shift().trim())
  if (strippedH1.length) {
    raw = headLines.join('\n').replace(/^\s*\n+/, '')
    for (const h of strippedH1) {
      thesisWarnings.push(`⚠ ${THESIS_FILE}（${scene.rel}）的 H1 已被忽略（页面 H1 来自目录名）：${h}`)
    }
  }
  if (!raw.trim()) throw new Error(`${THESIS_FILE}（${scene.rel}）剥掉 H1 之后是空的 —— 正文没有内容`)
  // ② 不许在正文里放素材：图和视频由模板在正文之前统一渲染，这里的相对路径一定指不到文件
  if (/!\[[^\]]*\]\(/.test(raw) || /<video\b/i.test(raw)) {
    throw new Error(`${THESIS_FILE}（${scene.rel}）里不要写图片/视频引用 —— 素材由模板在正文之前渲染一次；`
      + '正文要指图就用文字说「见本页上图」')
  }
  // ③ 别在正文里复述固定提示行的文案（那是模板的事，且会被 assertStrict 当成非法提示）
  if (raw.split('\n').some((l) => l.trim().startsWith('> ⏳') || l.trim().startsWith('> 🔍'))) {
    throw new Error(`${THESIS_FILE}（${scene.rel}）里不要写「⏳/🔍」那类固定提示行 —— 由模板按场景类型渲染`)
  }
  return raw
}

/**
 * 正文块：原文照排，只加一对 HTML 注释当边界。
 *
 * 为什么要边界：assertStrict 的 ≤25 字额度只该管「模板里那行一句话摘要」，
 * 不该管正文 —— 但正文**必须留在页面上**（它就是这个页面的主体）。所以用注释把它整段摘出来计量。
 */
function thesisBlock(thesis) {
  return [THESIS_START, thesis, THESIS_END].join('\n')
}

/** 实测数字块：一张「断言 → 实测」表 + 每条自己的只读 SQL（正文之下一条分隔线，不加解释性段落） */
function measuredBlock(measured) {
  const lines = [MEASURED_START, `## ${MEASURED_TITLE}`, '']
  for (const h of measured.head) lines.push(`> ${h}`)
  if (measured.head.length) lines.push('')
  measured.cases.forEach((c, i) => {
    const n = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧'][i] ?? `${i + 1}.`
    lines.push(`### ${n} ${c.claim}`, '')
    lines.push('```sql', ...c.sql.filter((l) => l.trim()), '```', '')
    const out = c.out.length ? c.out.join(' ｜ ') : '（空结果集）'
    lines.push(`实测：\`${out.replace(/`/g, "'")}\``, '')
  })
  lines.push(MEASURED_END)
  return lines.join('\n')
}

/* ------------------------------------------------------------------ 渲染 */

const relPrefix = (pagePath) => {
  const depth = dirname(pagePath).split('/').filter(Boolean).length
  return '../'.repeat(depth)
}

/**
 * 「没有视频」的两种语义（页面摘要下方、图片上方，各渲染一行 blockquote）。
 *
 * 出页门禁口径：**图是硬要求，视频不是**。有图就出页 —— 旧版「图 + 视频都齐才出页」
 * 会把「有图没视频」的页面（工具能力矩阵、令牌管理）**静默吞掉**：不报错、只是页不见。
 *
 * 但「没有视频」不等于都是「待补」，必须分两类，否则会写出永久假账：
 *   HINT_NO_UI  —— 产品本身没有界面（MCP / AI 集成类）。视频不适用，永远不会来，
 *                 所以显式写清真正的验收方式（mcp-server 测试套件断言）。**不计入「待补」**。
 *   HINT_PENDING —— 有界面、只是录屏还没做完。明确标「待补」，构建结束有汇总清单。
 */
const HINT_PENDING = '> ⏳ 视频待补：本场景的录屏尚未完成'
const HINT_NO_UI = '> 🔍 本场景无界面操作，验收方式：mcp-server 测试套件 195 条断言（两轮全绿）。'
/** 渠道/台账类的纯概念页：验收方式不是 mcp-server 套件，别把 MCP 那句照抄过来（那是另一种假账） */
const HINT_NO_UI_LEDGER = '> 🔍 本场景无界面操作：内容是「平台同步日志」与「仓库交换台账」的逐行对照，没有专属界面；验收按本页的字段对照表 + 两侧台账的只读 SQL 实测算，不借任何邻居场景的录屏。'
/**
 * 「能演却没演」的场景：**有真界面、点得动、调得通，只是这一轮还没录**。
 * 原来 07/1、07/2 被写成「无界面操作（验收 = mcp-server 195 条断言）」，那是偷懒的一种：
 * MCP 调试器（scripts/start-inspector.sh 起的就是它）能列工具 → 调工具 → 看返回，是真界面。
 * 所以这两页现在按「视频待补」出页（⏳），并写清欠的是什么 —— 欠 UI 测试 + 录屏，不是「不适用」。
 */
const HINT_PENDING_UI_VIDEO = '> ⏳ 待补 UI 测试与视频：本场景可点可调（MCP 调试器，scripts/start-inspector.sh 启动），属「能演未演」，不是「无界面」。'
/**
 * 性能与容量类（雪崩点400 起）：**没有界面可演，视频不适用** —— 这一类不是偷懒，
 * 而是「能演未演」与「无界面」都不对：录一段压测终端什么也证明不了，这条结论只能由
 * 曲线数据 + 一条复现命令证明。所以它写死自己的验收方式，不借 MCP 那句、也不进待补清单。
 */
const HINT_NO_UI_PERF = '> 🔍 本场景没有界面可演：验收方式是**曲线数据 + 一条复现命令**（见本页「凭什么证明好了」一节的命令与产物路径），不录视频。'
/**
 * 事故与修复类（2026-11-14 起）：**没有界面可演，视频不适用，也不该有**。
 * 这一桶的现场是「白屏 + 一份已经挂在 CDN 边缘缓存上的坏副本」「一条查询要跑 25 秒」
 * 「自检那一行静默没打出来」—— 界面上只表现为转圈或空白。录一段屏幕证明不了任何一条结论，
 * 硬录就是造假。所以它既不进待补清单（"该录没录"），也不借邻居场景的录屏：
 * 验收方式写死在本行 —— 命令输出 + 数字 + 时间线。
 */
const HINT_NO_UI_INCIDENT = '> 🔍 本场景是一起真实事故的复盘：现场是白屏/转圈/自检行静默，界面演不出来，所以不录视频；验收 = 命令输出 + 数字 + 时间线（见本页「凭什么证明好了」一节的命令）。'
const HINTS = new Set([HINT_PENDING, HINT_NO_UI, HINT_NO_UI_LEDGER, HINT_PENDING_UI_VIDEO, HINT_NO_UI_PERF, HINT_NO_UI_INCIDENT])

/**
 * 无界面产品能力的场景：
 *   ① 业务/AI集成/ 下、且不是「令牌管理」（那是后台真实 UI 页面，属正常场景），
 *      也不是下面 PENDING_UI_VIDEO_SCENES 里那两个「能演未演」的场景；
 *   ② 台账与取证/取证链与证据对齐/与内部系统的边界 —— 纯概念页：内容是「平台同步日志」与「仓库交换台账」逐行对照，
 *      没有专属界面；它的验收口径写在 HINT_NO_UI_LEDGER 里，**不依赖邻居场景的视频**。
 *
 * ⚠ 本清单必须与 qiqi-mall/scripts/check-docs-site.mjs 的 NO_UI_SCENES 保持一致。
 *   两边的语义分工：体检那边按「无界面场景免视频」放行，这边要同步标「无界面」。只改一边的后果是
 *   **页面印着「视频待补」而体检是绿的** —— 那是假账，比缺件更糟。
 *   现在先靠这段注释 + 一致性提醒（跨仓库共享常量不值当，见 2026-11-14 裁决）。
 *
 * 键就是场景的 rel 路径（'台账与取证/取证链与证据对齐/与内部系统的边界'），
 * 与 check-docs-site.mjs 的 NO_UI_SCENES 逐字相同 —— 两边都是目录路径，不再拼编号。
 */
const NO_UI_SCENE_HINTS = new Map([
  ['台账与取证/取证链与证据对齐/与内部系统的边界', HINT_NO_UI_LEDGER],
  // 性能与容量桶：没有界面可演，验收 = 曲线数据 + 复现命令（HINT_NO_UI_PERF）。
  // 这条与 check-docs-site.mjs 的 NO_UI_SCENES 必须逐字同步，只改一边会变成「页面印着待补、体检是绿的」。
  ['性能与容量/雪崩点400', HINT_NO_UI_PERF],
  ['性能与容量/每单19条SQL', HINT_NO_UI_PERF],
  ['性能与容量/会话探针', HINT_NO_UI_PERF],
  ['性能与容量/开环与闭环压测', HINT_NO_UI_PERF],
  ['性能与容量/复合索引消filesort', HINT_NO_UI_PERF],
])

/**
 * 「能演未演」清单（**不是**无界面清单）：这两页有真界面（MCP 调试器），视频欠着。
 * 与 NO_UI_SCENE_HINTS 的区别是语义，不是措辞：这里缺的是**该录还没录**，会进构建结束的「待补视频」汇总；
 * 体检 ⑤ 那边把这两个从 NO_UI_SCENES 摘掉后，缺件数 +2 —— 那是把真缺口露出来，不是放松门禁。
 */
const PENDING_UI_VIDEO_SCENES = new Set([
  '业务/AI集成/MCP最小闭环',
  '并发与竞态/版本冲突/AI端并发',
])

/**
 * 本场景「没有视频」时该印哪一行说明；返回 null 表示「有界面、只是还没录」→ 走 HINT_PENDING。
 * 无界面场景各写各的验收方式（MCP 能力矩阵走套件、渠道边界走本页字段对照），不共用一句文案。
 */
// 键就是 scene.rel（一段目录路径）—— 名字与目录逐字相同，不存在「带不带空格」两种写法了
const noUiHintOf = (scene) => {
  if (PENDING_UI_VIDEO_SCENES.has(scene.rel)) return HINT_PENDING_UI_VIDEO
  // AI集成桶里除「令牌管理」（后台真实页面）之外都是能力类场景：没有界面，视频不适用
  if (scene.bucket === '业务' && scene.sub === 'AI集成' && scene.name !== '令牌管理') return HINT_NO_UI
  // 事故与修复桶：整桶都没有可演的界面（理由写在 HINT_NO_UI_INCIDENT），按桶判而不是逐页登记 ——
  // 逐页登记会漏：新加一页事故、忘了同步两处名单，页面就会印成「视频待补」，那是假账。
  if (scene.bucket === '事故与修复') return HINT_NO_UI_INCIDENT
  return NO_UI_SCENE_HINTS.get(scene.rel) ?? null
}
/** 「无界面」与「能演未演」要分开：前者视频不适用（不进待补），后者是欠账（进待补汇总） */
const isPendingUiVideo = (scene) => PENDING_UI_VIDEO_SCENES.has(scene.rel)
const isNoUiScene = (group, item) => noUiHintOf(group, item) !== null && !isPendingUiVideo(group, item)

/**
 * 页面严格模板：H1 + 可选一行说明 + 可选一行「缺视频」提示
 * +（多段视频：每段上面一行它自己的名字）+ 图
 * +（场景目录有 正文.md 时）五段式正文 +（dev）口语稿折叠块 +（数据类场景）实测数字块。
 * 模板自己不加任何背景/取舍/总结/TODO/注意事项 —— 那些只能出自 正文.md。
 */
function renderPage({ title, desc, hint, videos, images, pagePath, spoken, thesis, measured }) {
  // docsify：页面在 material/**，素材在站点根的 _assets/，用相对上跳；
  // rspress：素材在 doc/public/_assets/（构建后落站点根 /_assets/），页面深两三层，
  //          直接用站点绝对路径最稳（Rspress 会把 img src 转百分号编码，绝对路径对得上门禁的 decode）
  const up = TARGET === 'rspress' ? '/' : relPrefix(pagePath)
  const lines = [`# ${title}`, '']
  if (desc) lines.push(desc, '')
  if (hint) lines.push(hint, '')
  const multi = videos.length > 1
  for (const v of videos) {
    if (multi && v.label) lines.push(v.label, '')
    lines.push(`<video src="${up}_assets/video/${v.path}" controls width="800"></video>`, '')
  }
  for (const img of images) lines.push(`![${title}](${up}_assets/img/${img})`, '')
  // 正文：素材之后。读者先看见「这东西长什么样 / 怎么动」，再读「谁在用、什么时候撑不住、改了什么、凭什么证明」
  if (thesis) lines.push(thesisBlock(thesis), '')
  if (thesis) {
    // ── 有正文.md 的页面：新顺序（正文 → 口语稿 → 实测数字）──────────────
    // 口语稿紧跟正文：读者读完正文，想照着讲的时候顺手展开；
    // 实测数字殿后：它是「正文里那些数字怎么来的」的查询凭证，当附录最顺。
    if (spoken) lines.push(spokenBlock(spoken), '')
    if (measured) lines.push(measuredBlock(measured), '')
  } else {
    // ── 没有正文.md 的页面：**旧顺序原样保留**（实测数字 → 口语稿）──────────
    // 为什么不全站统一成新顺序：那会把 8 个既有页（带 实测数字.sql 的台账页）的块位置全挪一遍，
    // 只为了一个块的位置去重写 8 个既有页（而且差别只在 dev 可见）不值当 ——
    // 「没有意图的变化就不该发生」，否则以后回看 diff 分不清哪次是真改了内容。
    // 这 8 页将来补上 正文.md 时会自然切到新顺序，是个平滑迁移。
    if (measured) lines.push(measuredBlock(measured), '')
    if (spoken) lines.push(spokenBlock(spoken), '')
  }
  return `${lines.join('\n')}\n`
}

/**
 * 断言：标题之外只有 说明/分段名 + video + img（+ 正文块 + 实测数字块 + dev 口语稿折叠块）；
 * 标题不得带数字前缀。
 *
 * **≤25 字额度的语义（2026-11-14 收窄）**：它只管「模板自己产出的文字」—— 也就是那行一句话摘要（desc）
 * 与视频分段名。它**不再管** 正文.md 与 实测数字：那两块是作者写的正经内容，有各自的家
 * （场景目录里的 正文.md / 实测数字.sql），不是「在生成物里手写解释文字」—— 后者才是当年设这个上限的原因。
 * 所以正文/实测/口语稿三块整段摘掉之后再量；摘不干净（未闭合）直接报错，不许把额度悄悄放大。
 */
function assertStrict(pagePath, page) {
  const [h1, ...allRest] = page.split('\n')
  if (!/^# \S/.test(h1)) throw new Error(`${pagePath}: H1 缺失`)
  if (/^# \d+-/.test(h1)) throw new Error(`${pagePath}: H1 不应带数字前缀`)
  // 三块「作者写的材料」整段摘掉再量正文，不占 25 字额度：
  //   ① 口语稿折叠块（只在 dev 出现）—— 一分钟口播稿，是给作者念的，不是页面正文；
  //   ② 正文块（THESIS_START…THESIS_END）—— 来自场景目录的 正文.md，原文直出；
  //   ③ 实测数字块（MEASURED_START…MEASURED_END）—— SQL 与实测输出是数据类证据本身。
  const rest = []
  let inSpoken = false
  let inMeasured = false
  let inThesis = false
  for (const line of allRest) {
    if (line.startsWith('<details class="spoken-script">')) { inSpoken = true; continue }
    if (inSpoken) { if (line.trim() === '</details>') inSpoken = false; continue }
    if (line.trim() === THESIS_START) { inThesis = true; continue }
    if (inThesis) { if (line.trim() === THESIS_END) inThesis = false; continue }
    if (line.startsWith(MEASURED_START)) { inMeasured = true; continue }
    if (inMeasured) { if (line.trim() === MEASURED_END) inMeasured = false; continue }
    rest.push(line)
  }
  if (inSpoken) throw new Error(`${pagePath}: 口语稿折叠块未闭合（缺 </details>）`)
  if (inThesis) throw new Error(`${pagePath}: 正文块未闭合（缺 ${THESIS_END}）`)
  if (inMeasured) throw new Error(`${pagePath}: 实测数字块未闭合（缺 ${MEASURED_END}）`)
  // 「缺视频」提示行是 blockquote：只允许出现一次，且必须是两种标准文案之一；
  // 它不算「正文」（否则 25 字上限会被一句固定提示吃光），但仍受模板约束。
  const hints = rest.filter((l) => l.trim().startsWith('>'))
  if (hints.length > 1) throw new Error(`${pagePath}: 缺视频提示行 ${hints.length} 行，最多 1 行`)
  for (const h of hints) if (!HINTS.has(h.trim())) throw new Error(`${pagePath}: 非标准缺视频提示：${h.trim()}`)
  const prose = rest.filter((l) => l.trim() && !l.startsWith('<video') && !l.startsWith('![')
    && !l.trim().startsWith('>'))
  const text = prose.join('')
  const len = [...text].length
  if (len > BODY_LIMIT) throw new Error(`${pagePath}: 正文 ${len} 字，超过 ${BODY_LIMIT} 字上限`)
  if (/TODO|注意|背景|取舍|变更|总结|说明：/i.test(text)) throw new Error(`${pagePath}: 正文含解释性内容`)
  return len
}

const written = []
const changed = []
const sceneStats = []
const spokenEmbedded = []      // dev：真正嵌入了口语稿的场景（prod 恒为空，因为压根不读）
const measuredEmbedded = []    // 真正渲染了「实测数字」块的场景（场景目录下有 实测数字.sql）
const thesisEmbedded = []      // 真正渲染了「五段式正文」的场景（场景目录下有 正文.md）
const thesisWarnings = []      // 正文.md 里被忽略的东西（目前只有「多余的 H1」），构建结束统一打出来

function emit(pagePath, content, meta) {
  const abs = join(OUT_ROOT, pagePath)
  const dir = dirname(abs)
  mkdirSync(dir, { recursive: true })
  chmodSync(dir, 0o755)                 // 目录要能让 nginx worker 穿过（umask 可能是 077）
  const old = existsSync(abs) ? readFileSync(abs, 'utf8') : null
  if (old !== content) { writeFileSync(abs, content); changed.push(pagePath) }
  chmodSync(abs, 0o644)
  written.push({ pagePath, ...meta, bodyChars: assertStrict(pagePath, content) })
}

/* ------------------------------------------------------------------ 生成图页 */

for (const group of DIAGRAM_GROUPS) {
  for (const item of group.items) {
    if (!passOnly(item.name)) continue
    const cands = DOC_FILES
      .filter((p) => isImage(p))
      .filter((p) => p.startsWith('er/') || p.startsWith('flows/'))
      .filter((p) => item.match.every((t) => p.includes(t)))
      .sort((a, b) => a.length - b.length)
    const image = pickArt(cands)              // 有 .svg 就取 .svg（prod 也一律 SVG）
    if (!image) continue                       // 素材缺失 → 不生成空页
    // 图页文件名同样不带编号（顺序只由 DIAGRAM_GROUPS 的书写顺序决定）
    const pagePath = `material/diagrams/${group.dir}/${slug(item.name)}.md`
    // 一分钟口语稿：与场景页同一套机制（prod 完全不读）。图页的图是平铺文件、没有「一张图一个目录」，
    // 所以稿件按「图所在的目录 + 图文件名」定位，见 readDiagramSpoken。
    const spoken = readDiagramSpoken(image)
    if (spoken) spokenEmbedded.push(`${group.label}/${item.name}`)
    emit(pagePath, renderPage({ title: item.name, desc: item.desc, videos: [], images: [image], pagePath, spoken, thesis: null }), {
      kind: 'diagram', group: group.label, label: item.name,
      imageFiles: [image], videoFiles: [], hasSpoken: Boolean(spoken),
    })
  }
}

/* ------------------------------------------------------------------ 生成场景页 */

for (const scene of SCENES) {
  {
    if (!passOnly(scene.name)) continue
    const videos = sceneVideos(scene)
    const images = sceneImages(scene)
    // 出页门禁：**图是硬要求，视频不是**。
    //   缺图        → 不出页（图是页面主体，无图的场景页没意义）；
    //   有图缺视频  → 照出页，摘要下方显式渲染一行提示（分「无界面操作」/「视频待补」两种语义），
    //                 绝不静默吞掉 —— 旧版两者缺一就 skip，能力矩阵页 / 令牌页直接消失且不报错。
    const sceneLabel = scene.subLabel ? `${scene.bucketLabel}/${scene.subLabel}/${scene.name}` : `${scene.bucketLabel}/${scene.name}`
    if (!images.length) {
      const why = videos.length ? '缺图（未生成）' : '缺素材（未生成）'
      sceneStats.push({ scene: sceneLabel, status: why,
        videos: videos.length, images: images.length })
      continue
    }
    const pendingUi = isPendingUiVideo(scene)
    const noUiHint = videos.length || pendingUi ? null : noUiHintOf(scene)
    const hint = videos.length ? null : (pendingUi ? HINT_PENDING_UI_VIDEO : (noUiHint ?? HINT_PENDING))
    const status = !videos.length ? (noUiHint ? '已生成·无界面' : '已生成·视频待补') : '已生成'
    // 页面路径 = 场景的 rel 直接当文件名：material/scenarios/<桶>/[<子分类>/]<场景名>.md
    // （rel 的末段就是场景名，别再拼一次 —— 拼了就得到 …/购物车/购物车.md，门禁 ① 也按这个口径查）
    const pagePath = `material/scenarios/${scene.rel}.md`
    // 一分钟口语稿：只有 dev 会读到这里（readSpokenScript 在 prod 直接返回 null）
    const spoken = readSpokenScript(scene)
    if (spoken) spokenEmbedded.push(sceneLabel)
    // 五段式正文：场景目录下的 `正文.md`（一等输入，原文直出；缺文件时页面与旧版逐字节一致）
    const thesis = readThesis(scene)
    if (thesis) thesisEmbedded.push(sceneLabel)
    // 实测数字：场景目录下的 `实测数字.sql`（只读 select + 实测输出），缺文件就不渲染这一块
    const measured = readMeasured(scene)
    if (measured) measuredEmbedded.push(sceneLabel)
    emit(pagePath, renderPage({ title: scene.name, desc: null, hint, videos, images, pagePath, spoken, thesis, measured }), {
      kind: 'scene', group: scene.bucketLabel, bucket: scene.bucket, sub: scene.sub,
      label: scene.name, hint, rel: scene.rel,
      imageFiles: images, videoFiles: videos.map((v) => v.path), hasSpoken: Boolean(spoken),
      hasThesis: Boolean(thesis), hasMeasured: Boolean(measured),
    })
    sceneStats.push({ scene: sceneLabel, status, pagePath,
      hint: hint
        ? (pendingUi ? '待补 UI 测试与视频（能演未演）'
          : (noUiHint ? '无界面操作（视频不适用）' : '视频待补'))
        : null,
      videos: videos.length, images: images.length, spoken: Boolean(spoken),
      thesis: Boolean(thesis), measured: Boolean(measured),
      videoFiles: videos.map((v) => v.path), imageFiles: images })
  }
}

/* ------------------------------------------------------------------ 清理过期页 */

const keep = new Set(written.map((w) => w.pagePath))
// --only（冒烟/抽查）时不许清过期页：只生成 3 页就把其余 84 页当「过期」删掉是灾难
const stale = ONLY.length ? [] : walk(OUT).filter((p) => p.endsWith('.md')).map((p) => `material/${p}`).filter((p) => !keep.has(p))
for (const p of stale) rmSync(join(OUT_ROOT, p))

/* ------------------------------------------------------------------ 清理空目录 */

/**
 * 上面只删**过期的 .md**，目录会原样留下：分组改名/合并后（如 03-状态机 → 01-状态机、
 * 06-数据模型 → 05-数据模型），旧分组目录里的页面搬空或被删光，就剩一个光秃秃的空目录——
 * 素材里看不见（没有 .md），但 `find material -type d` 会一直数出它们，属于假的「目录结构」。
 *
 * 只用 rmdir 语义：**非空目录删不掉**（ENOTEMPTY）就跳过，天然安全 —— 不需要先判断「是不是只有 .md」，
 * 也不会误删还有页面的分组。
 * 遍历顺序是自底向上（先子目录、后父目录），这样「子目录被删空后父目录也空了」的情况能一并清掉；
 * 必须排在 stale 清理之后（否则删掉的页面还没删、目录都还算非空）。
 */
let emptyDirsRemoved = 0
const allDirs = []
{
  const stack = ['']
  while (stack.length) {
    const rel = stack.pop()
    for (const ent of readdirSync(join(OUT, rel), { withFileTypes: true })) {
      if (!ent.isDirectory()) continue
      const childRel = rel ? `${rel}/${ent.name}` : ent.name
      allDirs.push(childRel)
      stack.push(childRel)
    }
  }
}
for (const rel of allDirs.reverse()) {          // 自底向上：子目录先删
  try {
    rmdirSync(join(OUT, rel))                    // 非空 → 抛 ENOTEMPTY → 保留
    emptyDirsRemoved++
  } catch {
    /* 非空目录：跳过（这正是 rmdir 的安全边界） */
  }
}
console.log(`ℹ 清理空目录 ${emptyDirsRemoved} 个`)

/** 权限归一：目录 755 / 文件 644，保证容器里 nginx worker（uid 101，非 root）全程可读 */
function normalizePerms(root) {
  chmodSync(root, 0o755)
  const stack = ['']
  while (stack.length) {
    const rel = stack.pop()
    const abs = join(root, rel)
    chmodSync(abs, 0o755)
    for (const ent of readdirSync(abs, { withFileTypes: true })) {
      const childRel = rel ? `${rel}/${ent.name}` : ent.name
      if (ent.isDirectory()) stack.push(childRel)
      else chmodSync(join(abs, ent.name), 0o644)
    }
  }
}
normalizePerms(OUT)

/* ------------------------------------------------------------------ 侧边栏 */

const byKind = (kind) => written.filter((w) => w.kind === kind)

/**
 * 侧边栏只有**一棵树**（buildNavTree），再由两个 renderer 渲染成两份等价产物：
 *   · renderDocsifySidebar → _sidebar.md   （docsify 的 loadSidebar；门禁 ⑨⑫ 读的就是它）
 *   · renderRspressSidebar → sidebar.generated.mjs（Rspress 的 themeConfig.sidebar）
 * 两份产物必须同序同名 —— 分组顺序、编号连续性只在这里定义一次，⑫ 门禁对两边同时成立。
 *
 * 节点类型：section（`- **粗体分组**`，非链接，带子节点）/ group（可展开分组）/
 *           page（叶子链接）/ pending（「素材未到」占位，仅代码里兜底，现状 5 个图页组全有页）
 */
const navPage = (text, path) => ({ type: 'page', text, path })
const navGroup = (text, items) => ({ type: 'group', text, items })
const navSection = (text, items, blankBefore = false) => ({ type: 'section', text, items, blankBefore })

/** 12 篇面试私人笔记的入口（只在 dev 站出现；prod 连文件都不拷，见 PRIVATE_DOCS） */
const businessTree = () => [
  navPage('项目总体介绍', 'project-intro.md'),
  navPage('项目架构', 'project-architecture.md'),
  navPage('架构决策取舍', 'architecture-decisions.md'),
  navGroup('演进与技术', [
    navPage('项目演进', 'evolution.md'),
    navPage('并发与一致性', 'concurrency.md'),
    navPage('MySQL 调优', 'mysql-tuning.md'),
    navPage('多实例与分布式', 'multi-instance.md'),
  ]),
  navGroup('订单履约（OMS）', [
    navGroup('宏观认知', [
      navPage('OMS 与商城的关系', 'oms-relation.md'),
      navPage('订单全链路', 'oms-pipeline.md'),
      navPage('与真实 OMS 的差距', 'oms-gap.md'),
    ]),
    navGroup('拆单', [
      navPage('拆单：动机', 'oms-split.md'),
      navPage('拆单：算法与口径', 'oms-algorithm.md'),
    ]),
  ]),
]

function buildNavTree({ includeBusiness }) {
  const tree = [navPage('首页', '/'), ]
  // 顺序 = SCENE_BUCKETS / DIAGRAM_GROUPS 的书写顺序（显式顺序表），没有编号可排
  // 场景树 = 桶 →（子分类）→ 场景页；顺序完全由 SCENE_BUCKETS/SCENES 决定，
  // byKind('scene') 的插入顺序就是 SCENES 的顺序，所以这里只做「按桶/子分类分组」，不再排序。
  const sceneTree = []
  for (const bucket of SCENE_BUCKETS) {
    const pages = byKind('scene').filter((w) => w.bucket === bucket.dir)
    if (!pages.length) continue                 // 该桶素材全未到，先不出空桶
    if (bucket.subs) {
      const subGroups = []
      for (const sub of bucket.subs) {
        const subPages = pages.filter((p) => p.sub === sub.dir)
        if (!subPages.length) continue
        subGroups.push(navGroup(sub.label, subPages.map((p) => navPage(p.label, p.pagePath))))
      }
      sceneTree.push(navGroup(bucket.label, subGroups))
    } else {
      sceneTree.push(navGroup(bucket.label, pages.map((p) => navPage(p.label, p.pagePath))))
    }
  }
  const diagramGroups = DIAGRAM_GROUPS.map((group) => {
    const pages = byKind('diagram').filter((w) => w.group === group.label)
    return navGroup(group.label, pages.length
      ? pages.map((p) => navPage(p.label, p.pagePath))
      : [{ type: 'pending', text: '（素材未到）' }])
  })
  tree.push(navSection('项目材料', [
    navGroup('一、功能场景（图 + 视频）', sceneTree),
    ...diagramGroups,
  ]))
  if (includeBusiness) tree.push(navSection('业务逻辑', businessTree(), true))
  return tree
}

/** 树 → _sidebar.md（缩进 = 2×(层-1)；section 是「粗体行 + 更深一层的子列表」） */
function renderDocsifySidebar(tree) {
  const lines = []
  const emit = (n, depth) => {
    const pad = '  '.repeat(depth - 1)
    if (n.blankBefore) lines.push('')
    if (n.type === 'section') {
      lines.push(`${pad}- **${n.text}**`)
      for (const c of n.items ?? []) emit(c, depth + 1)
      return
    }
    if (n.type === 'group') {
      lines.push(`${pad}- ${n.text}`)
      for (const c of n.items ?? []) emit(c, depth + 1)
      return
    }
    if (n.type === 'page') { lines.push(`${pad}- [${n.text}](${n.path})`); return }
    if (n.type === 'pending') { lines.push(`${pad}- ${n.text}`); return }
    throw new Error(`未知侧边栏节点：${n.type}`)
  }
  for (const n of tree) emit(n, 1)
  return `${lines.join('\n')}\n`
}

/**
 * 树 → Rspress sidebar 数组。
 * 折叠口径对齐 docsify 的 sidebarDisplayLevel: 2 —— 层 1~2 展开、层 3 及更深默认收起。
 * （section 是 Rspress 的 sectionHeaderText，不是可点项；page 的 link 去掉 .md，Rspress 自己补 .html）
 */
function renderRspressSidebar(tree) {
  const toItems = (nodes, depth) => nodes.flatMap((n) => {
    if (n.type === 'section') return [{ sectionHeaderText: n.text }, ...toItems(n.items ?? [], depth + 1)]
    if (n.type === 'page') return [{ text: n.text, link: n.path === '/' ? '/' : `/${n.path.replace(/\.md$/, '')}` }]
    if (n.type === 'group') {
      return [{ text: n.text, collapsible: true, collapsed: depth >= 3, items: toItems(n.items ?? [], depth + 1) }]
    }
    // 占位行：collapsible:false 才不会渲染出可点的折叠箭头（Rspress 只给 collapsible 项加 chevron）
    if (n.type === 'pending') return [{ text: n.text, collapsible: false, items: [] }]
    throw new Error(`未知侧边栏节点：${n.type}`)
  })
  return toItems(tree, 1)
}

const NAV = buildNavTree({ includeBusiness: MODE !== 'prod' })
const sidebarText = renderDocsifySidebar(NAV)

/**
 * 顺序清单（`material/_nav-manifest.json`）：把 SCENE_BUCKETS / DIAGRAM_GROUPS 的书写顺序**物化**一份，
 * 供门禁 ⑫ 拿它跟 _sidebar.md 逐条比对（顺序一致 + 无重复 + 无遗漏）。
 *
 * 为什么需要它：编号从文件名里去掉以后，「少一个场景」在 URL 上再也看不出来（以前靠 7→9 断档发现），
 * 而侧边栏是生成物，自己跟自己对永远一致。所以判据必须是「生成物 == 清单」，清单必须由构建落盘，
 * 不能靠门禁去解析 build-material.mjs 的源码（那是靠正则猜代码，改一行注释就误判）。
 * 内容只有路径与显示名（无内网信息、无时间戳），因此 prod 产物里留着也安全，且保证幂等。
 */
const navManifest = {
  version: 1,
  scenes: written.filter((w) => w.kind === 'scene').map((w) => ({
    bucket: w.bucket, sub: w.sub, label: w.label, page: w.pagePath,
  })),
  diagrams: written.filter((w) => w.kind === 'diagram').map((w) => ({
    group: w.group, label: w.label, page: w.pagePath,
  })),
}
const manifestText = `${JSON.stringify(navManifest, null, 2)}\n`

if (TARGET === 'docsify') {
  const sidebarPath = join(OUT_ROOT, '_sidebar.md')
  const oldSidebar = existsSync(sidebarPath) ? readFileSync(sidebarPath, 'utf8') : null
  if (oldSidebar !== sidebarText) { writeFileSync(sidebarPath, sidebarText); changed.push('_sidebar.md') }
  chmodSync(sidebarPath, 0o644)
  // 顺序清单：与 _sidebar.md 同一份数据渲染出来的，放一处（material/ 下）给门禁 ⑫ 读
  const manifestPath = join(OUT, '_nav-manifest.json')
  mkdirSync(OUT, { recursive: true })
  const oldManifest = existsSync(manifestPath) ? readFileSync(manifestPath, 'utf8') : null
  if (oldManifest !== manifestText) { writeFileSync(manifestPath, manifestText); changed.push('material/_nav-manifest.json') }
  chmodSync(manifestPath, 0o644)
}


/* ------------------------------------------------------------------ 生产模式：组装自包含站点 */

/**
 * dev 站点靠 nginx alias 把 /_assets/{img,video}/ 指到宿主素材目录（见 _deploy/nginx.conf）；
 * prod 要能直接丢给 OSS / Caddy file_server，所以把「页面真正引用到的」图与视频**拷进** dist-prod：
 *   dist-prod/  index.html  _404.md  _sidebar.md  README.md  material/  _assets/{img,video}/  _vendor/
 * 只拷被引用的文件（不拷 .mmd 源、不拷旧结构图、不拷未引用素材），站点自包含且无多余内部文件。
 */
function copyInto(srcAbs, relToOut) {
  const dst = join(OUT_ROOT, relToOut)
  mkdirSync(dirname(dst), { recursive: true })
  copyFileSync(srcAbs, dst)
  chmodSync(dirname(dst), 0o755)
  chmodSync(dst, 0o644)
}

/** README 脱敏：入口表整行删除式重建（只留 3 行线上入口），开发环境整张表删掉 */
const PROD_ENTRY_ROWS = [
  '| C 端买家商城 | https://mall.rose7.cn | 逛商品、下单、售后 |',
  '| B 端管理后台 | https://admin.rose7.cn | 需要访问口令 |',
  '| 文档站 | https://docs.rose7.cn | 本页 |',
]

function prodReadme(src) {
  const lines = src.split('\n')
  const out = []
  for (let i = 0; i < lines.length;) {
    const line = lines[i]
    const isEnvSection = /^##\s*(测试环境|开发环境)\s*$/.test(line)
    if (isEnvSection) {
      const isTest = /测试环境/.test(line)
      i++
      // 吞掉该节的表格行与空行，直到遇到正文（非空且非表格行）
      while (i < lines.length && (/^\s*$/.test(lines[i]) || lines[i].startsWith('|'))) i++
      if (isTest) out.push('## 线上环境', '', '| 入口 | 地址 | 用途 |', '|---|---|---|',
        ...PROD_ENTRY_ROWS, '')
      continue                                    // 开发环境：整张表连标题一起消失
    }
    out.push(line)
    i++
  }
  // 兜底：任何残留的敏感行直接丢（正常应为 0 行）
  const kept = out.filter((l) => !PROD_REDACT.some((re) => re.test(l)))
  const dropped = out.length - kept.length
  // 压掉连续空行
  const tidy = []
  for (const l of kept) {
    if (l.trim() === '' && tidy.length && tidy[tidy.length - 1].trim() === '') continue
    tidy.push(l)
  }
  return { text: `${tidy.join('\n').replace(/\n+$/, '')}\n`, dropped }
}

/** 页面正文兜底脱敏：命中 → 用中性描述替换掉敏感片段（正常应为 0 命中） */
const PROD_NEUTRAL = [
  { re: /MCP ?Inspector/gi, to: '后台调试入口' },
  { re: /MCP_?调试器/g, to: '后台调试入口' },
  { re: /swagger/gi, to: '接口文档' },
  { re: /100\.113\.135\.55/g, to: '内部地址' },
  { re: /admin\s*\/\s*123456/g, to: '管理员账号（口令另发）' },
  { re: /ai_operator/g, to: '运维账号' },
  { re: /tailcb871e/g, to: '内部域名' },
  { re: /:(30\d\d|51\d\d|808[0-9]|18080)/g, to: '' },
  { re: /qiqi_mall_e2e/g, to: '自动化测试库' },
  { re: /playwright/gi, to: '自动化测试' },
  { re: /e2e/gi, to: '自动化测试' },
  { re: /\/root\//g, to: '/' },
  { re: /\/tmp\//g, to: '/' },
]
const neutral = (line) => PROD_NEUTRAL.reduce((acc, { re, to }) => acc.replace(re, to), line)

if (TARGET === 'docsify' && MODE === 'prod') {
  mkdirSync(OUT_ROOT, { recursive: true })

  // 1) 站点外壳（index.html 原样拷贝：basePath 不写死，见报告结论）
  for (const f of ['index.html', '_404.md', 'favicon.ico']) copyInto(join(SITE, f), f)
  // 手工文档：12 篇私人业务笔记一律不拷（prod 只保留 README/_404/_sidebar 三份 md）
  const privateSet = new Set(PRIVATE_DOCS)
  for (const f of readdirSync(SITE)) {
    if (!f.endsWith('.md') || f === 'README.md' || f === '_sidebar.md') continue
    if (privateSet.has(f)) continue
    copyInto(join(SITE, f), f)
  }
  // 上一版产物里已经拷进去的私人文档 → 明确清掉，保证 `ls dist-prod/*.md` 只剩 3 个
  const removedPrivate = []
  for (const f of PRIVATE_DOCS) {
    const abs = join(OUT_ROOT, f)
    if (existsSync(abs)) { rmSync(abs); removedPrivate.push(f) }
  }
  for (const f of walk(join(SITE, '_vendor'))) copyInto(join(SITE, '_vendor', f), `_vendor/${f}`)

  // 2) 只拷贝页面引用到的图 / 视频（相对路径与页面里的 _assets/ 引用一一对应）
  //    图一律是 .svg（矢量）；万一某张 SVG 的文字里真有端口/IP（不是浮点误撞），
  //    只在**拷进去的副本**上把那段数字掩掉，源素材一个字节不动。
  const imgRefs = new Set(written.flatMap((w) => w.imageFiles || []))
  const vidRefs = new Set(written.flatMap((w) => w.videoFiles || []))
  const svgMasked = []
  const svgResidual = []
  let svgCount = 0
  for (const rel of imgRefs) {
    const srcAbs = join(SRC_DOCS, rel)
    if (!hasExt(rel, '.svg')) { copyInto(srcAbs, `_assets/img/${rel}`); continue }
    svgCount++
    const { text, hits } = maskSvgText(readDoc(rel))
    if (hits.length) svgMasked.push({ file: rel, hits })
    const residual = scanSensitive(text)
    if (residual.length) svgResidual.push({ file: rel, residual })
    if (!hits.length) { copyInto(srcAbs, `_assets/img/${rel}`); continue }
    const dst = join(OUT_ROOT, `_assets/img/${rel}`)
    mkdirSync(dirname(dst), { recursive: true })
    writeFileSync(dst, text)
    chmodSync(dirname(dst), 0o755)
    chmodSync(dst, 0o644)
  }
  for (const rel of vidRefs) copyInto(join(SRC_VIDEO, rel), `_assets/video/${rel}`)

  // 2b) 清掉上一版残留的素材（旧版把 21 张 SVG 换成了 PNG，那些 PNG 现在必须清走）
  const imgKeep = new Set([...imgRefs].map((r) => `_assets/img/${r}`))
  const vidKeep = new Set([...vidRefs].map((r) => `_assets/video/${r}`))
  const prunedAssets = []
  for (const rel of walk(join(OUT_ROOT, '_assets'))) {
    const p = `_assets/${rel}`
    if (imgKeep.has(p) || vidKeep.has(p)) continue
    rmSync(join(OUT_ROOT, p))
    prunedAssets.push(p)
  }

  // 3) README：dev 版是「测试环境 + 开发环境」两张内网入口表，prod 版重建为线上入口
  const { text: prodReadmeText, dropped: readmeDropped } = prodReadme(readFileSync(join(SITE, 'README.md'), 'utf8'))
  writeFileSync(join(OUT_ROOT, 'README.md'), prodReadmeText)
  chmodSync(join(OUT_ROOT, 'README.md'), 0o644)

  // 4) 页面级扫描：命中就用中性描述替换（正常 0 命中，命中会有明显日志）
  let neutralized = 0
  for (const rel of walk(OUT_ROOT).filter((p) => p.endsWith('.md'))) {
    const abs = join(OUT_ROOT, rel)
    const src = readFileSync(abs, 'utf8')
    const lines = src.split('\n')
    const hits = lines.filter((l) => PROD_REDACT.some((re) => re.test(l)))
    if (!hits.length) continue
    neutralized += hits.length
    writeFileSync(abs, lines.map((l) => (PROD_REDACT.some((re) => re.test(l)) ? neutral(l) : l)).join('\n'))
    console.log(`  ! 脱敏替换 ${rel}: ${hits.length} 行`)
  }

  // 5) 权限归一（静态托管/Caddy 非 root 读取）：目录 755 / 文件 644
  normalizePerms(OUT_ROOT)

  // 6) 脱敏终检（口径见 PORT_PATTERNS）：站点自有内容按全部特征扫，必须全 0。
  //    第三方 vendor 库是原样上游文件（`z-index:1000` 这种 CSS 值会撞裸端口正则），
  //    只按「带主机的端口 / IP」这类强特征扫 —— 那里不可能出现我们自己的内网地址。
  const ownHits = new Map()   // 特征 -> [文件/命中片段]
  const vendorHits = new Map()
  const STRONG = [PROD_GATE_PATTERNS[1], PROD_GATE_PATTERNS[2], PROD_GATE_PATTERNS[3]]
  for (const rel of walk(OUT_ROOT)) {
    const bytes = readFileSync(join(OUT_ROOT, rel)).toString('latin1')
    const isVendor = rel.startsWith('_vendor/')
    const hits = isVendor ? scanSensitive(bytes, STRONG, PROD_GATE_TOKENS) : scanSensitive(bytes)
    const bag = isVendor ? vendorHits : ownHits
    for (const h of hits) {
      if (!bag.has(h.what)) bag.set(h.what, [])
      bag.get(h.what).push(`${rel} :: ${JSON.stringify(h.text).slice(0, 40)}`)
    }
  }
  const leaked = [...ownHits]
  const totalBytes = walk(OUT_ROOT)
    .reduce((sum, rel) => sum + statSync(join(OUT_ROOT, rel)).size, 0)

  console.log(`\n生产模式  : dist-prod（index.html + _sidebar.md + README.md + material/ + _assets/ + _vendor/）`)
  console.log(`素材拷贝  : 图 ${imgRefs.size} 个（其中 SVG ${svgCount} 个，全部为 SVG）/ 视频 ${vidRefs.size} 个（只拷被引用的）`)
  console.log(`README    : 入口表重建 ${PROD_ENTRY_ROWS.length} 行${readmeDropped ? `，兜底丢弃 ${readmeDropped} 行` : ''}`)
  if (prunedAssets.length) console.log(`清理素材  : ${prunedAssets.length} 个上一版残留文件（含旧 PNG 副本）`)
  console.log(`私人文档  : 排除 ${PRIVATE_DOCS.length} 篇（业务逻辑/面试私人笔记）${removedPrivate.length ? `，清掉上一版残留 ${removedPrivate.length} 篇` : ''}`)
  console.log(`正文脱敏  : ${neutralized ? neutralized + ' 行被替换 ⚠️' : '0 命中 ✅'}`)
  console.log(`产物大小  : ${(totalBytes / 1048576).toFixed(1)} MiB / ${walk(OUT_ROOT).length} 个文件`)
  console.log(svgMasked.length
    ? `SVG 真命中: ${svgMasked.length} 个 SVG 的文字里有真实端口/IP（不是浮点误撞），已在副本上掩码（源素材未改）：\n` +
      svgMasked.map((s) => `            ${s.file}: ${s.hits.map((h) => `${h.before}→${h.after}`).join(', ')}`).join('\n')
    : `SVG 真命中: 0（全部 ${svgCount} 张 SVG 的文字节点零命中，浮点坐标误撞已消除）`)
  if (svgResidual.length) {
    console.log(`⚠️ SVG 仍有掩不掉的真命中（需单独处理源 .mmd）：`)
    for (const s of svgResidual) console.log(`  ${s.file}: ${s.residual.map((r) => r.text).join(', ')}`)
  }
  if (vendorHits.size) {
    console.log(`vendor 强特征: ${[...vendorHits].map(([k, v]) => `${k}×${v.length}`).join(', ')} ⚠️`)
  }
  // 7) 私人文档断链自检：产物里任何 .md 都不该再链到那 12 篇（_sidebar 已整段删掉）
  const privateRe = new RegExp(PRIVATE_DOCS.map((f) => f.replace(/\.md$/, '')).join('|'))
  const privateLinks = walk(OUT_ROOT)
    .filter((r) => r.endsWith('.md'))
    .filter((r) => privateRe.test(readFileSync(join(OUT_ROOT, r), 'utf8')))
  console.log(privateLinks.length
    ? `私人文档引用: ${privateLinks.length} 个 md 仍链到私人文档 ⚠️ → ${privateLinks.join(', ')}`
    : '私人文档引用: 0（无页面链到那 12 篇）')
  if (privateLinks.length) process.exitCode = 4

  if (leaked.length) {
    console.log('\n❌ 脱敏终检未通过：')
    for (const [what, files] of leaked) console.log(`  ${what}: ${files.length} 处 → ${files.slice(0, 5).join(' | ')}`)
    process.exitCode = 3
  } else {
    const n = PROD_GATE_PATTERNS.length + PROD_GATE_TOKENS.length
    console.log(`脱敏终检  : ${n} 条特征（${PROD_GATE_PATTERNS.length} 条端口/IP 正则 + ${PROD_GATE_TOKENS.length} 条字面量）全 0 命中 ✅`)
  }
}

/* ------------------------------------------------- 旧链接兜底（docsify → Rspress） */

/**
 * 为什么需要两段脚本：docsify 是 **hash 路由**（`http://host:3027/#/material/…`），
 * `#` 之后的部分浏览器**根本不发给服务器** ——
 *   · 旧 hash 链接打到新站 → 浏览器只请求 `/` → 命中首页 200 → **永远不会进 404 页**。
 *     所以「读 location.hash 再重写」必须放在**每一页**（全局脚本），放 404 里等于永远不执行。
 *   · 旧裸路径（`…/9-库存台账与对账.md`，docsify 时代也直接可访问） → 新站没有这个文件 → 404，
 *     这一段才放在 404 页里，按 .md → .html 翻译。
 *
 * 两段都遵守三条纪律：
 *   1) 只用 location.replace（真跳转），不用 history.pushState —— 不制造「URL 变了但状态还是 404」
 *      的软 404，爬虫不会把空页当正常页索引；
 *   2) 翻译不出来就什么都不做：全局脚本直接 return（页面照常工作），404 页原地保留人话提示 + 首页链接；
 *   3) 跳过去还没这个页 → 服务器再返 404，但那时 URL 已无 hash / 已是 .html → 脚本立即 return，不会循环。
 */
const LEGACY_REDIRECT_JS = `/* docsify 旧 hash 链接兜底：/#/material/xxx → /material/xxx.html
 * 由 /root/docsify/build-material.mjs --target=rspress 生成，勿手改。
 * 只在 hash 以 "#/" 开头时动作（Rspress 自己的锚点是 "#中文标题"，不会误伤）；
 * 失败/不匹配一律 return，页面照常工作。 */
(function () {
  var raw = location.hash || '';
  if (raw.indexOf('#/') !== 0) return;
  var path = raw.slice(2).split('?')[0].split('#')[0];
  try { path = decodeURIComponent(path); } catch (e) {}
  while (path.charAt(0) === '/') path = path.slice(1);
  if (path.slice(-3).toLowerCase() === '.md') path = path.slice(0, -3);
  if (!path) { location.replace('/'); return; }
  location.replace('/' + path.split('/').map(encodeURIComponent).join('/') + '.html');
})();
`;


/* ------------------------------------------------------------------ Rspress 输出端（--target=rspress） */

/**
 * 与 docsify 侧的分工：
 *   · 页面正文 / 素材定位 / 口语稿条件编译 / assertStrict —— 完全复用上面的逻辑（同一份清单、同一套断言）；
 *   · 这里只做 Rspress 特有的四件事：
 *       1) 内容根就是 doc/（页面路径与旧站逐字对应，只差扩展名：*.md → *.html）；
 *       2) 首页 index.md（dev 用内网完整版 README，prod 用同一套入口表重建 + 兜底脱敏）；
 *       3) 被引用的图/视频进 doc/public/_assets/（Rspress 构建时把 public/ 原样搬进产物；
 *          dev 不能再靠 docsify 那套 nginx alias —— 站点要能独立起在任意静态服务上）；
 *       4) 两份侧边栏产物：sidebar.generated.mjs（config 用）+ _sidebar.md（门禁 ⑨⑫ 用）。
 *
 * 素材为什么要硬链而不是直接 copyFile：62 段视频 + 74 张 SVG 抄一份是 70+ MiB 的白占。
 * 硬链在同一文件系统内零成本，且源素材的属主/内容不动（**故意不 chmod 硬链**，那会改到源文件）。
 * prod 下若有 SVG 文字层命中真实端口/IP（不是浮点误撞），只在拷进去的**副本**上掩码，源素材仍不动。
 */
if (TARGET === 'rspress') {
  const put = (abs, content) => {
    mkdirSync(dirname(abs), { recursive: true })
    chmodSync(dirname(abs), 0o755)
    const old = existsSync(abs) ? readFileSync(abs, 'utf8') : null
    if (old !== content) { writeFileSync(abs, content); changed.push(abs.replace(`${RSPRESS_SITE}/`, '')) }
    chmodSync(abs, 0o644)
  }
  const linkOrCopy = (srcAbs, dstAbs) => {
    mkdirSync(dirname(dstAbs), { recursive: true })
    chmodSync(dirname(dstAbs), 0o755)
    if (existsSync(dstAbs)) rmSync(dstAbs, { force: true })   // 先断旧链，保证与源一致
    try { linkSync(srcAbs, dstAbs) } catch { copyFileSync(srcAbs, dstAbs); chmodSync(dstAbs, 0o644) }
  }

  // 1) 首页 index.md
  const readmeSrc = readFileSync(join(SITE, 'README.md'), 'utf8')
  put(join(RSPRESS_DOC, 'index.md'), MODE === 'prod' ? prodReadme(readmeSrc).text : readmeSrc)

  // 1b) 旧链接兜底（见 LEGACY_REDIRECT_JS 上方注释）：
  //     · public/legacy-redirect.js 由 rspress.config.ts 的 head 注入**每一页**（接 hash 旧链接）
  //     · doc/404.md 渲染成 404.html（接 .md 裸路径 + 给一句人话提示）
  put(join(RSPRESS_DOC, 'public', 'legacy-redirect.js'), LEGACY_REDIRECT_JS)
  // 404 页**不再**自己写一份 markdown：Rspress 客户端路由对未知路径渲染的是主题 404 布局，
  // 自建 /404 路由的内容会在 hydration 后被替换掉（实测：人话提示一闪而过）。
  // 所以人话提示改为在 rspress.config.ts 里覆盖主题文案 notFoundText（见该文件注释）。

  // 2) 12 篇私人笔记：dev 拷进内容根，prod 一律不拷（并清掉上一版残留）
  const privateKept = []
  const privateRemoved = []
  for (const f of PRIVATE_DOCS) {
    const dst = join(RSPRESS_DOC, f)
    if (MODE === 'prod') { if (existsSync(dst)) { rmSync(dst); privateRemoved.push(f) } continue }
    if (!existsSync(join(SITE, f))) continue
    put(dst, readFileSync(join(SITE, f), 'utf8'))
    privateKept.push(f)
  }

  // 3) 被引用的图 / 视频 → doc/public/_assets/
  const pubAssets = join(RSPRESS_DOC, 'public', '_assets')
  const imgRefs = [...new Set(written.flatMap((w) => w.imageFiles || []))].sort()
  const vidRefs = [...new Set(written.flatMap((w) => w.videoFiles || []))].sort()
  const svgMasked = []
  let svgCount = 0
  for (const rel of imgRefs) {
    const dst = join(pubAssets, 'img', rel)
    if (hasExt(rel, '.svg')) {
      svgCount++
      if (MODE === 'prod') {
        const { text, hits } = maskSvgText(readDoc(rel))
        if (hits.length) { put(dst, text); svgMasked.push({ file: rel, hits }); continue }
      }
    }
    linkOrCopy(join(SRC_DOCS, rel), dst)
  }
  for (const rel of vidRefs) linkOrCopy(join(SRC_VIDEO, rel), join(pubAssets, 'video', rel))
  // favicon 跟着站点根走（Rspress 的 public 就是站点根）
  if (existsSync(join(SITE, 'favicon.ico'))) linkOrCopy(join(SITE, 'favicon.ico'), join(RSPRESS_DOC, 'public', 'favicon.ico'))

  // 3b) 清掉上一版残留的素材（未被任何页面引用的、旧 PNG 副本）
  const keepAssets = new Set([...imgRefs.map((r) => `img/${r}`), ...vidRefs.map((r) => `video/${r}`), 'favicon.ico'])
  const prunedAssets = []
  for (const rel of walk(pubAssets)) {
    const key = rel.split('/')[0] === 'img' || rel.split('/')[0] === 'video' ? rel : rel
    if (keepAssets.has(key)) continue
    rmSync(join(pubAssets, rel), { force: true })
    prunedAssets.push(rel)
  }
  // 素材目录里的空目录（分组改名/素材下线后留下的空壳）
  const assetDirs = []
  {
    const stack = ['']
    while (stack.length) {
      const rel = stack.pop()
      let ents = []
      try { ents = readdirSync(join(pubAssets, rel), { withFileTypes: true }) } catch { continue }
      for (const ent of ents) {
        if (!ent.isDirectory()) continue
        const childRel = rel ? `${rel}/${ent.name}` : ent.name
        assetDirs.push(childRel); stack.push(childRel)
      }
    }
  }
  for (const rel of assetDirs.reverse()) { try { rmdirSync(join(pubAssets, rel)) } catch { /* 非空 → 保留 */ } }

  // 4) 两份侧边栏产物（同一棵树渲染，不可能漂移）
  put(join(RSPRESS_SITE, 'sidebar.generated.mjs'),
    `// 由 /root/docsify/build-material.mjs --target=rspress 生成，勿手改。\n`
    + `// 同序、同名、同编号的 _sidebar.md 也在同一目录（供 check-docs-site.mjs 的 ⑨⑫ 项）。\n`
    + `export default ${JSON.stringify(renderRspressSidebar(NAV), null, 2)}\n`)
  put(join(RSPRESS_SITE, '_sidebar.md'), sidebarText)
  // 同一份字节再放进 public/ → 构建后落在产物根 /_sidebar.md，
  // 与 docsify 的 dist-prod/_sidebar.md 完全同构：门禁 ⑨⑫ 用默认取源（<root>/_sidebar.md）
  // 就能验到「这个产物自己的那份侧边栏」，不需要靠 --sidebar 指到源码目录（也不会指错模式）。
  put(join(RSPRESS_DOC, 'public', '_sidebar.md'), sidebarText)
  // 顺序清单同样放进 public/ → 构建后落在产物根 /material/_nav-manifest.json（与 docsify 侧同路径）
  put(join(RSPRESS_DOC, 'public', 'material', '_nav-manifest.json'), manifestText)

  // 5) prod guard：产物内容里不许残留口语稿的任何字节。
  //    这不是重复劳动 —— 它拦的是「有人跳过 npm run build、直接 rspress build 复用 dev 内容」这条路，
  //    也是唯一能让 ⑩（按产物字节判）在 prod 侧 fail-closed 的保险。
  let spokenLeft = []
  if (MODE === 'prod') {
    spokenLeft = walk(RSPRESS_DOC)
      .filter((p) => p.endsWith('.md'))
      .filter((p) => /spoken-script|口语稿/.test(readFileSync(join(RSPRESS_DOC, p), 'utf8')))
  }

  console.log(`\nRspress   : ${RSPRESS_DOC}（内容根）+ sidebar.generated.mjs + _sidebar.md`)
  console.log(`首页      : index.md（${MODE === 'prod' ? '线上入口表' : '内网入口表（dev）'}）`)
  console.log(`私人笔记  : ${MODE === 'prod' ? `0 篇（排除 ${PRIVATE_DOCS.length} 篇）${privateRemoved.length ? `，清掉残留 ${privateRemoved.length} 篇` : ''}` : `${privateKept.length} 篇`}`)
  console.log(`素材      : 图 ${imgRefs.length} 个（SVG ${svgCount}）/ 视频 ${vidRefs.length} 段 → doc/public/_assets/（硬链，不占额外空间）`)
  if (prunedAssets.length) console.log(`清理素材  : ${prunedAssets.length} 个上一版残留文件`)
  if (svgMasked.length) console.log(`SVG 掩码  : ${svgMasked.length} 个（文字层真实端口/IP，只改副本）→ ${svgMasked.map((s) => s.file).join(', ')}`)
  console.log(MODE === 'prod'
    ? (spokenLeft.length ? `❌ 口语稿残留 ${spokenLeft.length} 个页面：${spokenLeft.slice(0, 5).join(', ')}` : '口语稿 guard: 0 字节残留 ✅')
    : '口语稿 guard: dev 模式不适用（口语稿是内网完整版的一部分）')
  if (spokenLeft.length) process.exitCode = 4
}

/* ------------------------------------------------------------------ 报告 */

const diagrams = byKind('diagram')
const scenes = byKind('scene')
const maxBody = Math.max(0, ...written.map((w) => w.bodyChars))

console.log(`图页      : ${diagrams.length}  (${DIAGRAM_GROUPS.map((g) => byKind('diagram').filter((w) => w.group === g.label).length).join(' / ')})`)
console.log(`场景页    : ${scenes.length}  / 清单 ${SCENES.length}`)
// 口语稿：一份一行，dev 嵌入数 / prod 恒不嵌入（prod 不读文件，产物里不会有它的任何字节）
console.log(`ℹ 正文块：${thesisEmbedded.length} 页有 正文.md（已渲染）/ ${SCENES.length - thesisEmbedded.length} 页无（跳过，页面与旧版逐字节一致）`
        + (thesisEmbedded.length ? `\n    有正文：${thesisEmbedded.join('、')}` : ''))
for (const w of thesisWarnings) console.log(w)
console.log(`ℹ 口语稿：dev 嵌入 ${spokenEmbedded.length} 份 / prod 不嵌入`)
console.log(`ℹ 实测数字：${measuredEmbedded.length} 个场景带「图 + SQL + 数字」证据块${measuredEmbedded.length ? '：' + measuredEmbedded.join('、') : '（下一轮补台账七页）'}`)
console.log(`正文上限  : 实测最大 ${maxBody} 字（上限 ${BODY_LIMIT}）`)
if (permFixed) console.log(`权限补读  : ${permFixed} 个素材条目（仅加 o+rX，属主/内容不变）`)
console.log(`本次写入  : ${changed.length} 个文件${changed.length ? '（' + changed.join(', ') + '）' : '（无变化，幂等）'}`)
if (stale.length) console.log(`清理过期  : ${stale.join(', ')}`)
// 缺视频汇总：门禁放宽（有图即出页）后，必须能一眼看出「哪些页面是待补状态」，
// 而且「待补」与「无界面操作（视频永不适用）」分开计数 —— 后者永远不会有视频来补，
// 混进待补清单就是永久假账。
const pendingVideo = sceneStats.filter((s) => s.status === '已生成·视频待补')
const noUiScenes = sceneStats.filter((s) => s.status === '已生成·无界面')
console.log('')
console.log(pendingVideo.length
  ? `⚠ ${pendingVideo.length} 个页面缺视频（已出页，正文标注待补）：${pendingVideo.map((s) => s.pagePath).join('、')}`
  : '⚠ 0 个页面缺视频（已出页，正文标注待补）：无')
console.log(noUiScenes.length
  ? `ℹ ${noUiScenes.length} 个页面为「无界面操作」场景（视频不适用，正文标注验收方式，不计入上面的待补计数）：${noUiScenes.map((s) => s.pagePath).join('、')}`
  : 'ℹ 0 个页面为「无界面操作」场景')

const SCENE_ICON = { '已生成': '✅', '已生成·无界面': '🔍', '已生成·视频待补': '⏳' }
console.log('\n场景页明细（视频段数 / 图片张数）：')
for (const s of sceneStats) {
  const icon = SCENE_ICON[s.status] ?? '⏸'
  console.log(`  ${icon} ${s.scene.padEnd(22)} 视频 ${s.videos} / 图 ${s.images}  ${s.status}`)
}
writeFileSync('/tmp/build-material.report.json', JSON.stringify({
  generatedAt: new Date().toISOString(), pages: written, scenes: sceneStats, stale,
  pendingVideo: pendingVideo.map((s) => s.pagePath),
  noUiScenes: noUiScenes.map((s) => s.pagePath),
}, null, 2))
