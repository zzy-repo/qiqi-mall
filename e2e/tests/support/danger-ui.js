/**
 * 高危写操作的统一确认弹窗（DangerConfirmDialog，全站单例）+ 并发冲突弹窗（ConflictDialog）。
 *
 * 为什么单独一个文件：
 *   support/ui.js 与 support/admin-ui.js 都用到这套弹窗，而 admin-ui.js 已经 import 了 ui.js
 *   （adminLogin）。把弹窗定位放在这个**不依赖任何业务 helper** 的文件里，两边都能 import，
 *   不会形成循环依赖。
 *
 * 新 UI 的交互骨架（14 个高危操作共用同一套）：
 *   点行内按钮 → 弹窗先开、显示「正在预演…」→ 预演回来填 willDo（+ 可选 details 明细区）
 *   → 「确认执行」变为可点 → 点它才会发执行请求；执行失败**不关窗**，原因显示在 error 区。
 *
 * 因此这里所有「确认」动作都必须先等 `danger-confirm-ok` 变成 enabled（预演完成），
 * 否则会点在一个 disabled 按钮上，测试表现为「点了没反应」。
 */
import { expect } from '@playwright/test'

/** 确认弹窗根节点 */
export const dangerDialog = (page) => page.getByTestId('danger-confirm-dialog')
/** 预演出来的「将要发生什么」（人话，含单号/名称/数量） */
export const dangerWillDo = (page) => page.getByTestId('danger-confirm-willdo')
/** 可选明细区（例如审核通过的拆单方案：哪个仓、几条明细、哪些商品） */
export const dangerDetails = (page) => page.getByTestId('danger-confirm-details')
/** 「确认执行」按钮（预演完成前是 disabled） */
export const dangerOk = (page) => page.getByTestId('danger-confirm-ok')
/** 「取消」按钮：点它不发任何请求 */
export const dangerCancel = (page) => page.getByTestId('danger-confirm-cancel')
/** 执行失败的中文原因（失败时弹窗不关，原因留在弹窗里） */
export const dangerError = (page) => page.getByTestId('danger-confirm-error')

/** 保存冲突弹窗（409 被别人改过 / 400 版本号缺失或过期） */
export const conflictDialog = (page) => page.getByTestId('conflict-dialog')
/** 冲突弹窗里的「刷新」（重拉最新数据再让用户改） */
export const conflictRefresh = (page) => page.getByTestId('conflict-refresh')

/**
 * 等「预演 → 可确认」这一步就绪：弹窗打开、willDo 写完、确认按钮可点。
 * @param {string} [opts.willDo] 期望 willDo 里包含的中文片段（例如「待审核→待发货」）
 */
export const waitDanger = async (page, { willDo, timeout = 20_000 } = {}) => {
  await expect(dangerDialog(page), '应弹出高危确认弹窗（danger-confirm-dialog）').toBeVisible({ timeout })
  if (willDo) {
    await expect(dangerWillDo(page), `弹窗应先说明将要发生什么（应含「${willDo}」）`)
      .toContainText(willDo, { timeout })
  }
  await expect(dangerOk(page), '预演完成后「确认执行」应变为可点').toBeEnabled({ timeout })
  return dangerDialog(page)
}

/** 等就绪 → 点「确认执行」→ 等弹窗收起（成功路径） */
export const confirmDanger = async (page, { willDo, timeout = 20_000 } = {}) => {
  await waitDanger(page, { willDo, timeout })
  await dangerOk(page).click()
  await expect(dangerDialog(page), '确认后弹窗应收起').toBeHidden({ timeout: 20_000 })
}

/** 等就绪 → 点「取消」→ 等弹窗收起（只看不执行） */
export const cancelDanger = async (page, { willDo, timeout = 20_000 } = {}) => {
  await waitDanger(page, { willDo, timeout })
  await dangerCancel(page).click()
  await expect(dangerDialog(page), '取消后弹窗应收起').toBeHidden({ timeout: 10_000 })
}

/** 关掉当前已经打开的确认弹窗（不点执行；例如「执行失败后我想放弃」） */
export const dismissDanger = async (page) => {
  await expect(dangerDialog(page), '应有一个打开的确认弹窗可关').toBeVisible()
  await dangerCancel(page).click()
  await expect(dangerDialog(page), '关掉后弹窗应收起').toBeHidden({ timeout: 10_000 })
}

/**
 * 「点了提交、接下来可能是两种结局」的等待（预演阶段就被服务端守卫拦下的场景）：
 *   ① 正常：弹窗出现 → 预演就绪 → 点「确认执行」→ 等收起，返回 'confirmed'
 *   ② 预演就失败：弹窗自己收起 + 一条「预演失败：…」toast（没有可点的确认），返回 'preview-failed'
 * 为什么需要：`useDangerConfirm.run()` 在 dryRun 抛错时是「toast + 直接收起」，
 * 硬等确认按钮会把这种正常业务分支误判成超时。
 * @returns {'confirmed'|'preview-failed'}
 */
export const confirmDangerOrToastError = async (page, { willDo, timeout = 20_000 } = {}) => {
  const errToast = page.locator('.el-message--error').first()
  const outcome = await Promise.race([
    waitDanger(page, { willDo, timeout }).then(() => 'ready', () => 'timeout').catch(() => 'timeout'),
    expect(errToast).toBeVisible({ timeout }).then(() => 'preview-failed').catch(() => 'timeout')
  ])
  if (outcome === 'ready') {
    await dangerOk(page).click()
    await expect(dangerDialog(page), '确认后弹窗应收起').toBeHidden({ timeout: 20_000 })
    return 'confirmed'
  }
  if (outcome === 'preview-failed') {
    await expect(dangerDialog(page), '预演失败时弹窗应自己收起（只留 toast）').toBeHidden({ timeout: 10_000 })
    return 'preview-failed'
  }
  throw new Error('既没有等到确认弹窗就绪，也没有等到失败提示')
}

/**
 * 等就绪 → 点「确认执行」→ 断言弹窗**没有**关，且把后端的中文原因显示在弹窗里（失败路径）。
 * @returns 错误文案
 */
export const confirmDangerExpectError = async (page, { willDo, timeout = 20_000 } = {}) => {
  await waitDanger(page, { willDo, timeout })
  await dangerOk(page).click()
  const err = dangerError(page)
  await expect(err, '执行失败时弹窗里应显示中文原因（而不是一闪而过的 toast）').toBeVisible({ timeout })
  const text = (await err.innerText()).trim()
  await expect(dangerDialog(page), '执行失败时弹窗不应自动关闭').toBeVisible()
  return text
}
