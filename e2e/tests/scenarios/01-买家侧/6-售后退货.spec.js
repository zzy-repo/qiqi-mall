import { test, expect } from '../../support/test.js'
import { PORTAL, ADMIN, stamp } from '../../support/env.js'
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
import {
  getProductApi,
  getAdminOrderBySnApi,
  listDeliveriesApi,
  getMemberReturnsApi,
  adminTokenOf,
  memberTokenOf,
  // 发货单状态 + 父订单聚合口径：共享实现（见 assert.js），不要在 spec 里再复制一份
  DELIVERY_OUTBOUND,
  DELIVERY_CANCELLED,
  ORDER_STATUS_TEXT,
  deliveryStatusText,
  aggregateOrderStatusOf
} from '../../support/assert.js'
import { tid } from '../../support/tid.js'
import { memberNickOf, receiverOf, returnDescriptionOf } from '../../support/fixtures.js'
import { demoName, demoUsername } from '../../support/images.js'
import { withBackstage } from '../../support/backstage.js'
import { confirmDanger } from '../../support/danger-ui.js'


/**
 * 买家侧 06 售后退货（docs/flows/02-业务场景/01-买家侧/6-售后退货）
 *
 * 覆盖：申请退货（选原因 + 说明）/ 申请后订单进入退款中、入口消失（不能重复申请）/
 *       仅已付款或已发货可申请（待审核订单无入口）/ 管理员审核通过（退款 + 库存回补）/
 *       管理员审核拒绝（订单由包裹聚合推导，不再硬写已付款）/ 买家侧能看到售后状态
 * 断言依据：portal/order/index.vue（申请退货 v-if status 1|2、售后台来自 GET /portal/returns）、
 *          PortalReturnController 守卫、后台 return-apply 审核
 */

// 发货单（包裹）状态与「父订单聚合」口径见 support/assert.js（DELIVERY_* / aggregateOrderStatusOf），
// 买家侧 6-售后退货 与 商家侧 4-退货处理 共用同一份实现。

/**
 * 后台准备：登录 + 把商品1 第一个 SKU 库存补回 50（幕布上下文，不进买家视角的录像）。
 * @returns 管理端令牌（需要在 admin 域里读，只能在幕布里顺手带出来）
 */
async function prepareStock(browser) {
  return withBackstage(browser, async (admin) => {
    await adminLogin(admin)
    await adminSetSkuStock(admin, 1, 0, 50)
    return adminTokenOf(admin)
  })
}

/**
 * 在幕布里推进后台状态，同时让买家页不断刷新列表 —— 画面里能看到状态自己变过去，
 * 既没有后台画面，也不会留下十几秒静止。
 * @param doneText 买家侧列表里出现这个文案就算推进完成
 */
async function backstageUntil(browser, buyerPage, orderSn, doneText, run) {
  const working = withBackstage(browser, run)
  let done = false
  for (let i = 0; i < 24 && !done; i++) {
    await buyerPage.reload()
    await buyerPage.waitForTimeout(1200)
    done = (await buyerPage.locator('.el-table__body tr').first().innerText()).includes(doneText)
  }
  await working
  return done
}

/** 幕布里把订单推到「已发货」（管理员审核 + 下发 + 出库，真实 UI） */
async function shipInBackstage(browser, buyerPage, orderSn) {
  return backstageUntil(browser, buyerPage, orderSn, '已发货', async (admin) => {
    await adminLogin(admin)
    await toShipped(admin, orderSn)
  })
}

/** 幕布里审核退货申请（通过 / 拒绝），等买家侧列表出现对应结果 */
async function auditReturnInBackstage(browser, buyerPage, orderSn, approve) {
  const doneText = approve ? '退款成功' : '退货被拒绝'
  return backstageUntil(browser, buyerPage, orderSn, doneText, async (admin) => {
    await auditReturn(admin, orderSn, approve)
  })
}

/** 买家把订单推进到「已发货」（管理员审核 + 下发 + 出库，真实 UI） */
async function toShipped(page, orderSn) {
  const row = await adminSearchOrderRow(page, orderSn)
  // 新 UI：行内「审核通过」→ 统一确认弹窗 → 确认执行
  await row.getByTestId('order-row-audit-pass').click()
  await confirmDanger(page, { willDo: '待审核→待发货' })
  const table = await openOrderDrawer(page, orderSn)
  const dRow = table.locator('.el-table__body tr').first()
  await clickDispatch(page, dRow)
  await clickWmsCallback(page, dRow, '出库', '已出库')
  await expect(row, '出库后订单应为已发货').toContainText('已发货')
}

/** 买家在「我的订单」提交退货申请 */
async function applyReturn(page, orderSn) {
  const row = await memberOrderRow(page, orderSn)
  await row.getByTestId('orders-row-return').click()
  const dialog = tid(page, 'order-return-dialog')
  await expect(dialog).toBeVisible()
  await tid(page, 'order-return-form-reason').click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').first().click()
  await tid(page, 'order-return-form-description').fill(returnDescriptionOf(orderSn))
  await tid(page, 'order-return-form-submit').click()
  await expect(page.locator('.el-message--success').first(), '退货申请应提交成功').toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(1200)
  return row
}

/** 管理员在「退货申请」页审核（通过=1 / 拒绝=2） */
async function auditReturn(page, orderSn, approve) {
  await adminLogin(page)
  await page.goto(`${ADMIN}/return-apply`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, '后台应看到该退货申请').toBeVisible()
  await row.getByTestId('return-apply-row-audit').click()
  const dialog = tid(page, 'return-apply-form-dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByTestId(approve ? 'return-apply-form-status-pass' : 'return-apply-form-status-reject').click()
  await tid(page, 'return-apply-form-submit').click()
  // 新 UI：提交后还要过统一确认弹窗（预演说清退款金额 / 回补哪些库存）
  await confirmDanger(page, { willDo: approve ? '审核为「通过」' : '审核为「拒绝」' })
  await expect(page.locator('.el-message--success').first(), '审核应保存成功').toBeVisible({ timeout: 15_000 })
  await page.reload()
  await page.waitForTimeout(1200)
  await expect(page.locator('.el-table__body tr').filter({ hasText: orderSn }).first())
    .toContainText(approve ? '已通过' : '已拒绝')
}

test.describe('买家侧 06 售后退货', () => {
  test('申请退货：选原因提交后进入退款中，入口消失（不能重复申请）', async ({ page, request, browser }) => {
    const username = demoUsername('张小雨') || `e2e_return_${stamp()}`
    // 后台补库存/审核发货都在幕布里点，买家视角的片子里不出现后台画面
    const prep = prepareStock(browser)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    await prep
    const orderSn = await submitCartOrder(page, receiverOf(username))
    await shipInBackstage(browser, page, orderSn)

    const row = await applyReturn(page, orderSn)
    await expect(row, '申请后售后列应显示退款处理中').toContainText('退款处理中', { timeout: 15_000 })
    await expect(row.getByTestId('orders-row-return'), '退款中订单不应再能申请').toHaveCount(0)

    // 服务端：申请为待处理(0)，订单进入退款中(5)
    const token = await memberTokenOf(page)
    const returns = await getMemberReturnsApi(request, token)
    expect(returns.length).toBe(1)
    expect(returns[0].status, '新申请应为待处理(0)').toBe(0)
    expect(returns[0].returnReason, '应记录所选原因').toBeTruthy()
  })

  test('仅已付款/已发货可申请：待审核订单没有申请入口', async ({ page, request }) => {
    const username = demoUsername('李思远') || `e2e_return_guard_${stamp()}`
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    const orderSn = await submitCartOrder(page, receiverOf(username))

    const row = await memberOrderRow(page, orderSn)
    await expect(row, '新订单应为待审核').toContainText('待审核')
    await expect(row.getByTestId('orders-row-return'), '待审核订单不应有申请退货入口').toHaveCount(0)
  })

  test('管理员审核通过：退款成功 + 库存回补，买家看到结果', async ({ page, request, browser }) => {
    const username = demoUsername('陈可') || `e2e_return_pass_${stamp()}`
    const prep = prepareStock(browser)          // 幕布里补库存，令牌顺手带出来
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    const sku = skus[0]
    await addSpecToCart(page, 1, sku.specText)
    const at = await prep
    const orderSn = await submitCartOrder(page, receiverOf(username))
    await shipInBackstage(browser, page, orderSn)

    const stockBefore = (await getProductApi(request, 1)).skus.find((s) => s.id === sku.id).stock
    await applyReturn(page, orderSn)
    await auditReturnInBackstage(browser, page, orderSn, true)

    // 库存回补（sku.stock 是缓存：轮询等它收敛，legacy 下第一次即命中）
    await expect.poll(async () => (await getProductApi(request, 1)).skus.find((s) => s.id === sku.id).stock,
      { timeout: 2_000, intervals: [50, 100, 200, 400], message: '审核通过后库存应回补 1 件' }
    ).toBe(stockBefore + 1)

    // 买家侧看到售后结果 + 订单已退款
    const row = await memberOrderRow(page, orderSn)
    await expect(row, '买家侧应显示退款成功').toContainText('退款成功', { timeout: 15_000 })
    expect((await getAdminOrderBySnApi(request, at, orderSn)).status, '订单应为已退款(6)').toBe(6)

    const token = await memberTokenOf(page)
    expect((await getMemberReturnsApi(request, token))[0].status, '申请应为已通过(1)').toBe(1)
  })

  test('管理员审核拒绝：订单由包裹聚合回到已发货，买家看到退货被拒绝', async ({ page, request, browser }) => {
    const username = demoUsername('王建国') || `e2e_return_reject_${stamp()}`
    const prep = prepareStock(browser)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))

    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText)
    const at = await prep
    const orderSn = await submitCartOrder(page, receiverOf(username))
    await shipInBackstage(browser, page, orderSn)     // 走到这步订单已有包裹「已出库」
    await applyReturn(page, orderSn)
    await auditReturnInBackstage(browser, page, orderSn, false)

    // ① 拒绝后订单状态 = 该订单所有「未取消包裹」聚合推导出来的状态（口径②：子单是事实源）
    //    后端 ReturnApplyController 拒绝分支：有未取消包裹 → recomputeOrderStatusAfterReturnReject（交聚合）；
    //    只有直发单（一张包裹都没有）才硬写 1 已付款。本用例前置调了 shipInBackstage（包裹已出库），
    //    所以正确结果就是聚合得出的 2 已发货 —— 断言跟着代码走，不写死旧值 1。
    const orderAfterReject = await getAdminOrderBySnApi(request, at, orderSn)
    const deliveries = await listDeliveriesApi(request, at, orderAfterReject.id)
    const active = deliveries.filter((d) => d.status !== DELIVERY_CANCELLED)
    const expectedStatus = aggregateOrderStatusOf(active)
    console.log(
      `[6-售后退货/拒绝] 订单 ${orderSn}(id=${orderAfterReject.id}) status=${orderAfterReject.status}` +
      `(${ORDER_STATUS_TEXT[orderAfterReject.status]})；包裹=${deliveries.length} 张` +
      `[${deliveries.map((d) => `${d.deliverySn}:${deliveryStatusText(d.status)}`).join(', ')}]` +
      `；未取消=${active.length} 张 → 聚合期望 status=${expectedStatus}(${ORDER_STATUS_TEXT[expectedStatus]})`
    )
    expect(active.length, '前置 shipInBackstage 应留下未取消的包裹（否则本用例退化成直发路径）').toBeGreaterThan(0)
    expect(
      orderAfterReject.status,
      `拒绝后订单状态应由包裹聚合推导：未取消包裹 [${active.map((d) => deliveryStatusText(d.status)).join(', ')}]` +
      ` → 期望 ${expectedStatus}(${ORDER_STATUS_TEXT[expectedStatus]})，实际 ${orderAfterReject.status}` +
      `(${ORDER_STATUS_TEXT[orderAfterReject.status]})`
    ).toBe(expectedStatus)

    // ② 分叉断言（本次改动的核心价值）：绝不允许「订单 = 1 已付款、却挂着已出库 / 已签收的包裹」
    //    旧实现拒绝时无条件硬写 1，就造出了这种分叉：父单说没发货，包裹事实说出过库了；
    //    之后任何一次包裹回传按子单重新聚合，又会把订单从 1 改回 2/9（真实的状态倒退序列）。
    //    这里从「两侧事实」直接对撞，而不是只比一个数字：只要包裹出过库，订单就必不可能是 1。
    const outboundOrSigned = active.filter((d) => d.status >= DELIVERY_OUTBOUND)
    expect(outboundOrSigned.length, '前置 shipInBackstage 应让包裹到「已出库」，分叉断言才有意义').toBeGreaterThan(0)
    expect(
      orderAfterReject.status,
      `分叉：包裹已出库/已签收（${outboundOrSigned.map((d) => `${d.deliverySn}:${deliveryStatusText(d.status)}`).join(', ')}）时，`
      + `订单不能是「已付款(1)」，也不该卡在「退款中(5)」，实际 ${orderAfterReject.status}(${ORDER_STATUS_TEXT[orderAfterReject.status]})`
    ).not.toBe(1)
    expect(orderAfterReject.status, '退货审核已拒绝，订单不应仍停在「退款中(5)」').not.toBe(5)

    // 买家侧看到的订单状态必须与服务端聚合结果一致（同一口径，不能各说各话）
    const row = await memberOrderRow(page, orderSn)
    await expect(row, '买家侧应显示退货被拒绝').toContainText('退货被拒绝', { timeout: 15_000 })
    await expect(
      row,
      `买家侧订单状态应与聚合结果一致（期望 ${ORDER_STATUS_TEXT[expectedStatus]}）`
    ).toContainText(ORDER_STATUS_TEXT[expectedStatus])

    const token = await memberTokenOf(page)
    expect((await getMemberReturnsApi(request, token))[0].status, '申请应为已拒绝(2)').toBe(2)
  })
})
