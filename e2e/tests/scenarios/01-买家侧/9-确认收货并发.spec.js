import { test, expect } from '../../support/test.js'
import { PORTAL, ADMIN, API } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import {
  adminLogin,
  memberLogin,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback
} from '../../support/ui.js'
import { editSku } from '../../support/catalog-ui.js'
import { confirmDanger } from '../../support/danger-ui.js'
import { getProductApi, getMemberOrdersApi, getMemberOrderApi, memberTokenOf } from '../../support/assert.js'
import { memberLoginOf } from '../../support/member-login.js'
import { memberNickOf, addressOf } from '../../support/fixtures.js'

/**
 * 买家侧 09 确认收货并发
 *
 * 一笔「已发货」订单，买家开两个标签页（两个独立 browser context，同一会员），
 * 两边同一时刻点「确认收货」。断言只有一条但必须精确：**恰好一胜一败**。
 *
 * 两条真实可达的拒绝路径（以代码为准，不猜文案）：
 *   ① 顺序再来一次（另一页读到的是已被改过的状态）→ 业务守卫先命中：
 *      HTTP 200 + code=1「只有已发货订单可以确认收货」；
 *   ② 两页真并发点下去 → 后到那笔读到 2 之后 UPDATE 影响 0 行（order 表有 @Version）：
 *      真 HTTP 409 +「订单状态已变化，请刷新后重试」。
 * 所以失败文案按「该页真实响应里的 message」断言，并校验 HTTP 状态码与 code 的对应关系
 * （409 必须配 409、守卫必须配 200+1），两条路径都不会被放松成「只要不是 0 就行」。
 *
 * 页面侧文案来源（portal-web/src/views/order/index.vue）：
 *   成功 `ElMessage.success('已确认收货，订单完成')`；失败 `ElMessage.error('确认收货失败：' + e.message)`；
 * 按钮只有 status===2 才有（`v-if="row.status === 2"`），确认后入口必须消失。
 *
 * 数据自造：注册新会员 → 加购 → 结算下单（自动模拟支付）→ 管理员审核通过 → 下发 → WMS 出库
 * → 订单真实停在「已发货(2)」，再开两页抢。跑完删订单 + 删会员 + 还原 SKU 库存（见 finally），
 * 同一份库连跑两轮（不 reset）不留残留。
 */

const PRODUCT_ID = 1

/**
 * 分片库名：确认收货的并发闸门是数据库行级版本号，只有直接查库才能证明
 * 「状态就是 3、版本只被推进了一次」。库名由 API_BASE 端口决定 —— 映射唯一维护在
 * e2e/tests/support/db.js 的 DB_OF_PORT（本 spec 原先自带一份，已收口）。
 */

/** 库里这张订单的 (status, version) —— 证明「只推进了一次」 */
const orderRowOf = (orderSn) => {
  const [status, version] = queryDb(
    `select status, version from \`order\` where order_sn = '${orderSn}'`
  ).split('\t')
  return { status: Number(status), version: Number(version) }
}

/**
 * 物理清掉本用例自造的那一笔订单。
 *
 * 为什么不能只用 `DELETE /admin/orders/{id}`：已发货订单在 `delivery_logistics` 上有
 * `fk_logi_delivery … ON DELETE NO ACTION` 的外键（order → delivery_order 是 CASCADE，
 * 但 delivery_order → delivery_logistics 拦住了级联），所以接口删不掉「走过 WMS 回传」的订单
 * （实测 HTTP 200 + code=1「数据处理失败，请稍后重试」），订单留着 → 会员也被
 * `fk_order_member` 拦住删不掉。这里按订单号精确清理这条 FK 链，只动本用例自己造的那一笔。
 */
const purgeOrderBySn = (orderSn) => {
  // 注意列名：delivery_logistics.delivery_id（指向 delivery_order.id），不是 delivery_order_id
  queryDb(`delete from delivery_logistics where delivery_id in (
             select id from delivery_order where order_id in
               (select id from \`order\` where order_sn = '${orderSn}'))`)
  queryDb(`delete from delivery_order where order_id in (select id from \`order\` where order_sn = '${orderSn}')`)
  queryDb(`delete from order_item where order_id in (select id from \`order\` where order_sn = '${orderSn}')`)
  queryDb(`delete from \`order\` where order_sn = '${orderSn}'`)
  queryDb(`delete from payment_record where order_sn = '${orderSn}'`)
}

/** 买家视角的两条拒绝文案（后端硬编码的两句，见 PortalOrderController#confirm / OptimisticLockGuard） */
const MSG_NOT_SHIPPED = '只有已发货订单可以确认收货'                // code=1（状态守卫）
const MSG_STATE_CHANGED = '订单状态已变化，请刷新后重试'             // 409（真并发撞版本号）

/** 订单状态文案（portal-web/src/views/order/index.vue 的 statusText） */
const SHIPPED = '已发货'
const DONE = '已完成'

/** 打开「我的订单」并按订单号定位行（行由 el-table 渲染，行内按钮一律走 testid） */
const openOrderRow = async (page, orderSn) => {
  await page.goto(`${PORTAL}/orders`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `我的订单应能看到 ${orderSn}`).toBeVisible()
  return row
}

const confirmRespOf = (page, orderId) =>
  page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes(`/portal/orders/${orderId}/confirm`),
    { timeout: 30_000 }
  )

/**
 * 把「待审核(7)」的订单推进到「已发货(2)」：
 * 审核通过 → 订单详情抽屉 → 发货单下发 → 模拟 WMS 出库（全程真实后台点击）。
 */
async function shipOrderByUi(admin, orderSn) {
  const row = await adminSearchOrderRow(admin, orderSn)
  await expect(row.getByTestId('order-row-status'), '自造订单支付后应停在待审核').toHaveText('待审核')
  await row.getByTestId('order-row-audit-pass').click()
  await confirmDanger(admin, { willDo: '待审核→待发货' })
  await expect(row.getByTestId('order-row-status'), '审核通过后应为待发货').toHaveText('待发货')

  const table = await openOrderDrawer(admin, orderSn)
  const dRow = table.locator('.el-table__body tr').first()
  await clickDispatch(admin, dRow)
  await clickWmsCallback(admin, dRow, '出库', '已出库')
  await expect(row.getByTestId('order-row-status'), '出库后订单应为已发货').toHaveText(SHIPPED)
}

/** 管理员令牌（只用于跑完删会员账号，不参与任何断言） */
const adminTokenForCleanup = async (request) => {
  const res = await request.post(`${API}/admin/auth/login`, {
    data: { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' }
  })
  return (await res.json()).data.accessToken
}

test.describe('买家侧 09 确认收货并发', () => {
  test('同一笔已发货订单两页同时确认收货：恰一胜一败，胜者把订单推到已完成，败者页面出现失败提示', async ({ page, request, browser }) => {
    test.setTimeout(300_000)

    const username = memberLoginOf(Date.now() + 7)
    const buyer = addressOf(username)
    let created = null
    let orderSku = null          // { id, specText, stockBefore } —— 跑完要把库存还原
    let memberId = null
    let ctx2, page2, ctxAdmin, pageAdmin

    try {
      await test.step('① 自造一笔订单：注册 → 加购 → 结算下单（自动模拟支付）', async () => {
        await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
        // 分片库正被重置的瞬间，商品 GET 会瞬时返回 code=1、data=null（不是被测业务缺陷），重试两次
        let skus
        for (let i = 0; !skus?.length; i++) {
          if (i >= 3) throw new Error('商品接口连续 3 次没返回 SKU（不是本用例的断言对象，先查环境）')
          if (i > 0) await page.waitForTimeout(1000)
          skus = (await getProductApi(request, PRODUCT_ID))?.skus
        }
        const sku = skus[0]
        orderSku = { id: sku.id, specText: sku.specText, stockBefore: Number(sku.stock) }
        await addSpecToCart(page, PRODUCT_ID, sku.specText, sku.id)
        await submitCartOrder(page, {
          receiverName: buyer.name,
          receiverPhone: buyer.phone,
          receiverAddress: `${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`
        })

        const token = await memberTokenOf(page)
        const pageData = await getMemberOrdersApi(request, token)
        expect(pageData.records.length, '新会员应只有 1 笔订单').toBe(1)
        const order = pageData.records[0]
        expect(order.status, '下单后前端自动模拟支付，订单应停在待审核(7)').toBe(7)
        created = { orderSn: order.orderSn, orderId: order.id, token }
        memberId = order.memberId
      })

      await test.step('② 后台把订单推进到「已发货」：审核通过 → 下发 → WMS 出库', async () => {
        ctxAdmin = await browser.newContext()
        pageAdmin = await ctxAdmin.newPage()
        await adminLogin(pageAdmin, 'admin', process.env.ADMIN_PASSWORD || '123456')
        await shipOrderByUi(pageAdmin, created.orderSn)

        const shipped = await getMemberOrderApi(request, created.token, created.orderId)
        expect(shipped.order.status, '服务端：订单应已发货(2)').toBe(2)
        expect(orderRowOf(created.orderSn).status, '库里订单状态应为已发货(2)').toBe(2)
      })

      let row1, row2
      await test.step('③ 买家开两个标签页（两个 context、同一会员），都停在「确认收货」入口', async () => {
        row1 = await openOrderRow(page, created.orderSn)
        await expect(row1.getByTestId('orders-row-status'), '甲看到的应是已发货').toHaveText(SHIPPED)
        await expect(row1.getByTestId('orders-row-confirm'), '已发货订单应有「确认收货」入口').toBeVisible()

        ctx2 = await browser.newContext()
        page2 = await ctx2.newPage()
        await memberLogin(page2, username, '123456')
        await expect(page2, '乙应登录成功').not.toHaveURL(/\/login/, { timeout: 20_000 })
        row2 = await openOrderRow(page2, created.orderSn)
        await expect(row2.getByTestId('orders-row-status'), '乙看到的也是已发货').toHaveText(SHIPPED)
        await expect(row2.getByTestId('orders-row-confirm'), '乙同样能点到确认收货入口').toBeVisible()
      })

      let respA, respB
      await test.step('④ 两页同一时刻点「确认收货」（真并发）', async () => {
        // 确认收货现在带二次确认（不可逆终态流转）：点行内按钮只弹框，真正发 POST 的是弹框里的「确定」。
        // 所以并发点法改成「两页先把框弹出来 → 再同一时刻点确定」，闸门仍是同一瞬间发出的两个请求。
        await Promise.all([
          row1.getByTestId('orders-row-confirm').click(),
          row2.getByTestId('orders-row-confirm').click()
        ])
        const boxA = page.locator('.el-message-box')
        const boxB = page2.locator('.el-message-box')
        await expect(boxA, '甲点「确认收货」后应弹二次确认框').toBeVisible()
        await expect(boxB, '乙点「确认收货」后应弹二次确认框').toBeVisible()

        const pA = confirmRespOf(page, created.orderId)
        const pB = confirmRespOf(page2, created.orderId)
        await Promise.all([
          boxA.getByRole('button', { name: '确定' }).click(),
          boxB.getByRole('button', { name: '确定' }).click()
        ])
        respA = await pA
        respB = await pB
      })

      await test.step('⑤ 恰有一胜一败；败者不是静默无反应，页面原样显示它自己收到的拒绝文案', async () => {
        const results = [
          { who: '甲', page, resp: respA, body: await respA.json() },
          { who: '乙', page: page2, resp: respB, body: await respB.json() }
        ]
        const winners = results.filter((r) => r.body.code === 0)
        const losers = results.filter((r) => r.body.code !== 0)
        expect(winners.length, '并发确认收货必须恰有一笔成功（不能两笔都成功）').toBe(1)
        expect(losers.length, '另一笔必须被明确拒绝').toBe(1)

        const winner = winners[0]
        const loser = losers[0]
        expect(winner.resp.status(), '胜者的确认收货应是 HTTP 200').toBe(200)
        expect(winner.body.data.order.status, '胜者的响应里订单状态应已是已完成(3)').toBe(3)

        // 失败文案只可能是后端这两句原话之一，且 HTTP 状态码必须与 code 语义一致
        expect([MSG_STATE_CHANGED, MSG_NOT_SHIPPED], `${loser.who} 的拒绝文案必须是后端原话`)
          .toContain(loser.body.message)
        if (loser.body.message === MSG_STATE_CHANGED) {
          expect(loser.resp.status(), '409 必须是真 HTTP 409').toBe(409)
          expect(loser.body.code, 'body 里的业务码也应是 409').toBe(409)
        } else {
          expect(loser.resp.status(), '状态守卫是 HTTP 200 + code=1').toBe(200)
          expect(loser.body.code, '状态守卫的业务码应为 1').toBe(1)
        }

        // 胜者页面：成功提示 + 行状态变成已完成
        await expect(winner.page.locator('.el-message--success').first(), `${winner.who} 应看到「已确认收货，订单完成」`)
          .toHaveText('已确认收货，订单完成', { timeout: 15_000 })
        await expect(
          winner.page.locator('.el-table__body tr').filter({ hasText: created.orderSn }).first()
            .getByTestId('orders-row-status'),
          `${winner.who} 那一行应刷新成已完成`
        ).toHaveText(DONE)

        // 败者页面：必须出现失败提示（不是静默无反应），文案是「确认收货失败：」+ 该页真实响应的 message
        const errToast = loser.page.locator('.el-message--error').first()
        await expect(errToast, `${loser.who} 应看到确认收货失败提示`).toBeVisible({ timeout: 15_000 })
        await expect(errToast, `${loser.who} 的提示必须是「确认收货失败：」+ 该页真实响应的 message`)
          .toHaveText(`确认收货失败：${loser.body.message}`)
        await expect(loser.page.locator('.el-message--success'), `${loser.who} 不应看到任何成功提示`).toHaveCount(0)

        // 服务端事实：状态已完成(3)
        const after = await getMemberOrderApi(request, created.token, created.orderId)
        expect(after.order.status, '订单应为已完成(3)').toBe(3)
        const dbRow = orderRowOf(created.orderSn)
        expect(dbRow.status, '库里订单状态应为已完成(3)').toBe(3)

        const errText = (await errToast.innerText()).trim()
        const okText = (await winner.page.locator('.el-message--success').first().innerText()).trim()
        console.log(`[09 并发结果] 胜者=${winner.who} http=${winner.resp.status()} code=0 body=${JSON.stringify(winner.body)}`)
        console.log(`[09 并发结果] 败者=${loser.who} http=${loser.resp.status()} code=${loser.body.code} message=${loser.body.message}`)
        console.log(`[09 页面文案] 胜者 toast="${okText}" / 败者 toast="${errText}"`)
        console.log(`[09 库中事实] order(${created.orderSn}).status=${dbRow.status} version=${dbRow.version}`)
      })

      await test.step('⑥ 两页刷新后都是「已完成」，确认收货入口消失（不会假成功、不会回退）', async () => {
        for (const [who, p] of [['甲', page], ['乙', page2]]) {
          await p.reload()
          await p.waitForTimeout(1500)
          const row = p.locator('.el-table__body tr').filter({ hasText: created.orderSn }).first()
          await expect(row.getByTestId('orders-row-status'), `${who} 刷新后应看到已完成`).toHaveText(DONE)
          await expect(row.getByTestId('orders-row-confirm'), `${who} 不应再有确认收货入口`).toHaveCount(0)
        }
      })
    } finally {
      // ---- 收尾：给商品补回本用例消耗的那 1 件库存 → 清订单 → 删会员 ----
      try {
        if (orderSku && pageAdmin && !pageAdmin.isClosed()) {
          await pageAdmin.goto(`${ADMIN}/product/${PRODUCT_ID}/skus`)
          await pageAdmin.waitForTimeout(1200)
          await editSku(pageAdmin, orderSku.specText, { stock: orderSku.stockBefore })
          const restored = Number(
            (await getProductApi(request, PRODUCT_ID)).skus.find((s) => s.id === orderSku.id)?.stock
          )
          console.log(`[09 清理] SKU id=${orderSku.id} 库存已还原为 ${restored}（原值 ${orderSku.stockBefore}）`)
        }
      } catch (e) {
        test.info().annotations.push({ type: '库存还原失败', description: String(e.message || e) })
      }
      try {
        if (created?.orderSn) {
          purgeOrderBySn(created.orderSn)
          console.log(`[09 清理] 订单 ${created.orderSn} 已物理删除（含明细/发货单/物流/流水）`)
        }
        if (memberId) {
          const token = await adminTokenForCleanup(request)
          const del = await request.delete(`${API}/admin/members/${memberId}`, {
            headers: { Authorization: `Bearer ${token}` }
          })
          const delBody = await del.json().catch(() => ({}))
          console.log(`[09 清理] 会员 id=${memberId} 删除 http=${del.status()} code=${delBody.code}`)
        }
      } catch (e) {
        test.info().annotations.push({ type: '清理失败', description: String(e.message || e) })
      }
      if (ctx2) await ctx2.close().catch(() => {})
      if (ctxAdmin) await ctxAdmin.close().catch(() => {})
    }
  })
})
