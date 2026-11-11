import { test, expect } from '@playwright/test'
import { stamp } from '../../support/env.js'
import { memberNickOf, receiverOf, returnDescriptionOf } from '../../support/fixtures.js'
import {
  adminSetSkuStock,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder
} from '../../support/ui.js'
import {
  adminLoginHardened,
  adminAuditOrderPass,
  adminOrderRowOf,
  adminOrderStatusOf,
  gotoReturnApplyList,
  returnApplyRow,
  returnApplyStatusOf,
  reloadReturnApplyList,
  openAuditDialog,
  pickAuditResult,
  fillAuditDescription,
  auditDescriptionValue,
  submitAudit,
  auditReturnApply,
  expectToastSuccess,
  expectToastError,
  memberOrderRowOf,
  memberApplyReturn,
  memberAfterSaleTextOf,
  getAdminReturnAppliesApi,
  paymentRecordsOf
} from '../../support/admin-ui-return.js'
import {
  adminTokenOf,
  memberTokenOf,
  getProductApi,
  getAdminOrderBySnApi,
  getMemberReturnsApi
} from '../../support/assert.js'

/**
 * 03-商家-履约 / 4-退货处理（docs/flows/02-业务场景/03-商家-履约/4-退货处理/1-退货处理时序图.mmd）
 *
 * 商家侧视角走完整条退货链路，全部真实点击：
 *   买家注册 → 加购 → 结算支付（订单=待审核）→ 管理员审核通过 → 下发 → 模拟WMS出库（订单=已发货）
 *   → 买家申请退货（订单转退款中）→ 管理员在「退货申请」页审核 → 结果回传买家
 *
 * 覆盖：
 *   ① 审核通过：申请已通过 + 订单已退款 + 退款流水（金额=实付）+ 库存回补 + 买家看到「退款成功」
 *   ② 审核拒绝：填拒绝理由 + 订单回到已付款 + 库存不变 + 不产生退款流水 + 买家看到「退货被拒绝」
 *   ③ 守卫：已处理的申请再点「审核」→ 服务端拒绝（该申请已处理，不能重复审核），状态与流水都不变
 *   ④ 边界：拒绝时不填理由 —— 按代码实际行为，前端规则里「详细说明」不是必填、后端也只判 null，
 *      所以空串照样提交成功，且说明会被存成空（不会保留买家原说明）
 *
 * 断言依据（读过代码，不是猜的）：
 *   ReturnApplyController.update：status!=0 → fail("该申请已处理，不能重复审核")；
 *     通过 → 订单 6 已退款 + 记退款流水 + 回补库存；拒绝 → 订单 1 已付款；
 *     `if (body.getDescription() != null) exist.setDescription(...)` → 说明非必填，空串会覆盖。
 *   PortalReturnController.apply：仅已付款/已发货可申请，且不能有第二个待处理申请；申请后订单转 5 退款中。
 *   return-apply/index.vue：审核入口对任何状态都渲染（前端没有状态守卫），拦截完全靠服务端。
 */

/**
 * 唯一后缀：多个 agent 共用一个库，账号/订单必须彼此不撞。
 * 只用于不显示的字段（登录名里的 4 位数字）；界面上的名字一律走 fixtures 的自然中文池。
 */
const uniq = () => `${stamp()}${Math.floor(Math.random() * 900 + 100)}`
const serial = () => String(Math.floor(Math.random() * 9000) + 1000)

/** 买家登录名：退货申请列表的「申请人」列直接显示它，所以长得像会员号而不是测试号 */
const buyerLoginOf = (seed) => `${memberNickOf(seed)}${serial()}`

/**
 * 造一笔「退款中」的退货申请（真实 UI 全链路，不碰接口）。
 * @returns {{ orderSn, skuId, stockBefore, adminToken, memberToken, totalAmount }}
 */
async function prepareReturnRequest(page, request, { description } = {}) {
  const username = buyerLoginOf(uniq())
  let adminToken
  let memberToken
  let orderSn
  let skuId
  let stockBefore
  let totalAmount

  await test.step('① 管理员备货（1 号商品首个 SKU 库存备到 50）', async () => {
    await adminLoginHardened(page)
    adminToken = await adminTokenOf(page)
    await adminSetSkuStock(page, 1, 0, 50)
  })

  await test.step('② 新买家注册 → 加购一件 → 结算支付', async () => {
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))   // 顶栏/会员列表显示的都是中文昵称
    memberToken = await memberTokenOf(page)
    const { skus } = await getProductApi(request, 1)
    skuId = skus[0].id
    await addSpecToCart(page, 1, skus[0].specText)
    orderSn = await submitCartOrder(page, receiverOf(username))
    totalAmount = Number((await getAdminOrderBySnApi(request, adminToken, orderSn)).totalAmount)
  })

  await test.step('③ 管理员审核通过 → 下发 → 模拟WMS出库（订单=已发货，有申请退货入口的前提）', async () => {
    const row = await adminSearchOrderRow(page, orderSn)
    await adminAuditOrderPass(page, row)
    const deliveryTable = await openOrderDrawer(page, orderSn)
    const dRow = deliveryTable.locator('.el-table__body tr').first()
    await clickDispatch(page, dRow)
    await clickWmsCallback(page, dRow, '出库', '已出库')
    expect((await getAdminOrderBySnApi(request, adminToken, orderSn)).status, '出库后订单应为已发货(2)').toBe(2)
  })

  await test.step('④ 买家申请退货 → 订单转退款中，售后列显示退款处理中', async () => {
    stockBefore = (await getProductApi(request, 1)).skus.find((s) => s.id === skuId).stock
    await memberApplyReturn(page, orderSn, description === undefined ? {} : { description })

    const row = await memberOrderRowOf(page, orderSn)
    expect(await memberAfterSaleTextOf(row), '申请后售后列应为「退款处理中」').toBe('退款处理中')
    await expect(row.getByRole('button', { name: '申请退货' }), '退款中订单不应再有申请入口').toHaveCount(0)

    const returns = await getMemberReturnsApi(request, memberToken)
    expect(returns.length, '买家应只有一条退货申请').toBe(1)
    expect(returns[0].status, '新申请应为待处理(0)').toBe(0)
    expect(returns[0].returnReason, '应记录所选退货原因').toBeTruthy()
    expect((await getAdminOrderBySnApi(request, adminToken, orderSn)).status, '订单应为退款中(5)').toBe(5)
  })

  return { orderSn, skuId, stockBefore, adminToken, memberToken, totalAmount }
}

test.describe('03-商家-履约 › 4-退货处理', () => {
  test('审核通过：退款流水 + 库存回补 + 买家看到退款成功', async ({ page, request }) => {
    const ctx = await prepareReturnRequest(page, request)

    await test.step('⑤ 管理员在「退货申请」页点审核 → 通过', async () => {
      await auditReturnApply(page, ctx.orderSn, { result: '通过' })
      await expectToastSuccess(page, '保存成功')
      await reloadReturnApplyList(page)
      const row = await returnApplyRow(page, ctx.orderSn)
      expect(await returnApplyStatusOf(row), '申请应显示「已通过」').toBe('已通过')
    })

    await test.step('⑥ 服务端事实：申请已通过 + 订单已退款', async () => {
      expect((await getAdminOrderBySnApi(request, ctx.adminToken, ctx.orderSn)).status, '订单应为已退款(6)').toBe(6)
      const returns = await getMemberReturnsApi(request, ctx.memberToken)
      expect(returns[0].status, '申请应为已通过(1)').toBe(1)
    })

    await test.step('⑦ 退款流水已写入（一条退款，金额=订单实付）', async () => {
      const records = paymentRecordsOf(ctx.orderSn)
      const refunds = records.filter((r) => r.type === 2)
      expect(refunds.length, '审核通过后应恰好有一条退款流水').toBe(1)
      expect(refunds[0].amount, '退款金额应等于订单实付').toBeCloseTo(ctx.totalAmount, 2)
      expect(refunds[0].tradeNo, '退款流水号应带 MOCKREF 前缀').toMatch(/^MOCKREF/)
      expect(records.filter((r) => r.type === 1).length, '支付流水应仍是那一条，没有被复制').toBe(1)
    })

    await test.step('⑧ 库存回补 +1，买家与后台都看到已退款', async () => {
      const stockAfter = (await getProductApi(request, 1)).skus.find((s) => s.id === ctx.skuId).stock
      expect(stockAfter, '审核通过后库存应回补 1 件').toBe(ctx.stockBefore + 1)

      const row = await memberOrderRowOf(page, ctx.orderSn)
      expect(await memberAfterSaleTextOf(row), '买家售后列应显示「退款成功」').toBe('退款成功')
      expect((await row.locator('.el-tag').first().innerText()).trim(), '买家侧订单状态应为已退款').toBe('已退款')

      const adminRow = await adminOrderRowOf(page, ctx.orderSn)
      expect(await adminOrderStatusOf(adminRow), '后台订单状态应为已退款').toBe('已退款')
    })
  })

  test('审核拒绝：填拒绝理由 → 订单回到已付款，买家看到退货被拒绝', async ({ page, request }) => {
    const buyerNote = returnDescriptionOf('reject')
    const ctx = await prepareReturnRequest(page, request, { description: buyerNote })
    const rejectReason = '商品已拆封使用，影响二次销售，不符合退货条件'

    await test.step('⑤ 管理员点审核 → 拒绝 → 填拒绝理由', async () => {
      await gotoReturnApplyList(page)
      const row = await returnApplyRow(page, ctx.orderSn)
      const dialog = await openAuditDialog(page, row)
      await pickAuditResult(dialog, '拒绝')
      expect(await auditDescriptionValue(dialog), '说明应带出买家原说明').toBe(buyerNote)
      await fillAuditDescription(dialog, rejectReason)
      await submitAudit(dialog)
      await expectToastSuccess(page, '保存成功')
      await reloadReturnApplyList(page)
      const after = await returnApplyRow(page, ctx.orderSn)
      expect(await returnApplyStatusOf(after), '申请应显示「已拒绝」').toBe('已拒绝')
      await expect(after, '拒绝理由应落到说明列').toContainText('不符合退货条件')
    })

    await test.step('⑥ 服务端事实：订单回到已付款，申请已拒绝且记下拒绝理由', async () => {
      expect((await getAdminOrderBySnApi(request, ctx.adminToken, ctx.orderSn)).status, '拒绝后订单应回到已付款(1)').toBe(1)
      const returns = await getMemberReturnsApi(request, ctx.memberToken)
      expect(returns[0].status, '申请应为已拒绝(2)').toBe(2)
      expect(returns[0].description, '说明应被拒绝理由覆盖').toBe(rejectReason)
    })

    await test.step('⑦ 拒绝不退款、不回补库存', async () => {
      const refunds = paymentRecordsOf(ctx.orderSn).filter((r) => r.type === 2)
      expect(refunds.length, '拒绝不应产生退款流水').toBe(0)
      const stockAfter = (await getProductApi(request, 1)).skus.find((s) => s.id === ctx.skuId).stock
      expect(stockAfter, '拒绝不应回补库存').toBe(ctx.stockBefore)
    })

    await test.step('⑧ 买家侧看到退货被拒绝、订单回到已付款', async () => {
      const row = await memberOrderRowOf(page, ctx.orderSn)
      expect(await memberAfterSaleTextOf(row), '买家售后列应显示「退货被拒绝」').toBe('退货被拒绝')
      expect((await row.locator('.el-tag').first().innerText()).trim(), '买家侧订单状态应为已付款').toBe('已付款')
    })
  })

  test('守卫：已处理的申请不能重复审核（入口在，服务端拦）', async ({ page, request }) => {
    const ctx = await prepareReturnRequest(page, request)

    await test.step('⑤ 先正常审核通过，把申请变成「已处理」', async () => {
      await auditReturnApply(page, ctx.orderSn, { result: '通过' })
      await expectToastSuccess(page, '保存成功')
      await reloadReturnApplyList(page)
      const row = await returnApplyRow(page, ctx.orderSn)
      expect(await returnApplyStatusOf(row), '申请应为已通过').toBe('已通过')
    })

    await test.step('⑥ 对已通过的申请再点「审核」→ 服务端拒绝，弹窗停在原地', async () => {
      const row = await returnApplyRow(page, ctx.orderSn)
      // 说明现状：列表对任何状态都渲染「审核」入口（前端没有状态守卫），拦截完全靠服务端
      await expect(row.getByRole('button', { name: '审核' }), '已处理的申请仍有审核入口').toHaveCount(1)

      const dialog = await openAuditDialog(page, row)
      await expect(dialog.locator('.el-form-item').filter({ hasText: '处理结果' }), '弹窗应能打开').toBeVisible()
      await pickAuditResult(dialog, '拒绝')
      await submitAudit(dialog)
      await expectToastError(page, '该申请已处理，不能重复审核')

      await dialog.getByRole('button', { name: '取消' }).click()
      await expect(dialog).toBeHidden({ timeout: 10_000 })
    })

    await test.step('⑦ 申请与订单都没被第二次审核改动', async () => {
      await reloadReturnApplyList(page)
      const row = await returnApplyRow(page, ctx.orderSn)
      expect(await returnApplyStatusOf(row), '重复审核不应改动申请状态').toBe('已通过')

      const applies = await getAdminReturnAppliesApi(request, ctx.adminToken)
      const mine = applies.find((a) => a.orderSn === ctx.orderSn)
      expect(mine, '后台应能查到这笔申请').toBeTruthy()
      expect(mine.status, '申请仍应为已通过(1)').toBe(1)
      expect((await getAdminOrderBySnApi(request, ctx.adminToken, ctx.orderSn)).status, '订单仍应为已退款(6)').toBe(6)
      expect(paymentRecordsOf(ctx.orderSn).filter((r) => r.type === 2).length, '不应多出第二条退款流水').toBe(1)
    })
  })

  test('边界：拒绝时不填理由也能提交，说明被清空', async ({ page, request }) => {
    const buyerNote = returnDescriptionOf('blank')
    const ctx = await prepareReturnRequest(page, request, { description: buyerNote })

    await test.step('⑤ 管理员点审核 → 拒绝 → 把说明清空 → 提交', async () => {
      await gotoReturnApplyList(page)
      const row = await returnApplyRow(page, ctx.orderSn)
      const dialog = await openAuditDialog(page, row)
      await pickAuditResult(dialog, '拒绝')
      expect(await auditDescriptionValue(dialog), '说明应带出买家原说明').toBe(buyerNote)
      await fillAuditDescription(dialog, '')          // 清空，不填任何拒绝理由
      expect(await auditDescriptionValue(dialog), '说明已被清空').toBe('')
      await submitAudit(dialog)

      // 实际行为：前端表单规则里「详细说明」不是必填（只校验订单/申请人/原因），后端也只判 null，
      // 所以空串照样提交成功 —— 这是当前实现的事实，不是本次要修的问题。
      await expectToastSuccess(page, '保存成功')
    })

    await test.step('⑥ 拒绝生效，说明存成空串（不保留买家原说明）', async () => {
      await reloadReturnApplyList(page)
      const row = await returnApplyRow(page, ctx.orderSn)
      expect(await returnApplyStatusOf(row), '申请应显示「已拒绝」').toBe('已拒绝')

      expect((await getAdminOrderBySnApi(request, ctx.adminToken, ctx.orderSn)).status, '订单应回到已付款(1)').toBe(1)
      const returns = await getMemberReturnsApi(request, ctx.memberToken)
      expect(returns[0].status, '申请应为已拒绝(2)').toBe(2)
      expect(returns[0].description, '说明应被清空').toBe('')
    })

    await test.step('⑦ 买家侧看到退货被拒绝', async () => {
      const row = await memberOrderRowOf(page, ctx.orderSn)
      expect(await memberAfterSaleTextOf(row), '买家售后列应显示「退货被拒绝」').toBe('退货被拒绝')
    })
  })
})
