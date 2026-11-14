import { test, expect } from '../../../support/test.js'
import { API, ADMIN } from '../../../support/env.js'
import { dbOfApi } from '../../../support/db.js'
import { adminLoginHardened } from '../../../support/admin-ui.js'
import { adminTokenOf } from '../../../support/assert.js'
import { tid } from '../../../support/tid.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  orderFactOf,
  decisionsOf,
  decisionJsonOf,
  missGroupOf,
  conditionOf,
  unevaluatedConditions,
  ledgerRowsOf,
  activeTagsOf,
  POLICY_NAME
} from '../../../support/audit-fixtures.js'
import { getAuditPolicies, getQueueRowOf } from '../../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  badgeOf,
  reasonMainOf,
  reasonSubOf,
  runAuditViaUi,
  filterByResultChip,
  clearResultFilter,
  openWhyDrawer,
  whyGroupOf,
  whyCondRow,
  whyRowMiss,
  whyRowUnevaluated
} from '../../../support/audit-ui.js'

/**
 * 业务 / 订单-自动审核 / 判定链与四结局-自动过审与留人工
 *
 * 这个场景要证明的一件事：
 *   **同一批「看起来都该过」的小额单，判出来的结局可以不一样，而且每个结局都说得清是
 *     哪条策略、哪一条条件、哪个实际值决定的 —— 不是「系统觉得该人工」。**
 *
 * 两笔样本（seed 里的 QT-1001 / QT-1002，只有客服备注不同）：
 *   QT-1001 淘宝 268 元 2 件 毛利 62  → AUTO_PASS（命中「淘宝-标准单」，订单 7→8）
 *   QT-1002 同上 + 客服备注「客户改地址：…」→ MANUAL（条件全满足，但被**全局排除**挡住）
 *
 * 「差在哪」抽屉这一轮要钉三件事（对应后端 ConditionTrace 的三态）：
 *   ① hint 行 —— 这个结局是什么意思（留人工 = 条件满足但被排除 / 预检降级）；
 *   ② 满足 / 不满足 / **未求值** 三态分开：AND 链上首个不满足之后的条件标「未求值」，
 *      不能混进「不满足」（否则页面在替引擎猜哪些条件比过）；
 *   ③ 全局排除单独列（它不属于任何策略：`audit_policy_condition.policy_id IS NULL`）。
 *
 * ── 为什么直查库（而不是只看页面）────────────────────────────────────────
 *   页面证明「人看得见」，库证明「事实真的落了」：
 *   order_audit_decision.result / matched_policy_name / round_no / order_status_seq、
 *   order.status（7→8 到底推没推）、order_status_ledger（推进要留一行、且 ref_type=AUDIT_RUN）、
 *   excluded_by_json（被哪条排除挡的，不是只写「转人工」三个字）。
 *
 * ── 依赖 ────────────────────────────────────────────────────────────────
 *   六笔演示单 + 7 条策略由 sql/seed-order-audit-demo.sql 铺（本用例 beforeAll 幂等补齐），
 *   每个用例前后复位成基线，所以**可以重复跑**。
 */
test.describe('业务 › 订单-自动审核 › 判定链与四结局-自动过审与留人工', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 订单-自动审核 / 判定链与四结局-自动过审与留人工] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()      // 见 audit-fixtures.js 文件头最后一段：定时任务会抢走种子单
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  test('QT-1001 自动过审（7→8）与 QT-1002 被全局排除留人工，差在哪抽屉给出三态与具体数值差', async ({ page, request }) => {
    test.setTimeout(240_000)

    // 后台走真实登录页登录；令牌只用于只读接口断言
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    // 策略 id 要在「去改这条策略」的跳转断言里用到 —— 从接口拿，不写死
    const bundle = await getAuditPolicies(request, token)
    const taobaoPolicy = bundle.policies.find((p) => p.name === POLICY_NAME.AUTO_TAOBAO)
    const directPolicy = bundle.policies.find((p) => p.name === POLICY_NAME.AUTO_DIRECT)
    expect(taobaoPolicy, `策略集里应有「${POLICY_NAME.AUTO_TAOBAO}」`).toBeTruthy()
    expect(directPolicy, `策略集里应有「${POLICY_NAME.AUTO_DIRECT}」`).toBeTruthy()

    /** 第 ② 步点出来的那一轮 run（后面的台账 ref_id 断言要用它的 id） */
    let run = null

    try {
      // ================================================================ ① 基线
      await test.step('① 复位基线：两笔单都是待审核(7)、零决策、零状态台账', async () => {
        resetAuditDemoBaseline()
        for (const sn of ['QT-1001', 'QT-1002']) {
          const f = orderFactOf(sn)
          expect(f.status, `${sn} 起点应是 7 待审核`).toBe(7)
          expect(f.statusSeq, `${sn} 起点 status_seq 应是 0（决策的 order_status_seq 依赖它）`).toBe(0)
          expect(decisionsOf(f.id), `${sn} 起点不应有决策行`).toHaveLength(0)
        }
        // 两笔单的差别只有一个：客服备注（这是本用例唯一自变量的证据）
        expect(orderFactOf('QT-1001').csRemark, 'QT-1001 不应有客服备注').toBeNull()
        expect(orderFactOf('QT-1002').csRemark, 'QT-1002 应有含「改」的客服备注').toContain('改')
        expect(orderFactOf('QT-1001').totalAmount, 'QT-1001 应收 268.00').toBe(268)
        expect(orderFactOf('QT-1002').totalAmount, 'QT-1002 应收 268.00（与 QT-1001 同额）').toBe(268)
      })

      // ================================================================ ② 点「立即审核一轮」
      await test.step('② 队列页点「立即审核一轮」→ run 接口把两笔单判成一绿一黄', async () => {
        await gotoAuditQueue(page)

        // 卡片/表格就位：本轮之前队列里还没有这两笔单的决策，所以行不该存在
        await expect(auditQueueRow(page, 'QT-1001'), '判之前队列里不该有 QT-1001 的行').toHaveCount(0)

        run = await runAuditViaUi(page)
        expect(run.total, `本轮范围内应至少包含六笔演示单（实到 ${run.total}）`).toBeGreaterThanOrEqual(6)

        const d1 = run.decisions.find((d) => d.orderSn === 'QT-1001')
        const d2 = run.decisions.find((d) => d.orderSn === 'QT-1002')
        expect(d1?.result, 'run 返回里 QT-1001 应是 AUTO_PASS').toBe('AUTO_PASS')
        expect(d1?.matchedPolicyName, 'QT-1001 命中的应是「淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(d1?.reason, 'QT-1001 的人话原因应含具体数值差「应收金额 268.00 < 500.00」')
          .toContain('应收金额 268.00 < 500.00')
        expect(d2?.result, 'run 返回里 QT-1002 应是 MANUAL（同条件但被排除）').toBe('MANUAL')
      })

      // ================================================================ ③ 队列页断言（徽标 + 原因文案）
      await test.step('③ 队列行：徽标 data-result / 原因首句 / 灰字里的具体差值 / 第几轮', async () => {
        // ---- QT-1001：自动过审 ----
        const row1 = auditQueueRow(page, 'QT-1001')
        await expect(row1, '队列里应有 QT-1001 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row1), 'QT-1001 徽标应是 AUTO_PASS（读 data-result，不靠颜色）')
          .toHaveAttribute('data-result', 'AUTO_PASS')
        await expect(badgeOf(row1), 'QT-1001 徽标文案应是「自动过审」').toHaveText('自动过审')
        await expect(reasonMainOf(row1), 'QT-1001 原因首句应说清命中了哪条策略')
          .toContainText(`命中「${POLICY_NAME.AUTO_TAOBAO}」→ 自动过审`)
        await expect(reasonSubOf(row1), 'QT-1001 灰字原因应带具体数值差（应收 268.00 < 500.00）')
          .toContainText('应收金额 268.00 < 500.00')
        // 口径提醒：QT-1001 是「1 个 SKU × 2 件」→ 种类数是 **1**（件数才是 2）。
        // 后端单测 AuditRunIntegrationTest 逐字断的就是「商品种类数 1 ≤ 5」。
        await expect(reasonSubOf(row1), 'QT-1001 灰字原因应带种类数口径（1 种，不是 2 件）')
          .toContainText('商品种类数 1 ≤ 5')
        await expect(row1, 'QT-1001 应是第 1 轮判定').toContainText('第 1 轮')
        await expect(row1, 'QT-1001 的店铺/渠道列应显示 seed 里的「官方旗舰店」/「淘宝」').toContainText('官方旗舰店')
        await expect(row1, 'QT-1001 应收列应显示 ¥268.00').toContainText('268.00')

        // ---- QT-1002：留人工（条件满足但被排除）----
        const row2 = auditQueueRow(page, 'QT-1002')
        await expect(row2, '队列里应有 QT-1002 的行').toBeVisible({ timeout: 20_000 })
        await expect(badgeOf(row2), 'QT-1002 徽标应是 MANUAL').toHaveAttribute('data-result', 'MANUAL')
        await expect(badgeOf(row2), 'QT-1002 徽标文案应是「留人工」').toHaveText('留人工')
        await expect(reasonMainOf(row2), 'QT-1002 原因首句应说明「条件满足但被排除」（不是「没命中」）')
          .toContainText(`「${POLICY_NAME.AUTO_TAOBAO}」条件满足但被排除`)
        await expect(reasonSubOf(row2), 'QT-1002 灰字原因应指出是客服备注含「改」把它挡下来的')
          .toContainText('客服备注 含「改」')
        await expect(reasonSubOf(row2), 'QT-1002 灰字原因应带备注实际值').toContainText('客户改地址')
        await expect(row2, 'QT-1002 应是第 1 轮判定').toContainText('第 1 轮')
      })

      // ================================================================ ④ chips 筛选
      await test.step('④ 点「留人工」chip：只剩 QT-1002，QT-1001 被筛掉（芯片既是筛选器也是计数）', async () => {
        await expect(tid(page, 'audit-chip-MANUAL'), '应有「留人工」chip').toHaveAttribute('data-result', 'MANUAL')
        await filterByResultChip(page, 'MANUAL')
        await expect(auditQueueRow(page, 'QT-1002'), '筛「留人工」后 QT-1002 仍应在').toBeVisible()
        await expect(auditQueueRow(page, 'QT-1001'), '筛「留人工」后 QT-1001（自动过审）不该出现').toHaveCount(0)

        await clearResultFilter(page)
        await expect(auditQueueRow(page, 'QT-1001'), '取消筛选后 QT-1001 应回来').toBeVisible()
      })

      // ================================================================ ⑤「差在哪」抽屉
      await test.step('⑤ 差在哪抽屉：hint 行 + 未求值行 + 全局排除 + 具体差值，并能跳到策略页', async () => {
        const row2 = auditQueueRow(page, 'QT-1002')
        const drawer = await openWhyDrawer(page, row2)

        await expect(drawer.getByTestId('audit-why-badge'), '抽屉顶部的徽标也应是 MANUAL')
          .toHaveAttribute('data-result', 'MANUAL')

        // ① hint 行：这个结局到底是什么意思（hint 文本没有 testid，按类名取 —— 已登记缺口）
        await expect(drawer.locator('.why-hint'), 'hint 行应解释「留人工 = 条件满足但被排除 / 预检降级」')
          .toContainText('条件满足但被排除')

        // 结论来源：后端回传了结构化留痕 → 页面直接用它，不在前端重算
        await expect(drawer.getByTestId('audit-why-source'), '抽屉应标明结论取自后端留痕（capability 已补齐）')
          .toContainText('结论取自后端留痕')

        // ② 三态（按实测收紧）：这张单**恰好 2 条条件不满足** —— 直营-小单差客户标签、抖音-标准单差渠道；
        //    而「首个不满足之后」的那几条（直营的应收 268<300、抖音的应收 268<500 与种类数 1≤5）实际都**满足**，
        //    前端按 satisfied 优先渲染成「满足」，所以这张单的抽屉里**没有**「未求值」标签。
        //    「未求值」出现在「首个不满足之后仍不满足」的条件上（见 3-不命中与差在哪 的 QT-1003）。
        await expect(drawer.getByTestId('audit-why-mark'), '恰好 2 条条件标「← 差在哪」（客户标签、渠道）')
          .toHaveCount(2)
        await expect(whyRowMiss(drawer), '「不满足」的真实数据行恰好 2 行').toHaveCount(2)
        // 按行级 class 数（不能数 testid：el-table 的 hidden-columns 会凭空克隆出一个「未求值」节点）
        await expect(whyRowUnevaluated(drawer), '这张单不再决定结论的条件都是「满足」，不该有未求值行')
          .toHaveCount(0)

        // ③ 具体数值差：直营-小单 的客户标签条件（实际「普通」≠ 期望「直营门店」）
        await expect(drawer, '抽屉里应写清直营-小单差在哪（客户标签 = 直营门店，实际 普通）')
          .toContainText('客户标签 = 直营门店')
        // 「后端留痕」那一行是引擎原话，expected 与 actual 都在里面（不是只给「不满足」两个字）
        const directGroup = whyGroupOf(drawer, directPolicy.id)
        await expect(directGroup, '抽屉里应有「直营-小单」这一组').toBeVisible()
        await expect(directGroup.locator('.why-backend-lines'), '直营-小单 的留痕里应写出实际值「普通」')
          .toContainText('实际 普通')
        // 字段级：客户标签那条是「差在哪」，应收 268<300 那条是「满足」→ 不该带标记
        await expect(whyCondRow(directGroup, 'CUSTOMER_TAG').getByTestId('audit-why-mark'),
          '客户标签那条（期望直营门店、实际 普通）应标「← 差在哪」').toHaveCount(1)
        await expect(whyCondRow(directGroup, 'RECEIVABLE_AMOUNT').getByTestId('audit-why-mark'),
          '应收 268 < 300 是满足的，不该被标成「差在哪」').toHaveCount(0)
        await expect(whyCondRow(directGroup, 'RECEIVABLE_AMOUNT'), '该行仍要如实给出期望/实际（300 / 268）')
          .toContainText('300')

        // ④ 全局排除单独一块（它不属于任何策略）：客服备注含「改」
        await expect(drawer.getByTestId('audit-why-global'), '抽屉里应有独立的「全局排除」块').toBeVisible()
        // 实测文案：这一块是**前端按条件行自己拼的**（condText），与引擎原话不同 ——
        // 引擎原话是「客服备注 含「改」（实际 …）」，这里没有引号：`排除：客服备注 含 改`
        await expect(drawer.getByTestId('audit-why-global'), '全局排除块应写出「排除：客服备注 含 改」')
          .toContainText('排除：客服备注 含 改')

        // ⑤「去改这条策略」：跳转目标是**第一条有「不满足」条件的策略**（不是「命中的那条」）。
        //    QT-1002 三个 AUTO 组里，直营-小单差客户标签、抖音-标准单差渠道、淘宝-标准单全满足（只是被排除），
        //    所以 focusPolicy = 直营-小单，且带上那条失败条件的 seq(=0) 供策略页高亮。
        await drawer.getByTestId('audit-why-goto-policy').click()
        await page.waitForURL(/\/audit-policy/, { timeout: 20_000 })
        const url = new URL(page.url())
        expect(url.searchParams.get('type'), '跳转应带上策略类型 AUTO（MANUAL/NO_MATCH 都归 AUTO 类）').toBe('AUTO')
        expect(Number(url.searchParams.get('focusPolicy')), '跳转应指向「直营-小单」（第一条真不满足的策略）')
          .toBe(directPolicy.id)
        expect(Number(url.searchParams.get('focusSeq')), '应带上那条失败条件的 seq=0（客户标签）').toBe(0)
        await expect(tid(page, 'policy-editor'), '策略页应直接打开编辑器（focusPolicy）').toBeVisible({ timeout: 20_000 })
        await expect(tid(page, 'policy-form-name'), '编辑器里应是「直营-小单」')
          .toHaveValue(POLICY_NAME.AUTO_DIRECT)
        // 反向证明「没跳错策略」：淘宝-标准单 的 id 不该出现在 focusPolicy 里
        expect(Number(url.searchParams.get('focusPolicy')), '不该跳到「淘宝-标准单」（它没有不满足的条件）')
          .not.toBe(taobaoPolicy.id)
      })

      // ================================================================ ⑥ 直查库（事实）
      await test.step('⑥ 直查库：QT-1001 决策+7→8 台账；QT-1002 只有决策、状态与台账都没动', async () => {
        const id1 = orderFactOf('QT-1001').id
        const id2 = orderFactOf('QT-1002').id

        // ---- QT-1001 ----
        const dec1 = decisionsOf(id1)
        expect(dec1, 'QT-1001 应恰好落 1 条决策（一轮一行）').toHaveLength(1)
        expect(dec1[0].roundNo, 'QT-1001 的决策应是第 1 轮').toBe(1)
        expect(dec1[0].result, 'QT-1001 决策结果应是 AUTO_PASS').toBe('AUTO_PASS')
        expect(dec1[0].matchedPolicyName, 'QT-1001 命中策略名快照应是「淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(dec1[0].matchedPolicyVersionNo, 'QT-1001 命中的应是第 1 版策略（seed 初版）').toBe(1)
        expect(dec1[0].orderStatusSeq, 'QT-1001 决策应记下本轮推进出来的 status_seq=1').toBe(1)
        expect(dec1[0].downgradeReason, 'QT-1001 不该有预检降级原因').toBeNull()
        expect(dec1[0].wouldBePolicyName, 'AUTO_PASS 不该有 would-be 回填（它本来就过了）').toBeNull()

        const after1 = orderFactOf('QT-1001')
        expect(after1.status, 'QT-1001 应被推进到 8 待发货').toBe(8)
        expect(after1.statusSeq, 'QT-1001 status_seq 应与决策里记的一致（1）').toBe(1)

        const led1 = ledgerRowsOf(id1)
        expect(led1, 'QT-1001 应恰好 1 行状态台账（只追加）').toHaveLength(1)
        expect(led1[0].from, '台账前态应是 7 待审核').toBe(7)
        expect(led1[0].to, '台账后态应是 8 待发货').toBe(8)
        expect(led1[0].reason, '自动过审的台账原因码应是 AUDIT_AUTO_PASS（与人工 AUDIT_PASS 分开）')
          .toBe('AUDIT_AUTO_PASS')
        expect(led1[0].refType, '台账应记下「本轮 run 推的」ref_type=AUDIT_RUN').toBe('AUDIT_RUN')
        expect(led1[0].refId, '台账的 ref_id 应是这一轮 run 的 id').toBe(Number(run.runId))
        expect(led1[0].idemKey, `台账幂等键应是 {orderId}:{status_seq} = ${id1}:1`).toBe(`${id1}:1`)
        expect(activeTagsOf(id1), '自动过审不该给订单打任何标').toHaveLength(0)

        // 命中条件留痕（matched_condition_json 与 miss/excluded 同一形状：策略组 → 条件数组）
        // ⚠️ 必须 parse 后按字段断言：MySQL 输出 JSON 列时会规范化成 `"policy_name": "直营-小单"`（带空格），
        //    任何「字符串 contains」式断言都是假的（实测踩过，故统一走 decisionJsonOf 的结构化出口）。
        const matched = decisionJsonOf(id1, 1, 'matched_condition_json')
        expect(Array.isArray(matched), 'matched_condition_json 应是「策略组 → 条件数组」').toBe(true)
        const hitGroup = missGroupOf(matched, POLICY_NAME.AUTO_TAOBAO)
        expect(hitGroup.conditions.every((c) => c.satisfied === true), 'AUTO_PASS 的命中条件应**全部** satisfied')
          .toBe(true)
        const hitAmount = conditionOf(hitGroup, 'RECEIVABLE_AMOUNT')
        expect(hitAmount.opCode, '应收条件是「小于」').toBe('LT')
        expect(hitAmount.expected, '应收门槛应是 500.00').toBe('500.00')
        expect(hitAmount.actual, '实际应收应是 268.00').toBe('268.00')
        expect(String(hitAmount.reason), '该条件的人话应含具体差值').toContain('应收金额 268.00 < 500.00')
        const hitKinds = conditionOf(hitGroup, 'ITEM_KIND_COUNT')
        expect(hitKinds.opCode, '种类数是「小于等于」').toBe('LTE')
        expect(hitKinds.expected, '种类数门槛应是 5').toBe('5')
        expect(hitKinds.actual, '实际种类数应是 1（1 个 SKU × 2 件：件数不是种类数）').toBe('1')
        expect(String(hitKinds.reason), '人话只有一句，且用种类数口径').toBe('商品种类数 1 ≤ 5')

        // ---- QT-1002 ----
        const dec2 = decisionsOf(id2)
        expect(dec2, 'QT-1002 也应恰好落 1 条决策（它参与了本轮）').toHaveLength(1)
        expect(dec2[0].result, 'QT-1002 决策结果应是 MANUAL').toBe('MANUAL')
        expect(dec2[0].matchedPolicyName, 'QT-1002 应记下「本来命中的是淘宝-标准单」').toBe(POLICY_NAME.AUTO_TAOBAO)
        expect(dec2[0].roundNo, 'QT-1002 是第 1 轮').toBe(1)
        expect(dec2[0].orderStatusSeq, 'QT-1002 没推进状态，status_seq 仍是 0').toBe(0)

        const after2 = orderFactOf('QT-1002')
        expect(after2.status, '留人工不该改动订单状态（仍是 7 待审核）').toBe(7)
        expect(ledgerRowsOf(id2), '留人工不推进状态 → 不该有状态台账行').toHaveLength(0)
        expect(activeTagsOf(id2), 'MANUAL（排除型）不该打挂起标 —— 只有库存/成本预检降级才打').toHaveLength(0)

        const excluded = decisionJsonOf(id2, 1, 'excluded_by_json')
        expect(Array.isArray(excluded), 'excluded_by_json 应是数组').toBe(true)
        const exGroup = missGroupOf(excluded, POLICY_NAME.AUTO_TAOBAO)
        expect(exGroup.policyId, '被排除的策略组应是「淘宝-标准单」（就是它被挡下的）').toBeTruthy()
        const csRemark = conditionOf(exGroup, 'CS_REMARK')
        expect(csRemark.opCode, '全局排除用的是「含」').toBe('CONTAINS')
        expect(String(csRemark.expected), '排除条件的期望值是「改」').toBe('改')
        expect(String(csRemark.actual), '实际值应是完整客服备注').toContain('客户改地址')
        expect(csRemark.satisfied, '这条排除确实成立（所以才转人工）').toBe(true)
        expect(String(exGroup.reasons), '被排除的组应有「被排除：…」的人话').toContain('被排除')

        const miss = decisionJsonOf(id2, 1, 'miss_reason_json')
        expect(Array.isArray(miss), 'miss_reason_json 应是数组（逐策略一条）').toBe(true)
        const missDirect = missGroupOf(miss, POLICY_NAME.AUTO_DIRECT)
        const missTag = conditionOf(missDirect, 'CUSTOMER_TAG')
        expect(missTag.satisfied, '直营-小单 的客户标签条件是**不**满足的（普通 ≠ 直营门店）').toBe(false)
        expect(missTag.expected, '期望值应是「直营门店」').toBe('直营门店')
        expect(missTag.actual, '实际值应是「普通」').toBe('普通')
        // AND 链三态：首个不满足之后的条件标 evaluated=false（值照实给出，只是不再决定结论）
        const unevalDirect = unevaluatedConditions([missDirect])
        expect(unevalDirect.length, '直营-小单：客户标签之后的那条应收应标 evaluated=false').toBe(1)
        expect(unevalDirect[0].fieldCode, '被标未求值的应是应收金额（268 < 300 其实满足）').toBe('RECEIVABLE_AMOUNT')
        expect(unevalDirect[0].satisfied, '它其实是「满足」的 —— 所以页面上显示满足而不是未求值').toBe(true)
        const missDouyin = missGroupOf(miss, POLICY_NAME.AUTO_DOUYIN)
        expect(unevaluatedConditions([missDouyin]).length, '抖音-标准单：渠道之后的两条（应收/种类数）都标 evaluated=false')
          .toBe(2)
      })

      // ================================================================ ⑦ 接口回读
      await test.step('⑦ 队列接口：留人工行带回结构与两份「差在哪」证据（不是一串字符串）', async () => {
        const row = await getQueueRowOf(request, token, 'QT-1002')
        expect(row.result, '接口里 QT-1002 的最新结论应是 MANUAL').toBe('MANUAL')
        expect(row.round_no, '接口里应是第 1 轮').toBe(1)
        expect(row.matched_policy_name, '接口里应带回命中策略名').toBe(POLICY_NAME.AUTO_TAOBAO)
        // 关键：服务端把 *_json 列解析成了**结构**；前端据此才走「结论取自后端留痕」这一路（不是本地对撞）
        expect(Array.isArray(row.miss_reasons), 'miss_reasons 应是数组（服务端已解析 JSON 列）').toBe(true)
        expect(row.miss_reasons.length, 'miss_reasons 里应有未命中的策略组').toBeGreaterThan(0)
        const apiDirect = missGroupOf(row.miss_reasons, POLICY_NAME.AUTO_DIRECT)
        expect(conditionOf(apiDirect, 'CUSTOMER_TAG').satisfied, '接口里的留痕也要能判别「不满足」').toBe(false)
        expect(unevaluatedConditions(row.miss_reasons).length, '接口应带回 evaluated=false（三态依据）')
          .toBeGreaterThan(0)
        expect(Array.isArray(row.excluded_by), 'excluded_by 应是数组').toBe(true)
        expect(conditionOf(missGroupOf(row.excluded_by, POLICY_NAME.AUTO_TAOBAO), 'CS_REMARK').satisfied,
          'excluded_by 里客服备注那条排除应是「成立」').toBe(true)
        // 队列行的原因文本在接口里叫 reason_text（SQL 别名；前端 mapQueueRow 同时认 reason / reason_text）
        expect(row.reason_text, '接口里的原因应含具体差值（不是空的）').toContain('客服备注 含「改」')
      })
    } finally {
      // 幂等清理：无论成败都把这六笔单还原成 seed 基线（删决策/标/台账 + 状态回 7）
      resetAuditDemoBaseline()
    }
  })
})
