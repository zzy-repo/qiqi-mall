import { test, expect } from '@playwright/test'
import {
  PORTAL,
  ADMIN,
  API,
  adminLogin,
  memberLogin,
  registerMemberApi,
  createOrderApi,
  payOrderApi,
  prepareSku,
  stamp
} from './helpers.js'

/**
 * 场景4 售后退货（docs/flows/6-场景4-售后退货.mmd）
 *   买家「我的订单」选已收货订单 → 选退货原因 + 填说明 → 提交退货申请
 *   → 管理员「退货申请」页审核（通过/拒绝）→ 结果回传买家 → 售后闭环
 */
test.describe('场景4 售后退货', () => {
  test('买家提交退货申请 → 管理员审核通过 → 买家能看到审核结果', async ({ page, request }) => {
    // 准备：一个已付款订单（文档要求「已收货订单」，但确认收货能力缺失，见场景3）
    const username = `e2e_return_${stamp()}`
    const member = await registerMemberApi(request, username)
    const { skuId } = await prepareSku(request)
    const order = await createOrderApi(request, member.token, { skuId })
    await payOrderApi(request, member.token, order.id)

    // ---------- 1. 买家：我的订单 → 申请退货 ----------
    await memberLogin(page, username)
    await page.waitForTimeout(2500)
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1200)

    const row = page.locator('.el-table__body tr').filter({ hasText: order.orderSn }).first()
    await expect(row).toBeVisible()
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

    // 服务端复核：申请已落库且为待处理
    const api = await request.get(`${API}/portal/returns`, { headers: { Authorization: `Bearer ${member.token}` } })
    const returns = (await api.json()).data
    const apply = returns.find((r) => r.orderId === order.id)
    expect(apply, '退货申请应能在 C 端接口查到').toBeTruthy()
    expect(apply.status, '新申请应为待处理(0)').toBe(0)

    // ---------- 2. 管理员：退货申请页审核通过 ----------
    await adminLogin(page)
    await page.goto(`${ADMIN}/return-apply`)
    await page.waitForTimeout(1200)

    const applyRow = page.locator('.el-table__body tr').filter({ hasText: order.orderSn }).first()
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
      page.locator('.el-table__body tr').filter({ hasText: order.orderSn }).first(),
      '审核通过后列表状态应为已通过'
    ).toContainText('已通过')

    // ---------- 3. 买家：应能看到审核结果 ----------
    await memberLogin(page, username)
    await page.waitForTimeout(2500)
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1500)

    // 文档要求「收到审核结果通知」：买家端应能看到该订单的退货审核结果
    const buyerRowText = await page
      .locator('.el-table__body tr')
      .filter({ hasText: order.orderSn })
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
