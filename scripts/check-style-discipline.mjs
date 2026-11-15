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
 * 2) 白名单：文件 → { 选择器: 理由 }
 *
 * ============================================================================
 * 历史错判（这一节比下面任何一条理由都重要，请先读）
 * ============================================================================
 * 本轮之前，这个白名单里有 39 条理由**是错的**（67 条里 39 条），根因只有一个：
 *
 *      凭印象判断"EP 能不能做"，没有去翻官方文档的 Attributes / Slots 两张表。
 *
 * 具体四处，记在这里防止再犯：
 *   ① 「EP 字号只有 12/13/14/16/18 五档，11px 表达不了」
 *      —— 双错。档位是六档（20/18/16/14/13/12，theme-chalk/src/common/var.scss:198-209），
 *         且 --el-text-font-size / --el-text-color 是 text.scss:7-9 注入的公开变量，
 *         任意字号/字色都能表达（11px、13px、15px、24px 都是这么做的）。
 *   ② 「el-statistic 只能显示数字、改不了样式」
 *      —— 错。--el-statistic-content-font-size/-font-weight/-color 三个变量
 *         （var.scss:743-745）+ value-style 属性（文档 Statistic Attributes）。
 *   ③ 「el-avatar 做不了彩色图标底片」
 *      —— 错。shape="square" + :size + --el-avatar-bg-color/-text-color/-border-radius
 *         （avatar.scss:16-41，默认值 var.scss:1532-1542）。dashboard 的指标底片就是它。
 *   ④ 「el-badge 只能做角上红点」
 *      —— 错。不带默认插槽时 .el-badge__content 是 display:inline-flex 的**行内胶囊**
 *         （badge.scss:18）；只有带默认插槽才加 is-fixed 变绝对定位角标（:28-38）。
 *
 * 由此得到本文件的判据（改任何一条之前请对照）：
 *   · 先查文档 Attributes/Slots；再查 theme-chalk 里该组件的 set-component-css-var 变量表。
 *   · 需要 **700** 字重用 el-text 的 tag="b"（标签语义给的），不算"做不到"；
 *     需要 **600** 才是真做不到（纯文本没有 --el-*-font-weight 之外的入口）；
 *     但数字的 600 可以做 —— el-statistic 有 --el-statistic-content-font-weight。
 *   · 每条理由必须写成"el-X 的 Y prop/slot 只能…，做不到…"，不许写"EP 没有这个语义"。
 *
 * 只允许"EP 确实没有该语义"的条目进来，并且必须回答：
 *   - 这条规则描述的是什么？
 *   - 为什么 EP 的 props / slots / CSS 变量 / 令牌都表达不了？
 * 凡是写"暂时保留""先这样"的一律不接受。
 */
/**
 * 2b) 故意**不**全局化的类：它们在不同页面语义不同，抽成全局类会破坏"零视觉变化"。
 *
 * 匹配到这些选择器就跳过外观检查。**新增条目必须回答"为什么不能全局化"**。
 */
const NOT_GLOBALIZABLE = {
  '.thumb': '图片载体有 4 种尺寸语义：44px（表格缩略图，el-image 44×44）/ 72px（上传预览）'
          + '/ 110px 高（首页商品卡封面，width:100% 的非方形位）/ 220px（详情页主图，方图 + 占位底色）。'
          + 'el-image 无尺寸/圆角/底色属性与 --el-image-* 变量；el-avatar 有 :size + shape="square" '
          + '+ --el-avatar-bg-color/-border-radius，但它是**正方形定宽高**的，110px 那个非方形位套不进去，'
          + '套进去就等于为 5 处写 5 套 avatar 配置（还得把 <img> 换成 src 属性），可读性净损失。',
  '.badge': 'portal 购物车角标：el-badge 带默认插槽时 __content 是绝对定位角标（badge.scss:28-38），'
          + '要把「购物车」文本包进默认插槽才能定位，会改动 nav 结构并与 e2e 的文本断言耦合；'
          + '而且它的文字色写死 color: var(--el-color-white)，没有 --el-badge-text-color，'
          + '当前需要的是「浅灰底 + 深灰字」而不是「红底白字」。收益 4 行，不值得。'
}

const WHITELIST = {
  /* ---------------------------------------------------------------- 容器 / 布局 */
  'portal-web/src/layout/index.vue': {
    '.portal': '页面底色。el-container/el-main 的样式表（theme-chalk/src/container.scss:1-14 及各 part）'
             + '全文没有 background，文档 Container Attributes 也只有 direction ⇒ 没有组件出口，'
             + '只能留一行 background（值就是 --el-fill-color-light 的 #f5f7fa）。',
    '.topbar': '顶栏的白底 + 1px 下边框。同上：el-header 只有 height 属性、无 background/border 相关属性或变量。'
             + '另注 #e6e8eb 并不是 EP 的边框色（[V-BC] 六档无此值），它是 --el-fill-color-darker，'
             + '所以这里也不是"抄错令牌"，是自定色，保留原值。'
  },
  'admin-web/src/layout/index.vue': {
    '.layout-aside': '侧栏深色底 #1f2d3d = --qiqi-color-ink（EP 无此深色档）。el-aside 无 background 属性/变量。',
    '.logo': 'logo 区的深色底 #16222e + 白字 + nowrap。el-aside/el-header 无 background 属性；'
           + '白字可用 --el-text-color:var(--el-color-white)，但底色仍无处安放 ⇒ 整块保留。',
    '.logo-text': '17px 已交给 --el-text-font-size；剩下的 600 字重与 letter-spacing 是 el-text 的两个死角：'
                + '$text map 只有 font-size/color（var.scss:1172-1179），没有字重与字距变量。',
    '.layout-header': '同 portal 的 .topbar：el-header 只有 height 属性，无底色/下边框出口。',
    '.layout-main': '内容区底色 #f5f7fa：el-main 无 background 属性/变量（container.scss 全文无 background）。',
    '.collapse-btn': '折叠按钮的常态/悬停色。el-icon 只有 size 属性，没有 --el-icon-color 之类变量'
                   + '（icon.scss 只声明尺寸），字色只能落在宿主元素上。'
  },

  /* ---------------------------------------------------------------- 订单自动审核 */
  'admin-web/src/views/order-audit/index.vue': {
    '.chip-group': '「被拦」把阻止/冻结圈在一起的分组框（虚线 + 浅黄底 + 18px 圆角）。'
                 + 'el-tag 没有分组容器语义；el-segmented 的选项由 options 数据驱动、只有一个滑动指示器'
                 + '（文档 Segmented Attributes: options/block/disabled/size/direction）；'
                 + 'el-card 的 border 固定 1px solid（card.scss:13，无 border-style 变量）⇒ 虚线分组框无出口。',
    '.reason-would-be': '「本可自动过审」的标记：font-style:italic + 左 2px 竖线。'
                      + 'el-text 的 $text map 没有 font-style（var.scss:1172-1179），也没有单边 border。',
    '.why-would-be': '同上（抽屉里的同款标记）。',
    '.why-reason': '引注块：13px + 浅底 + 3px 左竖线。字号可 el-text、底色可 el-card'
                 + '（--el-card-bg-color），但**单边竖线**没有任何组件出口：el-card 四边同色同宽'
                 + '（card.scss:13）、el-alert 全文无 border（alert.scss:5-50）⇒ 保留。'
                 + '（若接受"el-card + 1 行 border-left"的混写，可再降 2 条，本轮按"零视觉变化"取舍未做。）',
    '.why-global': '全局排除提示块。已换成 el-card + --el-card-bg-color/-border-color/-border-radius，'
                 + '只剩 border-style: dashed 一条：卡片的 border 写死 1px solid（card.scss:13），'
                 + 'Card 文档 Attributes 也只有 header/footer/body-style/header-class/body-class/'
                 + 'footer-class/shadow，没有 border-style ⇒ 虚线这一位无出口。',
    '.why-mark': '「← 差在哪」：600 字重 + white-space:nowrap。white-space 是 el-text 没有的属性；'
               + '600 也没有档（tag="b" 给的是 700，改了就变粗）。'
               + '⚠️ 上一轮本文件这里被判成"700 可用 tag=b"，实测是 600，己方修正过一次。',
    '.c-frozen': 'FROZEN 的紫色数字。色已交给 --el-text-color:var(--qiqi-color-frozen-dark-2)，'
               + '剩 600 字重：纯文本用 el-text 没有字重变量（el-statistic 才有，但这不是统计数字）。'
  },

  /* ---------------------------------------------------------------- 审核策略 */
  'admin-web/src/views/audit-policy/index.vue': {
    '.priority-note': '多行口径说明：12px + secondary 灰 + 浅底 + 2px 圆角 + 3px 左竖线。'
                    + '字号/字色可 el-text，底色/圆角可 el-card，但左竖线无组件出口'
                    + '（el-card 四边同宽 solid；el-alert 无 border）。保留整块，避免把"一个视觉单元"'
                    + '拆成 el-card + 3 行残留（收益 2 条，可读性更差）。',
    '.policy-name': '策略名 600 字重。el-text 的 $text map 只有 font-size/color；tag="b" 是 700 不是 600。',
    '.cond-chip': '条件胶囊：浅蓝底 #f4f8ff + 1px #d0e0ff 边 + 深蓝字 #1f4fa3。'
                + 'el-tag 只有 5 个语义 type（primary/success/info/warning/danger，tag.mjs:11-21）'
                + '和一个 color 属性（只设背景，文字对比色由 EP 自动算黑/白，给不出"浅底深字"）'
                + '⇒ 表达不了这套自定义色对。--el-tag-bg-color/-border-color/-text-color 三个变量确实存在，'
                + '但 5 种条件色各写一组 = 15 条变量换 15 条声明，零收益。',
    '.cond-chip-exclude': '同上（排除条件用浅红）。',
    '.cond-chip-focus': '同上 + 被指到的条件要加粗描边；el-tag 没有 focus/active 态的公开变量。',
    '.cond-chip-kind': '胶囊里的「匹配/排除」小字块：11px + rgba(0,0,0,.06) 底 + 3px 圆角。'
                     + '11px 可 el-text 变量，但底块要 --el-tag-bg-color 才成立，而 el-tag 会连带引入 1px 边框，'
                     + '与现在"无边框小色块"不同 ⇒ 保留。',
    '.cond-seq': '序号列（0/1/G0）用等宽字体：theme-chalk/src 全库 grep "monospace" = 0 命中，'
               + '$font-family 只有一档 sans-serif（var.scss:188-196）⇒ EP 确实没有等宽语义。'
  },

  /* ---------------------------------------------------------------- 令牌管理 */
  'admin-web/src/views/api-key/index.vue': {
    '.key-prefix': 'Key 前缀等宽：同 .cond-seq，theme-chalk 里 monospace 0 命中、$font-family 只有一档。'
                 + '13px 已交 --el-text-font-size，只剩 font-family 这一位。',
    '.reveal-value': '一次性明文 Key：等宽 + user-select:all + word-break。等宽同上；'
                   + '13px/深色正文已交 el-text 变量，剩 font-family（+ 两条非外观声明）。',
    '.scope-group-name': '权限组名 600 字重：同 .policy-name（纯文本无 weight 变量）。',
    '.scope-group-count': '计数气泡 18px 高 / 9px 圆角 / #f0f2f5 底 / #909399 字。'
                       + 'el-badge 独立使用时**确实是行内胶囊**（badge.scss:18），这点上一轮判错了；'
                       + '真障碍是 .el-badge__content 的文字色写死 color:var(--el-color-white)、'
                       + '没有 --el-badge-text-color ⇒ "灰底灰字"要劫持全局 --el-color-white，代价大于收益。'
                       + 'el-tag round size="small" 也能行内，但 20px 高 / 9999 圆角 / 自带 1px 边框，与现在不同。',
    '.reveal-box': '一次性明文框。已换 el-card + --el-card-bg-color/-border-radius，只剩 border:1px dashed：'
                 + '卡片的 border 固定 1px solid 且无 border-style 属性/变量（同 .why-global）。'
  },

  /* ---------------------------------------------------------------- 库存对账 */
  'admin-web/src/views/inventory-reconcile/index.vue': {
    '.net-block': '台账净额强调块：浅黄底 + 4px 左粗线 + 4px 圆角。刻意与上面三张对比卡区分。'
                + '底色/圆角可 el-card/el-alert，4px 左粗线无出口（el-card 四边同宽、el-alert 无 border）。'
  },

  /* ---------------------------------------------------------------- 弹窗 */
  'admin-web/src/components/DangerConfirmDialog.vue': {
    '.willdo': '「将要发生什么」的正文：15px + pre-wrap + 可选中。15px 可 --el-text-font-size，'
             + '深色正文可 --el-text-color-primary，但 white-space:pre-wrap 无组件出口'
             + '（el-text 的 truncated 是 nowrap + 省略号，语义相反）⇒ 保留 3 行整块。',
    '.details': '高危明细块的浅底 + 4px 圆角 + 3px 橙色左线。明细内容已换 el-descriptions，'
              + '但它的容器外观里"左竖线"无出口（同 .why-reason），故容器这 3 行保留。'
  },
  'admin-web/src/components/ConflictDialog.vue': {
    '.conflict-message': '409 主句 600 字重（15px/深色已交 el-text 变量）。纯文本无 weight API。'
  },

  /* ---------------------------------------------------------------- 登录 / 其它 */
  'portal-web/src/views/login/index.vue': {
    '.login-page': '登录页品牌渐变背景。EP 无渐变语义：没有组件属性，也没有 --el-gradient-*；'
                 + '--el-bg-color-page 是纯色。'
    ,
    '.brand': '品牌标题 600 字重（18px/墨色已交 el-text 变量）。'
  },
  'admin-web/src/views/login/index.vue': {
    '.login-page': '后台登录页的深色渐变背景，同上：EP 无渐变语义。',
    '.brand': '品牌标题 600 字重（18px/墨色已交 el-text 变量）。纯文本无 weight API。',
    '.captcha-img': '验证码图 40px 高 + 4px 圆角 + 1px 边框。Image 文档 Attributes 全表'
                  + '（src/fit/loading/lazy/scroll-container/alt/referrerpolicy/crossorigin/preview-src-list/'
                  + 'z-index/initial-index/close-on-press-escape/preview-teleported/infinite/zoom-rate/'
                  + 'scale/min-scale/max-scale/show-progress）没有尺寸/圆角/边框，也没有 --el-image-* 变量；'
                  + 'el-avatar shape="square" 能拿 4px 圆角但会引入 --el-avatar-bg-color 底色且仍无边框。'
  },
  'portal-web/src/views/product/detail.vue': {
    '.gallery-item': '48px 相册缩略图 + 2px 选中描边（透明占位）。尺寸可布局，但"选中态描边色"'
                   + 'el-image 没有（Image 无 border/active 相关属性或变量），el-avatar 也没有选中态。',
    '.gallery-fallback': '无图占位（#f0f2f5 底 + #c0c4cc 字 + 11px）。el-image 的 #error 插槽确实存在'
                       + '（文档 Image Slots: placeholder/error/viewer；源码 image.vue:147），但它插的是'
                       + '"内容"，插槽里的底色/字色/字号仍要自己写 ⇒ 插槽换掉的是手写 v-if 结构，不是这 3 条声明。',
    '.price': '价格 24px 红字：24px 已交 --el-text-font-size、红已交 type="danger"，只剩 600 字重'
            + '（纯文本无 weight 变量）。'
  },
  'portal-web/src/views/home/index.vue': {},
  'admin-web/src/views/product/index.vue': {},
  'admin-web/src/views/product/skus.vue': {},
  'admin-web/src/components/ImageUploader.vue': {},
  'admin-web/src/views/channel/index.vue': {
    '.shop-platform': '渠道名 15px + 600。15px 已交 --el-text-font-size，剩 600（纯文本无 weight 变量）。'
  },
  'admin-web/src/views/dashboard/index.vue': {
    '.metric-value': '指标数字已整体交给 el-statistic（22px/600/墨色全部走 --el-statistic-content-*），'
                   + '只剩 suffix 插槽的 font-weight:400 一条：el-statistic 的 content 是 600，suffix 作为'
                   + '它的子节点会继承 600，而 el-text 没有字重变量用来抵消 ⇒ 这一条必须留。'
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
    const whitelist = WHITELIST[rel] || {}


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
        const key = Object.keys(whitelist).find(matches)
        const shared = key ? undefined : Object.keys(NOT_GLOBALIZABLE).find(matches)
        const appearance = rule.decls.filter((d) => APPEARANCE.some((re) => re.test(d.prop)))
        if (key || shared) {
          // 豁免按"声明"计数，不按"规则"计数：一条规则可能含 3 条外观声明
          for (const d of appearance) {
            whitelisted.push({ file: rel, selector: rule.selector, prop: d.prop, line: rule.line, why: key ? 'whitelist' : 'not-globalizable' })
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

// preflight 用的单行摘要：不刷屏，只留一行可 grep 的事实
if (process.argv.includes('--brief')) {
  const deepNote = DEEP_FAIL ? `:deep ${deepHits.length} 处 ❌` : ':deep 0 处 ✓'
  // 两段式：未豁免的真残留 vs 已审过的豁免。只报前者会把"藏进白名单"误读成"改掉了"。
  console.log(
    `样式纪律：外观声明 ${problems.length} 处（未豁免，warn，不阻塞）`
    + ` + 白名单豁免 ${whitelisted.length} 条（已逐条给反证），${deepNote}`
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
console.log(`外观声明命中：${problems.length} 处（未豁免），分布在 ${ranked.length} 个文件`)
console.log(`白名单豁免：${whitelisted.length} 条（每条都在 WHITELIST 里写了"哪个 prop 差在哪"）`)
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
