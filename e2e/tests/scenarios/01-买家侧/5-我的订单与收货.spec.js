import { test, expect } from '@playwright/test'
import { PORTAL, API, stamp } from '../../support/env.js'
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
} from '../../support/ui.js'
import { getProductApi, getAdminOrderBySnApi, adminTokenOf, memberTokenOf, getMemberOrdersApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { memberNickOf, receiverOf } from '../../support/fixtures.js'
import { demoUsername } from '../../support/images.js'


/**
 * 买家侧 05 我的订单与收货（docs/flows/02-业务场景/01-买家侧/5-我的订单与收货）
 *
 * 覆盖：订单列表与订单详情弹窗 / 各状态展示 / 包裹进度（发货单 + 运单号）/
 *       确认收货（守卫：仅已发货 + 本人订单）/ 重复确认收货（完成后入口消失、状态不回退）/
 *       越权：列表只含自己的订单，看不到别人的订单号
 * 断言依据：portal/order/index.vue（确认收货按钮 v-if status===2）、OrderController.confirm（守卫 status=2 且本人）
 */

/** 把订单推进到「已发货」：管理员审核 → 抽屉下发 → 出库（真实 UI） */
async function shipOrderByUi(page, orderSn) {
  const row = await adminSearchOrderRow(page, orderSn)
  await row.getByTestId('order-row-audit').click()
  await page.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
  await page.waitForTimeout(1500)
  await expect(row, '审核通过后应为待发货').toContainText('待发货')
  const table = await openOrderDrawer(page, orderSn)
  const dRow = table.locator('.el-table__body tr').first()
  await clickDispatch(page, dRow)
  await clickWmsCallback(page, dRow, '出库', '已出库')
  await expect(row, '出库后订单应为已发货').toContainText('已发货')
}

test.describe('买家侧 05 我的订单与收货', () => {
  test('订单列表 + 详情弹窗 + 包裹进度（含运单号）', async ({ page, request }) => {
    const username = demoUsername('张小雨') || `e2e_orders_${stamp()}`
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    const buyer = receiverOf(username)
    const orderSn = await submitCartOrder(page, buyer)
    await shipOrderByUi(page, orderSn)

    // 列表：状态为已发货
    const row = await memberOrderRow(page, orderSn)
    await expect(row).toContainText('已发货')

    // 详情弹窗：订单号、收货人、商品明细都在
    await row.getByTestId('orders-row-detail').click()
    const dialog = tid(page, 'order-detail-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText(orderSn)
    await expect(dialog).toContainText(buyer.receiverName)
    await expect(dialog.locator('.el-table__body tr'), '详情里应有商品明细').toHaveCount(1)
    await page.locator('.el-dialog__headerbtn').first().click()
    await page.waitForTimeout(500)

    // 包裹抽屉：发货单号 + 仓库 + 运单号
    await row.getByTestId('orders-row-parcels').click()
    const drawer = tid(page, 'parcel-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('.el-table__body tr'), '应看到 1 个包裹').toHaveCount(1)
    await expect(drawer, '包裹应显示运单号').toContainText(/SF\d{6,}/)
    await expect(drawer).toContainText('已出库')
  })

  test('确认收货：已发货订单 → 已完成，且入口随之消失、状态不回退', async ({ page, request }) => {
    const username = demoUsername('李思远') || `e2e_confirm_${stamp()}`
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    // 令牌必须在还停留在该端页面时取：admin 端令牌在 admin 域，买家端令牌在 portal 域
    const at = await adminTokenOf(page)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    const buyer = receiverOf(username)
    const orderSn = await submitCartOrder(page, buyer)
    await shipOrderByUi(page, orderSn)

    const row = await memberOrderRow(page, orderSn)
    await expect(row).toContainText('已发货')
    await row.getByTestId('orders-row-confirm').click()
    await expect(row, '确认收货后应为已完成').toContainText('已完成', { timeout: 15_000 })

    // 入口消失（只有已发货订单才有确认收货按钮）
    await expect(row.getByTestId('orders-row-confirm'), '完成后不应再有确认收货入口').toHaveCount(0)

    // 服务端守卫：重复确认收货被拒（状态已是 3，非 2）
    const token = await memberTokenOf(page)
    const orderId = (await getMemberOrdersApi(request, token)).records[0].id
    const repeat = await request.post(`${API}/portal/orders/${orderId}/confirm`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect((await repeat.json()).code, '重复确认收货应被服务端拒绝').not.toBe(0)

    const order = await getAdminOrderBySnApi(request, at, orderSn)
    expect(order.status, '订单应停在已完成(3)').toBe(3)
  })

  test('越权：买家只看得到自己的订单', async ({ page, request }) => {
    // A 账号下一单并推进到已发货
    const userA = demoUsername('陈可') || `e2e_own_a_${stamp()}`
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    await memberRegisterAndLogin(page, userA, '123456', memberNickOf(userA))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    const orderA = await submitCartOrder(page, receiverOf(userA))

    // B 账号登录：列表里不该出现 A 的订单
    const userB = demoUsername('王建国') || `e2e_own_b_${stamp()}`
    await memberRegisterAndLogin(page, userB, '123456', memberNickOf(userB))
    const res = await getMemberOrdersApi(request, await memberTokenOf(page))
    expect(res.records.length, 'B 账号不应看到任何订单').toBe(0)
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1000)
    await expect(tid(page, 'order-list-table'), 'B 的订单列表不应包含 A 的订单号').not.toContainText(orderA)
  })
})
