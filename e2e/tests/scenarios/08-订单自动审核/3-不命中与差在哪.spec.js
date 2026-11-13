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
  missGroupOf,
  conditionOf,
  unevaluatedConditions,
  ledgerRowsOf,
  activeTagsOf,
  POLICY_NAME
} from '../../support/audit-fixtures.js'
import { getQueueRowOf, getPolicyOf } from '../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  badgeOf,
  reasonMainOf,
  reasonSubOf,
  runAuditViaUi,
  openWhyDrawer,
  closeWhyDrawer,
  whyGroupOf,
  whyCondRow,
  whyRowMiss,
  whyRowUnevaluated
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

    // 抽屉里的策略组按 id 精确取（前缀选择器会同时命中组标题的 audit-why-policy-name-<id>）
    const taobaoPolicy = await getPolicyOf(request, token, POLICY_NAME.AUTO_TAOBAO)
    const douyinPolicy = await getPolicyOf(request, token, POLICY_NAME.AUTO_DOUYIN)

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
        // 灰字是引擎原话：逐条策略、只列**真不满足**的条件（实测文案：直营-小单 客户标签+应收<300；
        // 淘宝-标准单 渠道+应收<500；抖音-标准单 应收<500 —— 种类数 1≤5 满足，故一句都不出现）
        await expect(reasonSubOf(row3), '灰字原因应逐条列出差在哪，含「应收金额 1580.00 < 500.00」')
          .toContainText('应收金额 1580.00 < 500.00')
        await expect(reasonSubOf(row3), '淘宝-标准单 差在渠道：期望淘宝、实际抖音').toContainText('渠道 = 淘宝（实际 抖音）')
        await expect(reasonSubOf(row3), '直营-小单 差在客户标签').toContainText('客户标签 = 直营门店（实际 普通）')
        await expect(reasonSubOf(row3), '直营-小单 也差应收门槛 300（1580 < 300 同样是假）')
          .toContainText('应收金额 1580.00 < 300.00')
        await expect(reasonSubOf(row3), 'QT-1003 的灰字原因里**不该**有种类数那句（1 ≤ 5 满足）')
          .not.toContainText('商品种类数')
        await expect(row3, 'QT-1003 应是第 1 轮').toContainText('第 1 轮')
        await expect(row3, 'QT-1003 应收列应显示 1580.00').toContainText('1580.00')

        const row6 = auditQueueRow(page, 'QT-1006')
        await expect(row6, '队列里应有 QT-1006 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row6), 'QT-1006 徽标应是 NO_MATCH').toHaveAttribute('data-result', 'NO_MATCH')
        await expect(reasonMainOf(row6), 'QT-1006 原因首句也说「没有任何自动策略命中」')
          .toContainText('没有任何自动策略命中')
        await expect(reasonSubOf(row6), 'QT-1006 灰字原因应含「商品种类数 6 ≤ 5」')
          .toContainText('商品种类数 6 ≤ 5')
        // 实测原文：直营-小单：客户标签 = 直营门店（实际 普通）；应收金额 420.00 < 300.00
        //           | 淘宝-标准单：商品种类数 6 ≤ 5
        //           | 抖音-标准单：渠道 = 抖音（实际 淘宝）；商品种类数 6 ≤ 5
        await expect(reasonSubOf(row6), '抖音-标准单 差在渠道：期望抖音、实际淘宝（不是「渠道 = 淘宝」）')
          .toContainText('渠道 = 抖音（实际 淘宝）')
        await expect(reasonSubOf(row6), '直营-小单 差在客户标签').toContainText('客户标签 = 直营门店（实际 普通）')
        await expect(reasonSubOf(row6), '直营-小单 也差应收门槛 300（420 < 300）')
          .toContainText('应收金额 420.00 < 300.00')
        // **满足的条件一句都不出现**：淘宝-标准单 的渠道/应收都满足，文案里不该有它们
        await expect(reasonSubOf(row6), '淘宝-标准单 的渠道是满足的，不该出现在「差在哪」里')
          .not.toContainText('渠道 = 淘宝（实际 淘宝）')
        await expect(reasonSubOf(row6), '淘宝-标准单 的应收 420 < 500 是满足的，不该出现在「差在哪」里')
          .not.toContainText('应收金额 420.00 < 500.00')
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
        await expect(whyRowMiss(drawer), '「不满足」的真实数据行恰好 3 行（每个策略各一条）').toHaveCount(3)
        // 未求值按**行级 class** 数：真实是 2 行（直营的应收 1580<300、淘宝的应收 1580<500）。
        // 不能数 testid —— el-table 的 hidden-columns 每张策略表会多克隆一个「未求值」节点（实测数出 5 个）。
        await expect(whyRowUnevaluated(drawer), '首个不满足之后仍不满足的条件应标「未求值」（2 行）')
          .toHaveCount(2)

        // 具体差值：抽屉里的「后端留痕」行原样保留引擎的文案
        await expect(drawer, '抽屉里应原样写出「应收金额 1580.00 < 500.00」')
          .toContainText('应收金额 1580.00 < 500.00')
        await expect(drawer, '抽屉里应写出直营-小单差在哪（客户标签 = 直营门店，实际 普通）')
          .toContainText('客户标签 = 直营门店')
        await expect(drawer, '抽屉里应写出淘宝-标准单差在哪（渠道 = 淘宝，实际 抖音）')
          .toContainText('渠道 = 淘宝')
        // 种类数这条在抖音-标准单里是「满足」的（1 ≤ 5），所以它不该出现在那条策略的「差在哪」文案里
        const douyinGroup = whyGroupOf(drawer, douyinPolicy.id)
        await expect(douyinGroup, '抽屉里应有「抖音-标准单」这一组').toBeVisible()
        await expect(douyinGroup.locator('.why-backend-lines'), '抖音-标准单 的「差在哪」文案里不该有种类数那句')
          .not.toContainText('商品种类数')
        // 字段级三态（这一条是本用例的核心）：渠道满足 → 无标记；应收不满足 → 标「差在哪」；种类数满足 → 无标记
        await expect(whyCondRow(douyinGroup, 'CHANNEL').getByTestId('audit-why-mark'),
          '抖音-标准单 的渠道条件满足（抖音=抖音），不该标「差在哪」').toHaveCount(0)
        await expect(whyCondRow(douyinGroup, 'CHANNEL'), '该行要写清期望与实际（抖音 / 抖音）').toContainText('抖音')
        await expect(whyCondRow(douyinGroup, 'RECEIVABLE_AMOUNT').getByTestId('audit-why-mark'),
          '应收 1580 < 500 不满足，必须标「← 差在哪」').toHaveCount(1)
        await expect(whyCondRow(douyinGroup, 'RECEIVABLE_AMOUNT'), '该行期望 500、实际 1580')
          .toContainText('1580')
        await expect(whyCondRow(douyinGroup, 'ITEM_KIND_COUNT').getByTestId('audit-why-mark'),
          '种类数 1 ≤ 5 满足，不该标「差在哪」').toHaveCount(0)

        await closeWhyDrawer(page)
      })

      await test.step('⑤ 差在哪抽屉（QT-1006）：差在种类数 6 ≤ 5，且没有未求值行（它就是最后一条）', async () => {
        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1006'))

        const marks = drawer.getByTestId('audit-why-mark')
        await expect(marks, '应至少有一行标「← 差在哪」（种类数 6 ≤ 5）').not.toHaveCount(0)
        await expect(drawer, '抽屉里应原样写出「商品种类数 6 ≤ 5」').toContainText('商品种类数 6 ≤ 5')
        // 淘宝-标准单 的三个条件里，种类数是最后一条：它不满足 → 同组内没有「未求值」行
        const taobaoGroup = whyGroupOf(drawer, taobaoPolicy.id)
        await expect(taobaoGroup, '抽屉里应有「淘宝-标准单」这一组').toBeVisible()
        await expect(whyRowUnevaluated(taobaoGroup), '这条策略的三个条件都被求值过，不该有未求值行')
          .toHaveCount(0)
        await expect(taobaoGroup.getByTestId('audit-why-mark'), '差在种类数这一条（6 > 5）').toHaveCount(1)
        // 字段级：渠道满足、应收 420<500 满足、种类数 6>5 不满足
        await expect(whyCondRow(taobaoGroup, 'CHANNEL').getByTestId('audit-why-mark'),
          '淘宝-标准单 的渠道条件满足（淘宝=淘宝），不该标「差在哪」').toHaveCount(0)
        await expect(whyCondRow(taobaoGroup, 'RECEIVABLE_AMOUNT').getByTestId('audit-why-mark'),
          '应收 420 < 500 满足，不该标「差在哪」').toHaveCount(0)
        await expect(whyCondRow(taobaoGroup, 'ITEM_KIND_COUNT').getByTestId('audit-why-mark'),
          '种类数 6 > 5 不满足，必须标「← 差在哪」').toHaveCount(1)
        await expect(whyCondRow(taobaoGroup, 'ITEM_KIND_COUNT'), '该行期望 5、实际 6').toContainText('6')

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
          expect(Array.isArray(miss), `${sn} 的 miss_reason_json 应是数组`).toBe(true)
          const groups = miss.map((g) => String(g.policyName))
          expect(groups, `${sn} 的留痕应逐条列出三条 AUTO 策略`)
            .toEqual(expect.arrayContaining([POLICY_NAME.AUTO_DIRECT, POLICY_NAME.AUTO_TAOBAO, POLICY_NAME.AUTO_DOUYIN]))
        }

        // ---- 逐字段：把「差在哪一条、期望多少、实际多少、有没有被求值」钉死 ----
        const id3 = orderFactOf('QT-1003').id
        const id6 = orderFactOf('QT-1006').id

        // QT-1003：抖音-标准单 渠道满足、应收 1580<500 不满足、种类数 1≤5 满足但 evaluated=false
        const miss3 = decisionJsonOf(id3, 1, 'miss_reason_json')
        const douyin3 = missGroupOf(miss3, POLICY_NAME.AUTO_DOUYIN)
        const channel3 = conditionOf(douyin3, 'CHANNEL')
        expect(channel3.satisfied, 'QT-1003 渠道=抖音，抖音-标准单 的渠道条件应满足').toBe(true)
        const amount3 = conditionOf(douyin3, 'RECEIVABLE_AMOUNT')
        expect(amount3.opCode, '应收是「小于」').toBe('LT')
        expect(amount3.expected, '应收门槛 500.00').toBe('500.00')
        expect(amount3.actual, '实际应收 1580.00').toBe('1580.00')
        expect(amount3.satisfied, '1580 < 500 不成立').toBe(false)
        const kinds3 = conditionOf(douyin3, 'ITEM_KIND_COUNT')
        expect(kinds3.satisfied, '种类数 1 ≤ 5 其实是满足的').toBe(true)
        expect(kinds3.evaluated, '但它在首个不满足之后 → evaluated=false（这就是「未求值」的来源）').toBe(false)
        expect(String(douyin3.reasons.join('|')), '抖音-标准单 的人话里只该有应收那句').toContain('应收金额 1580.00 < 500.00')
        expect(String(douyin3.reasons.join('|')), '抖音-标准单 的人话里不该有种类数那句')
          .not.toContain('商品种类数')

        // QT-1006：淘宝-标准单 渠道/应收满足、种类数 6>5 不满足（三个条件都被求值过）
        const miss6 = decisionJsonOf(id6, 1, 'miss_reason_json')
        const taobao6 = missGroupOf(miss6, POLICY_NAME.AUTO_TAOBAO)
        expect(conditionOf(taobao6, 'CHANNEL').satisfied, 'QT-1006 渠道=淘宝，该条件满足').toBe(true)
        expect(conditionOf(taobao6, 'RECEIVABLE_AMOUNT').satisfied, '420 < 500 满足').toBe(true)
        const kinds6 = conditionOf(taobao6, 'ITEM_KIND_COUNT')
        expect(kinds6.opCode).toBe('LTE')
        expect(kinds6.expected, '种类数门槛 5').toBe('5')
        expect(kinds6.actual, '实际 6 个样').toBe('6')
        expect(kinds6.satisfied, '6 ≤ 5 不成立').toBe(false)
        expect(unevaluatedConditions([taobao6]).length, '这条策略的三个条件都被求值过，没有 evaluated=false').toBe(0)
        expect(String(taobao6.reasons.join('|')), '淘宝-标准单 的人话里应只有种类数那句')
          .toContain('商品种类数 6 ≤ 5')
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
