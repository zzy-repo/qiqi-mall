import { test, expect } from '../../../support/test.js'
import { API, ADMIN } from '../../../support/env.js'
import { dbOfApi, queryDb, execSql } from '../../../support/db.js'
import { tid } from '../../../support/tid.js'
import { adminLoginHardened } from '../../../support/admin-ui.js'
import { adminTokenOf } from '../../../support/assert.js'
import { receiverOf } from '../../../support/fixtures.js'

/**
 * 业务 / 商家-履约 / 后台建单（管理端 POST /admin/orders）
 *
 * 场景：**客服代客下单** —— 买家打电话来，客服在后台直接建一张单，先把"这单存在"落下来，
 * 明细与金额后面再补。所以这条路径必须允许**明细为空、总额 0**，并且它和"买家侧结算下单"
 * 是**两条完全不同的路**：买家侧是「购物车 → 结算 → 支付 → 建单（明细来自购物车、金额来自明细）」，
 * 后台建单不走支付、不走购物车、金额也**只认明细**。
 *
 * ── 这条用例为什么一半走界面、一半走接口（重要，别以为是偷懒）─────────────
 *   `admin-web` 的新增订单表单在提交前就拦了空明细：
 *     `if (!isEdit && (!form.items.length || …)) ElMessage.warning('请至少填写一条完整的订单明细…')`
 *   —— 也就是说"空明细建单"**在后台界面上根本点不出来**，它只对直连管理端接口存在
 *   （后端 OrderController#create 的注释里也明写了这一点）。所以：
 *     · 界面负责证「表单这条路被拦住、且拦住之后库里没有多出订单」；
 *     · 接口负责证「接口允许空明细，但金额必须归零、状态必须在白名单内、库存与台账一行都不能动」；
 *     · 最后回到界面证「这张单在列表里看得见：状态 待审核、金额 ¥0.00」。
 *   用例不假装"界面上能建空单"，那条断言只会变成假绿。
 *
 * ── 「再加明细 → 总额重算」为什么改成了对照组 ──────────────────────────────
 *   现有代码里**没有**"给已有订单加明细"的入口：`PUT /admin/orders/{id}` 收的是 Order 实体
 *   （没有 items 字段），而且 `body.setStatus(null)` —— 状态也不许从这里改；后台编辑弹窗同样
 *   不渲染明细表、提交不带明细。所以用例改成同一口径的**对照组**：
 *   建单时请求体里塞 `totalAmount: 999`，但明细是 1 条「价 199 × 2」→ 库里必须是 **398**。
 *   这就是"总额以明细重算、请求体里写的金额不算数"的直接证据，而且它同时证明了
 *   "没有明细 → 0"不是巧合（同一个重算分支的两头都被钉住）。
 *
 * 另外顺手钉一条：初始状态白名单只放行 0 / 1 / 7 —— 传 8（待发货）必须被拒，
 * 因为「8 待发货却一张包裹都没有」是任何真实流程都产不出的组合。
 */
test.describe('业务 › 商家-履约 › 后台建单', () => {
  test.beforeAll(() => {
    // eslint-disable-next-line no-console
    console.log(`[业务 / 商家-履约 / 后台建单] API=${API} ADMIN=${ADMIN} DB=${dbOfApi()}`)
  })

  /* ---------------------------------------------------------------- 库/接口小工具 */

  const one = (sql, what) => {
    const v = queryDb(sql)
    if (v === '') throw new Error(`查不到 ${what}（SQL 返回空）：${sql}`)
    return v
  }

  const memberIdOf = () => Number(one('select id from member order by id limit 1', '一个会员 id'))

  /** 一个"有货、有价"的规格（下单扣减走它的桶） */
  const pickSku = () => {
    const line = one(
      'select concat(s.id, \',\', s.price, \',\', s.product_id, \',\', p.name) from sku s '
      + 'join product p on p.id = s.product_id where s.stock >= 10 and s.price > 0 order by s.id limit 1',
      '一个库存 ≥10 且有价的规格'
    )
    const [id, price, productId, name] = line.split(',')
    return { skuId: Number(id), price: Number(price), productId: Number(productId), productName: name }
  }

  const stockOf = (skuId) => Number(one(`select coalesce(sum(stock), 0) from sku_bucket where sku_id = ${skuId}`, 'SUM(桶)'))
  const ledgerOf = (orderId) => Number(one(`select count(*) from stock_ledger where order_id = ${orderId}`, '订单的库存台账行数'))
  const birthLedgerOf = (orderId) => Number(one(
    `select count(*) from order_status_ledger where order_id = ${orderId} and from_status is null`,
    '订单的出生台账行数'))
  const itemsOf = (orderId) => Number(one(`select count(*) from order_item where order_id = ${orderId}`, '订单明细行数'))

  /** 建单用的请求体：order 走实体字段，items 单独给 */
  const createViaApi = async (request, token, { order, items }) => {
    const res = await request.post(`${API}/admin/orders`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { order, items }
    })
    return { status: res.status(), body: await res.json() }
  }

  /** 清掉用例自己造的订单（明细 / 出生台账 / 库存台账 / 订单本体） */
  const dropOrder = (orderId) => {
    if (!orderId) return
    execSql(`
      delete from stock_ledger        where order_id = ${orderId};
      delete from order_status_ledger where order_id = ${orderId};
      delete from order_item          where order_id = ${orderId};
      delete from \`order\`            where id = ${orderId};
    `)
  }

  /* ================================================================ ① 空明细 */

  test('空明细建单：订单存在但总额 0、状态待审核；表单那条路被前端挡住（库里不多订单）', async ({ page, request }) => {
    test.setTimeout(240_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)
    const memberId = memberIdOf()
    const who = receiverOf('后台代客下单')

    let orderId = 0

    try {
      await test.step('① 界面上试一次空明细：点「确定」被拦住，并且没有建出任何订单', async () => {
        const before = Number(one('select count(*) from `order`', '订单总数'))
        await page.goto(`${ADMIN}/order`)
        await expect(tid(page, 'order-list-table'), '订单列表应打开').toBeVisible({ timeout: 20_000 })
        await page.waitForTimeout(800)

        await page.getByRole('button', { name: '新增' }).click()
        const dlg = tid(page, 'order-form-dialog')
        await expect(dlg, '新增订单弹窗应打开').toBeVisible()

        // 只填会员与收货人，**一条明细都不加**
        await tid(page, 'order-form-member').click()
        await page.waitForTimeout(400)
        await page.locator('.el-select-dropdown__item:visible').first().click()
        await tid(page, 'order-form-receiver-name').fill(who.receiverName)
        await tid(page, 'order-form-receiver-phone').fill(who.receiverPhone)
        await tid(page, 'order-form-receiver-address').fill(who.receiverAddress)

        await tid(page, 'order-form-submit').click()
        const warn = page.locator('.el-message--warning').first()
        await expect(warn, '空明细提交应被前端拦下并说明原因').toBeVisible({ timeout: 10_000 })
        await expect(warn, '拦截文案要让人知道怎么改').toContainText('至少填写一条完整的订单明细')

        // 弹窗没关（这次提交没发生）、库里也没多订单
        await expect(dlg, '被拦下时弹窗不该关闭（说明请求没发出去）').toBeVisible()
        expect(Number(one('select count(*) from `order`', '订单总数')), '空明细被拦下 → 订单总数不变').toBe(before)
        await tid(page, 'order-form-cancel').click()
        await page.waitForTimeout(400)
      })

      await test.step('② 接口建单（空明细）：请求体里写死 999 也不许生效，总额必须是 0', async () => {
        const { status, body } = await createViaApi(request, token, {
          order: {
            memberId,
            receiverName: who.receiverName,
            receiverPhone: who.receiverPhone,
            receiverAddress: who.receiverAddress,
            status: 7,
            totalAmount: 999
          },
          items: []
        })
        expect(status, '建单接口应 200').toBe(200)
        expect(body.code, `空明细建单应成功（message=${body.message || '无'}）`).toBe(0)
        orderId = Number(body.data.id)
        expect(orderId, '应返回新订单 id').toBeGreaterThan(0)
        expect(Number(body.data.totalAmount), '返回体里的总额必须是 0（请求体的 999 被明细重算覆盖）').toBe(0)
      })

      await test.step('③ 库里：总额 0、状态 7、明细 0 行、库存台账 0 行、出生台账恰好 1 行', async () => {
        const row = one(
          `select concat(total_amount, ',', status, ',', split_status, ',', ifnull(payment_time, 'NULL'), ',', pay_type) `
          + `from \`order\` where id = ${orderId}`,
          `订单 ${orderId}`
        )
        const [amount, status, splitStatus, paymentTime, payType] = row.split(',')
        expect(Number(amount), '总额 = 0（没有明细就没有金额）').toBe(0)
        expect(Number(status), '状态 = 7 待审核（客服建单后走审核）').toBe(7)
        expect(Number(splitStatus), 'split_status 强制 0（建单这一刻一张包裹都没有）').toBe(0)
        expect(paymentTime, '待审核单不该带支付时间（清支付痕迹）').toBe('NULL')
        expect(Number(payType), 'pay_type 落 0（未支付）').toBe(0)
        expect(itemsOf(orderId), '明细 0 行').toBe(0)
        expect(ledgerOf(orderId), '库存台账 0 行（没有明细就没有库存变动）').toBe(0)
        expect(birthLedgerOf(orderId), '出生台账恰好 1 行（建单本身是一次状态事件）').toBe(1)
      })

      await test.step('④ 回到界面：列表里搜得到它，状态「待审核」、金额 ¥0.00', async () => {
        const sn = one(`select order_sn from \`order\` where id = ${orderId}`, '订单号')
        await page.goto(`${ADMIN}/order`)
        await expect(tid(page, 'order-list-table')).toBeVisible({ timeout: 20_000 })
        await page.waitForTimeout(600)
        await tid(page, 'order-list-search-sn').fill(sn)
        await tid(page, 'order-list-search-submit').click()
        await page.waitForTimeout(1200)

        const row = page.locator('.el-table__body tr').filter({ hasText: sn }).first()
        await expect(row, `订单列表应能搜到 ${sn}`).toBeVisible({ timeout: 15_000 })
        await expect(row.getByTestId('order-row-status'), '列表上的状态应是「待审核」').toHaveText('待审核')
        expect(await row.innerText(), '列表上的金额应是 ¥0.00（不是请求体里的 999）').toContain('0.00')
      })

      await test.step('⑤ 初始状态白名单：传 8（待发货）必须被拒，且不落库', async () => {
        const before = Number(one('select count(*) from `order`', '订单总数'))
        const { body } = await createViaApi(request, token, {
          order: {
            memberId,
            receiverName: who.receiverName,
            receiverPhone: who.receiverPhone,
            receiverAddress: who.receiverAddress,
            status: 8
          },
          items: []
        })
        expect(body.code, '「待发货却零包裹」这种组合必须被拒').not.toBe(0)
        expect(String(body.message || ''), '拒单原因要说人话（指向初始状态）').toMatch(/状态|待发货|8/)
        expect(Number(one('select count(*) from `order`', '订单总数')), '被拒的建单不落库').toBe(before)
      })
    } finally {
      dropOrder(orderId)
    }
  })

  /* ================================================================ ② 总额以明细为准 */

  test('带明细建单：请求体写 999、明细是 199×2 → 库里 398（总额由明细重算），并扣库存 + 记 1 行 ORDER_CREATE 台账', async ({ page, request }) => {
    test.setTimeout(240_000)
    await adminLoginHardened(page)
    const token = await adminTokenOf(page)
    const memberId = memberIdOf()
    const who = receiverOf('后台代客下单-带明细')
    const sku = pickSku()

    let orderId = 0
    let stockBefore = 0
    /** 明细重算出来的总额（不硬编码：单价随种子库变，写死 398 只会变成假红/假绿） */
    let expectAmount = 0

    try {
      await test.step('① 建单：请求体 totalAmount=999，明细 1 条 × 2 件', async () => {
        stockBefore = stockOf(sku.skuId)
        const { status, body } = await createViaApi(request, token, {
          order: {
            memberId,
            receiverName: who.receiverName,
            receiverPhone: who.receiverPhone,
            receiverAddress: who.receiverAddress,
            status: 7,
            totalAmount: 999
          },
          items: [{
            productId: sku.productId,
            productSkuId: sku.skuId,
            productName: sku.productName,
            productPrice: sku.price,
            productQuantity: 2
          }]
        })
        expect(status, '建单接口应 200').toBe(200)
        expect(body.code, `带明细建单应成功（message=${body.message || '无'}）`).toBe(0)
        orderId = Number(body.data.id)
        expect(orderId, '应返回新订单 id').toBeGreaterThan(0)
      })

      await test.step('② 总额由明细重算：199 × 2 = 398，而不是请求体里的 999', async () => {
        const amount = Number(one(`select total_amount from \`order\` where id = ${orderId}`, '订单总额'))
        expectAmount = Number((sku.price * 2).toFixed(2))
        expect(amount, '总额必须等于 明细单价 × 件数').toBe(expectAmount)
        expect(amount, '总额不能等于请求体里那个 999（金额凭空存在＝对账无据）').not.toBe(999)
        expect(itemsOf(orderId), '明细恰好 1 行').toBe(1)
      })

      await test.step('③ 建单即扣库存 + 记一行 ORDER_CREATE 台账（与买家下单同一口径）', async () => {
        expect(stockOf(sku.skuId), '桶总量应减 2').toBe(stockBefore - 2)
        expect(ledgerOf(orderId), '库存台账恰好 1 行').toBe(1)
        const reason = one(`select reason from stock_ledger where order_id = ${orderId}`, '台账原因码')
        expect(reason, '台账原因码应是 ORDER_CREATE（证明走的是建单扣减，不是别的路径）').toBe('ORDER_CREATE')
        expect(birthLedgerOf(orderId), '出生台账 1 行').toBe(1)
      })

      await test.step('④ 界面核对：列表金额 = 明细单价 × 2（不是请求体里的 999）、状态待审核', async () => {
        const sn = one(`select order_sn from \`order\` where id = ${orderId}`, '订单号')
        await page.goto(`${ADMIN}/order`)
        await expect(tid(page, 'order-list-table')).toBeVisible({ timeout: 20_000 })
        await page.waitForTimeout(600)
        await tid(page, 'order-list-search-sn').fill(sn)
        await tid(page, 'order-list-search-submit').click()
        await page.waitForTimeout(1200)
        const row = page.locator('.el-table__body tr').filter({ hasText: sn }).first()
        await expect(row, `订单列表应能搜到 ${sn}`).toBeVisible({ timeout: 15_000 })
        await expect(row.getByTestId('order-row-status'), '状态应是「待审核」').toHaveText('待审核')
        const text = await row.innerText()
        expect(text, `列表金额应是 ${expectAmount.toFixed(2)}（= 明细单价 × 2）`).toContain(expectAmount.toFixed(2))
        expect(text, '列表金额不该是请求体里那个 999').not.toContain('999.00')
      })
    } finally {
      // 复原：先把库存补回（造数层面直接写桶，不伪造台账），再删掉这张单
      if (orderId && stockBefore) {
        const now = stockOf(sku.skuId)
        const delta = stockBefore - now
        if (delta > 0) {
          execSql(`update sku_bucket set stock = stock + ${delta} where sku_id = ${sku.skuId} and bucket_no = `
            + '(select b from (select min(bucket_no) as b from sku_bucket where sku_id = ' + sku.skuId + ') t)')
        }
      }
      dropOrder(orderId)
    }
  })
})
