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
  ledgerRowsOf,
  orderAuditActionsOf
} from '../../support/audit-fixtures.js'
import { getAuditTrail, trailDecisions, trailSegments } from '../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  runAuditViaUi,
  unfreezeRow,
  openTrailDrawer,
  closeTrailDrawer,
  openOrderPageTrailTab
} from '../../support/audit-ui.js'

/**
 * 08-订单自动审核 / 4-审核轨迹与对齐
 *
 * 这个场景要证明的一件事：
 *   **一张单「为什么是现在这个样子」，能在一屏里答完** —— 而且三段证据是**对得上**的，
 *   不是三堆各自好看的时间线。
 *
 * 三段（后端 AuditQueryService.trail 把三张表按时间拼成一个数组）：
 *   DECISION 决策段：每轮怎么判的（结论 / 命中策略 + 版本 / 人话原因）
 *   STATUS   台账段：状态真的变了吗（7→8，reason=AUDIT_AUTO_PASS，ref=AUDIT_RUN#runId）
 *   AUDIT    审计段：谁点的（自动冻结 AUDIT_FREEZE / 人工解冻 AUDIT_UNFREEZE）
 *
 * 为什么用 QT-1005 当样本：它是唯一一条**三段都非空**的单 ——
 *   第 1 轮 FROZEN（自动冻结 → 写审计）、人工解冻（写审计）、第 2 轮 AUTO_PASS（推进状态 → 写台账）。
 * 纯自动过审的单（QT-1001）第三段是空的：run 级审计的 order_id 为 NULL，不挂在单上（已登记）。
 *
 * ── 「强对齐」到底强在哪（这一条只能靠接口 + 库证明）─────────────────────
 *   决策行的 `order_status_seq` 说的是「这一轮推进出来的那条台账的序号」，
 *   台账行的 `ref_type='AUDIT_RUN' && ref_id=run_id` 说的是「这条台账是本轮 run 写的」。
 *   两者同时成立 = **RUN_REF 强对齐**（同一条因果）；只有序号相同 = STATUS_SEQ 弱对齐。
 *   前端目前只显示「已对齐状态台账 seq=N」，`matchedBy / strong` 两个字段被 mapTrailEntry 丢掉了
 *   （已登记前端缺口），所以断言打在接口的 `aligned` 与库的 `ref_type/ref_id` 上。
 */
test.describe('08-订单自动审核 › 4-审核轨迹与对齐', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[08-订单自动审核/4] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  test('QT-1005 的三段轨迹齐全，且第 2 轮决策与 7→8 台账是 RUN_REF 强对齐', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    let run1 = null
    let run2 = null

    try {
      await test.step('① 造出三段都非空的现场：第 1 轮冻结 → 处理申请 + 页面解冻 → 第 2 轮自动过审', async () => {
        resetAuditDemoBaseline()
        await gotoAuditQueue(page)

        run1 = await runAuditViaUi(page)
        expect(run1.decisions.find((d) => d.orderSn === 'QT-1005')?.result, '第 1 轮应先冻结').toBe('FROZEN')

        rejectDemoReturnApply('QT-1005')                       // 造数：处理退款申请（见 audit-fixtures 注释）
        await unfreezeRow(page, auditQueueRow(page, 'QT-1005'))
        await expectToastSuccess(page, '已解冻')

        run2 = await runAuditViaUi(page)
        expect(run2.decisions.find((d) => d.orderSn === 'QT-1005')?.result, '第 2 轮应自动过审').toBe('AUTO_PASS')
      })

      await test.step('② 队列页「轨迹」抽屉：三段齐全（决策 2 / 台账 1 / 审计 ≥2）', async () => {
        await gotoAuditQueue(page)
        const drawer = await openTrailDrawer(page, auditQueueRow(page, 'QT-1005'))

        // ---- 三段都在 ----
        const decisions = drawer.getByTestId('audit-trail-decision')
        const statuses = drawer.getByTestId('audit-trail-status')
        const audits = drawer.getByTestId('audit-trail-audit')

        await expect(decisions, '决策段应有 2 条（第 1 轮冻结 + 第 2 轮自动过审）').toHaveCount(2, { timeout: 20_000 })
        await expect(statuses, '台账段应有 1 条（唯一一次 7→8）').toHaveCount(1)
        await expect(audits, '审计段应有自动冻结 + 人工解冻两条').not.toHaveCount(0)

        // ---- 段标题（决策段用「第 N 轮判定 → 中文结局」而不是英文码）----
        await expect(decisions.nth(0), '第 1 条决策应是「第 1 轮判定 → 冻结」')
          .toContainText('第 1 轮判定 → 冻结')
        await expect(decisions.nth(1), '第 2 条决策应是「第 2 轮判定 → 自动过审」')
          .toContainText('第 2 轮判定 → 自动过审')
        await expect(statuses.first(), '台账段标题应写清 待审核 → 待发货').toContainText('待审核')
        await expect(statuses.first(), '台账段标题应写清后态「待发货」').toContainText('待发货')
        await expect(statuses.first(), '台账段应显示原因码 AUDIT_AUTO_PASS').toContainText('AUDIT_AUTO_PASS')
        await expect(audits.first(), '审计段应能看出是冻结/解冻这类动作').toContainText('AUDIT_')

        // ---- 三段的图例计数与卡片数一致（页眉计数不是写死的）----
        await expect(drawer.getByTestId('audit-trail-legend-DECISION'), '图例里决策段计数应是 2').toContainText('2')
        await expect(drawer.getByTestId('audit-trail-legend-STATUS'), '图例里台账段计数应是 1').toContainText('1')

        // ---- 对齐：第 2 轮对上了台账；第 1 轮没有推进状态，也就没得对齐 ----
        await expect(drawer.getByTestId('audit-trail-aligned'), '第 2 轮决策应显示「已对齐状态台账 seq=1」')
          .toBeVisible()
        await expect(drawer.getByTestId('audit-trail-aligned'), '对齐标签里应写出序号 seq=1').toContainText('seq=1')
        await expect(drawer.getByTestId('audit-trail-align-pending'), '第 1 轮（冻结轮）没推进状态，应标「无法精确对齐」')
          .toBeVisible()

        await closeTrailDrawer(page)
      })

      await test.step('③ 接口：决策 ↔ 台账强对齐（matchedBy=RUN_REF / strong=true）', async () => {
        const id = orderFactOf('QT-1005').id
        const entries = await getAuditTrail(request, token, id)

        const decs = trailDecisions(entries)
        expect(decs.length, '时间线里应有 2 条决策').toBe(2)
        expect(decs[0].raw.result, '第 1 条决策是 FROZEN').toBe('FROZEN')
        expect(decs[0].aligned.matchedBy, '冻结轮没有推进状态 → 不该有对齐方式').toBeNull()
        expect(decs[0].aligned.strong, '冻结轮不是强对齐').toBe(false)

        expect(decs[1].raw.result, '第 2 条决策是 AUTO_PASS').toBe('AUTO_PASS')
        expect(decs[1].aligned.matchedBy, '自动过审轮应是 RUN_REF 强对齐（不是「序号恰好相同」）').toBe('RUN_REF')
        expect(decs[1].aligned.strong, '强对齐标记 strong 应为 true').toBe(true)
        expect(Number(decs[1].aligned.statusSeq), '对齐到的台账序号应是 1').toBe(1)
        expect(Number(decs[1].raw.round_no), '决策轮次应是 2').toBe(2)

        const statuses = trailSegments(entries, 'STATUS')
        expect(statuses.length, '台账段应只有 1 条（一次流转一行）').toBe(1)
        expect(String(statuses[0].raw.ref_type), '台账段应回传 ref_type=AUDIT_RUN').toBe('AUDIT_RUN')
        expect(Number(statuses[0].raw.ref_id), '台账段 ref_id 应是第 2 轮 run 的 id').toBe(Number(run2.runId))
        expect(statuses[0].aligned.strong, '台账侧的 strong 也应为 true').toBe(true)
        expect(Number(statuses[0].raw.status_seq), '台账侧回传的 status_seq 应是 1').toBe(1)

        const audits = trailSegments(entries, 'AUDIT')
        expect(audits.length, '审计段应至少有冻结 + 解冻两条').toBeGreaterThanOrEqual(2)
        const actions = audits.map((a) => String(a.raw.action))
        expect(actions, '审计段应含自动冻结 AUDIT_FREEZE').toContain('AUDIT_FREEZE')
        expect(actions, '审计段应含人工解冻 AUDIT_UNFREEZE').toContain('AUDIT_UNFREEZE')
      })

      await test.step('④ 直查库：决策 seq 与台账 seq 同一个数，且台账 ref 指回本轮 run', async () => {
        const id = orderFactOf('QT-1005').id
        const dec = decisionsOf(id)
        expect(dec.map((d) => d.result), '库里两轮结论分别是 FROZEN / AUTO_PASS').toEqual(['FROZEN', 'AUTO_PASS'])
        expect(dec[1].orderStatusSeq, '第 2 轮决策记的 status_seq 应是 1').toBe(1)

        const led = ledgerRowsOf(id)
        expect(led, '库里应只有 1 行状态台账').toHaveLength(1)
        expect(led[0].idemKey, '台账幂等键尾段就是 status_seq').toBe(`${id}:1`)
        expect(Number(led[0].idemKey.split(':').pop()), '台账幂等键尾段应等于决策记的 seq（这就是对齐口径）')
          .toBe(dec[1].orderStatusSeq)
        expect(led[0].refType, '台账 ref_type 应是 AUDIT_RUN').toBe('AUDIT_RUN')
        expect(led[0].refId, '台账 ref_id 应是第 2 轮 run 的 id').toBe(Number(run2.runId))

        // 第三段的库侧来源：admin_audit_log 里这张单的两条动作
        const actions = orderAuditActionsOf(id).map((a) => a.action)
        expect(actions, 'admin_audit_log 应有 AUDIT_FREEZE').toContain('AUDIT_FREEZE')
        expect(actions, 'admin_audit_log 应有 AUDIT_UNFREEZE').toContain('AUDIT_UNFREEZE')
        // 冻结/解冻都不改状态，所以台账仍只有那 1 行（7→8 是第 2 轮写的）
        expect(led[0].reason, '那唯一一行台账的原因应是自动过审').toBe('AUDIT_AUTO_PASS')
      })

      await test.step('⑤ 订单详情「审核轨迹」tab：同一份时间线在订单页也看得见', async () => {
        const drawer = await openOrderPageTrailTab(page, 'QT-1005')

        const decisions = drawer.getByTestId('audit-trail-decision')
        await expect(decisions, '订单页的时间线同样应有 2 条决策').toHaveCount(2, { timeout: 20_000 })
        await expect(drawer.getByTestId('audit-trail-status'), '订单页也应看到那条 7→8 的台账').toHaveCount(1)
        await expect(decisions.nth(1), '订单页的第 2 条决策同样是「第 2 轮判定 → 自动过审」')
          .toContainText('第 2 轮判定 → 自动过审')
      })
    } finally {
      resetAuditDemoBaseline()
    }
  })
})
