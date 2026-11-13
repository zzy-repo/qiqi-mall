/**
 * 「组件静默失效」守卫 —— 专门抓 B2（Element Plus 按需引入）那类故障。
 *
 * 为什么需要这个文件：
 *   B2 把 `app.use(ElementPlus)` 整包注册换成了 unplugin 解析器按需注入。一旦某个组件在
 *   **模板里出现、但解析器没注入**（配置没加载 / resolver 漏配 / 别名写错），Vue 不会报错，
 *   只在 console 打一条 `[Vue warn]: Failed to resolve component: el-xxx`，然后**静默渲染成
 *   一个空的自定义元素** —— 按钮没了、输入框没了、样式没了，页面看起来就是「少了一块」。
 *   这类故障的可怕之处：只要断言没恰好要求那个真实 DOM，用例照样全绿。
 *
 *   实测样本（2026-11-13 回归，admin dev server 旧配置残留）：
 *     admin /login 页面 → 7 条 `Failed to resolve component: el-*`
 *                      → DOM 里 8 个裸 <el-* 节点、button=0、input=0、正文 32 字
 *                      → 页面实际是坏的，但**零报错**，只有 console warn。
 *
 * 机制：
 *   监听 page.on('console') / page.on('pageerror')，命中「组件/指令解析失败」的 warning 就记账，
 *   用例结束时（fixture teardown）若有未豁免的命中，**让该用例失败**，错误信息里带 warning 原文。
 *
 * 开关（都走环境变量，不需要改代码）：
 *   E2E_ALLOW_VUE_WARN=1     临时放行：只记录不失败。**只用于排查**，不许在 CI / 发布门禁里用。
 *   E2E_VUE_WARN_ALLOW=...   追加白名单，逗号分隔的子串（子串命中即豁免），用于已知无害的告警。
 *                            默认白名单见 DEFAULT_ALLOWLIST。
 */

/** 命中即视为「组件静默失效」的模式 */
const RESOLVE_PATTERNS = [
  /Failed to resolve component/i,
  /Failed to resolve directive/i,
  /Vue warn.*resolve component/i
]

/**
 * 已知无害的白名单（子串匹配）。
 * 往这里加东西要谨慎：白名单只允许放「与组件解析无关」的弃用/用法提示，
 * 绝不能放 `Failed to resolve` 一类的解析失败告警，否则守卫就形同虚设。
 */
const DEFAULT_ALLOWLIST = [
  // Element Plus 3.0 弃用提示：el-radio 的 label 当 value 用（B2 之前就存在，与按需引入无关）
  'label act as value is about to be deprecated'
]

const envAllow = (process.env.E2E_VUE_WARN_ALLOW || '')
  .split(',')
  .map(s => s.trim())
  .filter(Boolean)

const ALLOWLIST = [...DEFAULT_ALLOWLIST, ...envAllow]
const SOFT_MODE = process.env.E2E_ALLOW_VUE_WARN === '1'

const isAllowed = text => ALLOWLIST.some(sub => text.includes(sub))
const isResolveFailure = text => RESOLVE_PATTERNS.some(re => re.test(text))

/**
 * 为一个 testInfo 建守卫，挂到 context（以及它下面所有 page）。
 * 返回的对象只用于内部记账；对外统一走 fixture。
 */
export function createConsoleGuard(testInfo) {
  const violations = []
  const attached = new WeakSet()

  const record = (kind, text) => {
    if (!isResolveFailure(text)) return
    if (isAllowed(text)) return
    violations.push({ kind, text })
    // 顺手挂到报告上，HTML 报告里能直接看到原文，不用翻 stdout
    testInfo.attachments.push({
      name: `vue-resolve-warning-${violations.length}`,
      contentType: 'text/plain',
      body: Buffer.from(`${kind}\n${text}`, 'utf8')
    })
  }

  const attach = page => {
    if (attached.has(page)) return
    attached.add(page)
    page.on('console', msg => {
      const type = msg.type()
      if (type !== 'warning' && type !== 'error') return
      record(`console.${type}`, msg.text())
    })
    page.on('pageerror', err => record('pageerror', String(err && err.message ? err.message : err)))
  }

  return {
    attach,
    attachAll(context) {
      context.pages().forEach(attach)
      context.on('page', attach)
      return () => context.off('page', attach)
    },
    get violations() {
      return violations
    },
    /**
     * 用例结束时调用。有未豁免的解析失败 → 抛错让用例红。
     * 错误信息带完整原文（前 5 条 + 总数），方便直接定位是哪个组件。
     */
    assertClean() {
      if (!violations.length) return
      if (SOFT_MODE) {
        // 放行模式：只在 stdout 留痕，供人工排查
        console.warn(
          `[console-guard] E2E_ALLOW_VUE_WARN=1 放行 ${violations.length} 条组件解析告警：\n` +
            violations.map(v => `  - [${v.kind}] ${v.text}`).join('\n')
        )
        return
      }
      const shown = violations.slice(0, 5)
      throw new Error(
        `组件静默失效：出现了 ${violations.length} 条「组件/指令解析失败」告警。\n` +
          `这通常意味着按需引入（B2 / unplugin resolver）没解析到模板里的组件 —— ` +
          `页面会静默少渲染那一块（按钮/输入框/样式消失），但不会报错。\n` +
          `排查方向：vite.config.js 的 Components/AutoImport 插件是否加载、resolver 是否覆盖该组件、` +
          `dev server 是否用了旧配置（看日志有没有 "config must export"/"server restart failed"）。\n\n` +
          shown.map((v, i) => `${i + 1}) [${v.kind}] ${v.text}`).join('\n\n') +
          (violations.length > shown.length ? `\n\n…… 其余 ${violations.length - shown.length} 条见附件。` : '')
      )
    }
  }
}
