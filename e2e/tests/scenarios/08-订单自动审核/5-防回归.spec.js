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
  restorePolicyPriorities,
  orderFactOf,
  decisionsOf,
  ledgerRowsOf,
  activeTagsOf,
  policyRowsOf,
  policyOf,
  policyVersionCountOf,
  auditRunCount,
  POLICY_NAME
} from '../../support/audit-fixtures.js'
import { getQueueRowOf } from '../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  badgeOf,
  runAuditViaUi,
  gotoPolicyPage,
  movePolicy,
  policyNameOrder
} from '../../support/audit-ui.js'

/**
 * 08-订单自动审核 / 5-防回归
 *
 * 两条「最容易被后续改动悄悄弄坏」的性质，各自钉一遍：
 *
 *  ① **重复执行不会重复过审**。
 *     人工点「立即审核一轮」两次（两次都真的跑，MANUAL 刻意不做 run 级幂等 —— 第二次的差异本身是证据），
 *     已经过审的单（7→8）不该再被推一次：它状态不是 7，下一轮就不是候选 → 不会再有第 2 条决策、
 *     也不会再写第 2 行 7→8 台账。把它们写成「一个按钮点两次 = 两次过审」，就是重复发货的源头。
 *
 *  ② **改配置不会改写历史**。
 *     策略的优先级换了位置（上移/下移），历史决策行的 `matched_policy_version_no` 一个字都不能变：
 *     决策行记的是「当时按第几版策略判的」，不是「现在这条策略是第几版」。
 *     顺带钉住另一件事：只挪优先级**不产生**版本快照（`audit_policy_version` 条数不变）——
 *     版本快照的语义是「条件改了才有一版」，挪位置不算改条件。
 *
 * ── 为什么用「两次完整执行」而不是「双击同一个按钮」───────────────────────
 *   「立即审核一轮」点下去先弹 ElMessageBox 确认框，双击的第二次点击落在遮罩上，
 *   测到的是「弹窗能不能被连点穿透」，不是「重复过审」这件事本身。
 *   真正要防的回归是「同一张单被推两次」，所以这里执行两轮完整 run（点两次按钮、确认两次），
 *   再断言「本单只被推过一次」—— 语义一致，且不依赖弹窗的遮挡行为。
 *   （进程内 RUNNING 互斥没有稳定的对外信号：并发两次 POST 可能都成功也可能一条 500，
 *    断言它只会换来一个 flaky 用例，故不作为断言对象。）
 */
test.describe('08-订单自动审核 › 5-防回归', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[08-订单自动审核/5] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  /* ================================================================ ① 重复执行 */

  test('连点两次「执行审核」→ 只过审一次（1 条决策、1 行 7→8 台账、状态只推进一次）', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    try {
      await test.step('① 复位基线并记下 run 条数（相对断言，不依赖绝对值）', async () => {
        resetAuditDemoBaseline()
        expect(orderFactOf('QT-1001').status, 'QT-1001 起点应是 7 待审核').toBe(7)
        expect(decisionsOf(orderFactOf('QT-1001').id), '起点不应有决策行').toHaveLength(0)
      })

      const runsBefore = auditRunCount()
      let run1 = null
      let run2 = null

      await test.step('② 第 1 次执行：QT-1001 自动过审（7→8）', async () => {
        await gotoAuditQueue(page)
        run1 = await runAuditViaUi(page)
        expect(run1.decisions.find((d) => d.orderSn === 'QT-1001')?.result, '第 1 次执行应过审 QT-1001')
          .toBe('AUTO_PASS')
      })

      await test.step('③ 第 2 次执行：真的又跑了一轮（run 必须多一条），但 QT-1001 已不在候选里', async () => {
        run2 = await runAuditViaUi(page)
        expect(Number(run2.runId), '第 2 次执行应是另一个 run（MANUAL 不做幂等重放）')
          .toBeGreaterThan(Number(run1.runId))
        expect(
          run2.decisions.find((d) => d.orderSn === 'QT-1001'),
          '第 2 轮的返回里不该再出现 QT-1001（它已是 8 待发货，不在候选范围）'
        ).toBeFalsy()

        expect(auditRunCount() - runsBefore, '两次点击应恰好产生 2 条 run 记录（不是 1 条，也不是 3 条）').toBe(2)
      })

      await test.step('④ 库断言：只过审一次 —— 1 条决策 / 1 行台账 / 状态 8 / status_seq 只涨 1', async () => {
        const f = orderFactOf('QT-1001')
        const dec = decisionsOf(f.id)
        expect(dec, 'QT-1001 只该有 1 条决策（第 2 轮没再判它）').toHaveLength(1)
        expect(dec[0].roundNo, '那唯一一条决策是第 1 轮').toBe(1)
        expect(dec[0].result, '结论是 AUTO_PASS').toBe('AUTO_PASS')
        expect(dec[0].runId, '决策应挂第 1 次执行的 run 上').toBe(Number(run1.runId))

        const after = orderFactOf('QT-1001')
        expect(after.status, '状态应被推进到 8 待发货（一次）').toBe(8)
        expect(after.statusSeq, 'status_seq 只该涨 1（涨 2 就说明推了两次）').toBe(1)

        const led = ledgerRowsOf(f.id)
        expect(led, '只该有 1 行 7→8 台账（台账是只追加证据，重复会立刻露出来）').toHaveLength(1)
        expect(led[0].from, '台账前态 7').toBe(7)
        expect(led[0].to, '台账后态 8').toBe(8)
        expect(led[0].refId, '台账只该挂在第 1 次的 run 上').toBe(Number(run1.runId))
      })

      await test.step('⑤ UI 断言：队列行仍是「第 1 轮 自动过审」，徽标没有被刷新成别的', async () => {
        await gotoAuditQueue(page)
        const row = auditQueueRow(page, 'QT-1001')
        await expect(row, '队列里仍应有 QT-1001 的行（队列显示每单最新一轮）').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row), '徽标仍是 AUTO_PASS').toHaveAttribute('data-result', 'AUTO_PASS')
        await expect(row, '轮次仍是「第 1 轮」（第 2 轮根本没判它）').toContainText('第 1 轮')

        const api = await getQueueRowOf(request, token, 'QT-1001')
        expect(Number(api.round_no), '接口里 QT-1001 的轮次也应是 1').toBe(1)
      })
    } finally {
      resetAuditDemoBaseline()
    }
  })

  /* ================================================================ ② 改配置不改历史 */

  test('改策略优先级后，旧决策的 policy_version_no 不变（且不产生版本快照）', async ({ page }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)

    /** cleanup 用的原值快照（UI 路径失败也要写回） */
    let baselinePriorities = []
    /** 上移之前的页面顺序（复原时要一模一样） */
    let orderBefore = []

    try {
      await test.step('① 造一条历史决策：跑一轮让 QT-1001 命中「淘宝-标准单」', async () => {
        resetAuditDemoBaseline()
        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)
        expect(run.decisions.find((d) => d.orderSn === 'QT-1001')?.result, 'QT-1001 应自动过审').toBe('AUTO_PASS')

        const taobao = policyOf(POLICY_NAME.AUTO_TAOBAO)
        const douyin = policyOf(POLICY_NAME.AUTO_DOUYIN)
        baselinePriorities = [
          { id: taobao.id, priority: taobao.priority },
          { id: douyin.id, priority: douyin.priority }
        ]
        expect(taobao.type, '「淘宝-标准单」应是 AUTO 策略').toBe('AUTO')

        const dec = decisionsOf(orderFactOf('QT-1001').id)
        expect(dec, 'QT-1001 应恰好 1 条决策').toHaveLength(1)
        expect(dec[0].matchedPolicyName, '决策记下命中的是「淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(dec[0].matchedPolicyVersionNo, '决策记下命中的是第 1 版').toBe(taobao.versionNo)
      })

      const taobao = policyOf(POLICY_NAME.AUTO_TAOBAO)
      const douyin = policyOf(POLICY_NAME.AUTO_DOUYIN)
      const snapshotsBefore = policyVersionCountOf(taobao.id)
      const decisionVersionBefore = decisionsOf(orderFactOf('QT-1001').id)[0].matchedPolicyVersionNo

      await test.step('② 页面上把「抖音-标准单」上移一位 → 优先级与「淘宝-标准单」换位（顺序真的变了）', async () => {
        await gotoPolicyPage(page)
        orderBefore = await policyNameOrder(page, 'AUTO')
        const iTb = orderBefore.indexOf(POLICY_NAME.AUTO_TAOBAO)
        const iDy = orderBefore.indexOf(POLICY_NAME.AUTO_DOUYIN)
        expect(iTb, 'AUTO 页签里应有「淘宝-标准单」').toBeGreaterThan(-1)
        expect(iDy, '「抖音-标准单」应紧跟在「淘宝-标准单」后面（seed 的优先级相邻）').toBe(iTb + 1)

        await movePolicy(page, douyin.id, 'UP')
        await expectToastSuccess(page, '上移')

        const orderAfter = await policyNameOrder(page, 'AUTO')
        expect(orderAfter.indexOf(POLICY_NAME.AUTO_DOUYIN), '上移后「抖音-标准单」应排到「淘宝-标准单」前面')
          .toBe(orderAfter.indexOf(POLICY_NAME.AUTO_TAOBAO) - 1)

        // 库侧：两条策略的 priority 真的交换了（页面上移不是只改本地状态）
        const tb = policyOf(POLICY_NAME.AUTO_TAOBAO)
        const dy = policyOf(POLICY_NAME.AUTO_DOUYIN)
        expect(dy.priority, '「抖音-标准单」应拿到原来「淘宝-标准单」的优先级').toBe(baselinePriorities[0].priority)
        expect(tb.priority, '「淘宝-标准单」应拿到原来「抖音-标准单」的优先级').toBe(baselinePriorities[1].priority)
        expect(tb.versionNo, '只挪位置不该改 version_no（挪位置不是改条件）').toBe(taobao.versionNo)
        expect(dy.versionNo, '「抖音-标准单」的 version_no 也不该变').toBe(douyin.versionNo)
      })

      await test.step('③ 历史决策一个字没变：matched_policy_name / policy_version_no 都还是当时那一版', async () => {
        const dec = decisionsOf(orderFactOf('QT-1001').id)
        expect(dec, '决策行数不变').toHaveLength(1)
        expect(dec[0].matchedPolicyName, '命中策略名快照不变').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(dec[0].matchedPolicyVersionNo, '旧决策的 policy_version_no 必须还是当时命中的那一版')
          .toBe(decisionVersionBefore)
        expect(dec[0].matchedPolicyVersionNo, '版本号必须是个真实的版本（不是 null）').toBeGreaterThan(0)

        // 版本快照没有多出来一条：挪优先级不是「改策略内容」
        expect(policyVersionCountOf(taobao.id), '只挪位置不该新增版本快照').toBe(snapshotsBefore)
      })

      await test.step('④ 页面上再下移回原位 → 顺序与优先级都复原（用例自身可重复）', async () => {
        await gotoPolicyPage(page)
        await movePolicy(page, douyin.id, 'DOWN')
        await expectToastSuccess(page, '下移')

        const orderBack = await policyNameOrder(page, 'AUTO')
        expect(orderBack, '下移后页面顺序应回到上移之前的样子').toEqual(orderBefore)

        const tb = policyRowsOf().find((p) => p.name === POLICY_NAME.AUTO_TAOBAO)
        const dy = policyRowsOf().find((p) => p.name === POLICY_NAME.AUTO_DOUYIN)
        expect(tb.priority, '「淘宝-标准单」优先级应复原').toBe(baselinePriorities[0].priority)
        expect(dy.priority, '「抖音-标准单」优先级应复原').toBe(baselinePriorities[1].priority)

        const dec = decisionsOf(orderFactOf('QT-1001').id)
        expect(dec[0].matchedPolicyVersionNo, '来回挪两次以后，旧决策的版本号依然不变').toBe(decisionVersionBefore)
      })

      await test.step('⑤ 顺带证明「过审结论没被配置改动顺手抹掉」：状态与台账都还在', async () => {
        const f = orderFactOf('QT-1001')
        expect(f.status, 'QT-1001 仍是 8 待发货').toBe(8)
        expect(ledgerRowsOf(f.id), '那行 7→8 台账仍在（改策略不影响历史流转）').toHaveLength(1)
        expect(activeTagsOf(f.id), '过审后不该有挂起/冻结标').toHaveLength(0)
      })
    } finally {
      // 双保险：UI 复原没走到也要把优先级按原值写回（否则后面所有用例的判定顺序都被带偏）
      resetAuditDemoBaseline()
      if (baselinePriorities.length === 2) {
        restorePolicyPriorities(baselinePriorities)
        const rows = policyRowsOf()
        const tb = rows.find((p) => p.name === POLICY_NAME.AUTO_TAOBAO)
        const dy = rows.find((p) => p.name === POLICY_NAME.AUTO_DOUYIN)
        const bad = (tb && tb.priority !== baselinePriorities[0].priority)
          || (dy && dy.priority !== baselinePriorities[1].priority)
        if (bad) {
          throw new Error(`策略优先级没复原：淘宝 ${tb?.priority}/${baselinePriorities[0].priority}、抖音 ${dy?.priority}/${baselinePriorities[1].priority}`)
        }
      }
    }
  })
})
