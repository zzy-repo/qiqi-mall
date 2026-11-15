#!/usr/bin/env node
/**
 * 前端样式纪律检查 —— scoped `<style>` 里只允许描述"位置"，不允许描述"外观"
 * ============================================================================
 *
 * 为什么要有这个脚本
 * ------------------
 * 自定义样式只允许描述"位置"（display/flex/grid/gap/margin/padding/width/height），
 * 不允许描述"外观"（颜色 / 背景 / 边框 / 圆角 / 阴影 / 字体族 / 字号 / 字重 / 字距 / white-space）。
 *
 * 外观只有四个合法来源：
 *   ① Element Plus 组件的 props（type / effect / size / tag / body-style …）
 *   ② Element Plus 公开的 CSS 变量（--el-*），且**只在组件默认值在该处确实坏掉时才覆盖**
 *   ③ 设计令牌（admin-web/src/assets/styles/main.css 里的 --qiqi-*）
 *   ④ 页面骨架（布局外壳）的最小自有样式 —— 见下面 B 组
 *
 * 判据（本轮重写过，这里是唯一标准）
 * ----------------------------------
 *   删掉这条声明之后，会不会丢**信息**或丢**可读性**？
 *     会   → 允许保留，理由必须写清"删了会丢什么信息 / 哪一处读不清"
 *     不会 → 接受组件默认样子，把自定义删掉
 *
 *   被废弃的旧判据：「删掉之后像素会不会不同？不同就保留」。
 *   那等于说"任何视觉差异都值得永久维护一段自定义 CSS"：600 还是 700、虚线还是实线、
 *   浅蓝底还是 el-tag 的语义色 —— 不影响业务、不影响阅读、不影响任何人做事。
 *   按旧判据这个白名单曾有 41 个 key / 103 条声明；按新判据只剩 7 个 key / 10 条。
 *
 * 三段式输出（三个数字必须分开看，混在一起就会把"分类错了"读成"组件做不到"）
 * ----------------------------------------------------------------------------
 *   ① 组件外观声明（未豁免）  目标 0。scoped <style> 里还剩多少条纯外观声明。
 *   ② 组件能力缺（A 组）      目标 0~1。EP 的 props / slots / CSS 变量 / 令牌确实表达不了，
 *        且删了会丢信息或丢可读性 —— **只有这一组的数字才叫"组件做不到"**。当前 0 条。
 *   ③ 页面骨架（S 组）        侧栏/顶栏/内容区/登录入口面的底色与分界线，10 条。
 *        这是骨架自己的事（el-container 是栅格，不是"带底色的容器"；任何 EP 后台模板都这么写），
 *        不属"组件能力缺"，单独统计、单独打印。
 *
 * 与"视觉统一"的关系（本轮的第二条铁律）
 * --------------------------------------
 *   `--el-*` 覆盖本身就是一种"让同一个组件在不同页面长得不一样"的手段。
 *   凡是"把字号/字色/圆角/色槽钉回某个历史值"的覆盖，一律回退到组件默认 ——
 *   判据是：删掉这个覆盖之后，组件回到它在本项目其它地方的样子了吗？是 → 删。
 *   现在全站 el-text 只有 3 种字号（14 默认 / 12 size=small / 16 size=large），
 *   el-card 只有 1 种圆角（4px），el-tag 只有 EP 那一套色阶。
 *
 * 两档门禁（关键区别）
 * --------------------
 *  `:deep()` > 0  → **立刻 fail**。撬组件内部 DOM 才是真问题：组件的内部类名不是 API，
 *                  升级就碎。这一条是 0，没有任何例外。
 *  外观声明 > 0   → **warn**（不阻塞）。目标不是归零，而是"每一条都答得出删了会丢什么"。
 *
 * 用法
 * ----
 *   node scripts/check-style-discipline.mjs            # 门禁档（:deep 命中即 exit 1）
 *   node scripts/check-style-discipline.mjs --brief    # 只打一行摘要（publish.sh 的 preflight 用）
 *   node scripts/check-style-discipline.mjs --strict   # 外观声明也当错误
 *   node scripts/check-style-discipline.mjs --verbose  # 逐条列出命中
 */

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

const ROOT = new URL('..', import.meta.url).pathname
const APPS = ['admin-web', 'portal-web']

/* ---------------------------------------------------------------------------
 * 1) 外观属性清单
 * ------------------------------------------------------------------------- */
const APPEARANCE = [
  // 颜色
  /^color$/,
  /^background(-color|-image|-size|-position|-repeat)?$/,
  // 边框（含圆角，圆角也是外观）—— 也覆盖 border-left-width / border-inline-start-color 这类长写法
  /^border(-(top|right|bottom|left|inline|block)(-(start|end))?(-(width|style|color))?)?$/,
  /^border-(width|style|color|radius)$/,
  // 阴影 / 轮廓
  /^box-shadow$/,
  /^text-shadow$/,
  /^outline(-color|-offset)?$/,
  // 字体
  /^font-(family|size|weight|style)$/,
  /^text-decoration$/,
  /^letter-spacing$/,
  /^white-space$/
]

/* ---------------------------------------------------------------------------
 * 2) 豁免清单：两类，分开统计、分开打印 —— 永远不许混成一个"组件做不到"的数字
 *
 *   A 组 COMPONENT_GAPS「组件能力真缺」
 *       EP 的 props / slots / CSS 变量 / 令牌确实表达不了，**且删了会丢信息或丢可读性**。
 *       只有这一组的数字才叫"组件能力缺"。
 *   S 组 PAGE_SHELL「页面骨架」
 *       侧栏 / 顶栏 / 内容区 / 登录入口面的底色与分界线。这是页面骨架自己的事
 *       （任何 Element Plus 后台模板都这么写），**不是**某组件缺一个 prop。
 *
 * 判据（唯一标准）：删掉这条声明之后，会不会丢**信息**或丢**可读性**？
 *   会   → 留在 COMPONENT_GAPS，理由写清"删了会丢什么信息 / 哪一处读不清"
 *   不会 → 接受组件默认样子，把自定义删掉
 *   被废弃的旧判据：「删掉之后像素会不会不同？不同就保留」—— 那等于说"任何视觉差异都值得
 *   永久维护一段自定义 CSS"，按它这个白名单曾长到 41 个 key / 103 条声明。
 *
 * 本轮把 4 类判错的从 A 组清掉（A 组 5 条 → 0 条），错在同一个地方：**没查组件自己给不给**。
 *   ① 等宽字体 3 处（.key-prefix / .reveal-value / .cond-seq）—— 判错
 *      el-text 有 `tag` prop（text.mjs，type String，default "span"）⇒ `el-text tag="code"`
 *      渲染成 `<code class="el-text">`，等宽来自浏览器 UA 样式表对 `<code>` 的 font-family: monospace。
 *      而 el-text 自己的 CSS 只有 font-size / color（el-text.css 全文无 font-family），
 *      不会把 UA 的等宽覆盖掉 ⇒ 本项目一行 font-family 都不用写。
 *      原来那句"EP 没有等宽语义"只对"没有 monospace 令牌"成立，对"用 <code> 渲染"不成立。
 *   ② white-space: nowrap 1 处（.why-mark「← 此项未满足」）—— 判错
 *      el-tag 的 CSS 本身就是 `display:inline-flex` + `white-space:nowrap`（el-tag.css），
 *      换成 el-tag 之后"不折行"与"带边框/底色"都是组件自带，自定义规则可整条删。
 *      原来那句"EP 没有'不折行但也不省略'的语义"漏了 el-tag 这个候选（只比了 el-text.truncated）。
 *   ③ white-space: pre-wrap 1 处（.willdo「将要发生什么」）—— 不是"EP 做不到"，是守了个不存在的东西
 *      后端 14 处 willDo 文案 + 前端 2 处本地构造的预演文案，全部是单行中文句子
 *      （全仓 grep willDo 附近 0 个换行符）⇒ pre-wrap 在守一个从来不出现的换行。
 *      改 `el-text tag="p"`（自带 margin:0 + overflow-wrap:break-word）即可，规则整条删。
 *      若哪天真要按行分点 / 按仓分条：后端把 willDo 改成数组（见下面 WILLDO_TODO），
 *      这不是"前端该用哪条 CSS"能解决的问题。
 *   ④ 布局外壳 10 条 —— 不是"组件做不到"，是**分类错了**，已整体移进 PAGE_SHELL（S 组）。
 * ------------------------------------------------------------------------- */
const COMPONENT_GAPS = [
  /* 当前 0 条。
   * 上一轮这里的 5 条（3 处等宽 + nowrap + pre-wrap）本轮全部证伪并改掉，
   * 判错的经过与反证见上面的 ① ② ③。
   *
   * 想往这里加一条之前，先回答三个问题（本轮 4 条都是栽在前两个上）：
   *   ① 真的把组件文档 / props 类型 / 该组件的 CSS 都读过了吗？（el-text 的 tag prop 就躺在 props 里）
   *   ② 有没有**另一个**组件天然带这个能力？（nowrap ← el-tag；等宽 ← tag="code"）
   *   ③ 这条声明守的那件事，在后端数据里真的会发生吗？（pre-wrap 守的换行从来没出现过）
   */
]

/* ---- S 组｜页面骨架（PAGE_SHELL）：不参与"组件能力缺"的统计 ----
 * 共同理由：这些是页面骨架的底色与分界线。el-container / el-aside / el-header / el-main
 * 是**栅格**，不是"带底色的容器"：container.scss 全文没有 background，el-header 只有 height，
 * 文档 Attributes 也只有 direction / width / height。给骨架一层底色不是"组件缺 prop"，
 * 而是"这层骨架本来就该由页面自己画"—— vue-element-admin 这类模板全这么写。
 */
const PAGE_SHELL = [
  {
    file: 'admin-web/src/layout/index.vue',
    sel: '.layout-aside',
    why: '后台侧栏的深色底 #1f2d3d（= 令牌 --qiqi-color-ink，EP 六档灰里没有这个深度）。'
       + '这是"这是后台管理端"的第一层识别，删了侧栏变白，与内容区、卡片糊成一片，'
       + '导航区与工作区不再有边界。el-aside 只有 :width 一个属性（无 background/变量）。'
  },
  {
    file: 'admin-web/src/layout/index.vue',
    sel: '.logo',
    why: '含两条：① logo 条的深色底 #16222e（比侧栏再深一档，是骨架的第二层）；'
       + '② white-space: nowrap —— 侧栏折到 64px 时 logo 文字必须留在一行被 overflow 裁掉，'
       + '删了它会折成三行、撑破 56px 的条（布局约束，不是排版偏好）。'
       + 'el-aside / el-header 无 background 属性或变量。'
  },
  {
    file: 'admin-web/src/layout/index.vue',
    sel: '.layout-header',
    why: '顶栏的白底 + 1px 下分界线。这条线是"内容区从哪开始"的边界：'
       + '删了之后白顶栏直接连上灰内容区，工作区起点看不清，且顶栏上的操作区（用户/退出）'
       + '看起来像浮在内容里。el-header 只有 height，没有底色/边框出口。'
  },
  {
    file: 'admin-web/src/layout/index.vue',
    sel: '.layout-main',
    why: '内容区灰底 #f5f7fa。白卡片靠它衬出边界：删了之后卡片与背景同为白色，'
       + '"哪里有卡、卡到哪儿结束"看不出来（整页一片白）。el-main 无 background 属性/变量'
       + '（container.scss 全文无 background）。'
  },
  {
    file: 'admin-web/src/views/login/index.vue',
    sel: '.login-page',
    why: '后台登录页的深色渐变入口面。这是全站唯一的深色页面：运营靠它一眼分清'
       + '"我在后台还是在前台"（两个端登录页的表单结构几乎一样）。删了会退化成白底卡片，'
       + '与前台登录页难以区分 —— 这是系统识别问题，不是"渐变更好看"。'
       + 'EP 没有任何渐变语义（无组件属性、无 --el-gradient-*，--el-bg-color-page 是纯色）。'
  },
  {
    file: 'portal-web/src/layout/index.vue',
    sel: '.portal',
    why: '前台页面灰底 #f5f7fa。与 .layout-main 同理：白商品卡片需要底色衬出边界。'
       + 'el-container 无 background 属性/变量。'
  },
  {
    file: 'portal-web/src/layout/index.vue',
    sel: '.topbar',
    why: '前台顶栏白底 + 1px 下分界线。与 .layout-header 同理：这条线是导航区与商品区的分界，'
       + '删了两个白色区块连成一片，滚动时看不出顶栏到哪儿结束。el-header 只有 height。'
  }
]

/* ---------------------------------------------------------------------------
 * 2b) 被保留的 `--el-*` 覆盖（不属"外观声明"，故不参与上面的计数，但同样要答"删了会丢什么"）
 *
 * 为什么单独列：`--el-text-color: X` 这类**自定义属性赋值**不在 APPEARANCE 清单里（清单看的是
 * `color:` / `font-size:` 这种真声明），所以它们不会出现在"未豁免"里 —— 但它们是同一件事：
 * 让组件在本页长得跟别处不一样。留着就必须有理由，否则下次又会悄悄长回来。
 *
 * 判据同上一节：删了会不会丢信息 / 可读性。目前 7 条，全部是"组件缺这一档语义/语义色"。
 * ------------------------------------------------------------------------- */
const RETAINED_OVERRIDES = [
  { file: 'admin-web/src/layout/index.vue', decl: '--el-text-color: var(--el-color-white)（.logo-text）',
    why: '侧栏是深色底，logo 文字必须白。el-text 的 5 个 type（primary/success/info/warning/danger）'
       + '没有"白"这一档；改全局 --el-text-color 代价更大（会波及全站所有 el-text）。' },
  { file: 'admin-web/src/layout/index.vue', decl: '--el-menu-border-color: transparent（.layout-menu）',
    why: 'el-menu 默认在右侧画 1px --el-border-color-light。深色侧栏里这条浅色竖线是个纯噪点，'
       + '而 el-menu 没有 border 相关属性 —— 只能把它的公开变量置空。' },
  { file: 'admin-web/src/views/inventory-reconcile/index.vue',
    decl: '--el-card-bg-color / -border-color（.stat-ok / .stat-bad）',
    why: '语义色：这张卡是"对上了"还是"对不上"。el-card 没有 status/type 变体'
       + '（Card 文档 Attributes 只有 header/footer/body-style/*-class/shadow），染色只能走这两个公开变量。' },
  { file: 'admin-web/src/views/inventory-reconcile/index.vue',
    decl: '--el-statistic-content-color（偏差行，inline）',
    why: '同上是语义色：偏差 0 = success，非 0 = danger。el-statistic 没有 type prop。' },
  { file: 'portal-web/src/views/product/detail.vue',
    decl: '--el-statistic-content-color: var(--el-color-danger)（.price）',
    why: '电商价格必须是红色（用户靠颜色在页面上第一眼找到它）。el-statistic 无 type prop，'
       + '只能走这个公开变量。价格本身用 20px 标准档，与全站指标数字同一个样子。' },
  { file: 'portal-web/src/views/product/detail.vue',
    decl: '--el-card-border-color: var(--el-color-primary)（.gallery-item.active）',
    why: '相册条里"当前主图是哪张"的唯一指示器：删了就看不出主图取的是哪一张（信息丢失）。'
       + '用 el-card 的公开变量改边框色，而不是自己写 border-color。' },
  { file: 'admin-web/src/views/dashboard/index.vue',
    decl: '--el-avatar-bg-color / -text-color（.metric-icon，inline）',
    why: '六个指标卡各一个语义色底片（金额红/订单蓝/商品绿/待审黄/待发紫/退货青），'
       + 'el-avatar 没有颜色属性 —— 这两个变量就是它的官方口子。' }
]

/* ---------------------------------------------------------------------------
 * 2c) 待办：willDo 该是结构化数据，而不是一整串带换行的中文
 *
 * 这条**不是**样式问题，所以不进任何豁免清单 —— 记在这里是为了别再有人为了它往
 * scoped <style> 里加一条 white-space。当前 willDo 全是单行句子，pre-wrap 已删（见 ① ② ③）。
 * 一旦产品要求「将要发生什么」按行/按仓分点，正确改法在后端：
 * ------------------------------------------------------------------------- */
const WILLDO_TODO = [
  {
    file: 'qiqi-mall-server（PreviewResult.of / *WillDo 构造处，共 14 个 dryRun 站点）',
    todo: '把 willDo 从 "拼接的长字符串" 改成结构化数组，例如 '
        + '{ summary: "将把订单 SN… 审核通过：待审核→待发货", '
        + 'steps: [{text: "…", warehouseId, warehouseName, lineCount}], notes: ["该规格未配置分仓库存…"] }。'
        + '前端用 el-text/el-descriptions/<ul><li> 逐条渲染 —— 换行由结构产生，不靠 white-space。'
        + '理由：一是真要分点就得靠 \\n 拼字符串（前端只能 pre-wrap 硬显示，抄走/复制/无障碍全是乱的）；'
        + '二是拆单方案本来就已经有结构化数据（split-preview 的 plan[]），文案却在手拼一遍，两处必然会漂。'
  }
]

/* ---------------------------------------------------------------------------
 * 3) 扫描
 * ------------------------------------------------------------------------- */
const walk = (dir, out = []) => {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'dist' || name.startsWith('.')) continue
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (name.endsWith('.vue')) out.push(p)
  }
  return out
}

/** 去掉注释（/* *​/ 与 //），避免把说明文字当成声明 */
const stripComments = (css) =>
  css.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

/**
 * 把 <style> 块拆成规则：{ selector, line, decls: [{prop, value}] }
 * 只做够用的解析：按 `}` 切，再按 `{` 切选择器与声明体。
 */
const parseRules = (css, startLine) => {
  const rules = []
  const re = /([^{}]+)\{([^{}]*)\}/g
  let m
  while ((m = re.exec(css))) {
    const selector = m[1].trim()
    const body = m[2]
    const line = startLine + css.slice(0, m.index).split('\n').length - 1
    const decls = []
    for (const raw of body.split(';')) {
      const i = raw.indexOf(':')
      if (i < 0) continue
      decls.push({ prop: raw.slice(0, i).trim().toLowerCase(), value: raw.slice(i + 1).trim() })
    }
    if (decls.length) rules.push({ selector, line, decls })
  }
  return rules
}

/** 扫描期只用这一张合并表；统计与打印时 A / S 两组严格分开 */
const EXEMPT = [
  ...COMPONENT_GAPS.map((e) => ({ ...e, group: 'A' })),
  ...PAGE_SHELL.map((e) => ({ ...e, group: 'S' }))
]

const problems = []
const deepHits = []
const whitelisted = []

for (const app of APPS) {
  const srcDir = join(ROOT, app, 'src')
  let files = []
  try {
    files = walk(srcDir)
  } catch {
    continue
  }
  for (const file of files) {
    const rel = relative(ROOT, file).split(sep).join('/')
    const text = readFileSync(file, 'utf8')
    const entries = EXEMPT.filter((e) => e.file === rel)

    const styleRe = /<style[^>]*>([\s\S]*?)<\/style>/g
    let sm
    while ((sm = styleRe.exec(text))) {
      const body = stripComments(sm[1])
      // :deep() 只在 <style> 里有意义；在注释里提到它（比如解释为什么不用）不算命中
      for (const dm of body.matchAll(/:deep\(([^)]*)\)/g)) deepHits.push({ file: rel, sel: dm[1] })
      const startLine = text.slice(0, sm.index).split('\n').length
      for (const rule of parseRules(body, startLine)) {
        // 边界匹配，不用 includes：否则 key `.logo` 会顺带豁免 `.logo-text`、
        // key `.total` 会顺带豁免 `.total b`（这两处都真发生过）。`(?![\w-])` 保证
        // `.chip` 不会命中 `.chip-group`，但 `.trail-line` 仍能命中 `.trail-line .label`。
        const matches = (k) => {
          const esc = k.replace(/[.$]/g, (c) => '\\' + c)
          return new RegExp(esc + '(?![\\w-])').test(rule.selector)
        }
        const hit = entries.find((e) => matches(e.sel))
        const appearance = rule.decls.filter((d) => APPEARANCE.some((re) => re.test(d.prop)))
        if (hit) {
          // 豁免按"声明"计数，不按"规则"计数：一条规则可能含 3 条外观声明
          for (const d of appearance) {
            whitelisted.push({ file: rel, selector: rule.selector, prop: d.prop, line: rule.line, group: hit.group })
          }
          continue
        }
        for (const d of appearance) {
          problems.push({ file: rel, selector: rule.selector, prop: d.prop, value: d.value, line: rule.line })
        }
      }
    }
  }
}

/* ---------------------------------------------------------------------------
 * 4) 报告
 * ------------------------------------------------------------------------- */
const STRICT = process.argv.includes('--strict')
const VERBOSE = process.argv.includes('--verbose')

const byFile = new Map()
for (const p of problems) {
  if (!byFile.has(p.file)) byFile.set(p.file, [])
  byFile.get(p.file).push(p)
}
const ranked = [...byFile.entries()].sort((a, b) => b[1].length - a[1].length)

const DEEP_FAIL = deepHits.length > 0

/* 三段式统计（不许合并）：
 *   ① 组件外观声明   = 未豁免的外观声明（problems）—— 目标 0
 *   ② 组件能力缺     = A 组声明数 —— 目标 0~1（真·EP 表达不了）
 *   ③ 页面骨架       = S 组声明数 —— 骨架自己的底色/分界线，本来就不算组件的事
 */
const gapsA = whitelisted.filter((w) => w.group === 'A')
const shellS = whitelisted.filter((w) => w.group === 'S')
const keysA = new Set(gapsA.map((w) => w.file + w.selector)).size
const keysS = new Set(shellS.map((w) => w.file + w.selector)).size

// preflight 用的单行摘要：不刷屏，只留一行可 grep 的事实
if (process.argv.includes('--brief')) {
  const deepNote = DEEP_FAIL ? `:deep ${deepHits.length} 处 ❌` : ':deep 0 处 ✓'
  // 三段式：① 组件外观的未豁免残留 ② 组件能力真缺 ③ 页面骨架。
  // 只报 ① 会把"藏进豁免表"误读成"改掉了"；把 ③ 混进 ② 会把"骨架底色"误读成"组件做不到"。
  console.log(
    `样式纪律：组件外观声明 ${problems.length} 处（未豁免，warn，不阻塞）`
    + ` + 组件能力缺 ${gapsA.length} 条（${keysA} 个选择器）`
    + ` + 页面骨架 ${shellS.length} 条（${keysS} 个选择器，不算组件的事），`
    + `${deepNote}`
  )
  if (DEEP_FAIL) {
    for (const h of deepHits) console.error(`  ✗ ${h.file}  :deep(${h.sel})`)
    console.error('  :deep() 撬的是组件内部 DOM（不是公开 API），升级就碎 —— 这条是硬门禁。')
    process.exit(1)
  }
  process.exit(0)
}

console.log('\n样式纪律检查（scoped <style> 里只该有"位置"，不该有"外观"）')
console.log('─'.repeat(72))
console.log(`扫描：${APPS.join(' + ')} 的 .vue 文件`)
console.log(`① 组件外观声明（未豁免，目标 0）：${problems.length} 处，分布在 ${ranked.length} 个文件`)
console.log(`② 组件能力缺 A 组（EP 表达不了且删了会丢信息，目标 0~1）：${gapsA.length} 条 / ${keysA} 个选择器`)
console.log(`③ 页面骨架 S 组（侧栏/顶栏/内容区/登录入口面的底色与分界线，不算组件的事）：${shellS.length} 条 / ${keysS} 个选择器`)
console.log(`   ②+③ 合计豁免 ${whitelisted.length} 条；保留的 --el-* 覆盖：${RETAINED_OVERRIDES.length} 处（不属外观声明，逐条写明了"删了会丢什么"）`)
console.log(`   待办 ${WILLDO_TODO.length} 条（非样式问题，不参与上面的计数）：willDo 改结构化数组`)
console.log(`:deep() 覆盖：${deepHits.length} 处（撬组件内部 DOM，应为 0）`)
console.log('─'.repeat(72))

if (ranked.length) {
  console.log('\n命中数 top 10：')
  for (const [file, list] of ranked.slice(0, 10)) {
    const sels = [...new Set(list.map((l) => l.selector))]
    console.log(`  ${String(list.length).padStart(3)}  ${file}`)
    console.log(`       选择器：${sels.slice(0, 6).join(', ')}${sels.length > 6 ? ` …(+${sels.length - 6})` : ''}`)
  }
  const rest = ranked.slice(10)
  if (rest.length) {
    console.log(`  …另有 ${rest.length} 个文件，合计 ${rest.reduce((n, [, l]) => n + l.length, 0)} 处`)
  }
}

if (VERBOSE) {
  console.log('\n逐条（未豁免）：')
  for (const [file, list] of ranked) {
    console.log(`\n  ${file}`)
    for (const p of list) console.log(`    L${p.line}  ${p.selector}  { ${p.prop}: ${p.value} }`)
  }
  console.log('\n逐条（② 组件能力缺 A 组）：')
  if (!gapsA.length) console.log('  （无 —— 这一组现在是空的）')
  for (const w of gapsA) console.log(`  [A] ${w.file}  L${w.line}  ${w.selector}  { ${w.prop} }`)
  console.log('\n逐条（③ 页面骨架 S 组）：')
  for (const w of shellS) console.log(`  [S] ${w.file}  L${w.line}  ${w.selector}  { ${w.prop} }`)
  console.log('\n待办（非样式问题）：')
  for (const t of WILLDO_TODO) console.log(`  · ${t.file}\n    ${t.todo}`)
}

if (deepHits.length) {
  console.log('\n:deep() 明细（这是最差的一档：用了组件又把组件拆了）：')
  for (const h of deepHits) console.log(`  ${h.file}  :deep(${h.sel})`)
}

console.log('')
// 硬门禁：:deep() 一处都不许有（撬组件内部 DOM = 升级即碎）
if (DEEP_FAIL) {
  console.log('❌ 硬门禁未过：存在 :deep() 覆盖，退出码 1。')
  process.exit(1)
}

if (!problems.length) {
  console.log('✅ 通过：没有未豁免的外观声明，也没有 :deep() 覆盖。')
  process.exit(0)
}

if (STRICT) {
  console.log('❌ --strict：外观声明也有命中，退出码 1。')
  process.exit(1)
}
console.log('⚠️  外观声明是 warn（不阻塞）：要么换成 props / --el-* 变量 / --qiqi-* 令牌，')
console.log('    要么把"删了会丢什么信息/可读性"写进本脚本的 WHITELIST。')
console.log('    （:deep() 一档是 fail，且当前已是 0 —— 底线守住了。）')
process.exit(0)
