import { test, expect } from '../../support/test.js'
import { PORTAL, ADMIN, API } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { adminLogin, memberRegisterAndLogin, addSpecToCart, adminSetSkuStock } from '../../support/ui.js'
import { editSku } from '../../support/catalog-ui.js'
import { getProductApi, getMemberCartApi, getMemberOrdersApi, getMemberOrderApi, getMemberProfileApi, memberTokenOf } from '../../support/assert.js'
import { memberLoginOf } from '../../support/member-login.js'
import { memberNickOf, addressOf } from '../../support/fixtures.js'
import { tid } from '../../support/tid.js'

/**
 * 库存 / 库存分桶：把一把锁拆成八把
 *
 * 这条用例**只证明分桶这件事本身**，不重复「原子扣减」那条（`一条SQL吃掉「读、判、写」` 已经证过不超卖）。
 * 它要证的是三件分桶特有的事：
 *
 *   ① **一份库存真的被物化成 8 行**：后台把库存改成 N，得到 8 行 sku_bucket，
 *      且「余数进 0 号桶」这条拆分规则在数字上对得上（N=2 → 0 号桶 2、其余 7 个空）。
 *   ② **扣减是「随机起点 + 换桶」而不是「只打 0 号桶」**：库存 2 全在 0 号桶时，
 *      随机起点先撞空桶会拿到 0 行，必须换桶才扣得到 → 命中桶号必然是 0；
 *      同时台账 `stock_ledger.bucket_no` 必须记下这个桶号（**非空**，这是分桶后新增的列）。
 *   ③ **单桶装不下、总量够的时候能跨桶拼单**：8 个桶各 1 件、买 3 件（任何一个桶都不够 3），
 *      下单仍成功，且合计恰好 8→5、恰好 3 个桶从 1 变 0 —— 只可能是跨桶拼出来的。
 *
 * 并发那一半（两个独立 browser context、两个账号，几乎同时点提交）照旧：
 * 库存 2 只够一单 2 件 → **恰好一人成功**，另一人拿到后端原话「库存不足：<商品名>」，
 * 且 SUM(桶) 精确落到 0、全库没有负桶、败者名下 0 单、台账 0 行（不留半扣）。
 *
 * 数据自造自清：库存原值先记下、跑完还原；自造的订单与会员在 finally 里清掉，
 * 所以同一份库连跑两轮（不 reset）也不留残留。
 */

const PRODUCT_ID = 1

/** 分桶数：必须与后端 qiqi.stock.bucket-count 一致（application-dev/test.yml 默认 8） */
const BUCKET_COUNT = 8

/** 库名由 API_BASE 端口决定：映射唯一维护在 support/db.js 的 DB_OF_PORT */

/** 该 SKU 的全部桶（桶号升序）：[{ bucketNo, stock }] */
const bucketsOf = (skuId) => {
  const raw = queryDb(`select bucket_no, stock from sku_bucket where sku_id = ${skuId} order by bucket_no`)
  if (!raw) return []
  return raw.split('\n').map((line) => {
    const [no, stock] = line.split('\t')
    return { bucketNo: Number(no), stock: Number(stock) }
  })
}

/** 权威库存 = SUM(桶)；分桶模式下这才是库存事实，sku.stock 只是缓存 */
const bucketSumOf = (skuId) => Number(queryDb(`select coalesce(sum(stock),0) from sku_bucket where sku_id = ${skuId}`))

/** 展示缓存的当前值（分桶模式下滞后 ≤250ms） */
const stockOf = (skuId) => Number(queryDb(`select stock from sku where id = ${skuId}`))

/** 全库最小桶库存：证明任何时刻都没有桶被扣成负数 */
const minBucketOfAll = () => Number(queryDb('select coalesce(min(stock),0) from sku_bucket'))

/** 该订单的库存台账行（下单扣减） */
const ledgerOfOrder = (orderId) => {
  const raw = queryDb(
    `select delta, bucket_no, reason from stock_ledger where order_id = ${orderId} order by id`
  )
  if (!raw) return []
  return raw.split('\n').map((line) => {
    const [delta, bucketNo, reason] = line.split('\t')
    return { delta: Number(delta), bucketNo: bucketNo === 'NULL' ? null : Number(bucketNo), reason }
  })
}

/** 该 SKU 在订单明细上的统计（算增量用，不假设全表数量） */
const orderItemStatsOf = (skuId) => {
  const [lines, qty, orders] = queryDb(
    `select count(*), coalesce(sum(product_quantity), 0), count(distinct order_id)`
    + ` from order_item where product_sku_id = ${skuId}`
  ).split('\t')
  return { lines: Number(lines), qty: Number(qty), orders: Number(orders) }
}

const orderCountOfMember = (memberId) => Number(queryDb(`select count(*) from \`order\` where member_id = ${memberId}`))

/** 按订单号精确清掉本用例自造的那笔订单（FK 链：order → delivery_order → logistics 先断掉） */
const purgeOrderBySn = (orderSn) => {
  queryDb(`delete from delivery_logistics where delivery_id in (
             select id from delivery_order where order_id in
               (select id from \`order\` where order_sn = '${orderSn}'))`)
  queryDb(`delete from delivery_order where order_id in (select id from \`order\` where order_sn = '${orderSn}')`)
  queryDb(`delete from order_item where order_id in (select id from \`order\` where order_sn = '${orderSn}')`)
  const orderId = queryDb(`select id from \`order\` where order_sn = '${orderSn}'`)
  if (orderId) queryDb(`delete from stock_ledger where order_id = ${orderId}`)
  queryDb(`delete from \`order\` where order_sn = '${orderSn}'`)
  queryDb(`delete from payment_record where order_sn = '${orderSn}'`)
}

const adminTokenForCleanup = async (request) => {
  const res = await request.post(`${API}/admin/auth/login`, {
    data: { username: 'admin', password: process.env.ADMIN_PASSWORD || '123456' }
  })
  return (await res.json()).data.accessToken
}

/** 把购物车里这个 SKU 的数量改成 qty（el-input-number：填完失焦才触发 change → 后端落库） */
async function setCartQty(page, skuId, qty) {
  const input = tid(page, `cart-row-${skuId}`).getByTestId('cart-row-qty').locator('input')
  await expect(input, `购物车应有 SKU ${skuId} 这一行`).toBeVisible({ timeout: 15_000 })
  await input.click()
  await input.fill(String(qty))
  await input.press('Tab')
  await page.waitForTimeout(1200)
  await expect(input, `购物车数量应已改成 ${qty}`).toHaveValue(String(qty))
}

/** 等到 /checkout 页面就绪（表单已填、提交按钮可点） */
async function gotoCheckoutReady(page, buyer) {
  await page.goto(`${PORTAL}/cart`)
  await page.waitForTimeout(1000)
  await tid(page, 'cart-checkout').click()
  await page.waitForURL(/\/checkout/, { timeout: 20_000 })
  await page.waitForTimeout(1200)
  await tid(page, 'checkout-input-receiver').fill(buyer.name)
  await tid(page, 'checkout-input-phone').fill(buyer.phone)
  await tid(page, 'checkout-input-address').fill(`${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`)
  await expect(tid(page, 'checkout-submit'), '提交按钮应可点').toBeEnabled()
}

const createOrderRespOf = (page) =>
  page.waitForResponse(
    (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/portal/orders',
    { timeout: 60_000 }
  )

/** 清理：还库存、删订单、清购物车、删会员（两段用例共用） */
async function cleanupAll({ pageAdmin, request, target, orders, pages, usernames, ctxAdmin, ctxes }) {
  try {
    if (target && pageAdmin && !pageAdmin.isClosed()) {
      await pageAdmin.goto(`${ADMIN}/product/${PRODUCT_ID}/skus`)
      await pageAdmin.waitForTimeout(1200)
      await editSku(pageAdmin, target.specText, { stock: target.originalStock })
      const restored = Number((await getProductApi(request, PRODUCT_ID)).skus.find((s) => s.id === target.id)?.stock)
      console.log(`[分桶 清理] SKU id=${target.id} 库存已还原为 ${restored}（原值 ${target.originalStock}）`)
    }
  } catch (e) {
    test.info().annotations.push({ type: '库存还原失败', description: String(e.message || e) })
  }
  for (const orderSn of orders) {
    try {
      purgeOrderBySn(orderSn)
      console.log(`[分桶 清理] 订单 ${orderSn} 已物理删除`)
    } catch (e) {
      test.info().annotations.push({ type: '清理失败（订单）', description: String(e.message || e) })
    }
  }
  for (const [who, p] of pages) {
    try {
      if (!p || p.isClosed()) continue
      await p.goto(`${PORTAL}/cart`)
      await p.waitForTimeout(1000)
      const removeBtn = tid(p, 'cart-row-remove').first()
      if (await removeBtn.count()) {
        await removeBtn.click()
        await p.waitForTimeout(1000)
        console.log(`[分桶 清理] ${who} 的购物车残留已删除`)
      }
    } catch { /* 清理失败不影响结论 */ }
  }
  try {
    const token = await adminTokenForCleanup(request)
    for (const username of usernames) {
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
        console.log(`[分桶 清理] 会员 ${username}(id=${mine.id}) 删除 http=${del.status()} code=${delBody.code}`)
      }
    }
  } catch (e) {
    test.info().annotations.push({ type: '清理失败（会员）', description: String(e.message || e) })
  }
  for (const c of ctxes) if (c) await c.close().catch(() => {})
  if (ctxAdmin) await ctxAdmin.close().catch(() => {})
}

/** 后台把商品 1 的某个 SKU 库存改成 stock，回读确认是哪个 SKU，并记下原值 */
async function setTargetStock({ pageAdmin, request, stock }) {
  const before = await getProductApi(request, PRODUCT_ID)
  expect(before?.skus?.length, '商品 1 应有 SKU').toBeGreaterThan(0)
  const productName = before.product.name
  const stockBefore = new Map(before.skus.map((s) => [s.id, Number(s.stock)]))
  await adminSetSkuStock(pageAdmin, PRODUCT_ID, 0, stock)
  const after = await getProductApi(request, PRODUCT_ID)
  const changed = after.skus.filter((s) => Number(s.stock) === stock)
  expect(changed.length, `恰好应有 1 个 SKU 的库存被改成 ${stock}`).toBe(1)
  return {
    id: changed[0].id,
    specText: changed[0].specText,
    price: Number(changed[0].price),
    originalStock: stockBefore.get(changed[0].id),
    productName
  }
}

test.describe('库存 › 库存分桶：把一把锁拆成八把', () => {
  test('库存 2 件、两个会员同时各买 2 件：恰一人成功、另一人「库存不足」，SUM(桶) 落到 0 且没有负桶，台账记下具体桶号', async ({ page, request, browser }) => {
    test.setTimeout(360_000)

    const userA = memberLoginOf(Date.now() + 51)
    const userB = memberLoginOf(Date.now() + 52)
    const buyerA = addressOf(userA)
    const buyerB = addressOf(userB)

    let ctxAdmin, pageAdmin, ctxB, pageB
    let target = null
    let baseline = null
    let winner = null
    let loserWho = null
    let memberIdA = null
    let memberIdB = null

    try {
      await test.step('① 后台把商品 1 的一个 SKU 库存改成 2，并确认它被物化成 8 个桶（余数进 0 号桶）', async () => {
        ctxAdmin = await browser.newContext()
        pageAdmin = await ctxAdmin.newPage()
        await adminLogin(pageAdmin, 'admin', process.env.ADMIN_PASSWORD || '123456')
        target = await setTargetStock({ pageAdmin, request, stock: 2 })
        baseline = orderItemStatsOf(target.id)

        const buckets = bucketsOf(target.id)
        expect(buckets.length, `该 SKU 应有 ${BUCKET_COUNT} 个桶（一份库存物化成 N 行）`).toBe(BUCKET_COUNT)
        expect(buckets.map((b) => b.bucketNo).join(','), '桶号应是 0..7 连续').toBe('0,1,2,3,4,5,6,7')
        expect(bucketSumOf(target.id), 'SUM(桶) 必须等于后台设置的 2').toBe(2)
        expect(buckets[0].stock, '2 件平均拆 8 份的余数应落在 0 号桶').toBe(2)
        expect(buckets.slice(1).every((b) => b.stock === 0), '其余 7 个桶应为空').toBe(true)
        console.log(
          `[分桶 ①] 商品「${target.productName}」/ SKU id=${target.id} 规格「${target.specText}」`
          + ` 库存 ${target.originalStock} → 2；桶=${JSON.stringify(buckets)}`
        )
      })

      await test.step('② 两个会员各自注册、各加购 2 件（两个独立 context，两个账号）', async () => {
        await memberRegisterAndLogin(page, userA, '123456', memberNickOf(userA))
        await addSpecToCart(page, PRODUCT_ID, target.specText, target.id)
        await setCartQty(page, target.id, 2)

        ctxB = await browser.newContext()
        pageB = await ctxB.newPage()
        await memberRegisterAndLogin(pageB, userB, '123456', memberNickOf(userB))
        await addSpecToCart(pageB, PRODUCT_ID, target.specText, target.id)
        await setCartQty(pageB, target.id, 2)

        for (const [who, p] of [['甲', page], ['乙', pageB]]) {
          const token = await memberTokenOf(p)
          const cart = await getMemberCartApi(request, token)
          const line = cart.find((c) => c.productSkuId === target.id)
          expect(line, `${who} 的购物车应有这个 SKU`).toBeTruthy()
          expect(line.quantity, `${who} 的购物车数量应为 2`).toBe(2)
          const profile = await getMemberProfileApi(request, token)
          if (who === '甲') memberIdA = profile.id
          else memberIdB = profile.id
        }
        expect(memberIdA === memberIdB, '两个 context 必须是两个不同账号').toBe(false)
      })

      await test.step('③ 两页都停在结算页（各自身份、各自令牌）', async () => {
        await gotoCheckoutReady(page, buyerA)
        await gotoCheckoutReady(pageB, buyerB)
      })

      let respA, respB
      await test.step('④ 两人几乎同时点「提交订单」', async () => {
        const pA = createOrderRespOf(page)
        const pB = createOrderRespOf(pageB)
        await Promise.all([
          tid(page, 'checkout-submit').click(),
          tid(pageB, 'checkout-submit').click()
        ])
        respA = await pA
        respB = await pB
      })

      await test.step('⑤ 恰好一人成功，另一人拿到「库存不足」明确报错', async () => {
        const results = [
          { who: '甲', page, resp: respA, body: await respA.json() },
          { who: '乙', page: pageB, resp: respB, body: await respB.json() }
        ]
        const winners = results.filter((r) => r.body.code === 0)
        const losers = results.filter((r) => r.body.code !== 0)
        expect(winners.length, '库存 2 只够一单：必须恰好一人下单成功').toBe(1)
        expect(losers.length, '另一个人必须被明确拒绝').toBe(1)

        const win = winners[0]
        const lose = losers[0]
        expect(win.resp.status(), '胜者的下单请求应是 HTTP 200').toBe(200)
        winner = {
          who: win.who,
          orderId: win.body.data.order.id,
          orderSn: win.body.data.order.orderSn,
          page: win.page
        }
        loserWho = lose.who
        expect(lose.resp.status(), '库存不足是业务失败：HTTP 仍是 200').toBe(200)
        expect(lose.body.code, '库存不足的业务码应为 1').toBe(1)
        expect(lose.body.message, '拒绝原因应是后端原话「库存不足：<商品名>」').toBe(`库存不足：${target.productName}`)
        const errToast = lose.page.locator('.el-message--error').first()
        await expect(errToast, `${lose.who} 应看到下单失败提示`).toBeVisible({ timeout: 20_000 })
        await expect(errToast, '提示必须是「下单失败：」+ 该页真实响应的 message')
          .toHaveText(`下单失败：${lose.body.message}`)
        await expect(lose.page.locator('.el-message--success'), `${lose.who} 不应看到成功提示`).toHaveCount(0)
        await expect(lose.page, `${lose.who} 应仍停在结算页`).toHaveURL(/\/checkout/)
        console.log(`[分桶 ⑤] 胜者=${win.who} 订单=${winner.orderSn}；败者=${lose.who} message=${lose.body.message}`)
      })

      await test.step('⑥ 桶归零、无负桶、台账记下具体桶号、败者不留半扣', async () => {
        const winPage = winner.page
        await winPage.waitForURL(/\/orders/, { timeout: 30_000 })
        const winToken = await memberTokenOf(winPage)
        for (let i = 0; i < 10; i++) {
          const d = await getMemberOrdersApi(request, winToken)
          if (d.records?.[0]?.status === 7) break
          await winPage.waitForTimeout(800)
        }

        // 权威库存：SUM(桶) 精确为 0（不是「大约」）
        expect(bucketSumOf(target.id), 'SUM(桶) 必须精确落到 0').toBe(0)
        expect(minBucketOfAll(), '全库任何桶都不许为负').toBeGreaterThanOrEqual(0)
        await expect.poll(() => stockOf(target.id), {
          timeout: 2_000, intervals: [50, 100, 200, 400],
          message: '展示缓存 sku.stock 应在 2 秒内追平 SUM(桶)=0（周期回写 250ms）'
        }).toBe(0)

        // 台账：下单那一行必须记下「从哪个桶出去的」，且这一次必然命中 0 号桶
        //（2 件全在 0 号桶，随机起点先撞空桶会拿 0 行 → 换桶，最终只有一个桶扣得动）
        const ledger = ledgerOfOrder(winner.orderId)
        const deducts = ledger.filter((l) => l.reason === 'ORDER_CREATE')
        expect(deducts.length, '胜者订单的下单扣减应有且仅有 1 行台账').toBe(1)
        expect(deducts[0].delta, '扣减量应是 -2').toBe(-2)
        expect(deducts[0].bucketNo, '台账必须记下落点桶号（分桶后新增的列），不能是 NULL').not.toBeNull()
        expect(deducts[0].bucketNo, '库存只有 0 号桶有钱，所以命中桶号必然是 0').toBe(0)

        // 败者：0 单、0 台账行（拒绝发生在扣减之前，没有半扣残留）
        const loserMemberId = loserWho === '甲' ? memberIdA : memberIdB
        expect(orderCountOfMember(loserMemberId), '被拒那个会员名下不应有任何订单').toBe(0)

        // 增量口径：只多 1 单、1 条明细、2 件
        const stats = orderItemStatsOf(target.id)
        expect(stats.orders - baseline.orders, '订单只多 1 笔').toBe(1)
        expect(stats.lines - baseline.lines, '明细只多 1 条').toBe(1)
        expect(stats.qty - baseline.qty, '成交件数只多 2 件').toBe(2)

        const detail = await getMemberOrderApi(request, winToken, winner.orderId)
        expect(detail.items.length, '胜者订单应只有 1 条明细').toBe(1)
        expect(detail.items[0].productQuantity, '明细件数应是 2').toBe(2)

        console.log(
          `[分桶 ⑥] SUM(桶)=${bucketSumOf(target.id)} min(桶)=${minBucketOfAll()} sku.stock=${stockOf(target.id)}`
          + `；台账 ${JSON.stringify(deducts)}；败者订单=${orderCountOfMember(loserMemberId)}`
        )
      })
    } finally {
      await cleanupAll({
        pageAdmin, request, target,
        orders: winner?.orderSn ? [winner.orderSn] : [],
        pages: [['甲', page], ['乙', pageB]],
        usernames: [userA, userB],
        ctxAdmin, ctxes: [ctxB]
      })
    }
  })

  test('8 个桶各 1 件、买 3 件：任何一个桶都不够，仍下单成功 —— 只可能是跨桶拼出来的', async ({ page, request, browser }) => {
    test.setTimeout(360_000)

    const user = memberLoginOf(Date.now() + 53)
    const buyer = addressOf(user)

    let ctxAdmin, pageAdmin
    let target = null
    let baseline = null
    let orderSn = null

    try {
      await test.step('① 后台把该 SKU 库存改成 8 → 8 个桶各 1 件', async () => {
        ctxAdmin = await browser.newContext()
        pageAdmin = await ctxAdmin.newPage()
        await adminLogin(pageAdmin, 'admin', process.env.ADMIN_PASSWORD || '123456')
        target = await setTargetStock({ pageAdmin, request, stock: 8 })
        baseline = orderItemStatsOf(target.id)

        const buckets = bucketsOf(target.id)
        expect(buckets.length, `应有 ${BUCKET_COUNT} 个桶`).toBe(BUCKET_COUNT)
        expect(buckets.every((b) => b.stock === 1), '8 件平均拆 8 份 → 每个桶恰好 1 件').toBe(true)
        expect(Math.max(...buckets.map((b) => b.stock)), '单个桶最多只有 1 件 —— 买 3 件必然单桶装不下').toBe(1)
        console.log(`[分桶 ①] SKU id=${target.id} 桶=${JSON.stringify(buckets)}（单桶最大 1 件）`)
      })

      await test.step('② 会员加购 3 件并提交订单（成功）', async () => {
        await memberRegisterAndLogin(page, user, '123456', memberNickOf(user))
        await addSpecToCart(page, PRODUCT_ID, target.specText, target.id)
        await setCartQty(page, target.id, 3)
        await gotoCheckoutReady(page, buyer)
        const respP = createOrderRespOf(page)
        await tid(page, 'checkout-submit').click()
        const resp = await respP
        const body = await resp.json()
        expect(resp.status(), '下单请求应是 HTTP 200').toBe(200)
        expect(body.code, `单桶装不下但总量够 → 跨桶拼单必须成功（实际 message=${body.message}）`).toBe(0)
        orderSn = body.data.order.orderSn
        console.log(`[分桶 ②] 跨桶拼单下单成功：订单 ${orderSn}`)
      })

      await test.step('③ 合计 8→5、恰好 3 个桶从 1 变 0、没有负桶、台账记下首个桶号', async () => {
        await page.waitForURL(/\/orders/, { timeout: 30_000 })
        const token = await memberTokenOf(page)
        let orderId = null
        for (let i = 0; i < 10; i++) {
          const d = await getMemberOrdersApi(request, token)
          if (d.records?.[0]) { orderId = d.records[0].id; break }
          await page.waitForTimeout(800)
        }
        expect(orderId, '应能读到刚创建的订单').toBeTruthy()

        const buckets = bucketsOf(target.id)
        expect(bucketSumOf(target.id), '合计必须精确是 8-3=5（一件不多一件不少）').toBe(5)
        expect(buckets.filter((b) => b.stock === 0).length, '恰好 3 个桶被取空（1→0），这正是跨桶的痕迹').toBe(3)
        expect(buckets.every((b) => b.stock >= 0), '没有任何桶被扣成负数').toBe(true)
        expect(Math.max(...buckets.map((b) => b.stock)) <= 1, '没有桶被回补/多扣成超过 1').toBe(true)
        expect(minBucketOfAll(), '全库任何桶都不许为负').toBeGreaterThanOrEqual(0)

        const deducts = ledgerOfOrder(orderId).filter((l) => l.reason === 'ORDER_CREATE')
        expect(deducts.length, '跨桶拼单也是一单一行台账（不是每桶一行）').toBe(1)
        expect(deducts[0].delta, '扣减量应是 -3').toBe(-3)
        expect(deducts[0].bucketNo, '台账必须记下「首个被扣的桶号」，回补时据此回原桶').not.toBeNull()

        const stats = orderItemStatsOf(target.id)
        expect(stats.orders - baseline.orders, '订单只多 1 笔').toBe(1)
        expect(stats.qty - baseline.qty, '成交件数只多 3 件').toBe(3)

        console.log(
          `[分桶 ③] 跨桶后桶=${JSON.stringify(buckets)}；SUM=${bucketSumOf(target.id)}`
          + `；台账 ${JSON.stringify(deducts)}`
        )
      })
    } finally {
      await cleanupAll({
        pageAdmin, request, target,
        orders: orderSn ? [orderSn] : [],
        pages: [['丙', page]],
        usernames: [user],
        ctxAdmin, ctxes: []
      })
    }
  })
})
