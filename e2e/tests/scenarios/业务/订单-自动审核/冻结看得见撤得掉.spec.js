import { test, expect } from '../../../support/test.js'
import { API, ADMIN } from '../../../support/env.js'
import { dbOfApi } from '../../../support/db.js'
import { adminLoginHardened, expectToastSuccess } from '../../../support/admin-ui.js'
import { adminTokenOf } from '../../../support/assert.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  orderFactOf,
  orderAuditActionsOf,
  decisionsOf,
  activeTagsOf,
  allTagsOf,
  POLICY_NAME
} from '../../../support/audit-fixtures.js'
import { getQueueRowOf } from '../../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  badgeOf,
  runAuditViaUi,
  freezeRow,
  unfreezeRow
} from '../../../support/audit-ui.js'

/**
 * 业务 / 订单-自动审核 / 冻结看得见撤得掉（P0）
 *
 * 这一条演的是运营**每天都会遇到**的那个场景，需求原话：
 *
 *   「队列只查决策表，没有冻结字段；前端拿 result 猜，人工冻的单永远显示"冻结"按钮。」
 *
 * ── 两个事实必须分开（本 spec 的全部断言都建在这条上）────────────────────────
 *   result  = 引擎**这一轮**判了什么（决策台账里的历史结论；冻结/解冻都不改写它）；
 *   frozen  = 这单**现在**有没有一条 active 的冻结标（order_audit_tag，与 result 解耦）。
 *
 *   ① 策略冻的（EXCEPTION 命中）：当轮 result='FROZEN' **且** 打了标 ⇒ 两个事实都成立；
 *   ② 人工冻的（后台点「冻结」）：**不产生新的决策行** ⇒ result 还是上一轮的
 *      MANUAL / NO_MATCH / BLOCKED…，只有 frozen 会变 true。
 *   旧实现拿 ① 的偶然巧合当判据（`row.result === 'FROZEN'`），于是 ② 永远判不出来：
 *   人工冻的单在队列上显示「冻结」按钮，解冻只能拿 curl 去救。
 *
 * ── 两笔样本（都走真实 UI 点击，数据只读库/接口核对）────────────────────────
 *   QT-1003 抖音 1580 元 → 一轮下来 result=NO_MATCH（**没有任何冻结含义**）→ 人工冻结
 *           → 队列：出现「冻结中」标记 + 原因；操作列变「解冻」→ 点解冻 → 标记消失、变回「冻结」
 *   QT-1005 淘宝 320 元 + 待处理退款申请 → 一轮下来 result=FROZEN（**策略冻的**）
 *           → 同样 frozen=true、同样能点解冻（这是「别修好人工冻结却弄坏策略冻结」的反证）
 *
 * ── 断言口径（为什么一条都不许读颜色）──────────────────────────────────────
 *   颜色/文案是给人看的，机器只读事实：徽标读 `data-result`、冻结标记读
 *   `audit-row-frozen-<单号>` 的 `data-frozen-reason`、按钮读
 *   `audit-row-unfreeze` / `audit-row-freeze` 的存在性。
 */
test.describe('业务 › 订单-自动审核 › 冻结看得见撤得掉', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 订单-自动审核 / 冻结看得见撤得掉] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  /* ================================================================ ① 人工冻结 */

  test('人工冻结 QT-1003：队列看得见「冻结中」+ 原因、操作列变「解冻」；解冻后标记消失、变回「冻结」', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    const FREEZE_REASON = `e2e：人工冻结 P0（${Date.now().toString().slice(-6)}）`

    try {
      await test.step('① 复位基线 + 跑一轮：QT-1003 落一条 NO_MATCH 决策（决策表有行，队列才列它）', async () => {
        resetAuditDemoBaseline()
        const f = orderFactOf('QT-1003')
        expect(f.status, 'QT-1003 起点应是 7 待审核').toBe(7)
        expect(decisionsOf(f.id), '起点不应有决策行').toHaveLength(0)

        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)
        const d = run.decisions.find((x) => x.orderSn === 'QT-1003')
        expect(d?.result, '一轮下来 QT-1003 应是 NO_MATCH（这张单本来就与冻结无关）').toBe('NO_MATCH')

        const row = auditQueueRow(page, 'QT-1003')
        await expect(row, '队列里应有 QT-1003 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row), '前提：徽标是 NO_MATCH').toHaveAttribute('data-result', 'NO_MATCH')
        // 前提：还没冻 —— 标记不该出现，操作列该是「冻结」
        await expect(row.getByTestId('audit-row-frozen-QT-1003'), '未冻结的行不该有冻结标记').toHaveCount(0)
        await expect(row.getByTestId('audit-row-freeze'), '未冻结的行应显示「冻结」按钮').toBeVisible()
        await expect(row.getByTestId('audit-row-unfreeze'), '未冻结的行不该出现「解冻」按钮').toHaveCount(0)
      })

      await test.step('② 点「冻结」→ 填原因 → 确认冻结（人工操作，不是策略命中）', async () => {
        const row = auditQueueRow(page, 'QT-1003')
        await freezeRow(page, row, FREEZE_REASON)
        await expectToastSuccess(page, '已冻结')

        // 库：标表多了一条 active 的 FROZEN；**决策表一条都没多**（人工冻结不产生决策行）
        const f = orderFactOf('QT-1003')
        const tags = activeTagsOf(f.id)
        expect(tags, '人工冻结应留下 1 条 active 的冻结标').toHaveLength(1)
        expect(tags[0].tagCode, '标码应是 FROZEN（不是细分标）').toBe('FROZEN')
        expect(tags[0].reason, '标的 reason 应是我填的原因').toBe(FREEZE_REASON)

        const dec = decisionsOf(f.id)
        expect(dec, '人工冻结**不产生**新的决策行（这正是旧实现判不出来的根因）').toHaveLength(1)
        expect(dec[0].result, '决策结论仍是 NO_MATCH —— 冻结不改 result').toBe('NO_MATCH')
        expect(orderFactOf('QT-1003').status, '冻结不动订单状态（仍 7 待审核）').toBe(7)
        expect(orderAuditActionsOf(f.id).map((a) => a.action), '人工冻结应写一条 AUDIT_FREEZE 审计')
          .toContain('AUDIT_FREEZE')

        // 接口：result 与 frozen 是**两个独立字段**，一个没变、一个变了
        const api = await getQueueRowOf(request, token, 'QT-1003')
        expect(api.result, '接口里 result 仍是 NO_MATCH（没被冻结改写）').toBe('NO_MATCH')
        expect(api.frozen, '接口必须显式给出 frozen=true（队列不再让人拿 result 猜）').toBe(true)
        expect(api.frozenReason, '接口应回传冻结原因').toBe(FREEZE_REASON)
        expect(api.frozenTagCode, '接口应回传冻结标码').toBe('FROZEN')
      })

      await test.step('③ 回队列：该行看得见「冻结中」标记 + 原因，操作列是「解冻」而不是「冻结」', async () => {
        // 重新进队列页（不是靠内存里的 state）：运营下次打开页面看到的必须是这个事实
        await gotoAuditQueue(page)
        const row = auditQueueRow(page, 'QT-1003')
        await expect(row, '队列里应有 QT-1003 的行').toBeVisible({ timeout: 20_000 })

        const mark = row.getByTestId('audit-row-frozen-QT-1003')
        await expect(mark, '冻结中的单必须在队列行上有一个可见标记').toBeVisible()
        await expect(mark, '标记文案应是「冻结中」').toHaveText('冻结中')
        await expect(mark, '标记的 title 应写明原因（悬停即知为什么冻）').toHaveAttribute('title', new RegExp(FREEZE_REASON))
        await expect(mark, '标记上应带 data-frozen-reason（机器可读的原因）')
          .toHaveAttribute('data-frozen-reason', FREEZE_REASON)

        const whyLine = row.getByTestId('audit-row-frozen-reason-QT-1003')
        await expect(whyLine, '原因列应直接写出「冻结原因：…」（不点开也知道为什么）').toBeVisible()
        await expect(whyLine).toContainText(FREEZE_REASON)

        // 判据换源的**核心断言**：徽标还是 NO_MATCH，按钮却已经是「解冻」
        await expect(badgeOf(row), '判定徽标仍应是 NO_MATCH（frozen 与 result 解耦，不许篡改结论）')
          .toHaveAttribute('data-result', 'NO_MATCH')
        await expect(row.getByTestId('audit-row-unfreeze'), '冻结中的行操作列应是「解冻」').toBeVisible()
        await expect(row.getByTestId('audit-row-freeze'), '冻结中的行不该再出现「冻结」按钮').toHaveCount(0)
      })

      await test.step('④ 抽屉里也要看得见：冻结状态 + 原因（人工冻的单在抽屉里必须显示原因）', async () => {
        const row = auditQueueRow(page, 'QT-1003')
        await row.getByTestId('audit-row-trail').click()
        const drawer = page.getByTestId('audit-order-drawer')
        await expect(drawer, '点「流转记录」应打开单笔抽屉').toBeVisible({ timeout: 15_000 })

        await drawer.locator('.el-tabs__item').filter({ hasText: '判定摘要' }).first().click()
        const frozenTag = drawer.getByTestId('audit-order-frozen')
        await expect(frozenTag, '抽屉「判定摘要」里应显示冻结状态').toBeVisible({ timeout: 10_000 })
        await expect(frozenTag, '冻结状态应是 true（读 data-frozen，不读颜色）').toHaveAttribute('data-frozen', 'true')
        await expect(frozenTag, '冻结状态应显示「冻结中」').toHaveText('冻结中')
        await expect(drawer.getByTestId('audit-order-frozen-reason'), '抽屉里必须写清冻结原因')
          .toContainText(FREEZE_REASON)

        await page.keyboard.press('Escape')
        await expect(drawer).toBeHidden({ timeout: 10_000 })
      })

      await test.step('⑤ 点「解冻」→ 成功；回队列 → 标记消失、操作列变回「冻结」', async () => {
        const row = auditQueueRow(page, 'QT-1003')
        await unfreezeRow(page, row)
        await expectToastSuccess(page, '已解冻')

        // 库：标被**解除**（active=0，行留着当证据），决策表依然只有那一条 NO_MATCH
        const f = orderFactOf('QT-1003')
        expect(activeTagsOf(f.id), '解冻后不应再有 active 的冻结标').toHaveLength(0)
        const all = allTagsOf(f.id)
        expect(all, '解冻是「解除」不是「删行」：标行必须留着当证据').toHaveLength(1)
        expect(all[0].active, '标行应是 active=0').toBe(0)
        expect(all[0].releasedBy, '解除人应被记下（不是空）').not.toBe('')
        expect(decisionsOf(f.id), '解冻同样不产生决策行').toHaveLength(1)
        expect(orderAuditActionsOf(f.id).map((a) => a.action), '解冻应写一条 AUDIT_UNFREEZE 审计')
          .toContain('AUDIT_UNFREEZE')

        await gotoAuditQueue(page)
        const after = auditQueueRow(page, 'QT-1003')
        await expect(after, '队列里仍应有 QT-1003 的行').toBeVisible({ timeout: 20_000 })
        await expect(after.getByTestId('audit-row-frozen-QT-1003'), '解冻后冻结标记必须消失').toHaveCount(0)
        await expect(after.getByTestId('audit-row-frozen-reason-QT-1003'), '解冻后原因行也必须消失').toHaveCount(0)
        await expect(after.getByTestId('audit-row-freeze'), '解冻后操作列应变回「冻结」').toBeVisible()
        await expect(after.getByTestId('audit-row-unfreeze'), '解冻后不该再有「解冻」按钮').toHaveCount(0)
        await expect(badgeOf(after), '判定徽标始终是 NO_MATCH（解冻不改结论）')
          .toHaveAttribute('data-result', 'NO_MATCH')

        const api = await getQueueRowOf(request, token, 'QT-1003')
        expect(api.frozen, '接口里 frozen 应回到 false').toBe(false)
        expect(api.frozenReason, '接口里 frozenReason 应为空（null）').toBe(null)
      })
    } finally {
      resetAuditDemoBaseline()
    }
  })

  /* ================================================================ ② 策略冻结（反证：别修好人工冻结却弄坏策略冻结） */

  test('策略命中的冻结（result=FROZEN）同样 frozen=true 且能一键解冻；解冻后 result 不变、按钮变回「冻结」', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    try {
      await test.step('① 复位基线 + 跑一轮：QT-1005 被异常策略冻住（result=FROZEN）', async () => {
        resetAuditDemoBaseline()
        expect(activeTagsOf(orderFactOf('QT-1005').id), '起点不应有冻结标').toHaveLength(0)

        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)
        const d = run.decisions.find((x) => x.orderSn === 'QT-1005')
        expect(d?.result, 'QT-1005 应是 FROZEN（异常策略命中）').toBe('FROZEN')
        expect(d?.matchedPolicyName, '命中的应是异常策略「退款申请冻结」').toBe(POLICY_NAME.EXCEPTION_RETURN)

        const row = auditQueueRow(page, 'QT-1005')
        await expect(badgeOf(row), '徽标是 FROZEN').toHaveAttribute('data-result', 'FROZEN')
        // 策略冻的也必须 frozen=true —— 这是「两个事实可以同时成立」的那一半
        const mark = row.getByTestId('audit-row-frozen-QT-1005')
        await expect(mark, '策略命中冻住的行也必须有冻结标记').toBeVisible()
        await expect(mark, '标记的 data-frozen-reason 应是策略里配的冻结原因')
          .toHaveAttribute('data-frozen-reason', /退款申请/)
        await expect(row.getByTestId('audit-row-unfreeze'), '策略冻住的行同样能点「解冻」').toBeVisible()

        const api = await getQueueRowOf(request, token, 'QT-1005')
        expect(api.result, '接口 result=FROZEN').toBe('FROZEN')
        expect(api.frozen, '接口 frozen 也必须是 true（两个事实同时成立）').toBe(true)
        expect(String(api.frozenReason), '接口 frozenReason 应说明是退款申请').toContain('退款申请')
        expect(api.frozenTagCode, '接口 frozenTagCode 应是 FROZEN').toBe('FROZEN')
      })

      await test.step('② 点「解冻」→ 成功；回队列：标记消失但 result 仍是 FROZEN（解冻不改写决策），按钮变回「冻结」', async () => {
        const row = auditQueueRow(page, 'QT-1005')
        await unfreezeRow(page, row)
        await expectToastSuccess(page, '已解冻')

        const f = orderFactOf('QT-1005')
        expect(activeTagsOf(f.id), '解冻后不应再有 active 的冻结标').toHaveLength(0)
        expect(decisionsOf(f.id)[0].result, '决策行仍是 FROZEN（历史结论不该被解冻改写）').toBe('FROZEN')

        await gotoAuditQueue(page)
        const after = auditQueueRow(page, 'QT-1005')
        await expect(after.getByTestId('audit-row-frozen-QT-1005'), '解冻后冻结标记必须消失').toHaveCount(0)
        await expect(badgeOf(after), '判定徽标仍是 FROZEN（result 只由判定产生，不由解冻产生）')
          .toHaveAttribute('data-result', 'FROZEN')
        // ← 这条是「按钮判据从 result 换成 frozen」最锋利的证据：
        //   旧实现（row.result === 'FROZEN'）在这里会**继续显示解冻**，点下去后端报「当前没有被冻结」。
        await expect(after.getByTestId('audit-row-freeze'), '解冻后即使 result 仍是 FROZEN，操作列也应变回「冻结」').toBeVisible()
        await expect(after.getByTestId('audit-row-unfreeze'), '解冻后不该再有「解冻」按钮').toHaveCount(0)

        const api = await getQueueRowOf(request, token, 'QT-1005')
        expect(api.result, '接口 result 仍是 FROZEN').toBe('FROZEN')
        expect(api.frozen, '接口 frozen 应回到 false').toBe(false)
      })
    } finally {
      resetAuditDemoBaseline()
    }
  })
})
