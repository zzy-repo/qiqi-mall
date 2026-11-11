/**
 * 真实 UI 操作 helper —— 五个业务场景的所有业务动作都在这里，靠真实点击完成：
 * 登录/注册、下拉选择、加购、结算下单、审核、发货单下发、WMS 回传、确认收货、申请退货……
 *
 * 规则：本文件只允许出现 page 上的真实交互（goto / click / fill / 断言可见性），
 * 不允许出现 request.post/put/delete 之类的接口直调推进业务状态。
 * 断言读取请用 ./assert.js；页面无入口的数据准备请用 ./data.js（当前为空）。
 */
import { expect } from '@playwright/test'
import { PORTAL, ADMIN } from './env.js'

/**
 * 已经在登录态时，/login 会被路由守卫重定向走（页面上没有表单），
 * 这里等一下表单：等不到就认为已登录，直接复用现有会话。
 */
async function waitLoginForm(page, timeout = 6000) {
  const input = page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input')
  return input.waitFor({ state: 'visible', timeout }).then(() => true).catch(() => false)
}

// ---------------------------------------------------------------------------
// 登录 / 注册
// ---------------------------------------------------------------------------

/** 后台登录（走真实登录页；已登录则直接复用会话） */
export async function adminLogin(page, username = 'admin', password = '123456') {
  await page.goto(`${ADMIN}/login`)
  if (!(await waitLoginForm(page))) {
    await expect(page, '已登录时不应停留在登录页').not.toHaveURL(/\/login/, { timeout: 10_000 })
    return
  }
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(username)
  await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login/, { timeout: 20_000 })
}

/** C 端登录（走真实登录页；已登录则直接复用会话） */
export async function memberLogin(page, username, password = '123456') {
  if (!page.url().includes('/login')) {
    await page.goto(`${PORTAL}/login`)
  }
  if (!(await waitLoginForm(page))) return
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(username)
  await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
}

/** C 端注册（注册即登录） */
export async function memberRegister(page, username, password = '123456', nickname = 'E2E买家') {
  // 已在登录页（例如被拦截跳过来）时不要重新打开，否则会丢掉 redirect 参数
  if (!page.url().includes('/login')) {
    await page.goto(`${PORTAL}/login`)
  }
  await page.getByRole('tab', { name: '注册' }).click()
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(username)
  await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(password)
  await page.locator('.el-form-item').filter({ hasText: '昵称' }).locator('input').fill(nickname)
  await page.getByRole('button', { name: '注册并登录' }).click()
}

/** 注册并登录（真实登录页 → 注册 tab → 注册并登录，等离开登录页） */
export async function memberRegisterAndLogin(page, username, password = '123456') {
  await page.goto(`${PORTAL}/login`)
  await memberRegister(page, username, password)
  await expect(page, '注册后应离开登录页').not.toHaveURL(/\/login/, { timeout: 20_000 })
  await page.waitForTimeout(1200)
}

// ---------------------------------------------------------------------------
// Element Plus 通用交互
// ---------------------------------------------------------------------------

/** 关掉可能残留的下拉浮层（点弹窗标题，不会触发关闭对话框） */
export async function dismissPoppers(page) {
  const title = page.locator('.el-dialog__title').first()
  if (await title.count()) {
    await title.click({ force: true }).catch(() => {})
  }
  await page.waitForTimeout(250)
}

/**
 * 在弹窗里按表单项 label 选择一个下拉值。
 * @param labelText 表单项 label 文本，如 '类型'
 * @param optionText 选项文本，如 '手机'
 */
export async function pickSelect(page, labelText, optionText) {
  await dismissPoppers(page)
  const item = page.locator('.el-form-item').filter({ hasText: labelText }).last()
  await item.locator('.el-select__wrapper').first().click()
  await page.waitForTimeout(400)
  const options = page.locator('.el-select-dropdown__item:visible')
  const target = optionText ? options.filter({ hasText: optionText }).first() : options.first()
  await target.click()
  await dismissPoppers(page)
}

/** 树形下拉（如商品分类） */
export async function pickTreeSelect(page, labelText, nodeText, { exact = true } = {}) {
  await dismissPoppers(page)
  const item = page.locator('.el-form-item').filter({ hasText: labelText }).last()
  await item.locator('.el-select__wrapper').first().click()
  await page.waitForTimeout(400)
  // 注意：优先按「叶子节点精确文本」匹配，父节点在单选模式下点选不生效（商品弹窗已加 check-strictly）
  const options = page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item')
  const target = nodeText
    ? options.filter({ hasText: exact ? new RegExp(`^${nodeText}$`) : nodeText }).first()
    : options.first()
  await target.click()
  await dismissPoppers(page)
}

// ---------------------------------------------------------------------------
// 管理端：商品 / SKU
// ---------------------------------------------------------------------------

/**
 * 在「SKU 管理」页把第 rowIndex 个 SKU 的库存改成固定值。
 * SKU 弹窗有「库存」输入框（el-input-number），这里真实点击+输入+失焦保存。
 */
export async function adminSetSkuStock(page, productId, rowIndex = 0, stock = 50) {
  await page.goto(`${ADMIN}/product/${productId}/skus`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').nth(rowIndex)
  await expect(row, `SKU 列表应有第 ${rowIndex + 1} 个 SKU`).toBeVisible()
  await row.getByRole('button', { name: '编辑' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  const stockInput = dialog.locator('.el-form-item').filter({ hasText: '库存' }).locator('input')
  await stockInput.click()
  await stockInput.fill(String(stock))
  await stockInput.press('Tab')            // 失焦触发 el-input-number 的 change
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message--success').first(), '库存保存应成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)
}

// ---------------------------------------------------------------------------
// 管理端：订单列表 → 详情抽屉 → 发货单下发 / 模拟WMS回传
// ---------------------------------------------------------------------------

/** 订单列表按订单号搜索，返回目标行（订单量大，必须先搜） */
export async function adminSearchOrderRow(page, orderSn) {
  await page.goto(`${ADMIN}/order`)
  await page.waitForTimeout(1200)
  const input = page.locator('input[placeholder="按订单号搜索"]')
  await input.fill(orderSn)
  await input.press('Enter')
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `管理员订单列表应能看到订单 ${orderSn}`).toBeVisible()
  return row
}

/**
 * 点「明细」打开订单详情抽屉，返回「发货单」区块的表格 locator。
 * 抽屉内有三张表（发货单 / 商品明细 / 退货申请），第一张是发货单。
 */
export async function openOrderDrawer(page, orderSn) {
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await row.getByRole('button', { name: '明细' }).click()
  const drawer = page.locator('.el-drawer')
  await expect(drawer, '订单详情抽屉应打开').toBeVisible()
  await expect(drawer.locator('h4').filter({ hasText: '发货单' })).toBeVisible()
  const deliveryTable = drawer.locator('.el-table').first()
  return deliveryTable
}

/** 发货单表格里按发货仓定位行 */
export const deliveryRowOf = (deliveryTable, warehouseName) =>
  deliveryTable.locator('.el-table__body tr').filter({ hasText: warehouseName }).first()

/** 点行内「下发」，断言状态变为已下发 */
export async function clickDispatch(page, row) {
  await row.getByRole('button', { name: '下发' }).click()
  await expect(row, '下发后发货单应为已下发').toContainText('已下发')
}

/**
 * 「模拟WMS回传 ▾」是 el-dropdown（不是 el-select）：点开后在浮层里选事件。
 * @param label 选项文案：拣货 | 出库 | 揽收 | 签收
 * @param expectText 该事件后发货单应显示的状态文案
 */
export async function clickWmsCallback(page, row, label, expectText) {
  await row.getByRole('button', { name: /模拟WMS回传/ }).click()
  // 浮层 teleport 到 body；每个发货单行都有一个下拉，只有当前展开的那个可见
  const item = page.locator('.el-dropdown-menu__item:visible').filter({ hasText: label }).first()
  await expect(item, `WMS 回传下拉应出现「${label}」选项`).toBeVisible()
  await item.click()
  await expect(row, `回传(${label})后发货单状态应为${expectText}`).toContainText(expectText)
}

// ---------------------------------------------------------------------------
// 门户侧：加购 → 结算下单 → 我的订单
// ---------------------------------------------------------------------------

/** 详情页选中指定规格（规格文本形如「颜色:黑,内存:256G」）并加入购物车 */
export async function addSpecToCart(page, productId, specText) {
  await page.goto(`${PORTAL}/product/${productId}`)
  await page.waitForTimeout(1200)
  const spec = page.locator('.el-radio-button').filter({ hasText: specText }).first()
  await expect(spec, `详情页应能选到规格「${specText}」`).toBeVisible()
  await spec.click()
  await page.getByRole('button', { name: '加入购物车' }).click()
  await page.waitForURL(/\/cart/, { timeout: 15_000 })
  await page.waitForTimeout(600)
}

/**
 * 购物车 → 结算 → 填收货信息 → 提交订单（结算页提交后自动模拟支付，落到「我的订单」）。
 * @returns 订单号（我的订单列表第一行）
 */
export async function submitCartOrder(page, {
  receiverName = 'E2E买家',
  receiverPhone = '13600001111',
  receiverAddress = '广东省深圳市南山区E2E路 1 号'
} = {}) {
  await page.goto(`${PORTAL}/cart`)
  await page.waitForTimeout(800)
  await page.getByRole('button', { name: '去结算' }).click()
  await page.waitForURL(/\/checkout/, { timeout: 15_000 })
  await page.waitForTimeout(1000)
  await page.locator('.el-form-item').filter({ hasText: '收货人' }).locator('input').fill(receiverName)
  await page.locator('.el-form-item').filter({ hasText: '电话' }).locator('input').fill(receiverPhone)
  await page.locator('.el-form-item').filter({ hasText: '收货地址' }).locator('textarea').fill(receiverAddress)
  await page.getByRole('button', { name: '提交订单' }).click()
  await page.waitForURL(/\/orders/, { timeout: 20_000 })
  await page.waitForTimeout(3000)          // 等自动支付 + 列表刷新
  const firstRow = page.locator('.el-table__body tr').first()
  await expect(firstRow).toBeVisible()
  const orderSn = (await firstRow.locator('td').first().innerText()).trim()
  return orderSn
}

/** 我的订单页，按订单号定位行 */
export async function memberOrderRow(page, orderSn) {
  await page.goto(`${PORTAL}/orders`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `买家我的订单应能看到 ${orderSn}`).toBeVisible()
  return row
}
