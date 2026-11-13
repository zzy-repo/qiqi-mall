/**
 * 共享 test 对象 —— 所有 spec 都从这里 import，而不是从 '@playwright/test'。
 *
 * 唯一目的：把「组件静默失效」守卫（见 console-guard.js）挂到每个用例上，
 * 让 spec 自己一行都不用改。
 *
 *   import { test, expect } from '../../support/test.js'
 *
 * 和原生 test 的唯一区别：
 *   · context fixture 被包了一层：每个 page（含用例中途新开的弹窗/新标签页）都挂上 console 监听；
 *   · 用例结束（含失败）时若出现未豁免的 `Failed to resolve component` 一类告警 → 用例判红。
 *
 * 开关见 console-guard.js 头部（E2E_ALLOW_VUE_WARN / E2E_VUE_WARN_ALLOW）。
 */
import { test as base, expect } from '@playwright/test'
import { createConsoleGuard } from './console-guard.js'

export const test = base.extend({
  // 包 context（page 依赖 context，所以新开的 page 也会被 attachAll 里的 'page' 事件接住）
  context: async ({ context }, use, testInfo) => {
    const guard = createConsoleGuard(testInfo)
    const detach = guard.attachAll(context)
    try {
      await use(context)
    } finally {
      detach()
    }
    guard.assertClean()
  }
})

export { expect }
