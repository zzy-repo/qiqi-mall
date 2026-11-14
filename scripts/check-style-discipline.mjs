#!/usr/bin/env node
/**
 * 前端样式纪律检查 —— scoped `<style>` 里只允许描述"位置"，不允许描述"外观"
 * ============================================================================
 *
 * 为什么要有这个脚本
 * ------------------
 * 这次全量审计的结论可以浓缩成一句话：
 *
 *     自定义样式只允许描述"位置"（display/flex/grid/gap/margin/padding/width），
 *     不允许描述"外观"（颜色 / 背景 / 边框 / 圆角 / 阴影 / 字体族 / 字号 / 字重）。
 *
 * 外观只有四个合法来源：
 *   ① Element Plus 组件的 props（type / effect / size / body-style / row-style …）
 *   ② Element Plus 公开的 CSS 变量（--el-color-*、--el-tag-* …，官方主题机制，不是内部类名）
 *   ③ 设计令牌（admin-web/src/assets/styles/main.css 里的 --qiqi-*）
 *   ④ 组件确实没有该语义时的"最小自有样式"——必须在下面的白名单里写明理由
 *
 * 判据能机器检查，才不会下次又长回来：`views/order-audit` 那排筛选胶囊当初就是
 * 23 行 CSS 重新发明了一个 EP 已有的组件；而 `views/api-key` 的 50 行自定义样式
 * 全是布局胶水、零外观 —— 这个脚本就是用来区分这两种的。
 *
 * 两档门禁（关键区别）
 * --------------------
 *  `:deep()` > 0  → **立刻 fail**。撬组件内部 DOM 才是真问题：组件的内部类名不是 API，
 *                  升级就碎，而且"用了组件又把组件拆了"比直接写一小段自有样式更难维护。
 *                  这一条现在就是 0，所以可以立刻收紧，没有过渡期。
 *  外观声明 > 0   → **warn**（不阻塞）。自有样式里出现 color/font-size 是正常的：
 *                  EP 只有 12/13/14/16/18 五档字号，11px 附注、22/26px 大数字、等宽字体、
 *                  斜体+左竖线的引注块…都没有对应语义，该留就得留（判据见白名单理由）。
 *                  这一档的目标是"趋势向下"，不是"归零"。
 *
 * 用法
 * ----
 *   node scripts/check-style-discipline.mjs            # 门禁档（:deep 命中即 exit 1）
 *   node scripts/check-style-discipline.mjs --brief    # 只打一行摘要（publish.sh 的 preflight 用）
 *   node scripts/check-style-discipline.mjs --strict   # 外观声明也当错误（消化到 <30 处后再开）
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
  // 边框（含圆角，圆角也是外观）
  /^border(-(top|right|bottom|left|width|style|color|radius))?$/,
  /^border-(top|bottom)-(left|right)-(radius|color|width)$/,
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

/**
 * 2) 白名单：文件 → { 选择器片段: 理由 }
 *
 * 只允许"EP 确实没有该语义"的条目进来，并且必须回答：
 *   - 这条规则描述的是什么？
 *   - 为什么 EP 的 props / CSS 变量 / 令牌都表达不了？
 * 凡是写"暂时保留""先这样"的一律不接受。
 */
/**
 * 2b) 故意**不**全局化的类：它们在不同页面语义不同，抽成全局类会破坏"零视觉变化"。
 *
 * 每个文件各写一遍太啰嗦（.tip 出现在 8 个文件、.thumb 5 个），所以在这里统一给一次理由；
 * 匹配到这些选择器就跳过外观检查。**新增条目必须回答"为什么不能全局化"**。
 */
const NOT_GLOBALIZABLE = {
  '.tip': '小字说明有 6 种 body（margin-top:10/12、line-height:1.6/1.7、text-align:center、橙色警告…），'
        + '其中 DangerConfirmDialog 那条只是 `margin-top:12px`、挂在 el-alert 外壳上 —— 抽成全局 `.tip` 会把 '
        + '12px/灰字泄漏进 alert 内部。为消重复而接受可见变化，与本轮"不为整齐而改视觉"的前提相冲。',
  '.thumb': '图片载体有 4 种尺寸语义：44px（表格缩略图）/ 72px（上传预览）/ 110px（首页商品卡封面）/ '
          + '220px（详情页主图，带占位底色）。尺寸本身就是各页的布局决策，抽成全局类必然要靠局部覆盖，'
          + '那等于把"两份定义"换成"一份定义 + 四份覆盖"，更难读。',
  '.badge': 'portal 购物车角标：改 el-badge 需要把「购物车」文本包进默认插槽才能让 sup 定位到右上角，'
          + '会改动 nav 结构并与 e2e 的文本断言耦合；收益 4 行，不值得。'
}

const WHITELIST = {
  'admin-web/src/views/order-audit/index.vue': {
    '.chip': '「按状态着色的筛选胶囊」EP 没有对应组件：el-segmented 只有一个滑动指示器（一档一色做不到），el-check-tag 未选中一律中性灰（五色图例消失）。这是本页唯一的自有外观，且色值全部走 --el-color-* / --qiqi-color-frozen-* 令牌。',
    '.chip--': '同上：五支各给一对 --chip-c / --chip-d 变量，替代原先 10 条两态彩边规则。',
    '.chip-group': '"被拦"是业务分组（阻止/冻结处理人不同），EP 的选项组件没有分组视觉，用虚线框表达"这两支同属被拦"。色值走 warning 令牌。',
    '.chip-group-label': '同上分组框里的小标签。',
    '.reason-main': '表格里"结论句"要比 el-table 默认字色深一档。EP 的 el-text 只有 5 个 type（primary/success/warning/danger/info），没有"更深的正文色"这一档，--el-text-color-primary 只能自己写。',
    '.reason-would-be': '「本可自动过审」与上一行同为 12px 灰字，必须再给一个"这不是结论"的标记（斜体 + 左竖线），否则会被读成实际原因。el-text 没有 italic / border 语义。',
    '.reason-stock-warn': '11px：EP 字号只有 12/13/14/16/18 五档，11px 表达不了（走 --qiqi-font-size-xs）。',
    '.why-would-be': '抽屉里与队列行同款的"本可怎样"标记（同上）。',
    '.why-reason': '灰底 + 3px 左竖线的引注块。el-alert 会加图标和更重的底色，el-card 太重，都不如这几行。',
    '.c-frozen': 'FROZEN 数字用紫：el-text 无紫档（走 --qiqi-color-frozen-dark-2）。',
    '.why-mark': '"← 差在哪"这格的红色加粗 + nowrap。el-text 能上色但没有 nowrap/bold 的组合语义。'
  },
  'admin-web/src/views/audit-policy/index.vue': {
    '.cond-row': '条件行的"行框"（浅底 + 1px 边框 + 圆角），用来把一条条件圈成一组。EP 没有"表格行框"这种原语，el-card 太重。色值走 fill/border 令牌。',
    '.cond-seq': '序号列（0 / 1 / G0），等宽字体 EP 没有 monospace 语义（走 --qiqi-font-mono）。',
    '.priority-note': '灰底 + 左竖线的多行口径说明。el-alert 会喧宾夺主（图标 + 更实底色）。',
    '.cond-chip': '策略列表里的条件小标签（浅蓝/浅红底），EP 没有"柔和底色的条件胶囊"，el-tag 的 light/plain 更接近但仍差一档观感——保留，色值走令牌。',
    '.cond-chip-exclude': '同上（排除条件用浅红）。',
    '.cond-chip-focus': '同上（被"差在哪"指到的那一条要高亮描边）。',
    '.cond-chip-kind': '标签里的"匹配/排除"小字块（11px 底色块）。',
    '.global-card': '全局排除区与上方策略表格的浅色区隔。'
  },
  'admin-web/src/components/DangerConfirmDialog.vue': {
    '.willdo': '15px 正文 + white-space:pre-wrap + user-select:text。EP 字号没有 15px 档，el-text 也不能 pre-wrap。',
    '.details': '灰底 + 3px 橙色左线的高危细节块。'
  },
  'admin-web/src/components/ConflictDialog.vue': {
    '.conflict-message': '15px 加粗主句（EP 无 15px 档）。',
    '.conflict-hint': '13px 说明（EP 无 13px+该色的组合；el-text 只有 12px 或 14px）。'
  },
  'admin-web/src/components/AuditTrail.vue': {
    '.trail-card': '三段证据的左侧色条（决策蓝/台账绿/审计黄），是对 el-timeline-item 节点色的二次强化，timeline 没有这个 API。',
    '.trail-line': '时间线正文 13px（EP 无 13px 档，el-text 只有 12/14）。',
    '.trail-detail': '细节小字 12px + line-height 20。',
    '.mono': '已迁到全局，此条可删。'
  },
  'admin-web/src/views/inventory-reconcile/index.vue': {
    '.stat-card': '三块并排的对比卡片（浅底 + 圆角 + 最小宽），el-card 会带阴影和内边距，观感不同。',
    '.stat-ok': '对平/有偏差的整卡染色，el-card 没有 status 变体。',
    '.stat-bad': '同上。',
    '.stat-value': '26px 大数字（EP 无该字号档）。',
    '.net-block': '台账净额强调块（左粗线 + 浅黄底），刻意与上面三块区分开，避免被当成"第四个应当对平的数"。',
    '.net-label': '净额块里的深黄色文字。',
    '.net-note': '同上。'
  },
  'admin-web/src/views/dashboard/index.vue': {
    '.metric-icon': '6 个语义色的图标底片（金额/订单/商品/待办/发货/售后），数据驱动，EP 没有"彩色图标底片"组件。',
    '.metric-value': '22px 指标数字（EP 无该字号档，走 --qiqi-font-size-num）。',
    '.metric-title': '13px 小标题（EP 无 13px 档）。',
    '.metric-hint': '12px 补充说明。',
    '.panel-sub': '卡片头右侧的 12px 说明。',
    '.alert-name': '预警行名称（13px + 单行省略）。',
    '.alert-spec': '同上。'
  },
  'admin-web/src/views/channel/index.vue': {
    '.shop-card': '渠道卡片（浅底 + 圆角 + 边框），el-card 观感不同。',
    '.muted': '空值占位符用的更浅的灰（--el-text-color-disabled），比全局 .muted 再淡一档。',
    '.action-hint': '不可点提示用的最浅灰。',
    '.reason-text': '失败原因整格标红（走 danger 令牌）。'
  },
  'admin-web/src/views/api-key/index.vue': {
    '.scope-group-count': '权限组标题后面的计数气泡（18px 圆角小块）。el-badge 是覆盖在元素角上的红点，做不了"行内计数"；el-tag 会偏高偏重。',
    '.scope-group-name': '组名 13px（EP 无 13px 档）。',
    '.key-prefix': 'Key 前缀等宽（EP 无 monospace 语义）。',
    '.reveal-value': '一次性明文 Key：等宽 + user-select:all + word-break。'
  },
  'admin-web/src/views/ledger/index.vue': {
    '.ledger-strong': '台账正文比表格默认字色深一档（#303133 不是 el-text 的任何 type）。'
  },
  'admin-web/src/views/login/index.vue': {
    '.login-page': '登录页品牌渐变背景。',
    '.brand': '登录卡片品牌标题（18px + 深色）。'
  },
  'admin-web/src/layout/index.vue': {
    '.logo': '侧栏 logo 区（深色底 + 白字），走 --qiqi-color-ink 令牌。',
    '.layout-aside': '侧栏深色底，走 --qiqi-color-ink 令牌。',
    '.collapse-btn': '折叠按钮的 hover 色。',
    '.user-name': '顶栏用户名。'
  },
  'portal-web/src/views/product/detail.vue': {
    '.thumb': '220px 商品主图位（固定方图 + 占位底色）。',
    '.gallery-item': '48px 相册缩略图 + 选中描边。',
    '.gallery-fallback': '无图占位。',
    '.price': '24px 价格。',
    '.desc-text': '13px 商品描述。'
  },
  'portal-web/src/views/home/index.vue': {
    '.thumb': '110px 商品卡封面位。',
    '.name': '14px 商品名 + 两行保底高度。',
    '.cta': '「查看详情 →」链接色。'
  },
  'portal-web/src/views/cart/index.vue': {
    '.total': '合计行金额强调。'
  },
  'portal-web/src/views/checkout/index.vue': {
    '.total': '合计行金额强调。',
    '.addr-hint': '未选地址的橙色提醒。'
  }
}

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
 * 把 <style> 块拆成规则：{ selector, line, decls: [{prop, line}] }
 * 只做够用的解析：按 `}` 切，再按 `{` 切选择器与声明体。
 */
const parseRules = (css, startLine) => {
  const rules = []
  let cursor = 0
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

const problems = []
const deepHits = []

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
    const whitelist = WHITELIST[rel] || {}


    const styleRe = /<style[^>]*>([\s\S]*?)<\/style>/g
    let sm
    while ((sm = styleRe.exec(text))) {
      const body = stripComments(sm[1])
      // :deep() 只在 <style> 里有意义；在注释里提到它（比如解释为什么不用）不算命中
      for (const dm of body.matchAll(/:deep\(([^)]*)\)/g)) deepHits.push({ file: rel, sel: dm[1] })
      const startLine = text.slice(0, sm.index).split('\n').length
      for (const rule of parseRules(body, startLine)) {
        const key = Object.keys(whitelist).find((k) => rule.selector.includes(k))
        if (key) continue // 已白名单（理由见上方 WHITELIST）
        const shared = Object.keys(NOT_GLOBALIZABLE).find((k) => rule.selector.includes(k))
        if (shared) continue // 故意不全局化（理由见上方 NOT_GLOBALIZABLE）
        for (const d of rule.decls) {
          if (APPEARANCE.some((re) => re.test(d.prop))) {
            problems.push({ file: rel, selector: rule.selector, prop: d.prop, value: d.value, line: rule.line })
          }
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

// preflight 用的单行摘要：不刷屏，只留一行可 grep 的事实
if (process.argv.includes('--brief')) {
  const deepNote = DEEP_FAIL ? `:deep ${deepHits.length} 处 ❌` : ':deep 0 处 ✓'
  console.log(`样式纪律：外观声明 ${problems.length} 处（warn，不阻塞），${deepNote}`)
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
console.log(`外观声明命中：${problems.length} 处，分布在 ${ranked.length} 个文件`)
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
  console.log('\n逐条：')
  for (const [file, list] of ranked) {
    console.log(`\n  ${file}`)
    for (const p of list) console.log(`    L${p.line}  ${p.selector}  { ${p.prop}: ${p.value} }`)
  }
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
  console.log('✅ 通过：没有外观声明，也没有 :deep() 覆盖。')
  process.exit(0)
}

if (STRICT) {
  console.log('❌ --strict：外观声明也有命中，退出码 1。')
  process.exit(1)
}
console.log('⚠️  外观声明是 warn（不阻塞）：要么换成 props / --el-* 变量 / --qiqi-* 令牌，')
console.log('    要么把"EP 确实没有该语义"的理由写进本脚本的 WHITELIST / NOT_GLOBALIZABLE。')
console.log('    （:deep() 一档是 fail，且当前已是 0 —— 底线守住了。）')
process.exit(0)
