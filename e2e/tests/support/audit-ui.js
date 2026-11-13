/**
 * 自动审核页面的**真实 UI 操作** —— 队列页（/order-audit）、策略页（/audit-policy）、订单详情「审核轨迹」tab。
 *
 * 规则与 support/admin-ui.js 一致：这里只做真实点击/输入/可见性断言，不推进任何业务状态。
 * 表里没有 testid 的三处（elastic 组件内部渲染）按 tid.js 的「未覆盖清单」兜底：
 *   · ElMessageBox 的按钮（第 1 条）：`.el-message-box__btns .el-button` 按文案点；
 *   · el-table 的 <tr>（第 4 条）：行锚点只挂在单号格上，整行按 `has: 单号 testid` 过滤；
 *   · el-tabs 的页签头（第 3 条同源的浮层问题）：`.el-tabs__item` 按文案点。
 */
import { expect } from '@playwright/test'
import { ADMIN } from './env.js'
import { tid } from './tid.js'
import { gotoOrderList, searchOrderRow } from './admin-ui.js'

/* ------------------------------------------------------------------ 队列页 */

/** 打开「销售单审核队列」并等表格渲染 */
export const gotoAuditQueue = async (page) => {
  await page.goto(`${ADMIN}/order-audit`)
  await expect(tid(page, 'audit-queue-table'), '审核队列页应打开（audit-queue-table）').toBeVisible({ timeout: 20_000 })
  // 队列是 onMounted 拉一次 + 计数并发 6 个请求，等一拍再断言行
  await page.waitForTimeout(800)
}

/**
 * 队列行定位。
 * 行锚点 `audit-queue-row-${orderSn}` 挂在「单号」格里的 el-link 上（el-table 的 <tr> 无法挂 testid），
 * 所以整行用「包含该锚点的 tr」来定位 —— 这是本套件唯一保留的结构选择器用法（tid.js 第 4 条）。
 */
export const auditQueueRow = (page, orderSn) =>
  page.locator('.el-table__body tr').filter({ has: page.getByTestId(`audit-queue-row-${orderSn}`) })

/** 行内判定徽标（颜色 + 中文文案 + data-result 三重冗余，断言一律读 data-result） */
export const badgeOf = (row) => row.getByTestId('audit-row-badge')

/** 行内原因第一行（「命中「X」→ 自动过审」这类结论句） */
export const reasonMainOf = (row) => row.getByTestId('audit-row-reason')

/** 行内原因第二行（后端原话，含具体数值差）—— 灰字没有 testid，按类名取 */
export const reasonSubOf = (row) => row.locator('.reason-sub')

/** 「差在哪」按钮（打不开就没得解释，所以点完必须断言抽屉真的开了） */
export const whyButtonOf = (row) => row.getByTestId('audit-row-why')

/** 「轨迹」按钮（打开单笔抽屉） */
export const trailButtonOf = (row) => row.getByTestId('audit-row-trail')

/**
 * 点「立即审核一轮」→ 确认框 → 等 /admin/order-audit/run 的响应。
 *
 * 为什么要 waitForResponse 而不是只等 toast：toast 是「上一次成功」的残留，
 * 只等它会在请求还在飞的时候就去查库/查页面（09-多平台接入踩过同一个坑）。
 * 确认框的按钮文案是「开始执行」（不是「确定」），必须按文案精确点。
 *
 * @returns {Promise<object>} run 接口的 data：{runId, triggerType, total, autoPass, manual, blocked, frozen, noMatch, skipped, decisions[]}
 */
export const runAuditViaUi = async (page, { timeout = 120_000 } = {}) => {
  const [response] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/admin/order-audit/run',
      { timeout }
    ),
    (async () => {
      await tid(page, 'audit-queue-run').click()
      await confirmMessageBox(page, '开始执行')
    })()
  ])
  const body = await response.json()
  expect(body.code, `执行一轮自动审核应成功（message=${body.message || '无'}）`).toBe(0)
  expect(body.data?.runId, 'run 接口应返回 runId').toBeTruthy()
  expect(String(body.data.triggerType).toUpperCase(), '页面按钮触发的应是 MANUAL 轮').toBe('MANUAL')
  // 等页面按新结果重拉队列（runNow → refreshAll）
  await page.waitForTimeout(1200)
  return body.data
}

/** 点某个筛选 chip（也是计数）：'AUTO_PASS' | 'MANUAL' | 'BLOCKED' | 'FROZEN' | 'NO_MATCH' */
export const filterByResultChip = async (page, result) => {
  await tid(page, `audit-chip-${result}`).click()
  await page.waitForTimeout(1000)
}

/** 取消筛选（回到「全部」） */
export const clearResultFilter = async (page) => {
  await tid(page, 'audit-chip-all').click()
  await page.waitForTimeout(1000)
}

/* ------------------------------------------------------------------ 「差在哪」抽屉 */

/**
 * 打开「差在哪」抽屉。
 * @returns {Promise<import('@playwright/test').Locator>} 抽屉根节点（后续断言都限定在它里面，避免读到页面上其它同名 testid）
 */
export const openWhyDrawer = async (page, row) => {
  await whyButtonOf(row).click()
  const drawer = page.getByTestId('audit-why-drawer')
  await expect(drawer, '点「差在哪」应打开抽屉（audit-why-drawer）').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)   // 抽屉内容由本地对撞/后端留痕两路数据渲染，等它稳定
  return drawer
}

export const closeWhyDrawer = async (page) => {
  const drawer = page.getByTestId('audit-why-drawer')
  if (await drawer.isVisible().catch(() => false)) {
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden({ timeout: 10_000 })
  }
}

/* ------------------------------------------------------------------ 单笔抽屉（审核轨迹） */

/**
 * 点队列行「轨迹」打开单笔抽屉，返回抽屉根节点；`audit-trail` 里的三段都在它下面。
 */
export const openTrailDrawer = async (page, row) => {
  await trailButtonOf(row).click()
  const drawer = page.getByTestId('audit-order-drawer')
  await expect(drawer, '点「轨迹」应打开单笔抽屉（audit-order-drawer）').toBeVisible({ timeout: 15_000 })
  await expect(drawer.getByTestId('audit-trail'), '抽屉里默认就是「审核轨迹」tab，应直接看到时间线').toBeVisible({ timeout: 15_000 })
  return drawer
}

export const closeTrailDrawer = async (page) => {
  const drawer = page.getByTestId('audit-order-drawer')
  if (await drawer.isVisible().catch(() => false)) {
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden({ timeout: 10_000 })
  }
}

/**
 * 订单列表页 → 明细抽屉 → 「审核轨迹」tab（前端交付清单里的第三个入口）。
 * 页签头没有 testid（el-tabs 内部渲染），按文案点。
 */
export const openOrderPageTrailTab = async (page, orderSn) => {
  await gotoOrderList(page)
  const row = await searchOrderRow(page, orderSn)
  await row.getByTestId('order-row-detail').click()
  const drawer = tid(page, 'order-detail-drawer')
  await expect(drawer, '订单详情抽屉应打开').toBeVisible({ timeout: 20_000 })
  await drawer.locator('.el-tabs__item').filter({ hasText: '审核轨迹' }).first().click()
  await expect(
    drawer.getByTestId('audit-trail'),
    '订单详情「审核轨迹」tab 里应渲染时间线（audit-trail）'
  ).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)
  return drawer
}

/* ------------------------------------------------------------------ 冻结 / 解冻 */

/**
 * 解冻：行内「解冻」→ 确认框「确认解冻」。
 * 解冻只解除 FROZEN 类标（不动 status、不写状态台账），下一轮 run 才会重新判定。
 */
export const unfreezeRow = async (page, row) => {
  await row.getByTestId('audit-row-unfreeze').click()
  await confirmMessageBox(page, '确认解冻')
  await page.waitForTimeout(1200)
}

/**
 * 人工冻结：行内「冻结」→ 原因输入框 → 「确认冻结」。
 * 本套用例只用它做「页面确实能人工冻结」这一条反证（主体冻结由异常策略自动完成）。
 */
export const freezeRow = async (page, row, reason = 'e2e：人工冻结回归') => {
  await row.getByTestId('audit-row-freeze').click()
  const box = page.locator('.el-message-box')
  await expect(box, '点「冻结」应弹出原因输入框').toBeVisible({ timeout: 10_000 })
  await box.locator('input').first().fill(reason)
  await box.locator('.el-message-box__btns').getByRole('button', { name: '确认冻结' }).click()
  await expect(box).toBeHidden({ timeout: 10_000 })
  await page.waitForTimeout(1200)
}

/* ------------------------------------------------------------------ 策略页 */

export const gotoPolicyPage = async (page) => {
  await page.goto(`${ADMIN}/audit-policy`)
  await expect(tid(page, 'policy-tabs'), '策略页应打开（policy-tabs）').toBeVisible({ timeout: 20_000 })
  await page.waitForTimeout(800)
}

/** 切到某类策略的页签（AUTO / BLOCK / EXCEPTION）*/
export const selectPolicyTypeTab = async (page, type) => {
  const label = { AUTO: '自动', BLOCK: '阻止', EXCEPTION: '异常' }[type] || type
  await tid(page, 'policy-tabs').locator('.el-tabs__item').filter({ hasText: label }).first().click()
  await page.waitForTimeout(600)
}

/** 上移 / 下移（只与同类型相邻策略换位；不产生版本快照、不动 version_no） */
export const movePolicy = async (page, policyId, direction) => {
  const testid = direction === 'UP' ? `policy-move-up-${policyId}` : `policy-move-down-${policyId}`
  await tid(page, testid).click()
  await page.waitForTimeout(1200)
}

/**
 * 某类策略页签里**当前顺序**的策略名（第 1 个 = 优先级最小 = 先判）。
 * 优先级数字本身直接查库（policyRowsOf）；页面这侧只需证明「顺序真的变了」。
 */
export const policyNameOrder = async (page, type = 'AUTO') =>
  (await tid(page, `policy-table-${type}`).locator('.policy-name').allInnerTexts())
    .map((s) => s.trim())
    .filter(Boolean)

/* ------------------------------------------------------------------ ElMessageBox 兜底 */

/**
 * 点 ElMessageBox 里的某个按钮（弹窗由组件内部渲染，按钮没有 testid —— tid.js 第 1 条）。
 * @param label 按钮文案（如「开始执行」「确认解冻」「确定」）
 */
export const confirmMessageBox = async (page, label) => {
  const box = page.locator('.el-message-box')
  await expect(box, `应弹出确认框（准备点「${label}」）`).toBeVisible({ timeout: 15_000 })
  await box.locator('.el-message-box__btns').getByRole('button', { name: label }).click()
  await expect(box, `点「${label}」后确认框应收起`).toBeHidden({ timeout: 20_000 })
}

/** 关掉 ElMessageBox（取消，不发请求） */
export const cancelMessageBox = async (page) => {
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible({ timeout: 10_000 })
  await box.locator('.el-message-box__btns').getByRole('button', { name: '取消' }).click()
  await expect(box).toBeHidden({ timeout: 10_000 })
}
