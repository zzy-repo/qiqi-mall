import { expect } from '@playwright/test'

export const PORTAL = process.env.PORTAL_BASE || 'http://127.0.0.1:5173'
export const ADMIN = process.env.ADMIN_BASE || 'http://127.0.0.1:5175'
export const API = process.env.API_BASE || 'http://127.0.0.1:8081'

/** 每次运行唯一后缀，避免唯一键冲突 */
export const stamp = () => Date.now().toString().slice(-8)

/**
 * 已经在登录态时，/login 会被路由守卫重定向走（页面上没有表单），
 * 这里等一下表单：等不到就认为已登录，直接复用现有会话。
 */
async function waitLoginForm(page, timeout = 6000) {
  const input = page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input')
  return input.waitFor({ state: 'visible', timeout }).then(() => true).catch(() => false)
}

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

/** 直接调接口拿 admin token（数据准备用） */
export async function adminToken(request) {
  const res = await request.post(`${API}/admin/auth/login`, {
    data: { username: 'admin', password: '123456' }
  })
  expect(res.ok(), 'admin 登录接口应返回 200').toBeTruthy()
  const body = await res.json()
  return body.data.accessToken
}

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
 * @param page
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
  // 注意：优先按「叶子节点精确文本」匹配，父节点在单选模式下点选不生效（见 e2e/FINDINGS.md）
  const options = page.locator('.el-tree-select__popper:visible li.el-select-dropdown__item')
  const target = nodeText
    ? options.filter({ hasText: exact ? new RegExp(`^${nodeText}$`) : nodeText }).first()
    : options.first()
  await target.click()
  await dismissPoppers(page)
}

/** 通过接口注册会员，返回 {token, memberId, username} */
export async function registerMemberApi(request, username, password = '123456') {
  const res = await request.post(`${API}/portal/auth/register`, {
    data: { username, password, nickname: 'E2E接口买家' }
  })
  expect(res.ok(), 'C 端注册接口应成功').toBeTruthy()
  const body = await res.json()
  // 后端字段是 accessToken，测试里统一叫 token
  return { ...body.data, token: body.data.accessToken, username }
}

/** 通过接口下单（不支付），返回订单详情。需先领幂等令牌（后端要求） */
export async function createOrderApi(request, token, { skuId, quantity = 1, receiverName = 'E2E接口买家' } = {}) {
  const tokenRes = await request.get(`${API}/portal/orders/idempotent-token`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const idempotentToken = (await tokenRes.json()).data.tokenId
  const res = await request.post(`${API}/portal/orders`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      receiverName,
      receiverPhone: '13600003333',
      receiverAddress: '广东省深圳市南山区接口路 1 号',
      items: [{ productSkuId: skuId, quantity }],
      idempotentToken
    }
  })
  const body = await res.json()
  expect(body.code, `下单应成功：${body.message}`).toBe(0)
  return body.data.order
}

/** 模拟支付 */
export async function payOrderApi(request, token, orderId) {
  const res = await request.post(`${API}/portal/orders/${orderId}/pay?payType=1`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  const body = await res.json()
  expect(body.code, `支付应成功：${body.message}`).toBe(0)
  return body.data.order
}

/**
 * 准备一个可下单的 SKU：取商品1的第一个 SKU，并把库存补到至少 50，
 * 让测试可以重复运行（下单会扣库存）。
 */
export async function prepareSku(request, productId = 1) {
  const at = await adminToken(request)
  const res = await request.get(`${API}/admin/skus?productId=${productId}`, {
    headers: { Authorization: `Bearer ${at}` }
  })
  const skus = (await res.json()).data
  expect(skus.length, '商品应至少有一个 SKU').toBeGreaterThan(0)
  // 详情页默认选中的是第一个 SKU，这里固定补足它，保证下单链路可重复
  const sku = skus[0]
  const targetStock = Math.max(Number(sku.stock) || 0, 50)
  await request.put(`${API}/admin/skus/${sku.id}`, {
    headers: { Authorization: `Bearer ${at}` },
    data: { sku: { productId, price: sku.price, stock: targetStock } }
  })
  return { skuId: sku.id, price: sku.price, stock: targetStock }
}

/** 取第一个可用 SKU */
export async function firstSkuId(request) {
  const res = await request.get(`${API}/portal/products/1`)
  const body = await res.json()
  return body.data.skus[0].id
}

/** 审核通过（待审核 → 待发货，并自动拆单）；返回子发货单列表 */
export async function auditPassApi(request, adminToken, orderId) {
  const res = await request.post(`${API}/admin/orders/${orderId}/audit-pass`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  })
  const body = await res.json()
  expect(body.code, `审核通过应成功：${body.message}`).toBe(0)
  return body.data
}

/** 查父订单的子发货单 */
export async function listDeliveriesApi(request, adminToken, orderId) {
  const res = await request.get(`${API}/admin/deliveries?orderId=${orderId}`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  })
  return (await res.json()).data
}

/*
 * 下面两个接口 helper 仅作为「数据准备/断言」的备用工具保留：
 * 五个业务场景的履约主流程一律走真实 UI 点击（订单列表 → 明细抽屉 → 下发 / 模拟WMS回传），
 * 不得用它们推进订单状态。
 */

/** 发货单下发（备用，勿用于场景主流程） */
export async function dispatchDeliveryApi(request, adminToken, deliveryId) {
  const res = await request.post(`${API}/admin/deliveries/${deliveryId}/dispatch`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  })
  const body = await res.json()
  expect(body.code, `下发应成功：${body.message}`).toBe(0)
}

/** WMS 回传：pick | outbound | transit | signed */
export async function wmsCallbackApi(request, adminToken, deliveryId, event) {
  const res = await request.post(`${API}/admin/deliveries/${deliveryId}/wms-callback?event=${event}`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  })
  const body = await res.json()
  expect(body.code, `回传(${event})应成功：${body.message}`).toBe(0)
}

// ---------------------------------------------------------------------------
// 管理端「发货单」真实 UI 操作（订单列表 → 明细抽屉 → 下发 / 模拟WMS回传）
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
// 令牌读取（只读，不发请求）：断言用的 GET 直接复用页面里已登录的令牌
// ---------------------------------------------------------------------------

/** 管理端令牌（admin 已通过真实登录页登录后从 localStorage 读取） */
export const adminTokenOf = (page) => page.evaluate(() => localStorage.getItem('qiqi_admin_token'))

/** 买家令牌（portal 已通过真实登录页登录后从 localStorage 读取） */
export const memberTokenOf = (page) => page.evaluate(() => localStorage.getItem('qiqi_member_token'))

// ---------------------------------------------------------------------------
// 管理端：SKU 库存（页面有编辑入口，走真实 UI，保证用例可重复运行）
// ---------------------------------------------------------------------------

/**
 * 在「SKU 管理」页把第 rowIndex 个 SKU 的库存改成固定值。
 * admin 的 SKU 弹窗有「库存」输入框（el-input-number），这里真实点击+输入+失焦保存。
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
// 门户侧：注册 → 加购 → 结算下单（全真实 UI）
// ---------------------------------------------------------------------------

/** 注册并登录（真实登录页 → 注册 tab → 注册并登录） */
export async function memberRegisterAndLogin(page, username, password = '123456') {
  await page.goto(`${PORTAL}/login`)
  await memberRegister(page, username, password)
  await expect(page, '注册后应离开登录页').not.toHaveURL(/\/login/, { timeout: 20_000 })
  await page.waitForTimeout(1200)
}

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

// ---------------------------------------------------------------------------
// 门户侧：我的订单行
// ---------------------------------------------------------------------------

/** 我的订单页，按订单号定位行 */
export async function memberOrderRow(page, orderSn) {
  await page.goto(`${PORTAL}/orders`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `买家我的订单应能看到 ${orderSn}`).toBeVisible()
  return row
}
