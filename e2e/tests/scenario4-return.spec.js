import { test, expect } from '@playwright/test'
import {
  ADMIN,
  API,
  adminLogin,
  adminTokenOf,
  adminSetSkuStock,
  memberLogin,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder,
  memberOrderRow,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  stamp
} from './helpers.js'

const SPEC_BLACK_256 = '颜色:黑,内存:256G'   // 种子数据 SKU1

/** 只读：按订单号取订单（断言用） */
async function getOrderBySn(request, token, orderSn) {
  const res = await request.get(`${API}/admin/orders?page=1&size=10&orderSn=${orderSn}`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return (await res.json()).data.records.find((o) => o.orderSn === orderSn)
}

/**
 * 场景4 售后退货（docs/flows/6-场景4-售后退货.mmd）
 *   买家「我的订单」选已发货订单 → 选退货原因 + 填说明 → 提交退货申请
 *   → 管理员「退货申请」页审核（通过/拒绝）→ 结果回传买家 → 售后闭环
 *
 * 前置履约（下单支付→审核→下发→出库）也全部走真实 UI 点击；接口只用于只读断言。
 */
test.describe('场景4 售后退货', () => {
  test('买家提交退货申请 → 管理员审核通过 → 买家能看到审核结果', async ({ page, request }) => {
    const username = `e2e_return_${stamp()}`

    // ---------- 数据准备（UI）：补库存 ----------
    await adminLogin(page)
    await adminSetSkuStock(page, 1, 0, 50)
    const at = await adminTokenOf(page)

    // ---------- 买家真实下单并支付 ----------
    await memberRegisterAndLogin(page, username)
    await addSpecToCart(page, 1, SPEC_BLACK_256)
    const orderSn = await submitCartOrder(page)
    const order = await getOrderBySn(request, at, orderSn)
    expect(order.status, '支付后服务端状态应为待审核(7)').toBe(7)

    // ---------- 管理员审核通过 → 抽屉里「下发」+「出库」→ 订单已发货 ----------
    const adminRow = await adminSearchOrderRow(page, orderSn)
    await adminRow.getByRole('button', { name: '审核' }).click()
    await page.locator('.el-message-box__btns').getByRole('button', { name: '通过', exact: true }).click()
    await page.waitForTimeout(2000)
    await expect(adminRow, '审核通过后订单应为待发货').toContainText('待发货')

    const deliveryTable = await openOrderDrawer(page, orderSn)
    await expect(deliveryTable.locator('.el-table__body tr'), '单仓订单应生成 1 个发货单').toHaveCount(1)
    const deliveryRow = deliveryTable.locator('.el-table__body tr').first()
    await clickDispatch(page, deliveryRow)
    await clickWmsCallback(page, deliveryRow, '出库', '已出库')
    await expect(adminRow, '出库后订单应为已发货').toContainText('已发货')
    expect((await getOrderBySn(request, at, orderSn)).status, '服务端订单应为已发货(2)').toBe(2)

    // ---------- 1. 买家：我的订单 → 申请退货 ----------
    const row = await memberOrderRow(page, orderSn)
    await expect(row, '已发货订单应有申请退货入口').toContainText('已发货')
    const memberToken = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))
    await row.getByRole('button', { name: '申请退货' }).click()
    await page.waitForTimeout(800)

    // 选原因
    await page.locator('.el-dialog .el-form-item').filter({ hasText: '退货原因' }).locator('.el-select__wrapper').click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item:visible').first().click()
    await page.locator('.el-dialog .el-form-item').filter({ hasText: '详细说明' }).locator('textarea').fill('E2E 自动化：商品有划痕')
    await page.getByRole('button', { name: '提交申请' }).click()
    await page.waitForTimeout(1500)
    await expect(page.locator('.el-message--success').first(), '买家提交退货申请应成功').toBeVisible()

    // 服务端复核（只读）：申请已落库且为待处理
    const api = await request.get(`${API}/portal/returns`, {
      headers: { Authorization: `Bearer ${memberToken}` }
    })
    const returns = (await api.json()).data
    const apply = returns.find((r) => r.orderId === order.id)
    expect(apply, '退货申请应能在 C 端接口查到').toBeTruthy()
    expect(apply.status, '新申请应为待处理(0)').toBe(0)

    // ---------- 2. 管理员：退货申请页审核通过 ----------
    await adminLogin(page)
    await page.goto(`${ADMIN}/return-apply`)
    await page.waitForTimeout(1500)

    const applyRow = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
    await expect(applyRow, '管理员退货申请列表应出现新申请').toBeVisible()
    await applyRow.getByRole('button', { name: '审核' }).click()
    await page.waitForTimeout(800)
    await page.locator('.el-dialog .el-radio', { hasText: '通过' }).first().click()
    await page.getByRole('button', { name: '确定' }).click()
    await page.waitForTimeout(1500)
    await expect(page.locator('.el-message--success').first(), '管理员审核应保存成功').toBeVisible()

    await page.reload()
    await page.waitForTimeout(1200)
    await expect(
      page.locator('.el-table__body tr').filter({ hasText: orderSn }).first(),
      '审核通过后列表状态应为已通过'
    ).toContainText('已通过')

    // ---------- 3. 买家：重新登录后应能看到审核结果 ----------
    await memberLogin(page, username)
    await page.waitForTimeout(1500)
    await memberOrderRow(page, orderSn)

    // 文档要求「收到审核结果通知」：买家端应能看到该订单的退货审核结果
    const buyerRowText = await page
      .locator('.el-table__body tr')
      .filter({ hasText: orderSn })
      .first()
      .innerText()
      .catch(() => '')
    console.log('买家订单行内容:', buyerRowText.replace(/\s+/g, ' '))
    expect(
      buyerRowText,
      '【缺口】买家「我的订单」未展示退货审核结果（无售后状态/退款进度/通知），售后无法闭环'
    ).toMatch(/已通过|已拒绝|退货中|退款|售后/)

    await page.screenshot({ path: 'report/shots/scenario4-buyer-after-review.png', fullPage: true })
  })
})
