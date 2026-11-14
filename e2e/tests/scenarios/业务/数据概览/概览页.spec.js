import { test, expect } from '../../../support/test.js'
import { ADMIN } from '../../../support/env.js'
import { adminLogin } from '../../../support/ui.js'
import { tid } from '../../../support/tid.js'
import { adminTokenOf } from '../../../support/assert.js'

/**
 * 业务 / 数据概览 / 概览页
 *
 * 覆盖：页面能打开、6 个指标卡都有数字、三张图（7 天趋势 / 状态分布 / Top5）都渲染、
 *       库存告警列表按接口数据展示、页面上没有 JS 报错。
 * 断言依据：GET /admin/dashboard/overview + views/dashboard/index.vue（ECharts 按需引入）
 */

const METRIC_KEYS = [
  'today-amount', 'today-orders', 'products',
  'pending-audit', 'pending-ship', 'pending-return'
]

test.describe('业务 › 数据概览 › 概览页', () => {
  test('概览页：指标卡 / 三张图 / 库存告警都正常渲染，无 JS 报错', async ({ page, request }) => {
    const pageErrors = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    await adminLogin(page)
    const token = await adminTokenOf(page)
    await page.goto(`${ADMIN}/dashboard`)
    await page.waitForTimeout(2500)

    // ---- 指标卡：6 张，且每张都有非空数字 ----
    for (const key of METRIC_KEYS) {
      const card = tid(page, `dashboard-metric-${key}`)
      await expect(card, `指标卡 ${key} 应存在`).toBeVisible()
      const text = (await card.innerText()).replace(/\s+/g, '')
      expect(text.length, `指标卡 ${key} 不应为空`).toBeGreaterThan(0)
      // 卡片文案里必须含一个数字（金额/单数/个数）
      expect(text, `指标卡 ${key} 应展示数字`).toMatch(/\d/)
    }

    // ---- 三张图：接口有数据时必须渲染；没数据时显示占位 ----
    const overview = (await (await request.get(`${ADMIN}/admin/dashboard/overview`, {
      headers: { Authorization: `Bearer ${token}` }
    })).json()).data

    const hasTrend = overview.trend.some((t) => Number(t.orderCount) > 0 || Number(t.amount) > 0)
    const hasStatus = overview.statusDistribution.some((s) => Number(s.count) > 0)
    const alertCount = overview.stockAlerts.length

    if (hasTrend) {
      await expect(tid(page, 'dashboard-chart-trend'), '有成交数据时应渲染趋势图').toBeVisible()
      await expect(tid(page, 'dashboard-chart-trend').locator('canvas')).toBeVisible()
    } else {
      await expect(page.locator('.el-empty').first(), '无成交数据应显示占位').toBeVisible()
    }
    if (hasStatus) {
      await expect(tid(page, 'dashboard-chart-status'), '有订单时应渲染状态分布图').toBeVisible()
      await expect(tid(page, 'dashboard-chart-status').locator('canvas')).toBeVisible()
    }
    if (overview.topProducts.length > 0) {
      await expect(tid(page, 'dashboard-chart-top'), '有销量时应渲染 Top5 条形图').toBeVisible()
    }

    // ---- 库存告警：行数与接口返回一致（最多 5 条）----
    await expect(tid(page, 'dashboard-alert-row'), '库存告警行数应与接口一致').toHaveCount(alertCount)
    if (alertCount > 0) {
      await expect(tid(page, 'dashboard-alert-row').first()).toContainText(overview.stockAlerts[0].productName)
      await expect(tid(page, 'dashboard-alert-row').first()).toContainText(String(overview.stockAlerts[0].stock))
    }

    // ---- 没有前端异常 ----
    expect(pageErrors, `页面不应有 JS 报错：${pageErrors.join(' | ')}`).toEqual([])
    await page.screenshot({ path: 'report/shots/dashboard-overview-e2e.png', fullPage: true })
  })

  test('侧边菜单能进概览页（导航入口存在）', async ({ page }) => {
    await adminLogin(page)
    await page.goto(`${ADMIN}/dashboard`)
    // dashboard-page 是概览页的静态容器（路由挂载即出现），不依赖概览接口的返回值；
    // toBeVisible 自带轮询，固定 sleep 只是白等 1.2 秒。
    await expect(tid(page, 'dashboard-page'), '概览页容器应存在').toBeVisible()
    // 菜单里「数据概览」可点，点完仍在本页
    const navItem = page.locator('.el-menu-item').filter({ hasText: '数据概览' }).first()
    await expect(navItem, '菜单应有「数据概览」').toBeVisible()
    await navItem.click()
    await expect(page).toHaveURL(/\/dashboard/)
  })
})
