import { test, expect } from '@playwright/test'
import {
  PORTAL,
  ADMIN,
  API,
  adminLogin,
  adminToken,
  memberLogin,
  registerMemberApi,
  createOrderApi,
  payOrderApi,
  auditPassApi,
  listDeliveriesApi,
  dispatchDeliveryApi,
  wmsCallbackApi,
  prepareSku,
  stamp
} from './helpers.js'

/**
 * 场景3 订单履约（docs/flows/5-场景3-订单履约.mmd）
 *   买家「我的订单」看到已付款订单 → 管理员「订单列表」点发货（状态→已发货）
 *   → 买家「我的订单」点确认收货（状态→已完成）
 *
 * 文档中这一步被标注为 ⛔ 缺口（缺发货按钮 / 缺确认收货接口），
 * 本测试用真实 UI 断言这两步是否存在，失败即代表缺口未补。
 */
test.describe('场景3 订单履约', () => {
  test('管理员在订单列表发货，订单状态变为已发货', async ({ page, request }) => {
    // 准备：一个已付款订单
    const username = `e2e_ship_${stamp()}`
    const member = await registerMemberApi(request, username)
    const { skuId } = await prepareSku(request)
    const order = await createOrderApi(request, member.token, { skuId })
    await payOrderApi(request, member.token, order.id)

    await adminLogin(page)
    await page.goto(`${ADMIN}/order`)
    await page.waitForTimeout(1200)

    // 订单量大（历史压测数据），先按订单号搜索
    await page.locator('input[placeholder="按订单号搜索"]').fill(order.orderSn)
    await page.locator('input[placeholder="按订单号搜索"]').press('Enter')
    await page.waitForTimeout(1200)

    const row = page.locator('.el-table__body tr').filter({ hasText: order.orderSn }).first()
    await expect(row, '管理员订单列表应能看到该订单').toBeVisible()
    await expect(row, '支付后订单应进入待审核').toContainText('待审核')

    // 管理员审核通过（自动拆单）
    await row.getByRole('button', { name: '审核' }).click()
    // 注意：'不通过' 也含 '通过' 二字，必须用精确文本
    await page.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
    await page.waitForTimeout(2000)
    await expect(row, '审核通过后订单应为待发货').toContainText('待发货')

    // 服务端复核
    const at = await adminToken(request)
    const res = await request.get(`${AUTO_ADMIN_ORDERS}?page=1&size=10&orderSn=${order.orderSn}`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    const saved = (await res.json()).data.records.find((o) => o.id === order.id)
    expect(saved.status, '审核通过后服务端状态应为待发货(8)').toBe(8)
    expect(saved.splitStatus, '审核通过后应标记已拆单').toBe(1)
  })

  test('买家在「我的订单」确认收货，订单状态变为已完成', async ({ page, request }) => {
    // 准备：一个已发货订单（当前没有发货接口，直接用管理端改状态接口构造）
    const username = `e2e_confirm_${stamp()}`
    const member = await registerMemberApi(request, username)
    const { skuId } = await prepareSku(request)
    const order = await createOrderApi(request, member.token, { skuId })
    await payOrderApi(request, member.token, order.id)
    const at = await adminToken(request)
    await auditPassApi(request, at, order.id)   // 待审核 → 待发货 + 拆单
    const deliveries = await listDeliveriesApi(request, at, order.id)
    expect(deliveries.length, '单仓订单也应生成 1 个发货单').toBe(1)
    // 推进到已发货：下发 + WMS 出库（父订单在全部子单出库后自动流转）
    await dispatchDeliveryApi(request, at, deliveries[0].id)
    await wmsCallbackApi(request, at, deliveries[0].id, 'outbound')

    await memberLogin(page, username)
    await page.waitForTimeout(2500)
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1200)

    const row = page.locator('.el-table__body tr').filter({ hasText: order.orderSn }).first()
    await expect(row).toBeVisible()
    await expect(row).toContainText('已发货')

    // 确认收货 → 已完成
    await row.getByRole('button', { name: /确认收货/ }).click()
    await page.waitForTimeout(1500)
    await expect(row, '确认收货后订单状态应为已完成').toContainText('已完成')

    // 服务端复核
    const res = await request.get(`${API}/portal/orders/${order.id}`, {
      headers: { Authorization: `Bearer ${member.token}` }
    })
    expect((await res.json()).data.order.status, '服务端订单状态应为已完成(3)').toBe(3)
  })
})

const AUTO_ADMIN_ORDERS = `${process.env.API_BASE || 'http://127.0.0.1:8081'}/admin/orders`
