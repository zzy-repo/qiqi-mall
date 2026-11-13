import { test, expect } from '../../support/test.js'
import { API, ADMIN } from '../../support/env.js'
import { dbOfApi } from '../../support/db.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  auditRejectOrder,
  orderStatusOf
} from '../../support/admin-ui.js'
import { adminTokenOf } from '../../support/assert.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  orderFactOf,
  orderItemStatsOf,
  decisionsOf,
  decisionJsonOf,
  ledgerRowsOf,
  activeTagsOf,
  POLICY_NAME
} from '../../support/audit-fixtures.js'
import { getQueueRowOf } from '../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  badgeOf,
  reasonMainOf,
  reasonSubOf,
  runAuditViaUi,
  openWhyDrawer,
  closeWhyDrawer
} from '../../support/audit-ui.js'

/**
 * 08-订单自动审核 / 3-不命中与差在哪
 *
 * 这个场景钉住判定链的最后一步 [6]：**没有任何 AUTO 策略命中 → NO_MATCH**，
 * 并且这个「不命中」必须是**可解释的**（逐策略、逐条件、expected vs actual），
 * 而不是一句「失败了」。
 *
 * 两笔样本：
 *   QT-1003 抖音 1580 元 8 件（1 个样）→ NO_MATCH，差在「应收金额 1580.00 < 500.00」；
 *            **种类数那句不该出现**（1 ≤ 5 是满足的，只是不再决定结论 → 抽屉里标「未求值」）
 *   QT-1006 淘宝 420 元 6 件 6 个样      → NO_MATCH，差在「商品种类数 6 ≤ 5」
 *            然后再走**人工审核不通过**：7 待审核 → 4 已关闭（这条不归自动引擎管，
 *            它是「自动判不了的单最后由人来结」的那一段）
 *
 * ── 为什么「种类数不出现」这条要单独断 ────────────────────────────────────
 *   这是 AND 链的语义：QT-1003 的 抖音-标准单 前两条（渠道、应收）里应收已经不满足，
 *   种类数即使满足也不再决定结论。如果引擎把「差在哪几条」写成「全部条件都差」，
 *   运营就会去调一条根本没问题的条件。所以：原因行**只列真的不满足的**，
 *   抽屉里按三态分开标（未求值 ≠ 不满足）。
 *
 * ── 人工关单那一步为什么要断「决策行不变」────────────────────────────────
 *   自动审核与人工审核是两条独立证据链：人工关单只写 order_status_ledger（reason=AUDIT_REJECT），
 *   **不会**补一条 order_audit_decision（决策行只由 run 写）。混在一起就再也说不清
 *   「这一轮引擎怎么判的」与「人后来又做了什么」。
 */
test.describe('08-订单自动审核 › 3-不命中与差在哪', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[08-订单自动审核/3] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  test('QT-1003/QT-1006 都不命中，差在哪给出具体差值与未求值；QT-1006 再人工审核 7→4', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    try {
      await test.step('① 复位基线：两笔单的判定输入事实（金额 / 种类数 / 件数）', async () => {
        resetAuditDemoBaseline()

        const f3 = orderFactOf('QT-1003')
        expect(f3.status, 'QT-1003 起点应是 7 待审核').toBe(7)
        expect(f3.totalAmount, 'QT-1003 应收 1580.00').toBe(1580)
        expect(f3.channel, 'QT-1003 渠道应是抖音').toBe('抖音')
        const s3 = orderItemStatsOf('QT-1003')
        expect(s3.qty, 'QT-1003 是 8 件').toBe(8)
        expect(s3.kinds, 'QT-1003 只有 1 个商品种类（所以种类数条件其实是满足的）').toBe(1)

        const f6 = orderFactOf('QT-1006')
        expect(f6.status, 'QT-1006 起点应是 7 待审核').toBe(7)
        expect(f6.totalAmount, 'QT-1006 应收 420.00').toBe(420)
        expect(f6.channel, 'QT-1006 渠道应是淘宝').toBe('淘宝')
        const s6 = orderItemStatsOf('QT-1006')
        expect(s6.kinds, 'QT-1006 有 6 个商品种类（超过 5 的上限）').toBe(6)
        expect(s6.qty, 'QT-1006 是 6 件').toBe(6)
      })

      await test.step('② 跑一轮 → 两笔单都是「不命中」，原因里带各自的具体差值', async () => {
        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)

        const d3 = run.decisions.find((x) => x.orderSn === 'QT-1003')
        const d6 = run.decisions.find((x) => x.orderSn === 'QT-1006')
        expect(d3?.result, 'run 返回里 QT-1003 应是 NO_MATCH（抖音-标准单 差在应收 1580 < 500）').toBe('NO_MATCH')
        expect(d3?.matchedPolicyName, 'NO_MATCH 不应有命中策略').toBeFalsy()
        expect(d3?.reason, 'QT-1003 的原因应含「应收金额 1580.00 < 500.00」')
          .toContain('应收金额 1580.00 < 500.00')
        expect(d3?.reason, 'QT-1003 的原因**不该**出现种类数那句（1 ≤ 5 满足，只是不再决定结论）')
          .not.toContain('商品种类数')
        expect(d6?.result, 'run 返回里 QT-1006 应是 NO_MATCH（淘宝-标准单 差在种类数 6 > 5）').toBe('NO_MATCH')
        expect(d6?.reason, 'QT-1006 的原因应含「商品种类数 6 ≤ 5」').toContain('商品种类数 6 ≤ 5')
      })

      await test.step('③ 队列行断言：徽标 NO_MATCH + 原因首句 + 灰字里的具体差值', async () => {
        const row3 = auditQueueRow(page, 'QT-1003')
        await expect(row3, '队列里应有 QT-1003 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row3), 'QT-1003 徽标应是 NO_MATCH').toHaveAttribute('data-result', 'NO_MATCH')
        await expect(badgeOf(row3), 'QT-1003 徽标文案应是「不命中」').toHaveText('不命中')
        await expect(reasonMainOf(row3), '原因首句应是「没有任何自动策略命中」').toContainText('没有任何自动策略命中')
        await expect(reasonSubOf(row3), '灰字原因应逐条列出差在哪，含「应收金额 1580.00 < 500.00」')
          .toContainText('应收金额 1580.00 < 500.00')
        await expect(reasonSubOf(row3), 'QT-1003 的灰字原因里**不该**有种类数那句').not.toContainText('商品种类数')
        await expect(row3, 'QT-1003 应是第 1 轮').toContainText('第 1 轮')
        await expect(row3, 'QT-1003 应收列应显示 1580.00').toContainText('1580.00')

        const row6 = auditQueueRow(page, 'QT-1006')
        await expect(row6, '队列里应有 QT-1006 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row6), 'QT-1006 徽标应是 NO_MATCH').toHaveAttribute('data-result', 'NO_MATCH')
        await expect(reasonMainOf(row6), 'QT-1006 原因首句也说「没有任何自动策略命中」')
          .toContainText('没有任何自动策略命中')
        await expect(reasonSubOf(row6), 'QT-1006 灰字原因应含「商品种类数 6 ≤ 5」')
          .toContainText('商品种类数 6 ≤ 5')
        await expect(reasonSubOf(row6), 'QT-1006 灰字原因也应列出渠道/客户标签这两条真差的条件')
          .toContainText('渠道 = 淘宝')
      })

      await test.step('④ 差在哪抽屉（QT-1003）：逐策略逐条件 expected vs actual，未求值 ≠ 不满足', async () => {
        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1003'))

        await expect(drawer.getByTestId('audit-why-badge'), '抽屉徽标应是 NO_MATCH')
          .toHaveAttribute('data-result', 'NO_MATCH')
        await expect(drawer.getByTestId('audit-why-source'), '抽屉应标明结论取自后端留痕')
          .toContainText('结论取自后端留痕')

        // 三条 AUTO 策略各差一条：直营-小单（客户标签）、淘宝-标准单（渠道）、抖音-标准单（应收）
        const marks = drawer.getByTestId('audit-why-mark')
        await expect(marks, '三条 AUTO 策略各有一行标「← 差在哪」').toHaveCount(3)
        const unevaluated = drawer.getByTestId('audit-why-unevaluated')
        await expect(unevaluated, '首个不满足之后的条件应标「未求值」（本单有 2 条落在这一点上）')
          .toHaveCount(2)

        // 具体差值：抽屉里的「后端留痕」行原样保留引擎的文案
        await expect(drawer, '抽屉里应原样写出「应收金额 1580.00 < 500.00」')
          .toContainText('应收金额 1580.00 < 500.00')
        await expect(drawer, '抽屉里应写出直营-小单差在哪（客户标签 = 直营门店，实际 普通）')
          .toContainText('客户标签 = 直营门店')
        await expect(drawer, '抽屉里应写出淘宝-标准单差在哪（渠道 = 淘宝，实际 抖音）')
          .toContainText('渠道 = 淘宝')
        // 种类数这条在抖音-标准单里是「满足」的（1 ≤ 5），所以它不该出现在那条策略的「差在哪」文案里
        const douyinGroup = drawer.locator('[data-testid^="audit-why-policy-"]').filter({ hasText: POLICY_NAME.AUTO_DOUYIN })
        await expect(douyinGroup, '抽屉里应有「抖音-标准单」这一组').toBeVisible()
        await expect(douyinGroup.locator('.why-backend-lines'), '抖音-标准单 的「差在哪」文案里不该有种类数那句')
          .not.toContainText('商品种类数')

        await closeWhyDrawer(page)
      })

      await test.step('⑤ 差在哪抽屉（QT-1006）：差在种类数 6 ≤ 5，且没有未求值行（它就是最后一条）', async () => {
        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1006'))

        const marks = drawer.getByTestId('audit-why-mark')
        await expect(marks, '应至少有一行标「← 差在哪」（种类数 6 ≤ 5）').not.toHaveCount(0)
        await expect(drawer, '抽屉里应原样写出「商品种类数 6 ≤ 5」').toContainText('商品种类数 6 ≤ 5')
        // 淘宝-标准单 的三个条件里，种类数是最后一条：它不满足 → 同组内没有「未求值」行
        const taobaoGroup = drawer.locator('[data-testid^="audit-why-policy-"]').filter({ hasText: POLICY_NAME.AUTO_TAOBAO })
        await expect(taobaoGroup, '抽屉里应有「淘宝-标准单」这一组').toBeVisible()
        await expect(taobaoGroup.getByTestId('audit-why-unevaluated'), '这条策略的三个条件都被求值过，不该有未求值行')
          .toHaveCount(0)
        await expect(taobaoGroup.getByTestId('audit-why-mark'), '差在种类数这一条（6 > 5）').toHaveCount(1)

        await closeWhyDrawer(page)
      })

      await test.step('⑥ 直查库：两笔单都只有决策、状态与台账都没动（不命中不推进）', async () => {
        for (const [sn, expectReason] of [
          ['QT-1003', '应收金额 1580.00 < 500.00'],
          ['QT-1006', '商品种类数 6 ≤ 5']
        ]) {
          const f = orderFactOf(sn)
          const dec = decisionsOf(f.id)
          expect(dec, `${sn} 应恰好 1 条决策`).toHaveLength(1)
          expect(dec[0].result, `${sn} 决策结果应是 NO_MATCH`).toBe('NO_MATCH')
          expect(dec[0].matchedPolicyName, `${sn} 不该记命中策略`).toBeNull()
          expect(dec[0].roundNo, `${sn} 应是第 1 轮`).toBe(1)
          expect(dec[0].reason, `${sn} 的证据原因应含具体差值「${expectReason}」`).toContain(expectReason)
          expect(orderFactOf(sn).status, `${sn} 状态应仍是 7（不命中不推进）`).toBe(7)
          expect(ledgerRowsOf(f.id), `${sn} 不该有状态台账行`).toHaveLength(0)
          expect(activeTagsOf(f.id), `${sn} 不该打标`).toHaveLength(0)
          // miss_reason_json 是「逐条策略差在哪」的结构化留痕（不是一句人话）
          const miss = decisionJsonOf(f.id, 1, 'miss_reason_json')
          expect(miss, `${sn} 的 miss_reason_json 应含策略名与条件码`).toContain('policyName')
          expect(miss, `${sn} 的 miss_reason_json 应含 expected/actual 两列`).toContain('"actual"')
          expect(miss, `${sn} 的 miss_reason_json 应带 evaluated 三态依据`).toContain('"evaluated"')
        }
      })

      await test.step('⑦ 接口回读：种类数口径来自服务端（QT-1003=1 / QT-1006=6）', async () => {
        const r3 = await getQueueRowOf(request, token, 'QT-1003')
        expect(r3.result, '接口里 QT-1003 最新结论应是 NO_MATCH').toBe('NO_MATCH')
        expect(Number(r3.item_kind_count), '接口里 QT-1003 的商品种类数应是 1').toBe(1)
        expect(Number(r3.item_qty_total), '接口里 QT-1003 的总件数应是 8').toBe(8)
        expect(Number(r3.total_amount), '接口里 QT-1003 应收 1580').toBe(1580)

        const r6 = await getQueueRowOf(request, token, 'QT-1006')
        expect(r6.result, '接口里 QT-1006 最新结论应是 NO_MATCH').toBe('NO_MATCH')
        expect(Number(r6.item_kind_count), '接口里 QT-1006 的商品种类数应是 6').toBe(6)
      })

      await test.step('⑧ 人工审核不通过：QT-1006 由 7 待审核 → 4 已关闭（订单页真实点击）', async () => {
        await gotoOrderList(page)
        const row = await searchOrderRow(page, 'QT-1006')
        expect(await orderStatusOf(row), '人工审核前，订单行状态应是「待审核」').toBe('待审核')

        // 行内「不通过」→ 统一确认弹窗（预演会说明关闭订单 + 回补哪些库存）→ 确认执行
        await auditRejectOrder(page, row)

        const f = orderFactOf('QT-1006')
        expect(f.status, '人工审核不通过后订单应是 4 已关闭').toBe(4)

        const led = ledgerRowsOf(f.id)
        expect(led, '人工关单应恰好留 1 行状态台账').toHaveLength(1)
        expect(led[0].from, '台账前态 7').toBe(7)
        expect(led[0].to, '台账后态 4（已关闭）').toBe(4)
        expect(led[0].reason, '原因码应是 AUDIT_REJECT（与自动过审 AUDIT_AUTO_PASS 分开记）').toBe('AUDIT_REJECT')

        // 关键：人工关单**不产生**决策行 —— 两条证据链不混
        const dec = decisionsOf(f.id)
        expect(dec, '人工关单不该补决策行（决策只由 run 写）').toHaveLength(1)
        expect(dec[0].result, '那张决策行仍是自动引擎判的 NO_MATCH').toBe('NO_MATCH')

        // 页面上也要看得见结论
        await gotoOrderList(page)
        const rowAfter = await searchOrderRow(page, 'QT-1006')
        expect(await orderStatusOf(rowAfter), '订单行状态应刷新为「已关闭」').toBe('已关闭')
      })

      await test.step('⑨ 再跑一轮：已关闭的单不再进候选（决策不增），仍在待审核的不命中单继续被判（轮次 +1）', async () => {
        await gotoAuditQueue(page)
        const run2 = await runAuditViaUi(page)

        const id6 = orderFactOf('QT-1006').id
        const dec6 = decisionsOf(id6)
        expect(dec6, 'QT-1006 已关闭，第 2 轮不该再判它（决策行仍是 1 条）').toHaveLength(1)
        expect(
          run2.decisions.find((x) => x.orderSn === 'QT-1006'),
          '第 2 轮 run 的返回里不该再出现 QT-1006（候选只含待审核/冻结单）'
        ).toBeFalsy()
        expect(orderFactOf('QT-1006').status, 'QT-1006 仍是 4 已关闭').toBe(4)

        const id3 = orderFactOf('QT-1003').id
        const dec3 = decisionsOf(id3)
        expect(dec3.map((x) => x.roundNo), 'QT-1003 仍待审核 → 第 2 轮应再判一次（1、2 两行）').toEqual([1, 2])
        expect(dec3[1].result, '第 2 轮结论仍是 NO_MATCH（事实没变，结论不该变）').toBe('NO_MATCH')
        expect(orderFactOf('QT-1003').status, 'QT-1003 仍是 7 待审核').toBe(7)
      })
    } finally {
      resetAuditDemoBaseline()   // 决策/标/台账清掉，QT-1006 从 4 已关闭回到 7 待审核，SKU 库存回 seed 值
    }
  })
})
