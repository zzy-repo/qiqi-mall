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
  shipOrderApi,
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

    const row = page.locator('.el-table__body tr').filter({ hasText: order.orderSn }).first()
    await expect(row, '管理员订单列表应能看到已付款订单').toBeVisible()
    await expect(row).toContainText('已付款')

    // 关键断言 1：行内应有「发货」操作
    await expect(
      row.getByRole('button', { name: /发货/ }),
      '【缺口】管理员订单列表缺少「发货」按钮，无法把已付款订单流转为已发货'
    ).toBeVisible({ timeout: 5000 })

    await row.getByRole('button', { name: /发货/ }).click()
    // 弹出发货确认框
    await page.locator('.el-message-box__btns').getByRole('button', { name: '确定' }).click()
    await page.waitForTimeout(1500)
    await expect(row, '发货后订单状态应为已发货').toContainText('已发货')

    // 服务端复核
    const at = await adminToken(request)
    const res = await request.get(`${AUTO_ADMIN_ORDERS}`, { headers: { Authorization: `Bearer ${at}` } })
    const list = (await res.json()).data
    const saved = list.find((o) => o.id === order.id)
    expect(saved.status, '服务端订单状态应为已发货(2)').toBe(2)
  })

  test('买家在「我的订单」确认收货，订单状态变为已完成', async ({ page, request }) => {
    // 准备：一个已发货订单（当前没有发货接口，直接用管理端改状态接口构造）
    const username = `e2e_confirm_${stamp()}`
    const member = await registerMemberApi(request, username)
    const { skuId } = await prepareSku(request)
    const order = await createOrderApi(request, member.token, { skuId })
    await payOrderApi(request, member.token, order.id)
    const at = await adminToken(request)
    await shipOrderApi(request, at, order.id) // 走发货接口 → 已发货

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
