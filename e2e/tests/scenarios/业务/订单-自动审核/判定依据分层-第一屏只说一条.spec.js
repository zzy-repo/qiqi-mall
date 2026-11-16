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
import { gotoAuditQueue, auditQueueRow, openWhyDrawer, closeWhyDrawer } from '../../../support/audit-ui.js'
import { adminTokenOf } from '../../../support/assert.js'

/**
 * 业务 / 订单-自动审核 / 判定依据分层（期 4）——「让人真的看见」的那一屏
 *
 * ── 这个用例存在的理由（用户原话）────────────────────────────────────────
 * 「这明明是直营店的单，但是把淘宝、抖音的判定都展示了一遍，渠道多了肉眼看得过来么」
 * 病根不是「展示得难看」，而是把**不适用于这单**的策略（渠道 ≠ 淘宝）当成了「差一点点」。
 * 于是渠道从 3 条涨到 30 条，第一屏就长 10 倍，而信息量一条没多。
 *
 * ── 它钉三件事（每件都必须**在屏幕上看得见**，不是接口字段对不对）──────────
 *   ① 打开一张**渠道不符**的单（QT-1003 抖音单）的抽屉：第一层只显示 **1 条**策略（最接近那条
 *      抖音-标准单），同时有一行灰字「另有 N 条不适用（渠道不符 …）」；淘宝-标准单**不出现**在第一层；
 *   ② 点开第二层 → 看到「淘宝-标准单」等不适用策略的名字 + 一句为什么；点开第三层 → 看到完整矩阵
 *      （逐条件 expected vs actual）；
 *   ③ 渠道策略 **从 3 条涨到 30 条**，第一层容器高度**不变**（用 boundingBox() 各量一次）——
 *      这是「第一屏长度不变」这句话唯一可测的形式。
 *
 * ── 30 条怎么造，为什么这么造 ────────────────────────────────────────────
 * 用 SQL 插 27 条「渠道 = 某渠道」的 AUTO 策略（条件只有一条 CHANNEL EQ X），那么对抖音单来说
 * 它们全部是**渠道不符 = 不适用** —— 正好是被压缩掉的那一类噪音。跑一轮真判定（点页面上的
 * 「立即审核一轮」，不调接口），让决策行里真的出现 30 条策略的留痕，再量第一屏。
 * 用完在 finally 里删干净（DELETEs 按名字前缀），下一次跑不受影响。
 *
 * ── 折叠为什么按「容器高度」量，而不是按 testid 数 ─────────────────────────
 * 三层里的矩阵 DOM 是**保留**的（页内搜索 / 无障碍 / 老断言都还能读到），只是被
 * max-height:0 + overflow:hidden 收起来了 —— 所以「在不在第一屏」的正确判据是
 * **它的盒子高度是不是 0**，不是「节点在不在」。这一点必须按高度断言，否则这条用例
 * 会在「DOM 有节点」上误判成通过。
 */
const SHOTS = process.env.E2E_SHOT_DIR || '/tmp/explain-layers'

/** 30 条渠道策略的名字前缀（造数与清理都只认这一个前缀，绝不按 id 范围猜） */
const BULK_PREFIX = 'e2e-渠道-'

/** 量与断言都按「层」为单位：第一层容器 = 第一屏 */
const LAYER1 = 'audit-explain-layer1'
const LAYER2 = 'audit-explain-layer2'
const LAYER3 = 'audit-explain-layer3'

const heightOf = async (locator) => {
  const box = await locator.boundingBox()
  expect(box, '要量的容器必须已经在页面上（拿不到盒子 = 前一步就没渲染出来）').not.toBeNull()
  return Math.round(box.height * 100) / 100
}

/**
 * 抽屉内容区的**总高**（scrollHeight：把所有层真实占的高度加总，折起来的层贡献 0）。
 * 第一层容器高度只证明「那张卡片没变长」；这个数证明「整个第一屏没变长」——
 * 两个一起看，才堵得住「卡片没长但把矩阵塞到卡片下面」这种退化。
 */
const contentHeightOf = async (locator) =>
  Math.round((await locator.evaluate((el) => el.scrollHeight)) * 100) / 100

/** 插 27 条「渠道 = 某渠道」的 AUTO 策略（对抖音单全部不适用） */
const seedBulkChannelPolicies = (count = 27) => {
  const values = Array.from({ length: count }, (_, i) => {
    const name = `${BULK_PREFIX}${String(i + 1).padStart(2, '0')}`
    return `('AUTO', ${900 + i}, '${name}', 1, 1, NULL)`
  }).join(',')
  execSql(`INSERT INTO audit_policy (\`type\`, \`priority\`, \`name\`, \`enabled\`, \`version_no\`, \`action_json\`) VALUES ${values}`)
  // 每个策略一条 MATCH 条件：CHANNEL EQ '某渠道'（用一个不存在的渠道名，保证永远"不符"）
  execSql(`INSERT INTO audit_policy_condition
             (policy_id, seq, kind, scope, field_code, op_code, value_type, value_num, value_text, value_json)
           SELECT p.id, 0, 'MATCH', 'POLICY', 'CHANNEL', 'EQ', 'TEXT', NULL, CONCAT('别处渠道', p.priority), NULL
           FROM audit_policy p WHERE p.\`type\` = 'AUTO' AND p.name LIKE '${BULK_PREFIX}%'`)
}

const dropBulkChannelPolicies = () => {
  execSql(`DELETE c FROM audit_policy_condition c JOIN audit_policy p ON p.id = c.policy_id
           WHERE p.\`type\` = 'AUTO' AND p.name LIKE '${BULK_PREFIX}%'`)
  execSql(`DELETE FROM audit_policy WHERE \`type\` = 'AUTO' AND name LIKE '${BULK_PREFIX}%'`)
}

const countPolicies = (where = '1=1') =>
  Number(queryDb(`SELECT COUNT(*) FROM audit_policy WHERE ${where}`) || 0)

test.describe('业务 › 订单-自动审核 › 判定依据分层（第一屏只说一条）', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 订单-自动审核 / 判定依据分层] API 库=${dbOfApi()} ADMIN=${ADMIN} 截图目录=${SHOTS}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()
    dropBulkChannelPolicies()   // 上一次异常退出可能留下残骸
  })

  test.afterAll(() => {
    dropBulkChannelPolicies()
    restoreAuditSchedule()
  })

  test('QT-1003（抖音单）：第一层只说「抖音-标准单」，渠道不符聚合成一行；30 条渠道策略时第一屏高度不变', async ({ page, request }) => {
    test.setTimeout(420_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    try {
      await test.step('① 复位基线 + 真跑一轮（页面按钮，不调接口）', async () => {
        resetAuditDemoBaseline()
        await gotoAuditQueue(page)
        await page.getByTestId('audit-queue-run').click()
        await page.locator('.el-message-box__btns').getByRole('button', { name: '开始执行' }).click()
        await expect(page.locator('.el-message-box')).toBeHidden({ timeout: 60_000 })
        await page.waitForTimeout(2000)
      })

      await test.step('② 接口先证明后端算出来了：layers 三层，渠道不符进第二层 byField，第一层一个字都不提它', async () => {
        const row = await getQueueRowOf(request, token, 'QT-1003')
        expect(row.result, 'QT-1003 应是 NO_MATCH（抖音单，差在应收 1580<500）').toBe('NO_MATCH')
        expect(row.layers, '队列行必须带 layers（第一屏的数据源）').toBeTruthy()

        const { layer1, layer2, layer3 } = row.layers
        expect(layer1.conclusion, '第一层结论应是「最接近」').toBe('最接近')
        expect(layer1.closest.policyName, '抖音单最接近的只有一条：抖音-标准单').toBe(POLICY_NAME.AUTO_DOUYIN)
        expect(String(layer1.oneLiner).length, '一句人话 ≤ 80 字（渠道再多也不会被撑破）').toBeLessThanOrEqual(80)
        // 【核心】渠道不符 = 不适用 = 噪音：第一层的任何字段里都不许出现淘宝-标准单
        expect(JSON.stringify(layer1), '第一层不许出现「淘宝-标准单」').not.toContain(POLICY_NAME.AUTO_TAOBAO)
        expect(layer1.notApplicableNote, '聚合句必须点明是哪个字段不符').toContain('条不适用')
        expect(layer1.notApplicableNote, '渠道不符的那条要算在「渠道」头上').toContain('渠道不符')

        const sum = (layer2.byField || []).reduce((a, f) => a + Number(f.count), 0)
        expect(sum, 'byField 合计 == notApplicableTotal（界面上两个数字自洽）').toBe(layer2.notApplicableTotal)
        expect((layer2.byField || []).map((f) => f.field), '分类字段必须是中文名，不是 CHANNEL 这种码')
          .toContain('渠道')
        expect(layer3.policies.length, '第三层一条不删：三条 AUTO 策略都在').toBe(3)
        const taobao = layer3.policies.find((p) => p.policyName === POLICY_NAME.AUTO_TAOBAO)
        expect(taobao.notApplicable, '淘宝-标准单 对抖音单是「不适用」').toBe(true)
        expect(taobao.notApplicableField, '它算在「渠道」头上').toBe('渠道')
      })

      await test.step('③ 屏幕上：第一层只有 1 条策略 + 一行「另有 N 条不适用」；矩阵默认不在第一屏', async () => {
        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1003'))
        const layer1 = drawer.getByTestId(LAYER1)
        await expect(layer1, '第一层必须渲染出来（audit-explain-layer1）').toBeVisible()

        // 结论 + 一句人话
        await expect(drawer.getByTestId('audit-explain-conclusion'), '第一层要有结论徽标').toHaveText('最接近')
        await expect(drawer.getByTestId('audit-explain-oneliner'), '一句人话里带具体差值')
          .toContainText('抖音-标准单')
        await expect(drawer.getByTestId('audit-explain-oneliner'), '要写出差多少').toContainText('1580.00')

        // 最接近那条：策略名 + 缺的条件人话，且**只有一条**
        const closest = drawer.getByTestId('audit-explain-closest')
        await expect(closest, '第一层要给出「最接近的那一条」').toBeVisible()
        await expect(closest, '策略名必须是抖音-标准单').toContainText(POLICY_NAME.AUTO_DOUYIN)
        await expect(closest, '第一层不许出现「淘宝-标准单」（它是渠道不符，不是差一点点）')
          .not.toContainText(POLICY_NAME.AUTO_TAOBAO)
        await expect(closest, '第一层不许出现「直营-小单」（客户标签不符同理）')
          .not.toContainText(POLICY_NAME.AUTO_DIRECT)

        // 「另有 N 条不适用（渠道不符 …）」那一行灰字
        const notApplicable = drawer.getByTestId('audit-explain-not-applicable')
        await expect(notApplicable, '第一层要有一行「另有 N 条不适用」').toBeVisible()
        await expect(notApplicable, '这行要点明原因（渠道不符）').toContainText('另有 2 条不适用')
        await expect(notApplicable, '原因要用中文分类字段名').toContainText('渠道不符')

        // 【核心】第一屏不含矩阵：两层折叠容器的盒子高度必须是 0
        expect(await heightOf(drawer.getByTestId('audit-explain-layer2-body')),
          '第二层默认折起（高度 0）').toBe(0)
        expect(await heightOf(drawer.getByTestId('audit-explain-layer3-body')),
          '第三层（完整矩阵）默认折起：第一屏里它的高度必须是 0').toBe(0)
        // 兜底：矩阵的表格行确实还在 DOM 里（折叠 ≠ 删除），只是被收起来了
        // 三条策略各 1 行「真不满足」（直营的客户标签 / 淘宝的渠道 / 抖音的应收）—— 与旧用例同口径
        await expect(drawer.locator('.el-table__body tr.why-row-miss'),
          '矩阵行还在 DOM 里（折叠不删内容）').toHaveCount(3)

        await page.screenshot({ path: `${SHOTS}/1-第一层.png`, fullPage: false })
      })

      await test.step('④ 点开第二层：看到不适用策略的名字 + 一句为什么', async () => {
        const drawer = page.getByTestId('audit-why-drawer')
        await drawer.getByTestId('audit-explain-not-applicable').click()
        await page.waitForTimeout(400)

        const layer2 = drawer.getByTestId(LAYER2)
        await expect(layer2, '第二层应展开（audit-explain-layer2）').toBeVisible()
        expect(await heightOf(drawer.getByTestId('audit-explain-layer2-body')), '展开后高度 > 0').toBeGreaterThan(0)
        await expect(layer2, '第二层要列出「淘宝-标准单」并说明为什么（渠道 = 淘宝，实际 抖音）')
          .toContainText(POLICY_NAME.AUTO_TAOBAO)
        await expect(layer2, '第二层要列出「直营-小单」（客户标签不符）').toContainText(POLICY_NAME.AUTO_DIRECT)
        await expect(layer2, '一句为什么取自引擎原文：渠道 = 淘宝（实际 抖音）').toContainText('实际 抖音')
        // 第二层**不含**完整条件表（那是第三层的事）
        await expect(layer2.locator('.el-table__body tr'), '第二层不许含条件表').toHaveCount(0)

        await page.screenshot({ path: `${SHOTS}/2-第二层展开.png`, fullPage: false })
      })

      await test.step('⑤ 点开第三层：看到完整矩阵（逐条件 expected vs actual）', async () => {
        const drawer = page.getByTestId('audit-why-drawer')
        await drawer.getByTestId('audit-explain-layer3-toggle').click()
        await page.waitForTimeout(600)

        const layer3 = drawer.getByTestId(LAYER3)
        expect(await heightOf(drawer.getByTestId('audit-explain-layer3-body')), '第三层展开后高度 > 0').toBeGreaterThan(0)
        await expect(layer3, '完整矩阵里三条策略一条不删').toContainText(POLICY_NAME.AUTO_TAOBAO)
        await expect(layer3, '矩阵里要有具体差值：应收金额 1580.00 < 500.00').toContainText('应收金额 1580.00 < 500.00')
        await expect(layer3.locator('.el-table__body tr.why-row-miss').first(), '矩阵里有「不满足」的数据行').toBeVisible()

        await page.screenshot({ path: `${SHOTS}/3-第三层展开.png`, fullPage: false })
      })

      await test.step('⑥ 渠道 3→30：第一层高度不变（这是「第一屏长度不变」唯一可测的形式）', async () => {
        // ⚠️ 量之前必须**关掉再打开**：上一步把第三层（矩阵）点开了，折叠状态还留着。
        //    拿展开状态的 scrollHeight 当「第一屏」量出来的 1453px 是上一版的坑
        //    （30 条那一侧是新打开的、默认全折起 → 812px，于是「渠道多了反而变短」的假象）。
        //    抽屉每次打开都从第一层开始（openWhy 里把两层折叠都复位），所以重新打开 = 标准第一屏。
        await closeWhyDrawer(page)
        const drawer = await openWhyDrawer(page, auditQueueRow(page, 'QT-1003'))
        const before = await heightOf(drawer.getByTestId(LAYER1))
        const beforeContent = await contentHeightOf(drawer.locator('.el-drawer__body'))
        await closeWhyDrawer(page)

        // 造 27 条「渠道不符」的 AUTO 策略 → 一共 30 条
        seedBulkChannelPolicies(27)
        expect(countPolicies(`\`type\` = 'AUTO'`), '现在应有 30 条 AUTO 策略').toBe(30)

        // 重新打开页面（策略列表要重新拉，否则抽屉里的 whyGroups 还是 3 条）+ 再跑一轮真判定
        await page.reload()
        await gotoAuditQueue(page)
        await page.getByTestId('audit-queue-run').click()
        await page.locator('.el-message-box__btns').getByRole('button', { name: '开始执行' }).click()
        await expect(page.locator('.el-message-box')).toBeHidden({ timeout: 120_000 })
        await page.waitForTimeout(3000)

        const row30 = await getQueueRowOf(request, token, 'QT-1003')
        expect(row30.layers.layer2.notApplicableTotal,
          '30 条策略里应有 29 条不适用（直营-小单 客户标签 + 淘宝/27 条 渠道）').toBe(29)
        expect(row30.layers.layer1.closest.policyName, '最接近的还是那一条').toBe(POLICY_NAME.AUTO_DOUYIN)

        const drawer30 = await openWhyDrawer(page, auditQueueRow(page, 'QT-1003'))
        const layer1_30 = drawer30.getByTestId(LAYER1)
        await expect(layer1_30).toBeVisible()
        const after = await heightOf(layer1_30)
        const afterContent = await contentHeightOf(drawer30.locator('.el-drawer__body'))
        expect(await heightOf(drawer30.getByTestId('audit-explain-layer3-body')),
          '30 条时矩阵照样默认折起（高度 0）').toBe(0)

        // 第一层里仍然只有一条策略
        await expect(drawer30.getByTestId('audit-explain-closest'), '第一层仍然只说一条')
          .toContainText(POLICY_NAME.AUTO_DOUYIN)
        await expect(drawer30.getByTestId('audit-explain-closest'), '30 条时第一层也不许冒出渠道策略')
          .not.toContainText(POLICY_NAME.AUTO_TAOBAO)
        await expect(drawer30.getByTestId('audit-explain-not-applicable'), '聚合句换成 29 条')
          .toContainText('另有 29 条不适用')

        await page.screenshot({ path: `${SHOTS}/4-渠道30条时的第一屏.png`, fullPage: false })

        // eslint-disable-next-line no-console
        console.log(`[判定依据分层] 第一层高度：3 条渠道策略 = ${before}px，30 条 = ${after}px，差 = ${Math.abs(after - before)}px`)
        // eslint-disable-next-line no-console
        console.log(`[判定依据分层] 抽屉内容总高：3 条渠道策略 = ${beforeContent}px，30 条 = ${afterContent}px，差 = ${Math.abs(afterContent - beforeContent)}px`)
        expect(Math.abs(after - before),
          `第一层高度必须不变（3 条渠道策略 ${before}px vs 30 条 ${after}px）—— 渠道涨 10 倍，第一屏不能跟着涨`)
          .toBeLessThanOrEqual(1)
        expect(Math.abs(afterContent - beforeContent),
          `第一屏（抽屉内容总高）也必须不变（3 条 ${beforeContent}px vs 30 条 ${afterContent}px）`)
          .toBeLessThanOrEqual(2)
      })
    } finally {
      dropBulkChannelPolicies()
      resetAuditDemoBaseline()
    }
  })
})
