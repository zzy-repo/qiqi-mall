import { test, expect } from '@playwright/test'
import {
  PORTAL,
  API,
  adminLogin,
  adminTokenOf,
  adminSetSkuStock,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder,
  memberOrderRow,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  listDeliveriesApi,
  stamp
} from './helpers.js'

const SPEC_BLACK_256 = '颜色:黑,内存:256G'   // 种子数据 SKU1

/**
 * 场景3 订单履约（docs/flows/5-场景3-订单履约.mmd）
 *   买家下单支付 → 管理员「订单列表」审核通过 → 订单详情抽屉里「下发」+「模拟WMS回传·出库」
 *   → 订单聚合为已发货 → 买家「我的订单」点确认收货 → 订单已完成
 *
 * 全流程真实 UI 点击驱动；接口只用于只读断言（GET）。
 */

/** 买家侧真实下单并支付：注册 → 加购 → 结算提交（提交后自动模拟支付） */
async function buyAsNewMember(page, username) {
  await memberRegisterAndLogin(page, username)
  await addSpecToCart(page, 1, SPEC_BLACK_256)
  const orderSn = await submitCartOrder(page)
  await expect(
    page.locator('.el-table__body tr').filter({ hasText: orderSn }).first(),
    '支付后订单应进入待审核'
  ).toContainText('待审核')
  return orderSn
}

/** 管理员侧真实审核通过：订单列表 → 「审核」→ 确认框「通过」 */
async function auditPassByUi(page, orderSn) {
  const row = await adminSearchOrderRow(page, orderSn)
  await expect(row, '提交后订单应为待审核').toContainText('待审核')
  await row.getByRole('button', { name: '审核' }).click()
  // 注意：'不通过' 也含 '通过' 二字，必须用精确文本
  await page.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
  await page.waitForTimeout(2000)
  await expect(row, '审核通过后订单应为待发货').toContainText('待发货')
  return row
}

/** 订单详情抽屉里真实点击「下发」+「模拟WMS回传·出库」，返回发货单区块表格 */
async function dispatchAndOutboundByUi(page, orderSn) {
  const deliveryTable = await openOrderDrawer(page, orderSn)
  await expect(deliveryTable.locator('.el-table__body tr'), '单仓订单应生成 1 个发货单').toHaveCount(1)
  const deliveryRow = deliveryTable.locator('.el-table__body tr').first()
  await clickDispatch(page, deliveryRow)
  await clickWmsCallback(page, deliveryRow, '出库', '已出库')
  return { deliveryTable, deliveryRow }
}

/** 只读：按订单号取订单（断言用） */
async function getOrderBySn(request, token, orderSn) {
  const res = await request.get(`${API}/admin/orders?page=1&size=10&orderSn=${orderSn}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return (await res.json()).data.records.find((o) => o.orderSn === orderSn)
}

test.describe('场景3 订单履约', () => {
  test('管理员在订单列表发货，订单状态变为已发货', async ({ page, request }) => {
    const username = `e2e_ship_${stamp()}`

    // 数据准备（UI）：把商品1 第一个 SKU 库存补到 50，保证可重复运行
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    const at = await adminTokenOf(page)

    // 买家真实下单并支付
    const orderSn = await buyAsNewMember(page, username)
    const order = await getOrderBySn(request, at, orderSn)
    expect(order.status, '支付后服务端状态应为待审核(7)').toBe(7)

    // 管理员审核通过（自动拆单）
    const row = await auditPassByUi(page, orderSn)
    const saved = await getOrderBySn(request, at, orderSn)
    expect(saved.status, '审核通过后服务端状态应为待发货(8)').toBe(8)
    expect(saved.splitStatus, '审核通过后应标记已拆单').toBe(1)

    // 发货单区块：真实点击「下发」→「模拟WMS回传·出库」
    const { deliveryTable } = await dispatchAndOutboundByUi(page, orderSn)
    // 出库回传会写运单号
    await expect(deliveryTable, '出库后应生成运单号').toContainText(/SF\d{6,}/)

    // 全部子单出库 → 父订单聚合为已发货
    await expect(row, '全部子单出库后订单应为已发货').toContainText('已发货')

    // 服务端复核（只读）
    const deliveries = await listDeliveriesApi(request, at, saved.id)
    expect(deliveries.length, '单仓订单应只有 1 个发货单').toBe(1)
    expect(deliveries[0].status, '发货单状态应为已出库(3)').toBe(3)
    expect(deliveries[0].trackNo, '出库后发货单应有运单号').toBeTruthy()
    expect((await getOrderBySn(request, at, orderSn)).status,
      '服务端订单状态应为已发货(2)').toBe(2)
  })

  test('买家在「我的订单」确认收货，订单状态变为已完成', async ({ page, request }) => {
    const username = `e2e_confirm_${stamp()}`

    // 数据准备（UI）：补库存
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    const at = await adminTokenOf(page)

    // 买家下单支付 → 管理员审核通过 → 抽屉里下发+出库（全部真实点击）
    const orderSn = await buyAsNewMember(page, username)
    const order = await getOrderBySn(request, at, orderSn)
    const adminRow = await auditPassByUi(page, orderSn)
    await dispatchAndOutboundByUi(page, orderSn)
    await expect(adminRow, '全部子单出库后订单应为已发货').toContainText('已发货')

    // 服务端复核（只读）：父订单在全部子单出库后自动流转
    const deliveries = await listDeliveriesApi(request, at, order.id)
    expect(deliveries.length, '单仓订单也应生成 1 个发货单').toBe(1)
    expect(deliveries[0].status, '发货单应为已出库(3)').toBe(3)

    // 买家：我的订单 → 确认收货
    const row = await memberOrderRow(page, orderSn)
    await expect(row, '发货后买家侧应显示已发货').toContainText('已发货')
    // 买家令牌（只读断言用，从已登录的 portal 页面取）
    const token = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))

    // 确认收货 → 已完成
    await row.getByRole('button', { name: /确认收货/ }).click()
    await page.waitForTimeout(1500)
    await expect(row, '确认收货后订单状态应为已完成').toContainText('已完成')

    // 服务端复核（只读）
    const res = await request.get(`${API}/portal/orders/${order.id}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect((await res.json()).data.order.status, '服务端订单状态应为已完成(3)').toBe(3)

    // 顺带确认：买家「我的订单」页此刻仍在已完成状态（防止状态被缓存）
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1000)
    await expect(
      page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
    ).toContainText('已完成')
  })
})
