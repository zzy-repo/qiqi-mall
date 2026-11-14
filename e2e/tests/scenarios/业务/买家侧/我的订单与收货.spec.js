import { test, expect } from '../../../support/test.js'
import { PORTAL, API, stamp } from '../../../support/env.js'
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
} from '../../../support/ui.js'
import { getProductApi, getAdminOrderBySnApi, adminTokenOf, memberTokenOf, getMemberOrdersApi } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import { memberNickOf, receiverOf } from '../../../support/fixtures.js'
import { demoUsername } from '../../../support/images.js'
import { withBackstage } from '../../../support/backstage.js'
import { confirmDanger } from '../../../support/danger-ui.js'


/**
 * 业务 / 买家侧 / 我的订单与收货（docs/flows/02-业务场景/业务/买家侧/我的订单与收货）
 *
 * 覆盖：订单列表与订单详情弹窗 / 各状态展示 / 包裹进度（发货单 + 运单号）/
 *       确认收货（守卫：仅已发货 + 本人订单）/ 重复确认收货（完成后入口消失、状态不回退）/
 *       越权：列表只含自己的订单，看不到别人的订单号
 * 断言依据：portal/order/index.vue（确认收货按钮 v-if status===2）、OrderController.confirm（守卫 status=2 且本人）
 */

/**
 * 后台准备：登录 + 把商品1 第一个 SKU 库存补回 50。
 * 买家侧片子里不该出现后台画面（审计 C 项），所以这些点击都放到幕布上下文里做。
 */
async function prepareStock(browser) {
  return withBackstage(browser, async (admin) => {
    await adminLogin(admin)
    await adminSetSkuStock(admin, 1, 0, 50)
    return admin
  })
}

/**
 * 把订单推进到「已发货」：管理员审核 → 抽屉下发 → 出库（真实 UI）。
 * 买家侧片子要纯买家视角，所以这段在幕布上下文里跑；同时买家页不断刷新订单列表，
 * 画面里能看到状态从「待审核」变成「已发货」，不会出现十几秒的静止空档。
 */
async function shipOrderInBackstage(browser, buyerPage, orderSn) {
  // 幕布是**另一个上下文**（另一份 localStorage），所以要先登录再操作
  const shipping = withBackstage(browser, async (admin) => {
    await adminLogin(admin)
    await shipOrderByUi(admin, orderSn)
  })
  let shipped = false
  for (let i = 0; i < 20 && !shipped; i++) {
    await buyerPage.reload()
    await buyerPage.waitForTimeout(1200)
    shipped = (await buyerPage.locator('.el-table__body tr').first().innerText()).includes('已发货')
  }
  await shipping          // 后台的断言/异常照旧往上报
  return shipped
}

/** 把订单推进到「已发货」：管理员审核 → 抽屉下发 → 出库（真实 UI） */
async function shipOrderByUi(page, orderSn) {
  const row = await adminSearchOrderRow(page, orderSn)
  // 新 UI：行内「审核通过」→ 统一确认弹窗（预演说明「待审核→待发货」）→ 确认执行
  await row.getByTestId('order-row-audit-pass').click()
  await confirmDanger(page, { willDo: '待审核→待发货' })
  await expect(row, '审核通过后应为待发货').toContainText('待发货')
  const table = await openOrderDrawer(page, orderSn)
  const dRow = table.locator('.el-table__body tr').first()
  await clickDispatch(page, dRow)
  await clickWmsCallback(page, dRow, '出库', '已出库')
  await expect(row, '出库后订单应为已发货').toContainText('已发货')
}

test.describe('业务 › 买家侧 › 我的订单与收货', () => {
  test('订单列表 + 详情弹窗 + 包裹进度（含运单号）', async ({ page, request, browser }) => {
    const username = demoUsername('张小雨') || `e2e_orders_${stamp()}`
    // 幕布里的后台准备与买家注册/加购并行，买家页面不空转（幕布点击不进录像）
    const prep = prepareStock(browser)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    await prep
    const buyer = receiverOf(username)
    const orderSn = await submitCartOrder(page, buyer)
    await shipOrderInBackstage(browser, page, orderSn)

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

  test('确认收货：已发货订单 → 已完成，且入口随之消失、状态不回退', async ({ page, request, browser }) => {
    const username = demoUsername('李思远') || `e2e_confirm_${stamp()}`
    // 令牌必须在该端页面还开着时取（admin 令牌在 admin 域，买家令牌在 portal 域）；
    // 幕布里的补库存与买家注册/加购并行，买家页面不空转。
    const prep = withBackstage(browser, async (admin) => {
      await adminLogin(admin)
      await adminSetSkuStock(admin, 1, 0, 50)
      return adminTokenOf(admin)
    })
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    const at = await prep
    const buyer = receiverOf(username)
    const orderSn = await submitCartOrder(page, buyer)
    await shipOrderInBackstage(browser, page, orderSn)

    const row = await memberOrderRow(page, orderSn)
    await expect(row).toContainText('已发货')
    // 确认收货是不可逆终态流转 → 点按钮先弹二次确认，真正发请求的是弹框里的「确定」
    await row.getByTestId('orders-row-confirm').click()
    const confirmBox = page.locator('.el-message-box')
    await expect(confirmBox, '点「确认收货」应先弹二次确认框').toBeVisible()
    await expect(confirmBox, '确认框必须说清后果：确认后订单完成、不可撤销')
      .toContainText('确认已收到货？确认后订单完成，不可撤销。')
    await confirmBox.getByRole('button', { name: '确定' }).click()
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

  test('越权：买家只看得到自己的订单', async ({ page, request, browser }) => {
    // A 账号下一单并推进到已发货
    const userA = demoUsername('陈可') || `e2e_own_a_${stamp()}`
    const prep = prepareStock(browser)
    await memberRegisterAndLogin(page, userA, '123456', memberNickOf(userA))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    await prep
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
