import { test, expect } from '@playwright/test'
import { PORTAL, API, memberRegister, prepareSku, stamp } from './helpers.js'

/**
 * 场景2 购买下单（docs/flows/4-场景2-购买下单.mmd）
 *   查看详情 → 加购（匿名存本地）→ 未登录被拦截 → 注册即登录 → 合并购物车
 *   → 结算 → 提交订单（快照 + 扣库存）→ 模拟支付 → 订单已付款
 */
test.describe('场景2 购买下单', () => {
  test('游客加购→登录拦截→注册合并→结算下单→模拟支付', async ({ page, playwright }) => {
    const username = `e2e_buy_${stamp()}`
    const API_BASE = API

    // 记录下单前的库存，用于断言扣库存（先把库存补足，保证可重复运行）
    const api = await playwright.request.newContext({ baseURL: API_BASE })
    const prepared = await prepareSku(api)
    const skuId = prepared.skuId
    const skuPrice = prepared.price
    const beforeStock = prepared.stock

    // ---------- 1. 游客查看商品详情并加入购物车（存本地） ----------
    await page.goto(`${PORTAL}/product/1`)
    await page.waitForTimeout(1200)
    await expect(page.locator('h2')).toContainText('七七 Pro 15')
    await page.getByRole('button', { name: '加入购物车' }).click()
    await page.waitForURL(/\/cart/)
    await page.waitForTimeout(800)

    const localCart = await page.evaluate(() => localStorage.getItem('qiqi_local_cart'))
    expect(localCart, '未登录加购应写入浏览器本地购物车').toBeTruthy()
    expect(JSON.parse(localCart).length).toBe(1)

    // ---------- 2. 点结算被拦截到登录页，带 redirect ----------
    await page.getByRole('button', { name: '去结算' }).click()
    await page.waitForTimeout(1000)
    await expect(page).toHaveURL(/\/login\?redirect=%2Fcheckout|\/login\?redirect=\/checkout/)

    // ---------- 3. 没有账号 → 切注册 tab → 注册即登录 ----------
    await memberRegister(page, username)
    await page.waitForTimeout(3000)
    await expect(page, '注册成功后应回跳结算页').toHaveURL(/\/checkout/)

    // 本地购物车已合并到云端并清空
    const localAfter = await page.evaluate(() => localStorage.getItem('qiqi_local_cart'))
    expect(localAfter, '合并后本地购物车应清空').toBeNull()

    // ---------- 4. 结算页填写收货信息并提交订单 ----------
    await page.locator('.el-form-item').filter({ hasText: '收货人' }).locator('input').fill('E2E买家')
    await page.locator('.el-form-item').filter({ hasText: '电话' }).locator('input').fill('13600002222')
    await page.locator('.el-form-item').filter({ hasText: '收货地址' }).locator('textarea').fill('广东省深圳市南山区E2E路 1 号')
    await page.getByRole('button', { name: '提交订单' }).click()
    await page.waitForTimeout(3000)
    await expect(page, '提交订单后应进入我的订单并自动支付').toHaveURL(/\/orders/)

    const firstRow = page.locator('.el-table__body tr').first()
    await expect(firstRow).toContainText('已付款')

    // ---------- 5. 断言服务端：快照 + 金额 + 扣库存 ----------
    const detail = await api.get(`/portal/products/1`)
    const afterStock = (await detail.json()).data.skus.find((s) => s.id === skuId).stock
    expect(afterStock, '下单应扣减库存').toBe(beforeStock - 1)

    // 订单明细快照：下单时商品名与成交价
    const token = await page.evaluate(() => localStorage.getItem('qiqi_member_token'))
    const ordersRes = await api.get(`/portal/orders?page=1&size=10`, { headers: { Authorization: `Bearer ${token}` } })
    const pageData = (await ordersRes.json()).data
    expect(pageData.total, '分页响应应包含 total').toBeGreaterThan(0)
    expect(Array.isArray(pageData.records), '分页响应应包含 records 数组').toBeTruthy()
    const created = pageData.records[0]
    expect(created.status, '模拟支付后状态应为已付款(1)').toBe(1)
    expect(created.receiverName).toBe('E2E买家')
    expect(Number(created.totalAmount)).toBe(Number(skuPrice))

    const orderDetail = await api.get(`/portal/orders/${created.id}`, { headers: { Authorization: `Bearer ${token}` } })
    const items = (await orderDetail.json()).data.items
    expect(items.length).toBe(1)
    expect(items[0].productName, '订单明细应保存商品名快照').toContain('七七 Pro 15')
    expect(Number(items[0].productPrice), '订单明细应保存成交价快照').toBe(Number(skuPrice))

    await page.screenshot({ path: 'report/shots/scenario2-orders.png', fullPage: true })
    await api.dispose()
  })
})
