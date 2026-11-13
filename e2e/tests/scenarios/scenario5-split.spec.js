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
  deliveryRowOf,
  clickDispatch,
  clickWmsCallback
} from '../support/ui.js'
import { confirmDanger } from '../support/danger-ui.js'
import {
  adminTokenOf,
  getProductApi,
  getAdminOrderBySnApi,
  listDeliveriesApi
} from '../support/assert.js'

/**
 * 场景5 拆单履约（docs/flows/22-拆单时序图 + 23-发货单状态图 + FIX-oms-split Commit 4）
 *   买家下单两件跨仓商品 → 管理员审核通过 → 断言自动拆成 2 个发货单（各自正确仓库）
 *   → 抽屉里逐个「下发」+「模拟WMS回传·出库」 → 部分出库=部分发货，全部出库=已发货
 *   → 买家看到 2 个包裹 + 运单号 → 逐个「揽收」「签收」→ 订单已完成
 *
 * 全流程真实 UI 点击驱动；接口只用于只读断言（GET）。
 */
test.describe('场景5 拆单履约', () => {
  test('跨仓订单自动拆成 2 个发货单，部分出库不驱动订单已发货，全部签收后完成', async ({ page, request }) => {
    const username = `e2e_split_${stamp()}`

    // ---------- 数据准备（UI）：两个 SKU 库存都补到 50 ----------
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)   // SKU1 深圳仓
    await adminSetSkuStock(page, 1, 1, 50)   // SKU2 杭州仓
    const at = await adminTokenOf(page)

    // ---------- 买家真实下单：两件跨仓商品一单 → 自动支付 ----------
    // 种子数据：商品1 的 SKU1 → 深圳仓、SKU2 → 杭州仓，一单两明细必然跨仓；
    // 规格文本从接口读，避免写死种子数据
    const { skus } = await getProductApi(request, 1)
    expect(skus.length, '商品1 至少有 2 个 SKU').toBeGreaterThanOrEqual(2)
    await memberRegisterAndLogin(page, username)
    await addSpecToCart(page, 1, skus[0].specText)
    await addSpecToCart(page, 1, skus[1].specText)
    await expect(page.locator('.el-table__body tr'), '购物车应有 2 个 SKU 明细').toHaveCount(2)
    const orderSn = await submitCartOrder(page, { receiverName: '拆单E2E' })
    const order = await getAdminOrderBySnApi(request, at, orderSn)
    expect(order.status, '支付后服务端状态应为待审核(7)').toBe(7)

    // ---------- 管理员审核通过（UI）→ 自动拆单 ----------
    const adminRow = await adminSearchOrderRow(page, orderSn)
    // 新 UI：行内「审核通过」→ 统一确认弹窗（先预演拆单方案，再确认执行）
    await adminRow.getByTestId('order-row-audit-pass').click()
    await confirmDanger(page, { willDo: '待审核→待发货' })
    await expect(adminRow, '订单应为待发货').toContainText('待发货')

    // 只读复核拆单结果
    const deliveries = await listDeliveriesApi(request, at, order.id)
    expect(deliveries.length, '跨两仓订单应拆成 2 个发货单').toBe(2)
    const warehouses = deliveries.map((d) => d.warehouseId).sort()
    expect(warehouses, '两个发货单应分别落在深圳仓(1)与杭州仓(2)').toEqual([1, 2])

    // ---------- 界面确认：订单详情里能看到 2 个发货单 ----------
    const table = await openOrderDrawer(page, orderSn)
    await expect(table.locator('.el-table__body tr'), '订单详情应展示 2 个子发货单').toHaveCount(2)
    await expect(table).toContainText('深圳仓')
    await expect(table).toContainText('杭州仓')
    await page.screenshot({ path: 'report/shots/scenario5-deliveries.png' })

    // ---------- 第一个子单（深圳仓）：下发 + 出库 → 订单进入「部分发货」 ----------
    const szRow = deliveryRowOf(table, '深圳仓')
    await clickDispatch(page, szRow)
    await clickWmsCallback(page, szRow, '出库', '已出库')
    await expect(adminRow, '部分出库时订单应为部分发货').toContainText('部分发货')
    expect((await getAdminOrderBySnApi(request, at, orderSn)).status, '部分出库时订单应为部分发货(9)').toBe(9)

    // ---------- 第二个子单（杭州仓）：下发 + 出库 → 订单才变「已发货」 ----------
    const hzRow = deliveryRowOf(table, '杭州仓')
    await clickDispatch(page, hzRow)
    await clickWmsCallback(page, hzRow, '出库', '已出库')
    await expect(adminRow, '全部出库后订单才变已发货').toContainText('已发货')
    expect((await getAdminOrderBySnApi(request, at, orderSn)).status, '全部出库后订单才变已发货(2)').toBe(2)
    // 出库后每个包裹都有运单号
    await expect(table, '两个包裹都应有运单号').toContainText(/SF\d{6,}/)

    // ---------- 买家看到 2 个包裹 + 运单号 ----------
    const buyerRow = await memberOrderRow(page, orderSn)
    await expect(buyerRow, '买家侧订单应为已发货').toContainText('已发货')
    await buyerRow.getByRole('button', { name: '包裹' }).click()
    await page.waitForTimeout(1500)
    const parcelRows = page.locator('.el-drawer .el-table__body tr')
    await expect(parcelRows, '买家应看到 2 个包裹').toHaveCount(2)
    await expect(page.locator('.el-drawer')).toContainText('深圳仓')
    await expect(page.locator('.el-drawer')).toContainText('杭州仓')
    await expect(page.locator('.el-drawer'), '买家包裹应显示运单号').toContainText(/SF\d{6,}/)
    await page.screenshot({ path: 'report/shots/scenario5-parcels.png' })

    // ---------- 全部揽收 + 签收（UI）→ 订单已完成 ----------
    await adminSearchOrderRow(page, orderSn)
    const table2 = await openOrderDrawer(page, orderSn)
    await expect(table2.locator('.el-table__body tr'), '回来后仍应是 2 个发货单').toHaveCount(2)
    for (const wh of ['深圳仓', '杭州仓']) {
      const r = deliveryRowOf(table2, wh)
      await clickWmsCallback(page, r, '揽收', '运输中')
      await clickWmsCallback(page, r, '签收', '已签收')
    }
    await expect(table2.locator('.el-table__body tr'), '两个发货单都应为已签收').toHaveCount(2)
    expect((await getAdminOrderBySnApi(request, at, orderSn)).status, '全部签收后订单应已完成(3)').toBe(3)

    // 买家侧也应看到已完成
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1200)
    await expect(
      page.locator('.el-table__body tr').filter({ hasText: orderSn }).first(),
      '买家订单应显示已完成'
    ).toContainText('已完成')
  })
})
