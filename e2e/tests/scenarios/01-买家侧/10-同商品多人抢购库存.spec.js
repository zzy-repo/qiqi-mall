import { test, expect } from '../../support/test.js'
import { PORTAL, ADMIN, API } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { adminLogin, memberRegisterAndLogin, addSpecToCart, adminSetSkuStock } from '../../support/ui.js'
import { editSku } from '../../support/catalog-ui.js'
import {
  getProductApi,
  getMemberCartApi,
  getMemberOrdersApi,
  getMemberOrderApi,
  getMemberProfileApi,
  memberTokenOf
} from '../../support/assert.js'
import { memberLoginOf } from '../../support/member-login.js'
import { memberNickOf, addressOf } from '../../support/fixtures.js'
import { tid } from '../../support/tid.js'

/**
 * 买家侧 10 同商品多人抢购库存
 *
 * 一笔 SKU 库存 = 2，两个会员（两个独立 browser context，各登各的账号）几乎同时各下 2 件：
 * 这是 `UPDATE sku SET stock = stock - ? WHERE id = ? AND stock >= ?` 的原子性问题（不是版本号冲突），
 * 断言点只有一个：**不许超卖**。
 *   ① 恰好一个人成功（库存 2 只够一单 2 件）；
 *   ② 另一个人必须收到「库存不足」类明确报错（不是静默失败、不是假成功、不是假跳转）；
 *   ③ 不变量：下单成功后该 SKU 库存恰好落在 0，绝不为负；订单只多 1 笔（明细只多 1 条、件数 2）。
 *
 * 后端事实（以代码为准）：
 *   `OrderTransactionalCreator` 把「判断 + 减法」压进一条 SQL（SkuMapper.deductStock：`AND stock >= qty`），
 *   扣减失败抛 `IllegalStateException("库存不足：" + 商品名)` → GlobalExceptionHandler 兜底
 *   → HTTP 200 + code=1（业务失败，不是 HTTP 错误）。
 * 页面事实（portal-web/src/views/checkout/index.vue）：
 *   失败 `ElMessage.error('下单失败：' + e.message)`；成功 `ElMessage.success('下单成功，去支付')` 并跳 /orders。
 *
 * 「只多 1 笔」的口径：种子/历史数据里这个 SKU 本来就可能有订单明细，所以不按「全表恰好 1 条」断言，
 * 而是**跑前先取基线**，跑后断言「明细 +1 条、件数 +2、涉及订单 +1 张」，再叠加
 * 「胜者订单详情里恰好 1 条明细、件数 2」「败者名下 0 笔订单」两条精确断言。
 *
 * 数据自造：自己把商品 1 的一个 SKU 库存改成 2（后台 SKU 编辑弹窗），两个会员各自注册、加购 2 件、
 * 同时提交订单；跑完把库存改回原值、清掉订单、清掉残留购物车、删掉两个会员（见 finally）
 * —— 同一份库连跑两轮（不 reset）不留残留。
 */

const PRODUCT_ID = 1
/** 抢购目标库存：只够一个人买 */
const GRAB_STOCK = 2
/** 每人买多少件 */
const QTY_PER_BUYER = 2

// 库名由 API_BASE 端口决定；映射唯一维护在 e2e/tests/support/db.js 的 DB_OF_PORT（本 spec 原先自带一份，已收口）。

/**
 * 扣减模式：legacy（默认，sku.stock 就是库存）还是 bucket（库存权威在 sku_bucket，sku.stock 是缓存）。
 * 由跑 e2e 的环境变量指定，**不猜**：两种模式下的断言强度不同，混着断言只会两头都不准。
 *   E2E_STOCK_MODE=bucket npx playwright test ...   # 分桶镜像（镜像3）
 *   （不设）= legacy                                 # 镜像1/2
 */
const STOCK_MODE = process.env.E2E_STOCK_MODE || 'legacy'

/** 展示缓存：sku.stock（legacy 下它就是库存本身；bucket 下它滞后 ≤250ms） */
const stockOf = (skuId) => Number(queryDb(`select stock from sku where id = ${skuId}`))

/** 权威库存：分桶模式下的 SUM(sku_bucket.stock)（真正的库存事实，不滞后） */
const bucketSumOf = (skuId) => Number(queryDb(
  `select coalesce(sum(stock), 0) from sku_bucket where sku_id = ${skuId}`
))

/** 全库最小桶库存（分桶模式下证明没有任何桶被扣成负数） */
const minBucketOfAll = () => Number(queryDb('select coalesce(min(stock),0) from sku_bucket'))

/** 该 SKU 在订单明细上的统计（用来算「只多 1 笔」的增量）：{lines, qty, orders} */
const orderItemStatsOf = (skuId) => {
  const [lines, qty, orders] = queryDb(
    `select count(*), coalesce(sum(product_quantity), 0), count(distinct order_id)`
    + ` from order_item where product_sku_id = ${skuId}`
  ).split('\t')
  return { lines: Number(lines), qty: Number(qty), orders: Number(orders) }
}

/** 每个会员名下的订单条数 */
const orderCountOfMember = (memberId) => Number(queryDb(`select count(*) from \`order\` where member_id = ${memberId}`))

/** 全库最小库存（证明没有任何 SKU 被扣成负数） */
const minStockOfAll = () => Number(queryDb('select min(stock) from sku'))

/** 按订单号精确清掉本用例自造的那笔订单（order → delivery_order → logistics 这条 FK 链先断掉） */
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

/** 管理员令牌（清理用，不参与业务断言） */
const adminTokenForCleanup = async (request) => {
  const res = await request.post(`${API}/admin/auth/login`, {
    data: { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' }
  })
  return (await res.json()).data.accessToken
}

/** 把购物车里这个 SKU 的数量改成 2（el-input-number：填完失焦才触发 change → 后端落库） */
async function setCartQty(page, skuId, qty) {
  const input = tid(page, `cart-row-${skuId}`).getByTestId('cart-row-qty').locator('input')
  await expect(input, `购物车应有 SKU ${skuId} 这一行`).toBeVisible({ timeout: 15_000 })
  await input.click()
  await input.fill(String(qty))
  await input.press('Tab')
  await page.waitForTimeout(1200)   // change → updateCartQuantity → 重新 load()
  await expect(input, '购物车数量应已改成 2').toHaveValue(String(qty))
}

/** 等到 /checkout 页面就绪（表单已填、提交按钮可点），返回页面上的「应付金额」文本 */
async function gotoCheckoutReady(page, who, buyer) {
  await page.goto(`${PORTAL}/cart`)
  await page.waitForTimeout(1000)
  await tid(page, 'cart-checkout').click()
  await page.waitForURL(/\/checkout/, { timeout: 20_000 })
  await page.waitForTimeout(1200)
  await tid(page, 'checkout-input-receiver').fill(buyer.name)
  await tid(page, 'checkout-input-phone').fill(buyer.phone)
  await tid(page, 'checkout-input-address').fill(`${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`)
  await expect(tid(page, 'checkout-submit'), `${who} 的提交按钮应可点`).toBeEnabled()
  return (await tid(page, 'checkout-total').innerText()).trim()
}

const createOrderRespOf = (page) =>
  page.waitForResponse(
    (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/portal/orders',
    { timeout: 60_000 }
  )

test.describe('买家侧 10 同商品多人抢购库存', () => {
  test('库存只有 2 件、两个会员同时各买 2 件：恰一人成功，另一人收到「库存不足」，库存落到 0 且不为负、订单只多 1 笔', async ({ page, request, browser }) => {
    test.setTimeout(360_000)

    const userA = memberLoginOf(Date.now() + 31)
    const userB = memberLoginOf(Date.now() + 32)
    const buyerA = addressOf(userA)
    const buyerB = addressOf(userB)

    let ctxAdmin, pageAdmin, ctxB, pageB
    let target = null            // { id, specText, price, originalStock }
    let baseline = null          // 抢购前该 SKU 的订单明细基线
    let productName = ''
    let winner = null            // { who, orderId, orderSn, memberId, page }
    let memberIdA = null
    let memberIdB = null
    let loserWho = null

    try {
      await test.step('① 后台把商品 1 的一个 SKU 库存改成 2（记下原值，跑完还原）', async () => {
        ctxAdmin = await browser.newContext()
        pageAdmin = await ctxAdmin.newPage()
        await adminLogin(pageAdmin, 'admin', process.env.ADMIN_PASSWORD || '123456')

        // 改之前先把每个 SKU 的原库存记下来（按 id 记录，不依赖表格行序）
        const before = await getProductApi(request, PRODUCT_ID)
        expect(before?.skus?.length, '商品 1 应有 SKU').toBeGreaterThan(0)
        productName = before.product.name
        const stockBefore = new Map(before.skus.map((s) => [s.id, Number(s.stock)]))

        await adminSetSkuStock(pageAdmin, PRODUCT_ID, 0, GRAB_STOCK)

        // 改完再读一次：**按数据**确认到底是哪个 SKU 变成了 2（不假设表格第 1 行就是我们要的那个）
        const after = await getProductApi(request, PRODUCT_ID)
        const changed = after.skus.filter((s) => Number(s.stock) === GRAB_STOCK)
        expect(changed.length, `恰好应有 1 个 SKU 的库存被改成 ${GRAB_STOCK}`).toBe(1)
        target = {
          id: changed[0].id,
          specText: changed[0].specText,
          price: Number(changed[0].price),
          originalStock: stockBefore.get(changed[0].id)
        }
        expect(Number.isFinite(target.originalStock), '应记得该 SKU 的原库存').toBeTruthy()
        baseline = orderItemStatsOf(target.id)
        console.log(
          `[10 抢购对象] 商品「${productName}」(id=${PRODUCT_ID}) / SKU id=${target.id} 规格「${target.specText}」`
          + ` 库存 ${target.originalStock} → ${GRAB_STOCK}；历史订单明细基线 lines=${baseline.lines} qty=${baseline.qty} orders=${baseline.orders}`
        )
      })

      await test.step('② 两个会员各自注册（两个 context，两个账号），各加购 2 件', async () => {
        await memberRegisterAndLogin(page, userA, '123456', memberNickOf(userA))
        await addSpecToCart(page, PRODUCT_ID, target.specText, target.id)
        await setCartQty(page, target.id, QTY_PER_BUYER)

        ctxB = await browser.newContext()
        pageB = await ctxB.newPage()
        await memberRegisterAndLogin(pageB, userB, '123456', memberNickOf(userB))
        await addSpecToCart(pageB, PRODUCT_ID, target.specText, target.id)
        await setCartQty(pageB, target.id, QTY_PER_BUYER)

        // 服务端事实：两个购物车里都是 2 件，且各自是不同账号
        for (const [who, p] of [['甲', page], ['乙', pageB]]) {
          const token = await memberTokenOf(p)
          const cart = await getMemberCartApi(request, token)
          const line = cart.find((c) => c.productSkuId === target.id)
          expect(line, `${who} 的购物车应有这个 SKU`).toBeTruthy()
          expect(line.quantity, `${who} 的购物车数量应为 ${QTY_PER_BUYER}`).toBe(QTY_PER_BUYER)
          const profile = await getMemberProfileApi(request, token)
          if (who === '甲') memberIdA = profile.id
          else memberIdB = profile.id
        }
        expect(memberIdA, '甲的会员 id 应读到').toBeTruthy()
        expect(memberIdB, '乙的会员 id 应读到').toBeTruthy()
        expect(memberIdA === memberIdB, '两个 context 必须是两个不同账号').toBe(false)
      })

      await test.step('③ 两页都停在结算页（各自身份、各自令牌），确认应付金额', async () => {
        const totalA = await gotoCheckoutReady(page, '甲', buyerA)
        const totalB = await gotoCheckoutReady(pageB, '乙', buyerB)
        const expectTotal = `应付：¥ ${(target.price * QTY_PER_BUYER).toFixed(2)}`
        expect(totalA, '甲的应付金额应是 单价 × 2').toBe(expectTotal)
        expect(totalB, '乙的应付金额应是 单价 × 2').toBe(expectTotal)
      })

      let respA, respB, reqAtA, reqAtB, respAtA, respAtB
      await test.step('④ 两人几乎同时点「提交订单」（两个独立会话，真并发）', async () => {
        const pA = createOrderRespOf(page)
        const pB = createOrderRespOf(pageB)
        reqAtA = Date.now()
        reqAtB = reqAtA
        await Promise.all([
          tid(page, 'checkout-submit').click(),
          tid(pageB, 'checkout-submit').click()
        ])
        respA = await pA
        respAtA = Date.now()
        respB = await pB
        respAtB = Date.now()
      })

      await test.step('⑤ 恰好一人成功，另一人拿到「库存不足」明确报错（不是静默失败、不是假成功）', async () => {
        const results = [
          { who: '甲', page, resp: respA, body: await respA.json() },
          { who: '乙', page: pageB, resp: respB, body: await respB.json() }
        ]
        const winners = results.filter((r) => r.body.code === 0)
        const losers = results.filter((r) => r.body.code !== 0)
        expect(winners.length, '库存 2 只够一单：必须恰好一人下单成功（不能两人都成功）').toBe(1)
        expect(losers.length, '另一个人必须被明确拒绝').toBe(1)

        const win = winners[0]
        const lose = losers[0]
        expect(win.resp.status(), '胜者的下单请求应是 HTTP 200').toBe(200)
        expect(win.body.data.order.status, '刚创建的订单应是待付款(0)').toBe(0)
        winner = {
          who: win.who,
          orderId: win.body.data.order.id,
          orderSn: win.body.data.order.orderSn,
          memberId: win.body.data.order.memberId,
          page: win.page
        }
        loserWho = lose.who

        // 败者的报错必须是后端原话「库存不足：<商品名>」，HTTP 200 + code=1（业务失败）
        expect(lose.resp.status(), '库存不足是业务失败：HTTP 仍是 200').toBe(200)
        expect(lose.body.code, '库存不足的业务码应为 1').toBe(1)
        expect(lose.body.message, '拒绝原因应是后端原话「库存不足：<商品名>」').toBe(`库存不足：${productName}`)

        // 败者页面：出现「下单失败：」+ 该页真实响应的 message，且没有成功提示、没被跳走
        const errToast = lose.page.locator('.el-message--error').first()
        await expect(errToast, `${lose.who} 应看到下单失败提示`).toBeVisible({ timeout: 20_000 })
        await expect(errToast, `${lose.who} 的提示必须是「下单失败：」+ 该页真实响应的 message`)
          .toHaveText(`下单失败：${lose.body.message}`)
        await expect(lose.page.locator('.el-message--success'), `${lose.who} 不应看到任何成功提示`).toHaveCount(0)
        await expect(lose.page, `${lose.who} 应仍停在结算页（不能假成功跳走）`).toHaveURL(/\/checkout/)

        const errText = (await errToast.innerText()).trim()
        console.log(`[10 并发结果] 胜者=${win.who} http=${win.resp.status()} code=0 订单=${winner.orderSn} body.order.status=${win.body.data.order.status}`)
        console.log(`[10 并发结果] 败者=${lose.who} http=${lose.resp.status()} code=${lose.body.code} message=${lose.body.message}`)
        console.log(`[10 页面文案] 败者 toast="${errText}"；两请求发出间隔 ${Math.abs(reqAtB - reqAtA)}ms，响应到达间隔 ${Math.abs(respAtB - respAtA)}ms`)
      })

      await test.step('⑥ 不变量：库存恰好落到 0（绝不为负）、订单只多 1 笔、明细只多 1 条 2 件', async () => {
        // 胜者页面会自动模拟支付 → 订单进待审核(7)；这不是本用例断言对象，等它落定再查库更稳
        const winPage = winner.page
        await winPage.waitForURL(/\/orders/, { timeout: 30_000 })
        const winToken = await memberTokenOf(winPage)
        for (let i = 0; i < 10; i++) {
          const d = await getMemberOrdersApi(request, winToken)
          if (d.records?.[0]?.status === 7) break
          await winPage.waitForTimeout(800)
        }
        const winOrders = await getMemberOrdersApi(request, winToken)
        expect(winOrders.records.length, `${winner.who} 应只有 1 笔订单（另一单没被创建）`).toBe(1)
        expect(winOrders.records[0].orderSn, '订单号应与创建响应一致').toBe(winner.orderSn)

        // 胜者这笔订单自己的明细：恰好 1 条、件数 2、就是这个 SKU
        const detail = await getMemberOrderApi(request, winToken, winner.orderId)
        expect(detail.items.length, '胜者订单应只有 1 条明细').toBe(1)
        expect(detail.items[0].productSkuId, '明细应指向抢购的那个 SKU').toBe(target.id)
        expect(detail.items[0].productQuantity, '明细件数应是 2').toBe(QTY_PER_BUYER)

        // 库存：正好 0，不为负。两种模式分别钉各自的权威值 —— 这不比原来宽松，反而更严
        //   · 分桶模式：SUM(桶) 必须**精确**为 0（权威值，没有滞后）
        //   · 两种模式共用：sku.stock 轮询 2 秒内必须归零（legacy 是立刻，bucket 是 ≤250ms 的缓存回写）
        if (STOCK_MODE === 'bucket') {
          expect(bucketSumOf(target.id),
            '分桶模式下 SUM(sku_bucket.stock) 必须恰好为 0（这是真正的库存事实）').toBe(0)
          expect(minBucketOfAll(), '全库任何桶都不许出现负库存').toBeGreaterThanOrEqual(0)
        }
        await expect.poll(() => stockOf(target.id), {
          timeout: 2_000,
          intervals: [50, 100, 200, 400],
          message: '下单成功后该 SKU 的库存（sku.stock）必须在 2 秒内归零（缓存也最终一致）'
        }).toBe(0)
        const stockNow = stockOf(target.id)
        const minStock = minStockOfAll()
        expect(minStock, '全库任何 SKU 都不许出现负库存').toBeGreaterThanOrEqual(0)

        // 订单明细增量：只多 1 条、只多 1 张订单、只多 2 件
        const stats = orderItemStatsOf(target.id)
        expect(stats.orders - baseline.orders, '订单表只多 1 笔（该 SKU 涉及的订单数 +1）').toBe(1)
        expect(stats.lines - baseline.lines, '该 SKU 的订单明细只多 1 条').toBe(1)
        expect(stats.qty - baseline.qty, '该 SKU 的成交件数只多 2 件').toBe(QTY_PER_BUYER)

        // 败者名下没有订单（那一单确实没落库）
        const loserMemberId = loserWho === '甲' ? memberIdA : memberIdB
        expect(orderCountOfMember(loserMemberId), '被拒那个会员名下不应有任何订单').toBe(0)

        console.log(
          `[10 库中不变量] sku(id=${target.id}).stock=${stockNow}；order_item(该 SKU) lines ${baseline.lines}→${stats.lines}`
          + ` qty ${baseline.qty}→${stats.qty} orders ${baseline.orders}→${stats.orders}；min(stock)=${minStock}`
        )
      })
    } finally {
      // ---- 收尾：还原库存 → 清订单 → 清残留购物车 → 删两个会员 ----
      try {
        if (target && pageAdmin && !pageAdmin.isClosed()) {
          await pageAdmin.goto(`${ADMIN}/product/${PRODUCT_ID}/skus`)
          await pageAdmin.waitForTimeout(1200)
          await editSku(pageAdmin, target.specText, { stock: target.originalStock })
          const restored = Number(
            (await getProductApi(request, PRODUCT_ID)).skus.find((s) => s.id === target.id)?.stock
          )
          console.log(`[10 清理] SKU id=${target.id} 库存已还原为 ${restored}（原值 ${target.originalStock}）`)
        }
      } catch (e) {
        test.info().annotations.push({ type: '库存还原失败', description: String(e.message || e) })
      }
      try {
        if (winner?.orderSn) {
          purgeOrderBySn(winner.orderSn)
          console.log(`[10 清理] 订单 ${winner.orderSn} 已物理删除`)
        }
      } catch (e) {
        test.info().annotations.push({ type: '清理失败（订单）', description: String(e.message || e) })
      }
      // 被拒那一方的购物车还留着 2 件（订单没成立，购物车自然不清空）→ 真实点一次删除
      for (const [who, p] of [['甲', page], ['乙', pageB]]) {
        try {
          if (!p || p.isClosed()) continue
          await p.goto(`${PORTAL}/cart`)
          await p.waitForTimeout(1000)
          const removeBtn = tid(p, 'cart-row-remove').first()
          if (await removeBtn.count()) {
            await removeBtn.click()
            await p.waitForTimeout(1000)
            console.log(`[10 清理] ${who} 的购物车残留已删除`)
          }
        } catch { /* 清理失败不影响结论 */ }
      }
      try {
        const token = await adminTokenForCleanup(request)
        for (const username of [userA, userB]) {
          const res = await request.get(`${API}/admin/members?username=${encodeURIComponent(username)}`, {
            headers: { Authorization: `Bearer ${token}` }
          })
          const list = (await res.json()).data || []
          const mine = list.find((m) => m.username === username)
          if (mine) {
            const del = await request.delete(`${API}/admin/members/${mine.id}`, {
              headers: { Authorization: `Bearer ${token}` }
            })
            const delBody = await del.json().catch(() => ({}))
            console.log(`[10 清理] 会员 ${username}(id=${mine.id}) 删除 http=${del.status()} code=${delBody.code}`)
          }
        }
      } catch (e) {
        test.info().annotations.push({ type: '清理失败（会员）', description: String(e.message || e) })
      }
      if (ctxB) await ctxB.close().catch(() => {})
      if (ctxAdmin) await ctxAdmin.close().catch(() => {})
    }
  })
})
