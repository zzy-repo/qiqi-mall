import { test, expect } from '../../support/test.js'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
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
  submitCartOrder,
  adminLogin
} from '../../support/ui.js'
import {
  adminLoginHardened,
  adminAuditOrderPass,
  gotoReturnApplyList,
  returnApplyRow,
  returnApplyStatusOf,
  reloadReturnApplyList,
  pickAuditResult,
  submitAudit,
  expectToastSuccess,
  memberApplyReturn,
  memberOrderRowOf,
  memberAfterSaleTextOf,
  getAdminReturnAppliesApi,
  paymentRecordsOf
} from '../../support/admin-ui-return.js'
import { adminTokenOf, memberTokenOf, getProductApi, getAdminOrderBySnApi } from '../../support/assert.js'
import { tid } from '../../support/tid.js'

/**
 * 03-商家-履约 / 7-退货审核并发
 *
 * 两个管理员**同时**审核同一笔待审核退货申请：甲点「确认通过」成功，乙随后点「确认通过」被拒。
 *
 * 这条是全套并发里**最贵**的一条：审核通过 = 记一条退款流水 + 回补库存。
 * 如果两次都通过就是「同一笔退货退两次钱、补两次库存」——真金白银的损失。
 * 所以这里的不变量断言（不是「页面上看着对」）是重点：
 *   · 退款流水只有 1 条（直读 payment_record，type=2）
 *   · 库存只回补 1 次（精确到件数：stockBefore + 1，不是 + 2）
 *   · 退货申请只被处理一次（status=1），订单只退一次（status=6）
 *
 * 拒绝原因按**实际返回**断言（不写死成 409）：
 *   ReturnApplyController#update 的第一道闸是 `exist.getStatus() != 0 → fail("该申请已处理，不能重复审核")`
 *   —— **HTTP 200 + code=1 + 中文原因**；而且它是两段式交互的**预演**阶段
 *   （PUT /admin/return-applies/{id}?dryRun=true）就被拦下，所以前端不会进「确认执行」，
 *   表现是：表单停在原地（不收起）+ 一条「预演失败：…」toast + 表单内 `return-apply-form-preview-error`。
 *   （真 409 只在极窄的「两次 UPDATE 撞在同一毫秒」窗口出现，不是这条主路径的稳定结果。）
 *
 * 数据自给自足：买家注册 → 加购 → 支付 → 管理员审核通过 → 下发 → WMS 出库 → 买家申请退货，
 * 全程真实 UI（不碰接口推进状态），跑完把这笔一次性订单删掉。
 */

/** 证据落盘目录（report/ 已被 e2e/.gitignore 忽略，属于产物不是源码） */
const EVIDENCE_DIR = fileURLToPath(new URL('../../../report/shots/', import.meta.url))

/** 买家登录名：退货申请列表的「申请人」列直接显示它，所以长得像会员号而不是测试号 */
const buyerLoginOf = (seed) => `${memberNickOf(seed)}${Math.floor(Math.random() * 9000) + 1000}`

/**
 * 造一笔「退款中」的退货申请（真实 UI 全链路）。
 * @returns {{orderSn, orderId, skuId, stockBefore, adminToken, memberToken, totalAmount, applyId}}
 */
async function preparePendingReturnApply(page, request) {
  const username = buyerLoginOf(stamp())
  let adminToken, memberToken, orderSn, orderId, skuId, stockBefore, totalAmount

  await test.step('① 管理员备货（1 号商品首个 SKU 库存备到 50）', async () => {
    await adminLoginHardened(page)
    adminToken = await adminTokenOf(page)
    await adminSetSkuStock(page, 1, 0, 50)
  })

  await test.step('② 新买家注册 → 加购一件 → 结算支付（订单=待审核）', async () => {
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    memberToken = await memberTokenOf(page)
    const { skus } = await getProductApi(request, 1)
    skuId = skus[0].id
    await addSpecToCart(page, 1, skus[0].specText)
    orderSn = await submitCartOrder(page, receiverOf(username))
    const order = await getAdminOrderBySnApi(request, adminToken, orderSn)
    orderId = order.id
    totalAmount = Number(order.totalAmount)
    expect(order.status, '支付后订单应为待审核(7)').toBe(7)
  })

  await test.step('③ 管理员审核通过 → 下发 → 模拟WMS出库（订单=已发货，才有申请退货入口）', async () => {
    const row = await adminSearchOrderRow(page, orderSn)
    await adminAuditOrderPass(page, row)
    const deliveryTable = await openOrderDrawer(page, orderSn)
    const dRow = deliveryTable.locator('.el-table__body tr').first()
    await clickDispatch(page, dRow)
    await clickWmsCallback(page, dRow, '出库', '已出库')
    expect((await getAdminOrderBySnApi(request, adminToken, orderSn)).status, '出库后订单应为已发货(2)').toBe(2)
  })

  await test.step('④ 买家申请退货 → 订单转退款中（申请进入待处理）', async () => {
    stockBefore = (await getProductApi(request, 1)).skus.find((s) => s.id === skuId).stock
    await memberApplyReturn(page, orderSn, { description: returnDescriptionOf(orderSn) })
    const row = await memberOrderRowOf(page, orderSn)
    expect(await memberAfterSaleTextOf(row), '申请后售后列应为「退款处理中」').toBe('退款处理中')
    expect((await getAdminOrderBySnApi(request, adminToken, orderSn)).status, '订单应为退款中(5)').toBe(5)
  })

  const applies = await getAdminReturnAppliesApi(request, adminToken, orderId)
  expect(applies.length, '这笔订单应只有 1 条退货申请').toBe(1)
  expect(applies[0].status, '新申请应为待处理(0)').toBe(0)

  return { orderSn, orderId, skuId, stockBefore, adminToken, memberToken, totalAmount, applyId: applies[0].id }
}

/** 在「退货申请」页打开某笔申请的审核弹窗（行内按钮有 testid，不按文案找） */
async function openAuditDialog(page, orderSn) {
  await gotoReturnApplyList(page)
  const row = await returnApplyRow(page, orderSn)
  await row.getByTestId('return-apply-row-audit').click()
  const dialog = tid(page, 'return-apply-form-dialog')
  await expect(dialog, '审核弹窗应打开').toBeVisible()
  await page.waitForTimeout(400)
  return { row, dialog }
}

test.describe('03-商家-履约 › 7-退货审核并发', () => {
  test('两个会话同时审核同一笔退货申请：甲通过，乙被「该申请已处理」拦下，退款流水只 1 条、库存只回补 1 次', async ({ browser, page, request }) => {
    test.setTimeout(300_000)                    // 这条要跑完整条退货链路（下单/审核/发货/出库/申请）
    // 会话甲 = 本用例的 page fixture；会话乙 = 另起一个 browser context（独立 storageState）
    const pageA = page
    const ctxB = await browser.newContext()
    const pageB = await ctxB.newPage()

    let ctx = null
    try {
      ctx = await preparePendingReturnApply(pageA, request)

      // ---------- ① 两个会话各自打开同一笔待审核申请的审核弹窗（都停在「待处理」那一帧） ----------
      await adminLogin(pageB)
      const tokenB = await adminTokenOf(pageB)
      expect(tokenB, '乙的会话应拿到自己的令牌').toBeTruthy()
      await pageA.evaluate(() => localStorage.setItem('concurrency-probe', 'only-in-context-A'))
      expect(
        await pageB.evaluate(() => localStorage.getItem('concurrency-probe')),
        '两个 browser context 的 localStorage 必须互不可见（否则就不是两个会话）'
      ).toBeNull()

      const a = await openAuditDialog(pageA, ctx.orderSn)
      expect(await returnApplyStatusOf(a.row), '甲看到的申请应是待处理').toBe('待处理')
      await pickAuditResult(a.dialog, '通过')

      const b = await openAuditDialog(pageB, ctx.orderSn)
      expect(await returnApplyStatusOf(b.row), '乙看到的申请也应是待处理（同一份数据）').toBe('待处理')
      await pickAuditResult(b.dialog, '通过')

      // ---------- ② 甲点确定 → 预演通过 → 确认执行 → 审核成功（退款 + 回补库存） ----------
      const outcomeA = await submitAudit(a.dialog, { willDo: '审核为「通过」' })
      expect(outcomeA, '甲应该在确认弹窗里确认后执行成功').toBe('confirmed')
      await expectToastSuccess(pageA, '审核完成')
      await reloadReturnApplyList(pageA)
      expect(await returnApplyStatusOf(await returnApplyRow(pageA, ctx.orderSn)), '申请应显示「已通过」')
        .toBe('已通过')

      const orderAfterA = await getAdminOrderBySnApi(request, ctx.adminToken, ctx.orderSn)
      expect(orderAfterA.status, '甲审核通过后订单应为已退款(6)').toBe(6)
      const refundsAfterA = paymentRecordsOf(ctx.orderSn).filter((r) => r.type === 2)
      expect(refundsAfterA.length, '甲审核通过后应恰好有 1 条退款流水').toBe(1)
      expect(refundsAfterA[0].amount, '退款金额应等于订单实付').toBeCloseTo(ctx.totalAmount, 2)
      expect(refundsAfterA[0].tradeNo, '退款流水号应带 MOCKREF 前缀').toMatch(/^MOCKREF/)
      const stockAfterA = (await getProductApi(request, 1)).skus.find((s) => s.id === ctx.skuId).stock
      expect(stockAfterA, '甲审核通过后库存应回补 1 件').toBe(ctx.stockBefore + 1)

      // ---------- ③ 乙随后点确定（手里还是「待处理」那一帧）→ 预演被业务守卫拒绝 ----------
      const dryRunResp = pageB.waitForResponse(
        (r) => r.request().method() === 'PUT'
          && r.url().includes(`/admin/return-applies/${ctx.applyId}`)
          && r.url().includes('dryRun'),
        { timeout: 20_000 }
      )
      const outcomeB = await submitAudit(b.dialog)
      const respB = await dryRunResp
      const bodyB = await respB.json()
      expect(outcomeB, '乙应在预演阶段就被拦下（不会进入确认执行）').toBe('preview-failed')
      expect(respB.status(), '预演请求本身是 HTTP 200（业务失败用 code 表达，不是 409）').toBe(200)
      expect(bodyB.code, '守卫拒绝的业务码是 1').toBe(1)
      expect(bodyB.message, '守卫拒因应是后端那句原文').toBe('该申请已处理，不能重复审核')

      // 证据：把乙这次的真实响应体落盘
      mkdirSync(EVIDENCE_DIR, { recursive: true })
      writeFileSync(
        `${EVIDENCE_DIR}7-退货审核并发-乙被拒响应.json`,
        JSON.stringify(
          {
            capturedAt: new Date().toISOString(),
            request: { method: 'PUT', path: `/admin/return-applies/${ctx.applyId}?dryRun=true`, body: { status: 1 } },
            response: { status: respB.status(), body: bodyB }
          },
          null,
          2
        ) + '\n'
      )

      // 乙的 UI 必须有明确失败提示：toast + 表单内的原因，且表单不收起（不丢用户选的结论）
      await expect(pageB.locator('.el-message--error').first(), '乙应看到「预演失败：该申请已处理，不能重复审核」')
        .toContainText('该申请已处理，不能重复审核', { timeout: 15_000 })
      const inlineError = tid(pageB, 'return-apply-form-preview-error')
      await expect(inlineError, '预演失败的原因要留在表单里（不是只发一条会消失的 toast）')
        .toBeVisible({ timeout: 15_000 })
      await expect(inlineError, '表单内的原因就是服务端那句守卫')
        .toHaveText('预演失败：该申请已处理，不能重复审核')
      await expect(b.dialog, '预演失败时表单必须停在原地').toBeVisible()
      await expect(b.dialog.locator('.el-radio.is-checked').first(), '刚选的「通过」不能被重置掉')
        .toContainText('通过')
      await pageB.screenshot({ path: `${EVIDENCE_DIR}7-退货审核并发-乙被拒.png`, fullPage: false })

      // ---------- ④ 关键不变量：钱只退一次、库存只补一次 ----------
      const refundsFinal = paymentRecordsOf(ctx.orderSn)
      expect(refundsFinal.filter((r) => r.type === 2).length, '退款流水必须只有 1 条（两次就是重复退款）').toBe(1)
      expect(refundsFinal.filter((r) => r.type === 1).length, '支付流水应仍是原来那一条，没有被复制').toBe(1)
      const stockFinal = (await getProductApi(request, 1)).skus.find((s) => s.id === ctx.skuId).stock
      expect(stockFinal, '库存只回补 1 次（回补两次就是资金/库存损失）').toBe(ctx.stockBefore + 1)
      expect((await getAdminOrderBySnApi(request, ctx.adminToken, ctx.orderSn)).status, '订单仍应为已退款(6)')
        .toBe(6)

      const appliesFinal = await getAdminReturnAppliesApi(request, ctx.adminToken, ctx.orderId)
      expect(appliesFinal.length, '不应出现第二条退货申请').toBe(1)
      expect(appliesFinal[0].status, '申请仍应为已通过(1)，没被第二次审核改动').toBe(1)

      await reloadReturnApplyList(pageB)
      expect(await returnApplyStatusOf(await returnApplyRow(pageB, ctx.orderSn)), '乙刷新后看到的是已通过')
        .toBe('已通过')
    } finally {
      // 收尾说明（不假装清理干净）：退货链路的台账删不掉 ——
      // 「删除订单」的后端级联（order_item / delivery_order / delivery_logistics）里**不含 return_apply**，
      // 而 return_apply 对 order 有外键，所以「已产生退货申请的订单」执行删除会被外键拦下
      // （dryRun 会说「将删除订单…」，真删回 code=1「数据处理失败，请稍后重试」）。
      // 所以本场景的残留意向是明确的：1 笔已退款订单 + 1 条退货申请 + 2 条 payment_record；
      // orderSn 每轮唯一，不污染下一轮的断言（这也是仓库里 4-退货处理.spec.js 一直以来的做法）。
      // 这里只把乙停在原地的表单关掉，不做无效的删除尝试（否则白等 20s 且报错误导）。
      await (async () => {
        const cancelBtn = tid(pageB, 'return-apply-form-cancel')
        if (await cancelBtn.count()) await cancelBtn.click({ timeout: 5000 }).catch(() => {})
      })().catch(() => {})
      await ctxB.close()
    }
  })
})
