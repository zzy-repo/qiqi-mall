import { test, expect } from '../../support/test.js'
import { API, ADMIN } from '../../support/env.js'
import { dbOfApi } from '../../support/db.js'
import { adminLoginHardened, expectToastSuccess } from '../../support/admin-ui.js'
import { adminTokenOf } from '../../support/assert.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  rejectDemoReturnApply,
  orderFactOf,
  decisionsOf,
  decisionJsonOf,
  conditionOf,
  ledgerRowsOf,
  activeTagsOf,
  allTagsOf,
  orderAuditActionsOf,
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
  unfreezeRow
} from '../../support/audit-ui.js'

/**
 * 08-订单自动审核 / 2-阻止与冻结
 *
 * 这个场景钉住判定链的**前两步**（顺序固定，不可插队）：
 *
 *   [3] BLOCK 命中  → BLOCKED：拦是**结论**，不是动作 —— 不动订单状态、不写台账、不打标；
 *                    同时回填 would_be（「若没被拦，本来会命中哪条 AUTO」），供运营谈单。
 *   [4] EXCEPTION 命中 → FROZEN：打冻结标 + 写操作审计，**也不动状态**；
 *                    冻结期间这张单仍参与每轮 run，但结论是 SKIPPED（除非解冻）。
 *
 * 两笔样本：
 *   QT-1004 直营门店 199 元 毛利 −12 → BLOCKED（命中「毛利不为正」），would_be = 「直营-小单」
 *   QT-1005 淘宝 320 元 + 待处理退款申请 → 第 1 轮 FROZEN（命中「退款申请冻结」）
 *           → 处理申请 + 页面解冻 → 第 2 轮 AUTO_PASS（命中「淘宝-标准单」，7→8）
 *
 * ── 为什么 would_be 这条要同时读库和读接口 ────────────────────────────────
 *   后端把 would-be 落在 `order_audit_decision.would_be_auto_policy_name` + `evidence_json.would_be`，
 *   队列接口也原样回传 `would_be_auto_policy_name`；但**前端没有渲染它**（mapQueueRow 丢掉了这一列，
 *   页面无处显示），所以「回显」只能在接口 + 库上证明。这条已作为前端缺口登记。
 *
 * ── 「处理申请」为什么走库 ────────────────────────────────────────────────
 *   见 audit-fixtures.js#rejectDemoReturnApply 的注释：seed 造的「status=7 且有待处理退款申请」
 *   是业务上不可达的组合，后台退货审核两条路都会撞上状态机（通过要求 5；拒绝的 7→1 非法）。
 *   所以那一步是**造数**，真正的被测交互是**页面解冻**（点「解冻」→ 确认框 → 标被解除）。
 */
test.describe('08-订单自动审核 › 2-阻止与冻结', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[08-订单自动审核/2] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  /* ================================================================== 阻止 */

  test('QT-1004 毛利 −12 被阻止策略拦住：不动状态/不写台账，would_be 回填「直营-小单」', async ({ page, request }) => {
    test.setTimeout(240_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    try {
      await test.step('① 复位基线：QT-1004 待审核、毛利 −12.00、直营门店客户', async () => {
        resetAuditDemoBaseline()
        const f = orderFactOf('QT-1004')
        expect(f.status, 'QT-1004 起点应是 7 待审核').toBe(7)
        expect(f.grossProfit, 'QT-1004 的毛利应是 −12.00（阻止策略的唯一触发事实）').toBe(-12)
        expect(f.totalAmount, 'QT-1004 应收 199.00').toBe(199)
        expect(decisionsOf(f.id), '起点不应有决策行').toHaveLength(0)
      })

      let run = null
      await test.step('② 跑一轮 → 队列徽标「阻止」+ 原因写明毛利 −12.00 ≤ 0', async () => {
        await gotoAuditQueue(page)
        run = await runAuditViaUi(page)

        const d = run.decisions.find((x) => x.orderSn === 'QT-1004')
        expect(d?.result, 'run 返回里 QT-1004 应是 BLOCKED').toBe('BLOCKED')
        expect(d?.matchedPolicyName, 'QT-1004 命中的应是阻止策略「毛利不为正」').toBe(POLICY_NAME.BLOCK_PROFIT)
        expect(d?.wouldBeAutoPolicyName, 'run 返回里应回填 would-be = 「直营-小单」').toBe(POLICY_NAME.AUTO_DIRECT)
        expect(d?.reason, '原因文案应含具体数值差「毛利 −12.00 ≤ 0」').toContain('毛利 −12.00 ≤ 0')

        const row = auditQueueRow(page, 'QT-1004')
        await expect(row, '队列里应有 QT-1004 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row), 'QT-1004 徽标应是 BLOCKED').toHaveAttribute('data-result', 'BLOCKED')
        await expect(badgeOf(row), 'QT-1004 徽标文案应是「阻止」').toHaveText('阻止')
        await expect(reasonMainOf(row), '原因首句应说清命中了哪条阻止策略')
          .toContainText(`命中阻止策略「${POLICY_NAME.BLOCK_PROFIT}」`)
        await expect(reasonSubOf(row), '灰字原因应把「毛利 −12.00 ≤ 0」写出来（排版减号 U+2212）')
          .toContainText('毛利 −12.00 ≤ 0')
        await expect(row, 'QT-1004 应是第 1 轮').toContainText('第 1 轮')
        await expect(row, 'QT-1004 的毛利列应显示负数（不是 0 也不是空）').toContainText('12.00')
      })

      await test.step('③ 直查库：结论落决策行，订单状态与台账**一点没动**，也没有任何标', async () => {
        const f = orderFactOf('QT-1004')
        const dec = decisionsOf(f.id)
        expect(dec, 'QT-1004 应恰好 1 条决策').toHaveLength(1)
        expect(dec[0].result, '决策结果应是 BLOCKED').toBe('BLOCKED')
        expect(dec[0].matchedPolicyName, '命中策略名应是「毛利不为正」').toBe(POLICY_NAME.BLOCK_PROFIT)
        expect(dec[0].roundNo, '应是第 1 轮').toBe(1)
        expect(dec[0].wouldBePolicyName, '决策行应回填 would_be = 「直营-小单」').toBe(POLICY_NAME.AUTO_DIRECT)
        expect(dec[0].orderStatusSeq, '被拦不推进状态，status_seq 仍是 0').toBe(0)

        const after = orderFactOf('QT-1004')
        expect(after.status, '阻止不改订单状态（仍是 7 待审核，等人联系客户）').toBe(7)
        expect(after.statusSeq, '阻止不推 status_seq').toBe(0)
        expect(ledgerRowsOf(f.id), '阻止不写状态台账（拦是结论，不是动作）').toHaveLength(0)
        expect(activeTagsOf(f.id), '阻止不打任何标（冻结才是标）').toHaveLength(0)

        // would-be 的完整形态：命中方式和真过审完全一样，只是结论被丢弃（证据在 evidence_json 里）。
        // ⚠️ 按**结构**断言：MySQL 输出 JSON 列时是 `"policy_name": "直营-小单"`（键值之间有空格），
        //    任何「字符串 contains `"policy_name":"直营-小单"`」都必红（实测踩过）。
        const evidence = decisionJsonOf(f.id, 1, 'evidence_json')
        expect(evidence.result, 'evidence.result 应是 BLOCKED').toBe('BLOCKED')
        expect(evidence.would_be?.policy_name, 'evidence.would_be.policy_name 应是「直营-小单」')
          .toBe(POLICY_NAME.AUTO_DIRECT)
        expect(evidence.would_be?.policy_id, 'would_be 应带上策略 id（不是只有名字）').toBeTruthy()
        // would_be 的条件留痕：命中方式和真过审完全一样，门槛与实值都在
        const wouldBeConds = evidence.would_be?.conditions || []
        expect(Array.isArray(wouldBeConds), 'would_be.conditions 应是条件数组').toBe(true)
        const wbAmount = conditionOf({ policyName: '直营-小单', conditions: wouldBeConds }, 'RECEIVABLE_AMOUNT')
        expect(wbAmount.expected, 'would_be 的应收门槛应是 300.00（应收 199 < 300 才会命中）').toBe('300.00')
        expect(wbAmount.actual, '该单实际应收 199.00').toBe('199.00')
        expect(wbAmount.satisfied, 'would_be 的条件当然是满足的（否则它也不叫 would-be）').toBe(true)
        expect(evidence.cost?.gross_profit, 'evidence.cost.gross_profit 应是 −12').toBe(-12)
        expect(String(evidence.reason), 'evidence_json 的 reason 应含具体数值差')
          .toContain('毛利 −12.00 ≤ 0.00')
      })

      await test.step('④ 接口回显：队列行带回 would_be_auto_policy_name（页面暂不展示 —— 已登记前端缺口）', async () => {
        const row = await getQueueRowOf(request, token, 'QT-1004')
        expect(row.result, '接口里 QT-1004 的最新结论应是 BLOCKED').toBe('BLOCKED')
        expect(row.would_be_auto_policy_name, '接口应回显 would-be 策略名「直营-小单」').toBe(POLICY_NAME.AUTO_DIRECT)
        expect(row.matched_policy_name, '接口应回显命中策略名').toBe(POLICY_NAME.BLOCK_PROFIT)
        expect(Number(row.gross_profit), '接口里的毛利应是 −12.00').toBe(-12)
        expect(row.reason_text, '接口里的原因（reason_text）应含具体差值').toContain('毛利 −12.00 ≤ 0')
      })
    } finally {
      resetAuditDemoBaseline()
    }
  })

  /* ================================================================== 冻结 */

  test('QT-1005 有退款申请 → 第 1 轮冻结；处理申请 + 页面解冻 → 第 2 轮自动过审（round 1→2）', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    try {
      await test.step('① 复位基线：QT-1005 待审核且有一笔待处理退款申请', async () => {
        resetAuditDemoBaseline()
        const f = orderFactOf('QT-1005')
        expect(f.status, 'QT-1005 起点应是 7 待审核').toBe(7)
        expect(decisionsOf(f.id), '起点不应有决策行（第 1 轮还没跑）').toHaveLength(0)
        expect(activeTagsOf(f.id), '起点不应有冻结标').toHaveLength(0)
      })

      let run1 = null
      await test.step('② 第 1 轮 run → 徽标「冻结」，原因写明有退款申请 = 是', async () => {
        await gotoAuditQueue(page)
        run1 = await runAuditViaUi(page)

        const d = run1.decisions.find((x) => x.orderSn === 'QT-1005')
        expect(d?.result, 'run 返回里 QT-1005 应是 FROZEN（异常策略优先于自动过审）').toBe('FROZEN')
        expect(d?.matchedPolicyName, '命中的应是异常策略「退款申请冻结」').toBe(POLICY_NAME.EXCEPTION_RETURN)

        const row = auditQueueRow(page, 'QT-1005')
        await expect(row, '队列里应有 QT-1005 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row), 'QT-1005 徽标应是 FROZEN').toHaveAttribute('data-result', 'FROZEN')
        await expect(badgeOf(row), 'QT-1005 徽标文案应是「冻结」').toHaveText('冻结')
        await expect(reasonMainOf(row), '原因首句应说清命中异常策略并已冻结')
          .toContainText(`命中异常策略「${POLICY_NAME.EXCEPTION_RETURN}」`)
        await expect(reasonSubOf(row), '灰字原因应写出触发事实「有退款申请 = 是（实际 是）」')
          .toContainText('有退款申请 = 是')
        await expect(row, 'QT-1005 应是第 1 轮').toContainText('第 1 轮')
        // FROZEN 行才有「解冻」按钮 —— 这是页面对「已经冻上」的状态表达
        await expect(row.getByTestId('audit-row-unfreeze'), '冻结行应提供「解冻」按钮').toBeVisible()
      })

      await test.step('③ 直查库：冻结 = 打标 + 写审计，状态仍是 7、台账仍是空的', async () => {
        const f = orderFactOf('QT-1005')
        const dec = decisionsOf(f.id)
        expect(dec, '第 1 轮只落 1 条决策').toHaveLength(1)
        expect(dec[0].result, '决策结果应是 FROZEN').toBe('FROZEN')
        expect(dec[0].matchedPolicyName, '命中策略名应是「退款申请冻结」').toBe(POLICY_NAME.EXCEPTION_RETURN)
        expect(dec[0].roundNo, '这是第 1 轮').toBe(1)

        const after = orderFactOf('QT-1005')
        expect(after.status, '冻结不改状态（仍 7 待审核：它确实还没审完，只是被按住）').toBe(7)
        expect(ledgerRowsOf(f.id), '冻结不写状态台账（7→7 不是流转）').toHaveLength(0)

        const tags = activeTagsOf(f.id)
        expect(tags, '冻结应留下 1 个 active 标').toHaveLength(1)
        expect(tags[0].tagCode, '标码应是 FROZEN（FROZEN% 前缀都算冻结）').toMatch(/^FROZEN/)
        expect(tags[0].reason, '标的 reason 应是策略里配的冻结原因').toContain('退款申请')

        const actions = orderAuditActionsOf(f.id)
        expect(actions.map((a) => a.action), '操作审计里应有自动冻结这一笔（谁冻的、凭什么冻）')
          .toContain('AUDIT_FREEZE')
        expect(actions.find((a) => a.action === 'AUDIT_FREEZE').summary, '审计摘要应说明是自动审核的哪条策略冻的')
          .toContain(POLICY_NAME.EXCEPTION_RETURN)
      })

      await test.step('④ 处理退款申请（造数，见文件头）→ 页面点「解冻」→ 标被解除、留一条解冻审计', async () => {
        // 造数：把待处理申请置为已拒绝，让 HAS_RETURN_APPLY 变否（否则第 2 轮仍会被冻）
        const applyId = rejectDemoReturnApply('QT-1005')
        expect(applyId, '应有一条待处理退款申请被处理').toBeGreaterThan(0)

        const row = auditQueueRow(page, 'QT-1005')
        await unfreezeRow(page, row)
        await expectToastSuccess(page, '已解冻')
        await page.waitForTimeout(1200)

        const f = orderFactOf('QT-1005')
        expect(activeTagsOf(f.id), '解冻后不应再有 active 的冻结标').toHaveLength(0)
        const all = allTagsOf(f.id)
        expect(all.length, '解冻是「解除」而不是「删行」：标行要留着当证据').toBe(1)
        expect(all[0].active, '标行应是 active=0').toBe(0)
        expect(all[0].releasedBy, '解除人应被记下（不是空）').not.toBe('')
        expect(orderAuditActionsOf(f.id).map((a) => a.action), '解冻应写一条 AUDIT_UNFREEZE 审计')
          .toContain('AUDIT_UNFREEZE')
        expect(orderFactOf('QT-1005').status, '解冻同样不动状态（仍是 7，等下一轮重新判定）').toBe(7)
      })

      let run2 = null
      await test.step('⑤ 第 2 轮 run → 自动过审（7→8），决策 round 1→2 且结论变了', async () => {
        await gotoAuditQueue(page)
        run2 = await runAuditViaUi(page)
        expect(Number(run2.runId), '第 2 轮应是另一个 run（MANUAL 不做幂等重放）').toBeGreaterThan(Number(run1.runId))

        const d = run2.decisions.find((x) => x.orderSn === 'QT-1005')
        expect(d?.result, '第 2 轮 QT-1005 应是 AUTO_PASS').toBe('AUTO_PASS')
        expect(d?.roundNo, '第 2 轮 run 里这张单应是第 2 轮').toBe(2)
        expect(d?.matchedPolicyName, '第 2 轮命中的应是「淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)

        const row = auditQueueRow(page, 'QT-1005')
        await expect(badgeOf(row), '队列行应刷新成 AUTO_PASS（队列显示每单最新一轮）')
          .toHaveAttribute('data-result', 'AUTO_PASS')
        await expect(row, '队列行应显示「第 2 轮」').toContainText('第 2 轮')
        await expect(reasonSubOf(row), '灰字原因应换成自动过审的具体差值')
          .toContainText('应收金额 320.00 < 500.00')

        // ---- 库：两轮决策都在，结论不同；状态台账只有 1 行 7→8，ref 指向第 2 轮 ----
        const f = orderFactOf('QT-1005')
        const dec = decisionsOf(f.id)
        expect(dec.map((x) => x.roundNo), '决策轮次应是 1、2（同一单多轮各一行）').toEqual([1, 2])
        expect(dec.map((x) => x.result), '第 1 轮冻结、第 2 轮自动过审（结论变了但历史行不改）')
          .toEqual(['FROZEN', 'AUTO_PASS'])
        expect(dec[1].matchedPolicyName, '第 2 轮命中策略名应是「淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(dec[1].orderStatusSeq, '第 2 轮决策应记下本轮推进出来的 status_seq=1').toBe(1)

        const after = orderFactOf('QT-1005')
        expect(after.status, '第 2 轮后订单应推进到 8 待发货').toBe(8)
        expect(after.statusSeq, 'status_seq 应是 1（唯一一次流转）').toBe(1)

        const led = ledgerRowsOf(f.id)
        expect(led, '两轮里只有第 2 轮推了状态 → 只应有 1 行台账').toHaveLength(1)
        expect(led[0].from, '台账前态 7').toBe(7)
        expect(led[0].to, '台账后态 8').toBe(8)
        expect(led[0].reason, '原因码应是 AUDIT_AUTO_PASS').toBe('AUDIT_AUTO_PASS')
        expect(led[0].refType, '台账应记 ref_type=AUDIT_RUN').toBe('AUDIT_RUN')
        expect(led[0].refId, '台账 ref_id 应是第 2 轮 run 的 id（不是第 1 轮）').toBe(Number(run2.runId))
      })

      await test.step('⑥ 接口回读：最新一轮是 AUTO_PASS / 第 2 轮', async () => {
        const row = await getQueueRowOf(request, token, 'QT-1005')
        expect(row.result, '接口里 QT-1005 最新结论应是 AUTO_PASS').toBe('AUTO_PASS')
        expect(Number(row.round_no), '接口里应是第 2 轮').toBe(2)
        expect(row.matched_policy_name, '接口里命中策略应是「淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(Number(row.order_status), '接口里订单状态应是 8 待发货').toBe(8)
      })
    } finally {
      resetAuditDemoBaseline()   // 决策/标/台账清掉，退款申请回到待处理，订单回 7
    }
  })
})
