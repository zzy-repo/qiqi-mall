import { test, expect } from '../../../support/test.js'
import { API, ADMIN } from '../../../support/env.js'
import { dbOfApi, execSql, queryDb } from '../../../support/db.js'
import { tid } from '../../../support/tid.js'
import { adminLoginHardened, expectToastSuccess } from '../../../support/admin-ui.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  restorePolicyPriorities,
  policyOf,
  policyRowsOf,
  policyVersionCountOf,
  decisionsOf,
  orderFactOf,
  POLICY_NAME
} from '../../../support/audit-fixtures.js'
import {
  gotoAuditQueue,
  runAuditViaUi,
  gotoPolicyPage,
  movePolicy,
  policyNameOrder,
  selectPolicyTypeTab
} from '../../../support/audit-ui.js'

/**
 * 业务 / 订单-自动审核 / 审核策略与优先级
 *
 * 这个场景只证明一件事，但必须由界面来证：
 *   **优先级只在同类型内比较；跨类型固定 阻止 > 异常 > 自动。**
 *
 * 两条用例把这句话拆成两个方向：
 *   ① 同类内 —— 数字决定顺序。把「双地址」上移一位（10↔20 换位），同一张单的命中策略
 *      从「毛利不为正」变成「双地址」：页面上的顺序一动，判定结果跟着变。
 *   ② 跨类型 —— 数字不决定顺序。把「毛利不为正」的优先级改成 5000（比三条自动策略的
 *      100/200/300 都大），同一张单**仍然**是阻止命中，而且决策里"本来会过审的那条"
 *      照样记着「直营-小单」——数字大不妨碍它整段先跑。
 *
 * ── 为什么用例 1 要直接写库造一个「双地址」标记 ───────────────────────────────
 *   `order.audit_addr_multi` 全仓只有实体字段和读取（AuditContext 的 ADDRESS_MULTI 来源），
 *   **没有任何写入路径**：后台没有这个字段的入口，接口也不接受它。它是平台侧同步的字段。
 *   要让两条 BLOCK 同时命中，只能在造数阶段把它置 1（用例 finally 里写回 0）。
 *   这和「库存不足要留到人工审核才发现」是同一类：造现场 ≠ 改断言。
 *
 * ── 为什么用「两轮 run」而不是两条策略手动改两次 ──────────────────────────────
 *   一轮 run 的结论落在 order_audit_decision 里（round_no 递增），两轮正好给出
 *   「改之前 / 改之后」两行可对照的库事实 —— 页面上的顺序变化、库里的优先级变化、
 *   决策里的命中策略变化，三者在同一个用例里闭环。
 *
 * ── 依赖 ─────────────────────────────────────────────────────────────────
 *   demo 策略集与六笔演示单由 sql/seed-order-audit-demo.sql 铺（ensureAuditDemoSeed）；
 *   定时自动审核在 beforeAll 里关掉（否则后台跑一轮会把单子提前判掉，结论就不是我们等的那一轮）。
 */
test.describe('业务 › 订单-自动审核 › 审核策略与优先级', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 订单-自动审核 / 审核策略与优先级] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  /* ---------------------------------------------------------------- 造数小工具 */

  /** 读 QT-1004 的「双地址」标记（造数后必回读，失败要响，不能让断言去猜） */
  const addrMultiOf = () => Number(queryDb("SELECT audit_addr_multi FROM `order` WHERE order_sn = 'QT-1004'"))

  /** 把 QT-1004 的「双地址」标记置成 v（后台没有这个字段的写入口，见文件头） */
  const setAddrMulti = (v) => {
    const want = Number(v) ? 1 : 0
    execSql(`UPDATE \`order\` SET audit_addr_multi = ${want} WHERE order_sn = 'QT-1004'`)
    const got = addrMultiOf()
    if (got !== want) throw new Error(`audit_addr_multi 造数失败：写 ${want}、读回 ${got}`)
  }

  /* ---------------------------------------------------------------- 弹窗小工具 */

  /** 点行内「编辑」→ 等策略弹窗打开 */
  const openPolicyEditor = async (page, policyId, expectName) => {
    await tid(page, `policy-edit-${policyId}`).click()
    const dlg = tid(page, 'policy-editor')
    await expect(dlg, '点「编辑」应打开策略弹窗（policy-editor）').toBeVisible({ timeout: 20_000 })
    if (expectName) {
      await expect(dlg, `弹窗标题应带上策略名「${expectName}」`).toContainText(expectName)
    }
    await page.waitForTimeout(400)
    return dlg
  }

  /**
   * 改弹窗里的优先级数字。
   *
   * ⚠️ 为什么用「点名称 → Tab → 输入」而不是 `policy-form-priority.locator('input').fill()`：
   *   这个输入框在当前弹窗布局里被挤成了 **0 宽**（el-input-number 的根容器实测 39px，
   *   内层 input 实测 0×22px），鼠标点它的中心只会命中右侧那对加减箭头 —— 实测点一下
   *   优先级从 10 变成了 9（命中 decrease），而 `fill()` 会因为「元素不可见」永远超时。
   *   所以走键盘：点进「名称」→ Tab 一格就落在优先级 input 上（焦点实测 value=10）→
   *   Ctrl+A 覆盖 → 输入数字。这条路真人同样走得通，且不碰任何 DOM 注入。
   *   这是**前端布局缺陷**（优先级一栏 `:span="4"` + 96px 标签宽度 + 内层 flex 挤到 0），
   *   本轮已登记在交付说明里，不在用例里"绕过并装作没看见"：断言一律回读库里的真实优先级。
   */
  const setEditorPriority = async (page, dlg, value) => {
    await dlg.getByTestId('policy-form-name').click()
    await page.keyboard.press('Tab')
    await page.keyboard.press('Control+a')
    await page.keyboard.type(String(value))
    await page.keyboard.press('Tab')
    const shown = await dlg.getByTestId('policy-form-priority').locator('input').inputValue()
    expect(shown, '优先级输入框里的值应等于刚输入的数字').toBe(String(value))
  }

  /** 保存：会先弹一次确认（带影响预演文案），点「确认保存」后等成功提示 */
  const savePolicyEditor = async (page, dlg) => {
    await dlg.getByTestId('policy-save').click()
    const box = page.locator('.el-message-box')
    await expect(box, '保存前应弹一次确认框（含影响预演）').toBeVisible({ timeout: 25_000 })
    await expect(box, '确认框里应带上影响预演那句话').toContainText('影响')
    await box.locator('.el-message-box__btns').getByRole('button', { name: '确认保存' }).click()
    await expectToastSuccess(page, '策略已保存')
    await expect(dlg).toBeHidden({ timeout: 20_000 })
    await page.waitForTimeout(800)
  }

  /* ================================================================ ① 同类内 */

  test('同类内：把「双地址」上移一位 → 同一张单的命中策略从「毛利不为正」变成「双地址」', async ({ page }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)

    /** cleanup 用的原值快照（UI 路径失败也要写回） */
    let baseline = []
    /** 两条策略的 version_no 原值（挪位置不该动它；不许硬编码成 1 —— 库被别的用例改过就不是 1 了） */
    let versionsBefore = null
    const profitName = POLICY_NAME.BLOCK_PROFIT
    const addrName = POLICY_NAME.BLOCK_ADDR

    try {
      await test.step('① 造现场：QT-1004 同时踩两条阻止（把「双地址」标记置 1）', async () => {
        resetAuditDemoBaseline()
        setAddrMulti(1)

        const profit = policyOf(profitName)
        const addr = policyOf(addrName)
        baseline = [
          { id: profit.id, priority: profit.priority },
          { id: addr.id, priority: addr.priority }
        ]
        expect(profit.type, '「毛利不为正」应是 BLOCK 策略').toBe('BLOCK')
        expect(addr.type, '「双地址」应是 BLOCK 策略').toBe('BLOCK')
        expect(profit.priority, 'seed 基线：毛利不为正 是同类里数字小的那条').toBeLessThan(addr.priority)
        versionsBefore = { profit: profit.versionNo, addr: addr.versionNo }
        expect(orderFactOf('QT-1004').status, 'QT-1004 起点应是 7 待审核').toBe(7)
        expect(orderFactOf('QT-1004').grossProfit, 'QT-1004 的毛利是 −12（毛利不为正的条件）').toBeLessThanOrEqual(0)
      })

      await test.step('② 第 1 轮：走界面点「立即审核一轮」→ 命中数字小的「毛利不为正」', async () => {
        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)
        expect(run.decisions.find((d) => d.orderSn === 'QT-1004')?.result, 'QT-1004 应被阻止').toBe('BLOCKED')

        const dec = decisionsOf(orderFactOf('QT-1004').id)
        expect(dec, 'QT-1004 应恰好 1 条决策（第 1 轮）').toHaveLength(1)
        expect(dec[0].result, '第 1 轮结论应是阻止').toBe('BLOCKED')
        expect(dec[0].matchedPolicyName, '两条阻止都命中时，记的是优先级小的「毛利不为正」').toBe(profitName)
      })

      await test.step('③ 页面上把「双地址」上移一位 → 同类型内 priority 换位（10 ↔ 20）', async () => {
        await gotoPolicyPage(page)
        await selectPolicyTypeTab(page, 'BLOCK')

        const orderBefore = await policyNameOrder(page, 'BLOCK')
        const iProfit = orderBefore.indexOf(profitName)
        const iAddr = orderBefore.indexOf(addrName)
        expect(iProfit, 'BLOCK 页签里应有「毛利不为正」').toBeGreaterThan(-1)
        expect(iAddr, '「双地址」应紧跟其后（seed 里优先级相邻）').toBe(iProfit + 1)

        const addr = policyOf(addrName)
        await movePolicy(page, addr.id, 'UP')
        await expectToastSuccess(page, '上移')

        const orderAfter = await policyNameOrder(page, 'BLOCK')
        expect(orderAfter.indexOf(addrName), '上移后「双地址」应排到「毛利不为正」前面')
          .toBe(orderAfter.indexOf(profitName) - 1)

        // 库侧：两条策略的 priority 真的交换了（页面换位不是只改本地状态）
        const p = policyOf(profitName)
        const a = policyOf(addrName)
        expect(a.priority, '「双地址」应拿到原来「毛利不为正」的优先级').toBe(baseline[0].priority)
        expect(p.priority, '「毛利不为正」应拿到原来「双地址」的优先级').toBe(baseline[1].priority)
        expect(p.versionNo, '只挪位置不产生版本快照（挪位置不是改条件）').toBe(versionsBefore.profit)
        expect(a.versionNo, '「双地址」的 version_no 也不该变').toBe(versionsBefore.addr)
      })

      await test.step('④ 第 2 轮：同一张单、同一套条件 → 命中换成「双地址」', async () => {
        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)
        expect(run.decisions.find((d) => d.orderSn === 'QT-1004')?.result, 'QT-1004 仍在队列里被再判一次').toBe('BLOCKED')

        const dec = decisionsOf(orderFactOf('QT-1004').id)
        expect(dec, '两轮应各有 1 条决策').toHaveLength(2)
        expect(dec[0].matchedPolicyName, '第 1 轮：毛利不为正').toBe(profitName)
        expect(dec[1].matchedPolicyName, '第 2 轮：换了顺序，命中的变成「双地址」').toBe(addrName)
        expect(dec[1].result, '两轮都是阻止（换的是"谁先判"，不是"判成什么"）').toBe('BLOCKED')
      })
    } finally {
      // 双保险：UI 复原没走到也要把优先级与造数按原值写回
      if (baseline.length === 2) restorePolicyPriorities(baseline)
      const rows = policyRowsOf()
      const p = rows.find((x) => x.name === profitName)
      const a = rows.find((x) => x.name === addrName)
      const bad = (baseline.length === 2)
        && ((p && p.priority !== baseline[0].priority) || (a && a.priority !== baseline[1].priority))
      setAddrMulti(0)
      resetAuditDemoBaseline()
      if (bad) throw new Error(`策略优先级没复原：毛利不为正 ${p?.priority}/${baseline[0].priority}、双地址 ${a?.priority}/${baseline[1].priority}`)
    }
  })

  /* ================================================================ ② 跨类型 */

  test('跨类型：把阻止的优先级改成 5000（比三条自动都大）→ 仍然是阻止先赢', async ({ page }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)

    let baseline = []
    const profitName = POLICY_NAME.BLOCK_PROFIT
    const addrName = POLICY_NAME.BLOCK_ADDR

    try {
      await test.step('① 基线：QT-1004 只踩「毛利不为正」；先跑一轮记下"本来会过审那条"', async () => {
        resetAuditDemoBaseline()
        setAddrMulti(0)

        baseline = policyRowsOf().map((p) => ({ id: p.id, priority: p.priority }))
        const profit = policyOf(profitName)
        const autos = policyRowsOf().filter((p) => p.type === 'AUTO')
        expect(autos.length, 'seed 应有 3 条自动策略').toBeGreaterThanOrEqual(3)
        expect(profit.priority, 'seed 基线：毛利不为正 的优先级是 10').toBe(10)

        await gotoAuditQueue(page)
        await runAuditViaUi(page)
        const dec = decisionsOf(orderFactOf('QT-1004').id)
        expect(dec, '基线一轮：1 条决策').toHaveLength(1)
        expect(dec[0].result, '基线结论：阻止').toBe('BLOCKED')
        expect(dec[0].matchedPolicyName, '基线命中：毛利不为正').toBe(profitName)
        expect(dec[0].wouldBePolicyName, '决策里记着"如果没被拦住，会自动过审的是直营-小单"')
          .toBe(POLICY_NAME.AUTO_DIRECT)
      })

      const profit = policyOf(profitName)
      // 版本快照条数基线：seed 是直接 INSERT 策略行的，所以基线可以是 0；
      // 这里只记「改之前是多少」，改完只允许 +1（绝不硬编码期望值为 1）
      const versionBefore = policyVersionCountOf(profit.id)

      await test.step('② 界面上把「毛利不为正」的优先级改成 5000（超过自动的 100/200/300）', async () => {
        await gotoPolicyPage(page)
        await selectPolicyTypeTab(page, 'BLOCK')

        const dlg = await openPolicyEditor(page, profit.id, profitName)
        await setEditorPriority(page, dlg, 5000)
        await savePolicyEditor(page, dlg)

        // 库侧：优先级真的改了，而且比所有自动策略都大
        const after = policyRowsOf()
        const p = after.find((x) => x.name === profitName)
        const autos = after.filter((x) => x.type === 'AUTO')
        expect(p.priority, '「毛利不为正」的优先级应是 5000').toBe(5000)
        expect(Math.max(...autos.map((x) => x.priority)), '5000 必须比每一条自动策略的优先级都大')
          .toBeLessThan(5000)
        expect(policyVersionCountOf(profit.id), '弹窗保存是"整条覆盖"，版本快照 +1（与行内上移不同）')
          .toBe(versionBefore + 1)

        // 页面：BLOCK 页签里它已经排到最后（同类内 still 按数字升序）
        const orderInTab = await policyNameOrder(page, 'BLOCK')
        expect(orderInTab.indexOf(profitName), '阻止页签里它应排到「双地址」之后')
          .toBe(orderInTab.indexOf(addrName) + 1)
      })

      await test.step('③ 再跑一轮：结论仍是阻止，而且"本来会过审"依旧被记下（数字大不妨碍它先赢）', async () => {
        await gotoAuditQueue(page)
        const run = await runAuditViaUi(page)

        const dec = decisionsOf(orderFactOf('QT-1004').id)
        expect(dec, '两轮各 1 条决策').toHaveLength(2)
        const latest = dec[dec.length - 1]
        expect(latest.result, '把阻止的优先级调到 5000 之后，结论不是自动过审，仍是阻止').toBe('BLOCKED')
        expect(latest.matchedPolicyName, '命中的仍是「毛利不为正」（它只是同类内排最后，整段仍先于自动）').toBe(profitName)
        expect(latest.wouldBePolicyName, '证据里仍记着"本来会自动过审的是直营-小单"（说明自动那条确实也满足）')
          .toBe(POLICY_NAME.AUTO_DIRECT)

        // 对照组：这一轮的判定链整条还在跑（QT-1002 仍被全局排除留人工、QT-1006 仍是不命中）。
        // 不拿 QT-1001 当对照：它第 1 轮已经自动过审（7→8），第 2 轮不是候选、不该再被推一次 ——
        // 那正是「重复执行不会重复过审」的另一条性质（六笔单走查-防回归 里单独钉过）。
        expect(run.decisions.find((d) => d.orderSn === 'QT-1006')?.result, '同一轮里自动那一段照旧在跑：QT-1006 仍是不命中')
          .toBe('NO_MATCH')
        expect(run.decisions.find((d) => d.orderSn === 'QT-1002')?.result, 'QT-1002 仍被全局排除挡在人工')
          .toBe('MANUAL')
      })
    } finally {
      if (baseline.length) restorePolicyPriorities(baseline)
      const rows = policyRowsOf()
      const p = rows.find((x) => x.name === profitName)
      const bad = p && baseline.length && p.priority !== baseline.find((b) => b.id === p.id).priority
      setAddrMulti(0)
      resetAuditDemoBaseline()
      if (bad) throw new Error(`「毛利不为正」的优先级没复原：实际 ${p.priority}，应为 ${baseline.find((b) => b.id === p.id).priority}`)
    }
  })
})
