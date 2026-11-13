import { test, expect } from '../../support/test.js'
import { API, PORTAL } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { memberRegisterAndLogin, memberLogin, addSpecToCart, submitCartOrder } from '../../support/ui.js'
import { getProductApi, getMemberOrdersApi, getMemberOrderApi, memberTokenOf } from '../../support/assert.js'
import { memberLoginOf } from '../../support/member-login.js'
import { memberNickOf, addressOf } from '../../support/fixtures.js'

/**
 * 买家侧 08 支付并发冲突（PortalOrderController#pay 的并发保护）
 *
 * 只验一件事：**同一张待付款订单被支付两次时，第二个请求必须被明确拒绝，并且买家在页面上
 * 原样看到服务端那句买家视角的文案**，同时不会落下第二条支付流水。
 *
 * 为什么值得单独一个 spec：
 *   `PortalOrderController.pay()` 这轮改成「@Transactional + updateById 影响行数检查」——
 *   0 行（读出来之后订单已被改过，最常见就是被并发付了两次）→ 409 +
 *   `OptimisticLockGuard.MSG_ORDER_STATE_CHANGED`（「订单状态已变化，请刷新后重试」），
 *   事务回滚连支付流水一起撤掉。但买家端（portal-web）一条 spec 都没跑过：
 *   页面拿到拒绝之后到底提示什么、会不会假成功、会不会多记一条流水，全都没人验。
 *
 * 两个用例对应两条真实可达的拒绝路径（都以代码为准，不猜文案）：
 *   ① 顺序再来一次（甲付完、乙再点）→ 业务守卫先命中：HTTP 200 + code=1「当前订单状态不可支付」；
 *   ② 两页真并发点下去 → 后到的那笔读到旧状态后 UPDATE 影响 0 行：真 HTTP 409
 *      +「订单状态已变化，请刷新后重试」（哪一笔撞上 409 取决于时序，所以断言「恰有一笔成功」
 *      和「失败页显示的文案 === '支付失败：' + 该页真实响应里的 message」这两条恒真的不变量）。
 *   页面侧文案来自 portal-web/src/views/order/index.vue 的 pay() catch：
 *   `ElMessage.error('支付失败：' + e.message)`。
 *
 * 数据自造（不碰种子订单）：注册新会员 → 加购 → 结算下单，**并且把下单后前端那一次自动支付掐掉**，
 * 让订单真实停在「待付款」；跑完把这张单删掉（见 finally）。
 */

/**
 * 分片库名：pay 的并发闸门是数据库行级版本号，只有直接查库才能证明「没有第二条流水」
 * （payment_record 没有任何对外接口）。库名由 API_BASE 端口决定 —— 映射唯一维护在
 * e2e/tests/support/db.js 的 DB_OF_PORT（本 spec 原先自带一份、缺 18080，已收口）。
 */

/** 某订单号的支付流水条数（只读查询，用于证明「没有第二条流水」） */
const countPaymentRecords = (orderSn) =>
  Number(queryDb(`select count(*) from payment_record where order_sn = '${orderSn}'`))

/** 买家视角的两条拒绝文案（后端 OptimisticLockGuard 写死的那两句） */
const MSG_STATE_CHANGED = '订单状态已变化，请刷新后重试'   // 409（真并发）
const MSG_NOT_PAYABLE = '当前订单状态不可支付'             // code=1（状态守卫）

/**
 * 造一笔**真实待付款**订单：注册新会员 → 加购 → 结算下单，
 * 同时把「跳转我的订单后自动支付」那一次请求掐掉（route.abort），订单就停在 status=0。
 * 订单号/订单 id 一律从接口读，不靠「列表第一项」。
 */
const createPendingOrder = async (page, request, username, buyer) => {
  await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
  // 分片库正被重置的瞬间，商品 GET 会瞬时返回 code=1、data=null（不是被测业务缺陷）：
  // 与 adminLoginHardened 同一口径，重试两次；三次都拿不到才抛错。
  let skus
  for (let i = 0; !skus?.length; i++) {
    if (i >= 3) throw new Error('商品接口连续 3 次没返回 SKU（不是本用例的断言对象，先查环境）')
    if (i > 0) await page.waitForTimeout(1000)
    skus = (await getProductApi(request, 1))?.skus
  }
  const sku = skus[0]
  await addSpecToCart(page, 1, sku.specText, sku.id)

  await page.route('**/portal/orders/*/pay**', (route) => route.abort())
  await submitCartOrder(page, {
    receiverName: buyer.name,
    receiverPhone: buyer.phone,
    receiverAddress: `${buyer.province}${buyer.city}${buyer.region}${buyer.detail}`
  })
  await page.unroute('**/portal/orders/*/pay**')

  const token = await memberTokenOf(page)
  const pageData = await getMemberOrdersApi(request, token)
  expect(pageData.records.length, '新会员应只有 1 笔订单').toBe(1)
  const order = pageData.records[0]
  expect(order.status, '造出来的订单必须停在「待付款」(0)').toBe(0)
  expect(order.paymentTime, '待付款订单不应有支付时间').toBeFalsy()
  expect(countPaymentRecords(order.orderSn), '支付前不应有任何支付流水').toBe(0)
  return { orderSn: order.orderSn, orderId: order.id, token }
}

/** 打开「我的订单」并按订单号定位行（行内按钮一律走 testid，不用「列表第一项」） */
const openOrderRow = async (page, orderSn) => {
  await page.goto(`${PORTAL}/orders`)
  await page.waitForTimeout(1200)
  const row = page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
  await expect(row, `我的订单应能看到 ${orderSn}`).toBeVisible()
  return row
}

const payRespOf = (page, orderId) =>
  page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes(`/portal/orders/${orderId}/pay`),
    { timeout: 20_000 }
  )

/** 管理员令牌（仅用于跑完删掉本用例自造的订单，不参与任何断言） */
const adminTokenForCleanup = async (request) => {
  const res = await request.post(`${API}/admin/auth/login`, { data: { username: 'admin', password: '123456' } })
  return (await res.json()).data.accessToken
}

/** 收尾：删掉自造订单（级联删明细/发货单）。流水与会员是台账/账号，不做物理删除。 */
const cleanupOrder = async (request, orderId, orderSn) => {
  if (!orderId) return false
  try {
    const token = await adminTokenForCleanup(request)
    const del = await request.delete(`${API}/admin/orders/${orderId}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const ok = del.status() === 200
    test.info().annotations.push({
      type: '清理',
      description: `订单 ${orderSn} 已删除=${ok}；支付流水保留 ${countPaymentRecords(orderSn)} 条（对账台账，无外键）`
    })
    return ok
  } catch (e) {
    test.info().annotations.push({ type: '清理失败', description: String(e.message || e) })
    return false
  }
}

test.describe('买家侧 08 支付并发冲突', () => {
  test('甲付完乙再付：第二个请求被明确拒绝，买家看到拒绝原话，且只留 1 条流水', async ({ page, request, browser }) => {
    test.setTimeout(240_000)

    const username = memberLoginOf(Date.now())
    const buyer = addressOf(username)
    let created
    let ctx2
    let page2

    try {
      await test.step('① 自造一笔「待付款」订单（注册 → 加购 → 结算，掐掉前端那次自动支付）', async () => {
        created = await createPendingOrder(page, request, username, buyer)
      })

      let row1
      await test.step('② 甲：打开该订单的支付入口', async () => {
        row1 = await openOrderRow(page, created.orderSn)
        await expect(row1.getByTestId('orders-row-status'), '甲看到的应是待付款').toHaveText('待付款')
        await expect(row1.getByTestId('orders-row-pay'), '待付款订单应有「模拟支付」入口').toBeVisible()
      })

      let row2
      await test.step('③ 乙：另一个 context 登同一会员，也打开同一订单的支付入口', async () => {
        ctx2 = await browser.newContext()
        page2 = await ctx2.newPage()
        await memberLogin(page2, username, '123456')
        await expect(page2, '乙应登录成功').not.toHaveURL(/\/login/, { timeout: 20_000 })
        row2 = await openOrderRow(page2, created.orderSn)
        await expect(row2.getByTestId('orders-row-status'), '乙看到的也是待付款').toHaveText('待付款')
        await expect(row2.getByTestId('orders-row-pay'), '乙同样能点到支付入口').toBeVisible()
      })

      await test.step('④ 甲支付成功：状态 0 → 待审核(7)，落 1 条流水', async () => {
        const respP = payRespOf(page, created.orderId)
        await row1.getByTestId('orders-row-pay').click()
        const resp = await respP
        expect(resp.status(), '甲支付应是 200').toBe(200)
        expect((await resp.json()).code, '甲支付业务码应为 0').toBe(0)

        await expect(page.locator('.el-message--success').first(), '甲应看到支付成功提示').toContainText('支付成功（模拟）')
        await expect(row1.getByTestId('orders-row-status'), '甲支付后状态应为待审核').toHaveText('待审核')

        const afterPay = await getMemberOrderApi(request, created.token, created.orderId)
        expect(afterPay.order.status, '服务端：订单已进待审核(7)').toBe(7)
        expect(afterPay.order.paymentTime, '服务端：应落支付时间').toBeTruthy()
        expect(countPaymentRecords(created.orderSn), '甲支付后应恰好 1 条流水').toBe(1)
      })

      await test.step('⑤ 乙支付：必须被明确拒绝，页面原样显示服务端那句买家视角文案', async () => {
        // 乙的页面还是支付前加载的那份（行上仍有「模拟支付」），点下去才是真正的第二次请求
        await expect(row2.getByTestId('orders-row-pay'), '乙的行仍是缓存里的待付款，支付入口还在').toBeVisible()

        const respP = payRespOf(page2, created.orderId)
        await row2.getByTestId('orders-row-pay').click()
        const resp = await respP
        const body = await resp.json()

        // 顺序执行的第二次支付：业务守卫先命中（status != 0）→ HTTP 200 + code=1
        expect(resp.status(), '第二次支付是业务失败（不是 HTTP 错误）').toBe(200)
        expect(body.code, '第二次支付不能返回成功').toBe(1)
        expect(body.message, '服务端的买家视角拒绝原话').toBe(MSG_NOT_PAYABLE)

        // 页面把服务端原话原样显示出来（pay() catch: '支付失败：' + e.message）
        const errToast = page2.locator('.el-message--error').first()
        await expect(errToast, '乙应看到支付失败提示').toBeVisible({ timeout: 15_000 })
        await expect(errToast, '提示文案必须是「支付失败：」+ 服务端原话').toHaveText(`支付失败：${MSG_NOT_PAYABLE}`)
        // 绝不能假成功
        await expect(page2.locator('.el-message--success'), '乙不应看到任何成功提示').toHaveCount(0)

        // 按钮没有被卡在 loading/disabled 状态（pay() 没有 :loading，失败后仍可点）
        await expect(row2.getByTestId('orders-row-pay'), '支付失败后按钮必须仍然可点（不能卡住）').toBeEnabled()

        // 服务端事实不变：订单仍是待审核、流水仍然只有 1 条
        const afterSecond = await getMemberOrderApi(request, created.token, created.orderId)
        expect(afterSecond.order.status, '第二次支付后订单状态不得变化').toBe(7)
        expect(countPaymentRecords(created.orderSn), '第二次支付不得产生第二条支付流水').toBe(1)
      })

      await test.step('⑥ 乙刷新后看到订单已是「待审核」，且支付入口消失', async () => {
        await page2.reload()
        await page2.waitForTimeout(1500)
        await expect(row2.getByTestId('orders-row-status'), '刷新后应看到订单已是待审核').toHaveText('待审核')
        await expect(row2.getByTestId('orders-row-pay'), '待审核订单不应再有支付入口').toHaveCount(0)
      })
    } finally {
      if (ctx2) await ctx2.close().catch(() => {})
      await cleanupOrder(request, created?.orderId, created?.orderSn)
    }
  })

  test('两页真并发点支付：恰有一笔成功，另一页原样显示该页真实响应里的买家文案，流水仍只 1 条', async ({ page, request, browser }) => {
    test.setTimeout(240_000)

    const username = memberLoginOf(Date.now() + 1)
    const buyer = addressOf(username)
    let created
    let ctx2
    let page2

    try {
      await test.step('① 自造一笔「待付款」订单', async () => {
        created = await createPendingOrder(page, request, username, buyer)
      })

      let row1, row2
      await test.step('② 两个 context 登同一会员、都停在同一个订单的支付入口', async () => {
        row1 = await openOrderRow(page, created.orderSn)
        ctx2 = await browser.newContext()
        page2 = await ctx2.newPage()
        await memberLogin(page2, username, '123456')
        await expect(page2, '乙应登录成功').not.toHaveURL(/\/login/, { timeout: 20_000 })
        row2 = await openOrderRow(page2, created.orderSn)
        for (const [who, row] of [['甲', row1], ['乙', row2]]) {
          await expect(row.getByTestId('orders-row-status'), `${who} 看到的应是待付款`).toHaveText('待付款')
          await expect(row.getByTestId('orders-row-pay'), `${who} 应能点到支付入口`).toBeVisible()
        }
      })

      let respA, respB
      await test.step('③ 两页同一时刻点「模拟支付」（真并发）', async () => {
        const payA = payRespOf(page, created.orderId)
        const payB = payRespOf(page2, created.orderId)
        await Promise.all([row1.getByTestId('orders-row-pay').click(), row2.getByTestId('orders-row-pay').click()])
        respA = await payA
        respB = await payB
      })

      await test.step('④ 恰有一笔成功，另一笔被拒（409 或状态守卫），文案原样落到失败那一页', async () => {
        const results = [
          { who: '甲', page, resp: respA, body: await respA.json() },
          { who: '乙', page: page2, resp: respB, body: await respB.json() }
        ]
        const winners = results.filter((r) => r.body.code === 0)
        const losers = results.filter((r) => r.body.code !== 0)
        expect(winners.length, '并发支付必须恰有一笔成功（不能两笔都成功）').toBe(1)
        expect(losers.length, '另一笔必须被拒绝').toBe(1)

        const loser = losers[0]
        // 失败文案只可能是后端这两句买家视角的原话之一：
        //   409（UPDATE 影响 0 行）→ 订单状态已变化，请刷新后重试
        //   200/code=1（读的时候状态已经不是待付款）→ 当前订单状态不可支付
        expect([MSG_STATE_CHANGED, MSG_NOT_PAYABLE], `${loser.who} 的拒绝文案必须是后端原话`)
          .toContain(loser.body.message)
        if (loser.body.message === MSG_STATE_CHANGED) {
          expect(loser.resp.status(), '409 必须是真 HTTP 409').toBe(409)
          expect(loser.body.code).toBe(409)
        } else {
          expect(loser.resp.status(), '状态守卫是 HTTP 200 + code=1').toBe(200)
          expect(loser.body.code).toBe(1)
        }

        // 失败那一页：原样显示「支付失败：」+ 自己收到的 message，且没有成功提示
        const errToast = loser.page.locator('.el-message--error').first()
        await expect(errToast, `${loser.who} 应看到支付失败提示`).toBeVisible({ timeout: 15_000 })
        await expect(errToast, `${loser.who} 的提示必须是「支付失败：」+ 该页真实响应的 message`)
          .toHaveText(`支付失败：${loser.body.message}`)
        await expect(loser.page.locator('.el-message--success'), `${loser.who} 不应看到成功提示`).toHaveCount(0)

        // 服务端：状态待审核、流水恰好 1 条
        const after = await getMemberOrderApi(request, created.token, created.orderId)
        expect(after.order.status, '订单应已进待审核(7)').toBe(7)
        expect(countPaymentRecords(created.orderSn), '并发支付后流水必须只有 1 条').toBe(1)
        test.info().annotations.push({
          type: '并发结果',
          description: `胜者=${winners[0].who}(code=0)；败者=${loser.who}(http=${loser.resp.status()}, code=${loser.body.code}, message=${loser.body.message})`
        })
      })

      await test.step('⑤ 两页刷新后都是「待审核」，支付入口消失', async () => {
        for (const [who, p, row] of [['甲', page, row1], ['乙', page2, row2]]) {
          await p.reload()
          await p.waitForTimeout(1500)
          await expect(row.getByTestId('orders-row-status'), `${who} 刷新后应看到待审核`).toHaveText('待审核')
          await expect(row.getByTestId('orders-row-pay'), `${who} 不应再有支付入口`).toHaveCount(0)
        }
      })
    } finally {
      if (ctx2) await ctx2.close().catch(() => {})
      await cleanupOrder(request, created?.orderId, created?.orderSn)
    }
  })
})
