import { test, expect } from '../../../support/test.js'
import { ADMIN } from '../../../support/env.js'
import { dbOfApi, execSql, queryDb } from '../../../support/db.js'
import { adminLoginHardened } from '../../../support/admin-ui.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  POLICY_NAME
} from '../../../support/audit-fixtures.js'
import { getQueueRowOf } from '../../../support/audit-api.js'
import { adminTokenOf } from '../../../support/assert.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  reasonMainOf,
  reasonClosestOf,
  runAuditViaUi,
  openWhyDrawer,
  closeWhyDrawer
} from '../../../support/audit-ui.js'

/**
 * 业务 / 订单-自动审核 / 队列行就说「最像哪条、差什么」（期 4 · T2）
 *
 * ── 这个用例存在的理由（审查者原话）─────────────────────────────────────
 * 「结论必须落地 …不落标，人就在队列里看不见它——现在 21 条不命中的单全是空白，正是这个缺口。」
 * 标落了之后还差最后一步：**"为什么卡、最像哪条"还得点开抽屉才知道**。
 * 也即「只做第 1 步就收工，那还是"要再点一下才看见"，不够」。
 *
 * 所以本用例钉的是「**不点抽屉**，在队列里就读得到」这件事，三条断言一条都不能少：
 *   a) 一张**不命中**的单（QT-1003），队列行里能读到**策略名**（抖音-标准单）与
 *      **差的条件的人话 + 数字**（应收金额 1580.00 < 500.00）；
 *   b) 表头仍是 **10 列**（不许加列：这张表 10 列已 1647px 顶满 1960 画幅，
 *      加列会把「原因」挤出画面 —— 上一轮踩过，见「判定」列与「结论标」用例的同类笔记）；
 *   c) 点开抽屉后，第一层的「一句人话」与行内那句**必须是同一句话**：本用例直接比较两边的
 *      文本，并且**两边都等于接口的 `layers.layer1.oneLiner`** ——
 *      这一条防的是「行内和抽屉说法不一样」，那是运营最不信任的情况。
 *      它之所以能成立，是取法决定的（两边渲染同一个字段，前端没有第二份 closest 拼装），
 *      而**不是**靠"我们测过一次一样"。
 *
 * ── 第二条（T1 遗留）为什么也要在这里钉 ─────────────────────────────────
 * 第一层的「最接近那条差在哪」以前是**逐条全列**的：一条策略差 8 个条件时，第一层（和行内那句话
 * 的来源）会跟着变长 —— 那正是「渠道 3→30、第一屏不能跟着长」这条纪律换了个维度的复发。
 * 现在显示层只摆前 2 条 + 「…还有 N 条」。要验它，就得让「最接近那条」真的差 3 个条件：
 *   · QT-1003 对 seed 的三条 AUTO 策略里，只有「抖音-标准单」适用且只差 1 条条件，
 *     淘宝/直营两条是「渠道/客户标签不符 = 不适用」（不参与竞争）；
 *   · 所以本用例**临时**造一条差 3 个条件的 AUTO 策略，并临时停用「抖音-标准单」，
 *     让那条策略成为唯一候选（= 最接近），跑一轮真判定看显示；
 *   · 用完在 finally 里按名字前缀删策略、把「抖音-标准单」的 enabled 写回 1，
 *     再复位六笔单的基线 —— 三步都可重复执行（幂等），不依赖上一轮是否成功。
 *
 * ── 为什么断言落在**文本**而不是高度上 ─────────────────────────────────
 * 「行内那句话看不看得见」这件事，唯一可测的形式是「这个元素的文本里有没有那些字」。
 * 高度只能证明"多了几行"，证明不了"说的是不是那句"。行高增量本用例也量（只作记录，见 ⑥），
 * 量法：把那一行 display:none 掉再量行高，差值就是它真实占的高度。
 */
const SHOTS = process.env.E2E_SHOT_DIR || '/tmp/t2-queue-row'

/** 造数策略名（清理只认这个名字前缀，绝不按 id 范围猜） */
const CRAFT_NAME = 'e2e-三缺项'

/** 断言用的两个口径：n 条不命中单的两侧文本都从接口这一个字段取 */
const layer1Of = (row) => row?.layers?.layer1 || {}

/** 行高（量之前必须已经渲染出来） */
const heightOf = async (locator) => {
  const box = await locator.boundingBox()
  expect(box, '要量的元素必须已经在页面上（拿不到盒子 = 前一步就没渲染出来）').not.toBeNull()
  return Math.round(box.height * 100) / 100
}

/** 造一条「差 3 个条件」的 AUTO 策略：三条量级条件对 QT-1003 全部不满足 */
const seedThreeMissPolicy = () => {
  execSql(`INSERT INTO audit_policy (\`type\`, \`priority\`, \`name\`, \`enabled\`, \`version_no\`, \`action_json\`)
           VALUES ('AUTO', 900, '${CRAFT_NAME}', 1, 1, NULL)`)
  // 三条都是**量级**条件（不是渠道/店铺/标签这类分类字段）—— 分类字段会把这单判成「不适用」，
  // 那它就不参与「最接近」的竞争，也就验不到「差 3 个条件时第一层只摆 2 条」。
  execSql(`INSERT INTO audit_policy_condition
             (policy_id, seq, kind, scope, field_code, op_code, value_type, value_num, value_text, value_json)
           SELECT id, 0, 'MATCH', 'POLICY', 'RECEIVABLE_AMOUNT', 'LT', 'NUM', 500, NULL, NULL
           FROM audit_policy WHERE name = '${CRAFT_NAME}'`)
  execSql(`INSERT INTO audit_policy_condition
             (policy_id, seq, kind, scope, field_code, op_code, value_type, value_num, value_text, value_json)
           SELECT id, 1, 'MATCH', 'POLICY', 'GROSS_PROFIT', 'LT', 'NUM', 100, NULL, NULL
           FROM audit_policy WHERE name = '${CRAFT_NAME}'`)
  execSql(`INSERT INTO audit_policy_condition
             (policy_id, seq, kind, scope, field_code, op_code, value_type, value_num, value_text, value_json)
           SELECT id, 2, 'MATCH', 'POLICY', 'ITEM_KIND_COUNT', 'LTE', 'NUM', 0, NULL, NULL
           FROM audit_policy WHERE name = '${CRAFT_NAME}'`)
}

const dropCraftPolicy = () => {
  execSql(`DELETE c FROM audit_policy_condition c JOIN audit_policy p ON p.id = c.policy_id
           WHERE p.name = '${CRAFT_NAME}'`)
  execSql(`DELETE v FROM audit_policy_version v JOIN audit_policy p ON p.id = v.policy_id
           WHERE p.name = '${CRAFT_NAME}'`)
  execSql(`DELETE FROM audit_policy WHERE name = '${CRAFT_NAME}'`)
}

/** 临时停用 / 恢复「抖音-标准单」（只为让造的那条成为唯一候选） */
const setDouyinEnabled = (enabled) =>
  execSql(`UPDATE audit_policy SET enabled = ${enabled ? 1 : 0}
           WHERE \`type\` = 'AUTO' AND name = '${POLICY_NAME.AUTO_DOUYIN}'`)

const douyinEnabled = () =>
  Number(queryDb(`SELECT enabled FROM audit_policy WHERE \`type\` = 'AUTO' AND name = '${POLICY_NAME.AUTO_DOUYIN}'`) || 0)

test.describe('业务 › 订单-自动审核 › 队列行就说「最像哪条、差什么」', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 订单-自动审核 / 队列行-不命中就说像哪条] API 库=${dbOfApi()} ADMIN=${ADMIN} 截图目录=${SHOTS}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
    // 上一次异常退出可能留下的残骸：造的策略要删、被停用的抖音策略要开回来
    dropCraftPolicy()
    setDouyinEnabled(true)
  })

  test.afterAll(() => {
    dropCraftPolicy()
    setDouyinEnabled(true)
    restoreAuditSchedule()
  })

  test('QT-1003：不点抽屉就看见「抖音-标准单，差应收 1580<500」；与抽屉第一层逐字相同；10 列不变', async ({ page, request }) => {
    test.setTimeout(420_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)
    // 运营真实画幅：这张表 10 列是按 1960 推的（见 playwright.config.js 的 1647px 推导），
    // 而 e2e 默认视口是 1440 —— 截图要「看得清字」，也要跟「10 列不挤」这句结论同一画幅。
    await page.setViewportSize({ width: 1960, height: 1080 })

    try {
      await test.step('① 复位基线 + 真跑一轮（点页面按钮，不调接口）', async () => {
        resetAuditDemoBaseline()
        await gotoAuditQueue(page)
        await runAuditViaUi(page)
        await expect(auditQueueRow(page, 'QT-1003'), '队列里要有 QT-1003 的行').toBeVisible({ timeout: 30_000 })
      })

      await test.step('② 接口先自证：这句话是后端算的（layers.layer1.oneLiner），不是页面拼的', async () => {
        const row = await getQueueRowOf(request, token, 'QT-1003')
        expect(row.result, 'QT-1003 应是 NO_MATCH（抖音单，差在应收 1580<500）').toBe('NO_MATCH')
        const l1 = layer1Of(row)
        expect(l1.conclusion, '第一层结论应是「最接近」').toBe('最接近')
        expect(l1.closest?.policyName, '最接近那条应是「抖音-标准单」').toBe(POLICY_NAME.AUTO_DOUYIN)
        expect(l1.oneLiner, '一句人话里要有策略名').toContain(POLICY_NAME.AUTO_DOUYIN)
        expect(l1.oneLiner, '一句人话里要有差的条件人话（含数字）').toContain('应收金额 1580.00 < 500.00')
      })

      await test.step('③ b) 表头仍是 10 列（不加列，靠行内多一行解决）', async () => {
        const headers = await page.locator('[data-testid="audit-queue-table"] thead th').allInnerTexts()
        const realHeaders = headers.map((h) => h.trim()).filter(Boolean)
        expect(realHeaders, '仍是 10 列：加列会把「原因」挤出 1960 画幅').toHaveLength(10)
      })

      await test.step('④ a) 队列行（**不点抽屉**）：策略名 + 差的条件人话（含数字）都在', async () => {
        const row = auditQueueRow(page, 'QT-1003')
        // 原有那句还在（这一行是"在它下面再补一行"，不是替换）
        await expect(reasonMainOf(row), '原因首句仍是「没有任何自动策略命中」')
          .toContainText('没有任何自动策略命中')

        const closest = reasonClosestOf(row)
        await expect(closest, '不点抽屉就要有「最像哪条」那一行（audit-row-closest）').toBeVisible()
        await expect(closest, '行内要说出策略名').toContainText(POLICY_NAME.AUTO_DOUYIN)
        await expect(closest, '行内要说出差的条件（人话 + 数字）').toContainText('应收金额 1580.00 < 500.00')
        await expect(closest, '行内要说出还差几个条件').toContainText('还差 1 个条件')
        // 这两条是「第一层只说一条」在**行内**的同一个要求：不适用策略（渠道/标签不符）不是「差一点点」，
        // 它们已经在后端被摘进第二层了，行内那句话里不该再出现它们的名字
        await expect(closest, '行内不该出现「淘宝-标准单」（渠道不符 = 不适用）')
          .not.toContainText(POLICY_NAME.AUTO_TAOBAO)
        await expect(closest, '行内不该出现「直营-小单」（客户标签不符 = 不适用）')
          .not.toContainText(POLICY_NAME.AUTO_DIRECT)
        // 同一行**下面**那行「后端原话」仍在（逐条列出所有策略差在哪，属于细节，不是行内那句话）
        await expect(row.locator('.reason-sub'), '后端原话那行没被顶掉（它保留了逐条细节）').toBeVisible()

        await row.locator('.reason-cell').screenshot({ path: `${SHOTS}/队列行-QT-1003-原因两行.png` })
        await page.screenshot({ path: `${SHOTS}/队列-QT-1003.png`, fullPage: false })
      })

      await test.step('⑤ c) 点开抽屉：第一层与行内那句**必须是同一句话**（且都等于接口那一个字段）', async () => {
        const rowText = (await reasonClosestOf(auditQueueRow(page, 'QT-1003')).innerText()).trim()
        const row = await getQueueRowOf(request, token, 'QT-1003')
        const apiText = String(layer1Of(row).oneLiner || '').trim()

        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1003'))
        const drawerText = (await drawer.getByTestId('audit-explain-oneliner').innerText()).trim()

        // 三边相等：行内 == 接口字段 == 抽屉第一层。
        // 只比「行内 vs 抽屉」还不够 —— 那样两处都自己拼一句「差不多」的话也能过；
        // 要求两边都等于**接口那一个字段**，才钉死「同一个 closest，没有第二处算一遍」。
        expect(rowText, '行内那句必须就是接口的 layers.layer1.oneLiner').toBe(apiText)
        expect(drawerText, '抽屉第一层的「一句人话」必须就是接口的 layers.layer1.oneLiner').toBe(apiText)
        expect(drawerText, '行内与抽屉第一层必须逐字相同').toBe(rowText)

        // 同一个 closest：名字一致，且行内那句话里带的就是抽屉 closest 的第一条缺项
        const closestBlock = drawer.getByTestId('audit-explain-closest')
        await expect(closestBlock, '抽屉 closest 策略名与行内那句话是同一家')
          .toContainText(`最接近：「${POLICY_NAME.AUTO_DOUYIN}」`)
        await expect(closestBlock, '抽屉 closest 的缺项就是行内那句里的那一条')
          .toContainText('应收金额 1580.00 < 500.00')
        // 只有 1 条缺项时**不该**出现「…还有 N 条」（别让一句多余的话把第一屏撑长）
        await expect(drawer.getByTestId('audit-explain-closest-more'), '只差 1 条时不该有「…还有 N 条」')
          .toHaveCount(0)

        await page.screenshot({ path: `${SHOTS}/抽屉第一层与行内一致.png`, fullPage: false })
        await closeWhyDrawer(page)
      })

      await test.step('⑥ 行高实测：那一行真实占了多少（1960 运营画幅 + 1440 e2e 视口各量一次）', async () => {
        /**
         * 把那一行 {@code display:none} 掉再量**同一条行**：差值就是它真实占的高度
         * （含 flex 的 2px 间距）。这比「换一张没有这行的单来比」准 —— 那样的差里还混着
         * 别的单元格高度差（冻结标、标 chips），证明不了是这一行的账。
         */
        const measureDelta = async () => {
          const row = auditQueueRow(page, 'QT-1003')
          const line = reasonClosestOf(row)
          const withLine = await heightOf(row)
          const lineHeight = await heightOf(line)
          await line.evaluate((el) => { el.style.display = 'none' })
          const withoutLine = await heightOf(row)
          await line.evaluate((el) => { el.style.display = '' })
          return { withLine, withoutLine, lineHeight, delta: Math.round((withLine - withoutLine) * 100) / 100 }
        }

        const wide = await measureDelta()   // 1960：运营画幅（这张表的 10 列就是按它推的）
        await page.setViewportSize({ width: 1440, height: 950 })
        await page.waitForTimeout(400)
        const narrow = await measureDelta() // 1440：e2e 默认视口（「原因」列更窄 → 折行更多，是最坏情况）
        await page.setViewportSize({ width: 1960, height: 1080 })
        await page.waitForTimeout(400)

        // eslint-disable-next-line no-console
        console.log(`[队列行-最像哪条] 行高增量（1960 画幅）：带这一行 ${wide.withLine}px / 去掉 ${wide.withoutLine}px，差 ${wide.delta}px（文字盒子 ${wide.lineHeight}px）`)
        // eslint-disable-next-line no-console
        console.log(`[队列行-最像哪条] 行高增量（1440 视口）：带这一行 ${narrow.withLine}px / 去掉 ${narrow.withoutLine}px，差 ${narrow.delta}px（文字盒子 ${narrow.lineHeight}px）`)

        expect(wide.delta, '这一行确实占了高度（不是 0，说明它真的渲染出来了）').toBeGreaterThan(0)
        expect(wide.delta, '只多一两行文字的高度，不许把整段矩阵塞进行内').toBeLessThan(60)
      })

      await test.step('⑦ 差 3 个条件时：行内只说第 1 条，抽屉第一层只摆 2 条 + 「…还有 1 条」', async () => {
        // 造数：一条差 3 个条件的 AUTO 策略 + 临时停用抖音-标准单（让它成为唯一候选 = 最接近）
        resetAuditDemoBaseline()
        seedThreeMissPolicy()
        setDouyinEnabled(false)
        expect(douyinEnabled(), '「抖音-标准单」应已临时停用').toBe(0)

        // 策略列表是页面打开时拉的 —— 重开页面再跑一轮，让新策略真的进入这一轮判定
        await page.reload()
        await gotoAuditQueue(page)
        await runAuditViaUi(page)
        await expect(auditQueueRow(page, 'QT-1003'), '队列里要有 QT-1003 的行').toBeVisible({ timeout: 30_000 })

        const apiRow = await getQueueRowOf(request, token, 'QT-1003')
        const l1 = layer1Of(apiRow)
        expect(l1.closest?.policyName, '此时最接近的应是造的那条（差的条件最少的那条）').toBe(CRAFT_NAME)
        expect(l1.closest?.missingConditions, '它确实差 3 个条件（这就是要截断的那种情况）').toHaveLength(3)
        expect(l1.oneLiner, '一句人话里带的是第 1 条缺项 + 总数').toContain('还差 3 个条件')
        expect(l1.oneLiner, '第 1 条缺项是 seq 最小的那条（应收）').toContain('应收金额 1580.00 < 500.00')

        const rowText = (await reasonClosestOf(auditQueueRow(page, 'QT-1003')).innerText()).trim()
        // 行内空间最小：只显示第 1 条 —— 第 2、3 条的人话不许出现
        expect(rowText, '行内那句就是接口的 oneLiner').toBe(String(l1.oneLiner).trim())
        expect(rowText, '行内只说第 1 条（不该出现第 2 条：毛利）').not.toContain('毛利')
        expect(rowText, '行内只说第 1 条（不该出现第 3 条：商品种类数）').not.toContain('商品种类数')

        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1003'))
        const closestBlock = drawer.getByTestId('audit-explain-closest')
        await expect(closestBlock, '第一层仍然只说一条策略').toContainText(`最接近：「${CRAFT_NAME}」`)
        // 只摆前 2 条：第 1、2 条在，第 3 条不在（数据层仍然全给，见接口断言 3 条）
        await expect(closestBlock, '第 1 条缺项要摆出来').toContainText('应收金额 1580.00 < 500.00')
        await expect(closestBlock, '第 2 条缺项要摆出来').toContainText('毛利')
        await expect(closestBlock, '第 3 条缺项被收起来（第一层只摆 2 条）').not.toContainText('商品种类数')
        // 收起的那几条要说个数，别让人以为只有 2 条
        await expect(drawer.getByTestId('audit-explain-closest-more'), '要明确写出「…还有 1 条」')
          .toHaveText('…还有 1 条（逐条见下方完整矩阵）')
        // 展开第三层（完整矩阵）仍然能看到被收起的那一条 —— 截断只在显示层，数据一条没少
        await drawer.getByTestId('audit-explain-layer3-toggle').click()
        await page.waitForTimeout(600)
        await expect(drawer.getByTestId('audit-explain-layer3-body'), '完整矩阵里那 3 个条件一条不少')
          .toContainText('商品种类数')

        await page.screenshot({ path: `${SHOTS}/差3个条件-第一层只摆2条.png`, fullPage: false })
        await closeWhyDrawer(page)
      })
    } finally {
      // 三步都幂等：删造的策略、把抖音策略开回来、复位六笔单
      resetAuditDemoBaseline()
      dropCraftPolicy()
      setDouyinEnabled(true)
      expect(douyinEnabled(), '「抖音-标准单」必须已经恢复启用（否则后面所有判定都会少一条策略）').toBe(1)
    }
  })
})
