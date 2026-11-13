import { test, expect } from '../support/test.js'
import { PORTAL, stamp } from '../support/env.js'
import {
  adminLogin,
  adminSetSkuStock,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder,
  memberOrderRow,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback
} from '../support/ui.js'
import { confirmDanger } from '../support/danger-ui.js'
import {
  adminTokenOf,
  memberTokenOf,
  getProductApi,
  getAdminOrderBySnApi,
  listDeliveriesApi,
  getMemberOrderApi
} from '../support/assert.js'

/** 商品1 第一个 SKU 的规格文本（种子数据可能换，这里从接口读，不写死） */
async function firstSpecOf(request, productId = 1) {
  const { skus } = await getProductApi(request, productId)
  expect(skus.length, '商品1 应有 SKU').toBeGreaterThan(0)
  return skus[0].specText
}

/**
 * 场景3 订单履约（docs/flows/5-场景3-订单履约.mmd）
 *   买家下单支付 → 管理员「订单列表」审核通过 → 订单详情抽屉里「下发」+「模拟WMS回传·出库」
 *   → 订单聚合为已发货 → 买家「我的订单」点确认收货 → 订单已完成
 *
 * 全流程真实 UI 点击驱动；接口只用于只读断言（GET）。
 */

/** 买家侧真实下单并支付：注册 → 加购 → 结算提交（提交后自动模拟支付） */
async function buyAsNewMember(page, username, specText) {
  await memberRegisterAndLogin(page, username)
  await addSpecToCart(page, 1, specText)
  const orderSn = await submitCartOrder(page)
  await expect(
    page.locator('.el-table__body tr').filter({ hasText: orderSn }).first(),
    '支付后订单应进入待审核'
  ).toContainText('待审核')
  return orderSn
}

/**
 * 管理员侧真实审核通过：订单列表 → 行内「审核通过」→ 预演确认弹窗 →「确认执行」。
 * 新 UI：原来的「审核」+ ElMessageBox（通过/不通过二选一）已拆成两个一级行按钮，
 * 点下去直接进统一确认弹窗，弹窗里先说明「待审核→待发货」。
 */
async function auditPassByUi(page, orderSn) {
  const row = await adminSearchOrderRow(page, orderSn)
  await expect(row, '提交后订单应为待审核').toContainText('待审核')
  await row.getByTestId('order-row-audit-pass').click()
  await confirmDanger(page, { willDo: '待审核→待发货' })
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

test.describe('场景3 订单履约', () => {
  test('管理员在订单列表发货，订单状态变为已发货', async ({ page, request }) => {
    const username = `e2e_ship_${stamp()}`

    // 数据准备（UI）：把商品1 第一个 SKU 库存补到 50，保证可重复运行
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    const at = await adminTokenOf(page)

    // 买家真实下单并支付
    const specText = await firstSpecOf(request)
    const orderSn = await buyAsNewMember(page, username, specText)
    const order = await getAdminOrderBySnApi(request, at, orderSn)
    expect(order.status, '支付后服务端状态应为待审核(7)').toBe(7)

    // 管理员审核通过（自动拆单）
    const row = await auditPassByUi(page, orderSn)
    const saved = await getAdminOrderBySnApi(request, at, orderSn)
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
    expect((await getAdminOrderBySnApi(request, at, orderSn)).status,
      '服务端订单状态应为已发货(2)').toBe(2)
  })

  test('买家在「我的订单」确认收货，订单状态变为已完成', async ({ page, request }) => {
    const username = `e2e_confirm_${stamp()}`

    // 数据准备（UI）：补库存
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    const at = await adminTokenOf(page)

    // 买家下单支付 → 管理员审核通过 → 抽屉里下发+出库（全部真实点击）
    const specText = await firstSpecOf(request)
    const orderSn = await buyAsNewMember(page, username, specText)
    const order = await getAdminOrderBySnApi(request, at, orderSn)
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
    const token = await memberTokenOf(page)

    // 确认收货 → 已完成
    // 确认收货是不可逆的终态流转（2→3，没有回退入口），portal-web 在 2026-11-12 起先弹
    // ElMessageBox 二次确认，**真正发请求的是弹框里的「确定」**——只点行内按钮不会有任何请求。
    // 所以这里（比原来更严）连「必须先弹确认框」一起断言，再点确定，然后等状态真翻到已完成。
    await row.getByRole('button', { name: /确认收货/ }).click()
    const confirmBox = page.locator('.el-message-box')
    await expect(confirmBox, '确认收货应先弹二次确认框（不可逆流转）').toBeVisible()
    await confirmBox.getByRole('button', { name: '确定' }).click()
    await expect(row, '确认收货后订单状态应为已完成').toContainText('已完成', { timeout: 15_000 })

    // 服务端复核（只读）
    const after = await getMemberOrderApi(request, token, order.id)
    expect(after.order.status, '服务端订单状态应为已完成(3)').toBe(3)

    // 顺带确认：买家「我的订单」页此刻仍在已完成状态（防止状态被缓存）
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1000)
    await expect(
      page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
    ).toContainText('已完成')
  })
})
