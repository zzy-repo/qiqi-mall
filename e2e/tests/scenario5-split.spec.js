import { test, expect } from '@playwright/test'
import {
  ADMIN,
  PORTAL,
  API,
  adminLogin,
  adminToken,
  memberLogin,
  registerMemberApi,
  createOrderApi,
  payOrderApi,
  auditPassApi,
  prepareSku,
  stamp
} from './helpers.js'

/**
 * 场景5 拆单履约（docs/flows/22-拆单时序图 + 23-发货单状态图 + FIX-oms-split Commit 4）
 *   管理员造跨仓商品（2 个 SKU 分属 2 个仓）→ 买家下一单买 2 SKU → 管理员审核通过
 *   → 断言：自动拆成 2 个发货单（各自正确仓库）
 *   → 逐个下发 + 模拟出库 → 断言：部分出库时订单=部分发货，全部出库后才=已发货
 *   → 全部签收 → 订单已完成
 */
test.describe('场景5 拆单履约', () => {
  test('跨仓订单自动拆成 2 个发货单，部分出库不驱动订单已发货，全部签收后完成', async ({ page, request }) => {
    const username = `e2e_split_${stamp()}`
    const member = await registerMemberApi(request, username)
    // SKU1 → 深圳仓(1)，SKU2 → 杭州仓(2)：种子数据保证一单跨两仓
    await prepareSku(request, 1)

    const order = await createOrderApi(request, member.token, {
      skuId: 1,
      quantity: 1,
      receiverName: '拆单E2E'
    })
    // 第二件商品：SKU2（杭州仓）；用同一幂等令牌新建一单会重复下单，所以直接走「同单两明细」的下单接口
    // —— createOrderApi 只支持单 SKU，这里改用接口直接下单（带两个明细）
    const tokenRes = await request.get(`${API}/portal/orders/idempotent-token`, {
      headers: { Authorization: `Bearer ${member.token}` }
    })
    const idempotentToken = (await tokenRes.json()).data.tokenId
    const multiRes = await request.post(`${API}/portal/orders`, {
      headers: { Authorization: `Bearer ${member.token}` },
      data: {
        receiverName: '拆单E2E',
        receiverPhone: '13600008888',
        receiverAddress: '广东省深圳市南山区拆单路 5 号',
        items: [
          { productSkuId: 1, quantity: 1 },
          { productSkuId: 2, quantity: 1 }
        ],
        idempotentToken
      }
    })
    const multiBody = await multiRes.json()
    expect(multiBody.code, `跨仓下单应成功：${multiBody.message}`).toBe(0)
    const crossOrder = multiBody.data.order
    await payOrderApi(request, member.token, crossOrder.id)

    // ---------- 管理员审核通过 → 自动拆单 ----------
    const at = await adminToken(request)
    const deliveries = await auditPassApi(request, at, crossOrder.id)
    expect(deliveries.length, '跨两仓订单应拆成 2 个发货单').toBe(2)

    const warehouses = deliveries.map((d) => d.warehouseId).sort()
    expect(warehouses, '两个发货单应分别落在深圳仓(1)与杭州仓(2)').toEqual([1, 2])

    // ---------- 界面确认：订单详情里能看到 2 个发货单 ----------
    await adminLogin(page)
    await page.goto(`${ADMIN}/order`)
    await page.waitForTimeout(1200)
    await page.locator('input[placeholder="按订单号搜索"]').fill(crossOrder.orderSn)
    await page.locator('input[placeholder="按订单号搜索"]').press('Enter')
    await page.waitForTimeout(1200)
    const row = page.locator('.el-table__body tr').filter({ hasText: crossOrder.orderSn }).first()
    await expect(row, '订单应为待发货').toContainText('待发货')
    await row.getByRole('button', { name: '明细' }).click()
    await page.waitForTimeout(1500)
    await expect(
      page.locator('.el-drawer .el-table__body tr'),
      '订单详情应展示 2 个子发货单'
    ).toHaveCount(2)
    await page.screenshot({ path: 'report/shots/scenario5-deliveries.png' })

    // ---------- 第一个子单下发 + 出库 → 订单进入「部分发货」 ----------
    await request.post(`${API}/admin/deliveries/${deliveries[0].id}/dispatch`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    await request.post(`${API}/admin/deliveries/${deliveries[0].id}/wms-callback?event=outbound`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    const afterFirst = await request.get(`${API}/admin/orders?page=1&size=10&orderSn=${crossOrder.orderSn}`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    expect((await afterFirst.json()).data.records[0].status, '部分出库时订单应为部分发货(9)').toBe(9)

    // ---------- 第二个子单下发 + 出库 → 订单才变「已发货」 ----------
    await request.post(`${API}/admin/deliveries/${deliveries[1].id}/dispatch`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    await request.post(`${API}/admin/deliveries/${deliveries[1].id}/wms-callback?event=outbound`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    const afterSecond = await request.get(`${API}/admin/orders?page=1&size=10&orderSn=${crossOrder.orderSn}`, {
      headers: { Authorization: `Bearer ${at}` }
    })
    expect((await afterSecond.json()).data.records[0].status, '全部出库后订单才变已发货(2)').toBe(2)

    // ---------- 买家看到 2 个包裹 + 运单号 ----------
    await memberLogin(page, username)
    await page.waitForTimeout(2500)
    await page.goto(`${PORTAL}/orders`)
    await page.waitForTimeout(1200)
    const buyerRow = page.locator('.el-table__body tr').filter({ hasText: crossOrder.orderSn }).first()
    await expect(buyerRow, '买家侧订单应为已发货').toContainText('已发货')
    await buyerRow.getByRole('button', { name: '包裹' }).click()
    await page.waitForTimeout(1500)
    const parcelRows = page.locator('.el-drawer .el-table__body tr')
    await expect(parcelRows, '买家应看到 2 个包裹').toHaveCount(2)
    await expect(page.locator('.el-drawer')).toContainText('深圳仓')
    await expect(page.locator('.el-drawer')).toContainText('杭州仓')
    await page.screenshot({ path: 'report/shots/scenario5-parcels.png' })

    // ---------- 全部签收 → 订单已完成 ----------
    for (const d of deliveries) {
      await request.post(`${API}/admin/deliveries/${d.id}/wms-callback?event=transit`, {
        headers: { Authorization: `Bearer ${at}` }
      })
      await request.post(`${API}/admin/deliveries/${d.id}/wms-callback?event=signed`, {
        headers: { Authorization: `Bearer ${at}` }
      })
    }
    const finalRes = await request.get(`${API}/portal/orders/${crossOrder.id}`, {
      headers: { Authorization: `Bearer ${member.token}` }
    })
    expect((await finalRes.json()).data.order.status, '全部签收后订单应已完成(3)').toBe(3)
  })
})
