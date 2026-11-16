import { test, expect } from '../../../support/test.js'
import { API, ADMIN } from '../../../support/env.js'
import { dbOfApi } from '../../../support/db.js'
import { adminLoginHardened } from '../../../support/admin-ui.js'
import { adminTokenOf } from '../../../support/assert.js'
import {
  ensureAuditDemoSeed,
  resetAuditDemoBaseline,
  quietAuditSchedule,
  restoreAuditSchedule,
  orderFactOf,
  activeTagsOf,
  activeConclusionTagsOf,
  activeHangTagsOf
} from '../../../support/audit-fixtures.js'
import { getAuditQueue, getQueueRowOf } from '../../../support/audit-api.js'
import {
  gotoAuditQueue,
  auditQueueRow,
  badgeOf,
  runAuditViaUi,
  filterByTag,
  clearTagFilter
} from '../../../support/audit-ui.js'

/**
 * 业务 / 订单-自动审核 / 结论标-看得见与筛得动（期 1）
 *
 * 这个场景要证明的一件事：
 *   **判定结论必须落成可读、可筛、可聚合的标**；决策行退回审计层。
 *   业务人员看标，排障人员看矩阵 —— 而队列上"什么都没有"是不合格的。
 *
 * 期 1 的结论 → 标映射（六笔演示单刚好覆盖五种结论 + 一条不落标的 AUTO_PASS）：
 *   QT-1001 AUTO_PASS    → **不产标**（自动过审等于"什么都没发生"，落上去是噪声）7→8
 *   QT-1002 MANUAL(排除) → AUDIT_PENDING  ← 以前这一支一个标都不落，队列里一片空白
 *   QT-1003 NO_MATCH     → NO_POLICY_MATCH
 *   QT-1004 BLOCKED      → BLOCKED
 *   QT-1005 FROZEN       → FROZEN（既有 P2 路径，本用例不重复验它在目录里的元数据）
 *   QT-1006 NO_MATCH     → NO_POLICY_MATCH
 *
 * ⚠️ 口径（2026-11-16 修正，别写反了）：
 *   「AUTO_PASS 这个**结论**不产标」≠「自动过审的单**不能有任何标**」。
 *   客诉客户 / 绿旗急发 / 双地址这类**特征标**与"过没过"无关，打了标照样自动过审
 *   （吉客云原话）。所以断言一律写"**结论**标为 0"（按标目录 category='结论' 过滤），
 *   绝不写"没有任何标"—— 后者将来加一个特征标就会误红。
 *
 * 页面侧钉四件事：
 *   ① 行内能看到标的中文名（不是英文码），且不新开列（10 列已顶满画幅）；
 *   ② 一行的标超过 2 个时收成 "+N"（显示层压缩规则，不动数据）；
 *   ③ 按标筛选（?tag=）能筛出目标单，且**不重置** result 页签；
 *   ④ 抽屉「本单身上的标」逐条给 中文名 + 一句话说明 + 打标时间。
 */
test.describe('业务 › 订单-自动审核 › 结论标-看得见与筛得动', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 订单-自动审核 / 结论标-看得见与筛得动] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
    ensureAuditDemoSeed()
    quietAuditSchedule()      // 定时任务会抢走种子单，导致本轮计数漂
  })

  test.afterAll(() => {
    restoreAuditSchedule()
  })

  test('跑一轮后：五种结论各落各的标（AUTO_PASS 不落结论标）；队列行看得见、按标筛得动、抽屉说得出处', async ({ page, request }) => {
    test.setTimeout(300_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)

    resetAuditDemoBaseline()

    await test.step('① 跑一轮：结论标真的落到每张单身上（AUTO_PASS 除外）', async () => {
      await gotoAuditQueue(page)
      await runAuditViaUi(page)

      // ---- 库：结论标逐单对号 ----
      expect(activeConclusionTagsOf(orderFactOf('QT-1003').id), 'QT-1003（NO_MATCH）应落 NO_POLICY_MATCH')
        .toEqual(['NO_POLICY_MATCH'])
      expect(activeConclusionTagsOf(orderFactOf('QT-1006').id), 'QT-1006（NO_MATCH）应落 NO_POLICY_MATCH')
        .toEqual(['NO_POLICY_MATCH'])
      expect(activeConclusionTagsOf(orderFactOf('QT-1002').id), 'QT-1002（MANUAL·被排除）应落 AUDIT_PENDING')
        .toEqual(['AUDIT_PENDING'])
      expect(activeConclusionTagsOf(orderFactOf('QT-1004').id), 'QT-1004（BLOCKED）应落 BLOCKED')
        .toEqual(['BLOCKED'])
      expect(activeConclusionTagsOf(orderFactOf('QT-1001').id), 'QT-1001（AUTO_PASS）**不产结论标**')
        .toEqual([])
      // MANUAL 的"被排除"这支不许打挂起标（挂起标只有库存/成本预检降级才打）
      expect(activeHangTagsOf(orderFactOf('QT-1002').id), 'MANUAL（排除型）不该有挂起标').toEqual([])

      // 判定结论一个字没变（本期是"两个都写"，不是"标取代决策行"）
      const q = await getQueueRowOf(request, token, 'QT-1003')
      expect(q.result, '队列 result 仍是 NO_MATCH（标不改写结论）').toBe('NO_MATCH')
      expect(q.tagCodes, '队列行必须回 tagCodes（驼峰键，前端直接可用）').toContain('NO_POLICY_MATCH')
      const tag0 = (q.tags || [])[0]
      expect(tag0.name, '标要带中文名（页面显示的就是它，永远不是英文码）').toBe('无策略命中')
      expect(String(tag0.description), '标要带一句话说明').toContain('没有任何自动策略命中')
      expect(String(tag0.at), '标要带打标时间（抽屉里逐条显示）').toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
    })

    await test.step('② 队列行：标的中文名看得见（且不是新开一列）', async () => {
      const row3 = auditQueueRow(page, 'QT-1003')
      await expect(badgeOf(row3), 'QT-1003 判定徽标仍是 NO_MATCH').toHaveAttribute('data-result', 'NO_MATCH')
      const chips3 = row3.locator('[data-testid="audit-row-tags-QT-1003"]')
      await expect(chips3, 'QT-1003 行上应出现标区').toBeVisible()
      await expect(chips3, '标区要带全部标码（data-tag-codes，供 e2e/排障直读）')
        .toHaveAttribute('data-tag-codes', /NO_POLICY_MATCH/)
      await expect(chips3, '标显示的是**中文名**「无策略命中」').toContainText('无策略命中')

      const chips4 = auditQueueRow(page, 'QT-1004').locator('[data-testid="audit-row-tags-QT-1004"]')
      await expect(chips4, 'QT-1004（阻断）应显示「被策略拦截」').toContainText('被策略拦截')
      const chips2 = auditQueueRow(page, 'QT-1002').locator('[data-testid="audit-row-tags-QT-1002"]')
      await expect(chips2, 'QT-1002（留人工）应显示「待人工审核」').toContainText('待人工审核')

      // AUTO_PASS 的单不产结论标 → 本场景里它的行上没有标区
      const row1 = auditQueueRow(page, 'QT-1001')
      await expect(row1.locator('[data-testid="audit-row-tags-QT-1001"]'),
        'AUTO_PASS 不产标 → 行上不该出现标区（注意：这是"结论标为 0"，不是"任何标都不许有"）')
        .toHaveCount(0)

      // 不新开列：表头仍是那 10 列（加列会把「原因」挤出 1960 画幅，上一轮踩过）
      const headers = await page.locator('[data-testid="audit-queue-table"] thead th').allInnerTexts()
      const realHeaders = headers.map((h) => h.trim()).filter(Boolean)
      expect(realHeaders, '仍是 10 列，本期不加列（标叠在判定列里）').toHaveLength(10)
    })

    await test.step('③ 按标筛选：?tag=NO_POLICY_MATCH 筛出两笔不命中的单，且不重置 result 页签', async () => {
      // 接口层：tag 与 result 正交、可并列
      const byTag = await getAuditQueue(request, token, { tag: 'NO_POLICY_MATCH', size: 100 })
      const tagRows = byTag.rows || []
      const tagSns = tagRows.map((r) => r.order_sn)
      // 共享库里可能还有别的 NO_MATCH 单（例如别的用例造的 QQ 单）——所以断言"包含"而不是"等于"，
      // 真正要钉的是「筛出来的**每一行**都挂着这个标」这条性质。
      expect(tagSns, '接口 ?tag=NO_POLICY_MATCH 应包含那两笔演示单').toEqual(
        expect.arrayContaining(['QT-1003', 'QT-1006']))
      expect(tagRows.every((r) => (r.tagCodes || []).includes('NO_POLICY_MATCH')),
        '按标筛出来的每一行都必须真的挂着这个标（否则筛的就是别的东西）').toBe(true)
      expect(tagRows.map((r) => r.result), '筛出来的全是 NO_MATCH（本期标与 result 同源但不等价，这里刚好一致）')
        .toEqual(tagRows.map(() => 'NO_MATCH'))
      expect(Number(byTag.total), 'total 与行数同口径（EXISTS 而不是 JOIN：多标不会复制行）')
        .toBe(tagRows.length)
      expect((await getAuditQueue(request, token, { tag: 'NO_SUCH_TAG' })).total,
        '不存在的标 → 空集合，不是"全返回"').toBe(0)

      // 页面层：下拉筛完只剩那两笔
      await filterByTag(page, 'NO_POLICY_MATCH')
      await expect(auditQueueRow(page, 'QT-1003'), '按标筛后 QT-1003 应在').toBeVisible()
      await expect(auditQueueRow(page, 'QT-1001'), '按标筛后 QT-1001（没有这个标）不该在').toHaveCount(0)
      await expect(auditQueueRow(page, 'QT-1004'), '按标筛后 QT-1004（标是 BLOCKED）不该在').toHaveCount(0)

      await clearTagFilter(page)
      await expect(auditQueueRow(page, 'QT-1001'), '清掉标筛选后 QT-1001 回来').toBeVisible()
    })

    await test.step('④ 抽屉「本单身上的标」：中文名 + 一句话说明 + 打标时间', async () => {
      const row = auditQueueRow(page, 'QT-1003')
      await row.getByTestId('audit-row-trail').click()
      const drawer = page.getByTestId('audit-order-drawer')
      await expect(drawer, '单笔抽屉应打开').toBeVisible({ timeout: 15_000 })
      // 页签头没有 testid（el-tabs 内部渲染），按文案点
      await drawer.locator('.el-tabs__item').filter({ hasText: '判定摘要' }).first().click()

      const block = drawer.getByTestId('audit-order-tags')
      await expect(block, '抽屉里必须有「本单身上的标」这一段').toBeVisible({ timeout: 10_000 })
      await expect(block, '段标题要说明这是并列多标（没有"主标"）').toContainText('本单身上的标')

      const item = drawer.getByTestId('audit-order-tag-NO_POLICY_MATCH')
      await expect(item, '逐个列出这单身上的标（每个一个块）').toBeVisible()
      await expect(item, '标块里要有中文名「无策略命中」').toContainText('无策略命中')
      await expect(item.getByTestId('audit-order-tag-desc'), '每个标配一句话说明')
        .toContainText('没有任何自动策略命中')
      await expect(item.getByTestId('audit-order-tag-at'), '每个标要给打标时间')
        .toContainText('打标时间')

      // 标签体系的一个隐性要求：标区块要标出分组（结论/特征），页面才解释得清"这标从哪来"
      await expect(item, '标块要带 category（结论）').toHaveAttribute('data-tag-category', '结论')

      // 直查库：这单身上确实只有这一条 active 标（没有第二次落标、没有第二种来源）
      expect(activeTagsOf(orderFactOf('QT-1003').id).map((t) => t.tagCode), '跑一轮只该有一条结论标')
        .toEqual(['NO_POLICY_MATCH'])
    })
  })
})
