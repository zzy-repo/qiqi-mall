import { expect } from '@playwright/test'

export const PORTAL = process.env.PORTAL_BASE || 'http://127.0.0.1:5173'
export const ADMIN = process.env.ADMIN_BASE || 'http://127.0.0.1:5175'
export const API = process.env.API_BASE || 'http://127.0.0.1:8081'

/** 每次运行唯一后缀，避免唯一键冲突 */
export const stamp = () => Date.now().toString().slice(-8)

/** 后台登录（走真实登录页，带验证码时自动失败提示按需处理） */
export async function adminLogin(page, username = 'admin', password = '123456') {
  await page.goto(`${ADMIN}/login`)
  await page.locator('.el-form-item').filter({ hasText: '用户名' }).locator('input').fill(username)
  await page.locator('.el-form-item').filter({ hasText: '密码' }).locator('input').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).not.toHaveURL(/\/login/, { timeout: 20_000 })
}

/** C 端登录（走真实登录页） */
export async function memberLogin(page, username, password = '123456') {
  if (!page.url().includes('/login')) {
    await page.goto(`${PORTAL}/login`)
  }
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

/** 管理员发货（准备「已发货」数据，走专用接口 + 守卫） */
export async function shipOrderApi(request, adminToken, orderId) {
  const res = await request.post(`${API}/admin/orders/${orderId}/ship`, {
    headers: { Authorization: `Bearer ${adminToken}` }
  })
  const body = await res.json()
  expect(body.code, `发货应成功：${body.message}`).toBe(0)
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

/** 发货单下发 */
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

/** 把一个订单推进到「已发货(2)」：审核通过 → 逐个子单下发+出库 */
export async function driveOrderToShipped(request, adminToken, orderId) {
  await auditPassApi(request, adminToken, orderId)
  const deliveries = await listDeliveriesApi(request, adminToken, orderId)
  for (const d of deliveries) {
    await dispatchDeliveryApi(request, adminToken, d.id)
    await wmsCallbackApi(request, adminToken, d.id, 'outbound')
  }
  return listDeliveriesApi(request, adminToken, orderId)
}
