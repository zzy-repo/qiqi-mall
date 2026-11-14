import { test, expect } from '../../support/test.js'
import { API, ADMIN, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { tid, tidPrefix } from '../../support/tid.js'
import { receiverOf } from '../../support/fixtures.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  createOrderViaUi,
  auditRejectOrder,
  expectToastSuccess,
} from '../../support/admin-ui.js'
import { adminSetSkuStock } from '../../support/ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../support/assert.js'

/**
 * 库存 / 库存台账与对账
 *
 * 这个场景要证明的一件事：
 *   **同一件货不会被补两次，而且每一次扣和补都能查出「谁、为什么、落在哪个桶」。**
 *
 * 库存有四个动账口子：下单扣减、审核不通过回补、取消包裹回补、退货审核通过回补。
 * 以前四个口子各写各的裸 SQL（`stock = stock + N`），叠加起来就把同一件货补了两次 —— 库存虚增。
 * 现在的口径：
 *   ① 扣和补都走同一个写入口，先记一行台账再动库存；
 *   ② 幂等键 = 订单 + 规格 + 原因 + 关联单据，UNIQUE 兜底，同一个触发不会记第二遍；
 *   ③ 回补再夹一道数量守恒（同一订单同一规格补到下单量为止），换个入口也补不出第二份；
 *   ④ 扣减记下落点桶号，回补优先回同一个桶 —— 所以对账能落到「哪一桶少了/多了」。
 *
 * 对账页三个数各是各的（这是最容易看错的地方）：
 *   · 桶的总和  = 权威值（桶是事实源）
 *   · 页面库存  = 展示缓存，本该等于权威值，差就是偏差
 *   · 台账净额  = 全生命周期进出和，**不是余额**，不能与权威值对平
 *
 * ── 为什么三条用例都要直查库 ──────────────────────────────────────────────
 *   台账页只证明「这一行看得见」；「只记了一行」「净额是零」「落点桶不是空」这些事实
 *   在页面上看不出来（页面只给一页 10 行、也不显示桶号）。所以每条用例都是
 *   界面看得见 + 库里数得清，两边都成立才算过。
 *
 * ── 依赖（当前状态，写在这里免得下一个人以为是假红）──────────────────────
 *   台账页 `/ledger` 与照页 `/inventory-reconcile` 的前端已就绪（testid 齐），
 *   但后端 `GET /admin/ledgers`、`GET /admin/inventory/reconcile` 两个控制器还没进仓，
 *   所以本文件**只写不跑**：接口上线后直接 `scripts/demo-run.sh` 即可验证。
 *   直查库那部分不依赖这两个接口，随时可跑。
 */

/* ------------------------------------------------------------------ 台账页（UI 侧） */

/** 打开台账查询页 */
const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

/** 台账表格里当前渲染了多少行（行锚点挂在时间格上，见 tid.js 第 4 条） */
const ledgerRowCount = (page) => tidPrefix(page, 'ledger-row-').count()

/**
 * 用筛选区把台账收敛到「某类型 + 某个业务ID」，然后断言恰好 rows 行。
 *
 * 为什么不是「等某个响应」再断言：类型下拉自己会触发一次查询（@change="search"），
 * 填完业务ID再点「查询」是第二次 —— 抓响应容易抓到中间那一次。
 * 所以这里改用「轮询到稳定」+「每行都必须是这个业务ID」双保险。
 */
const expectLedgerRows = async (page, { type, bizId, rows, message }) => {
  if (type) {
    await tid(page, 'ledger-type-select').click()
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: type }).first().click()
  }
  if (bizId !== undefined && bizId !== null) {
    await tid(page, 'ledger-biz-id-input').fill(String(bizId))
  }
  await tid(page, 'ledger-search-submit').click()
  await expect.poll(() => ledgerRowCount(page), {
    timeout: 20_000,
    intervals: [400, 800, 1500],
    message
  }).toBe(rows)
  if (bizId !== undefined && bizId !== null) {
    await expect(tid(page, 'ledger-list-table'), `台账页收敛到的每一行都应是业务ID ${bizId}`)
      .toContainText(`ID ${bizId}`)
  }
}

/* ------------------------------------------------------------------ 库上的事实（只读） */

const dbCount = (sql) => Number(queryDb(sql))

/** 这张单在库存台账里的行数（一行＝一次动账） */
const stockRowsOf = (orderId) => dbCount(`select count(*) from stock_ledger where order_id = ${orderId}`)

/** 这张单在库存台账里的净额（负数＝净扣了货；零＝扣补相抵） */
const stockNetOf = (orderId, skuId) => Number(queryDb(
  `select coalesce(sum(delta), 0) from stock_ledger where order_id = ${orderId} and sku_id = ${skuId}`
))

/** 原因码序列（按记账顺序）—— 用来证明「走了哪几条路径」 */
const stockReasonsOf = (orderId) => queryDb(
  `select coalesce(group_concat(reason order by id separator '、'), '') from stock_ledger where order_id = ${orderId}`
)

/** 落点桶号序列（NULL 记为 -）—— 回补要能落回某一桶，不能是空 */
const stockBucketsOf = (orderId) => queryDb(
  `select coalesce(group_concat(coalesce(bucket_no, '-') order by id separator '、'), '') from stock_ledger where order_id = ${orderId}`
)

/** 幂等键列表（每行一个）—— 用来钉住「键里带订单/规格/原因」这个格式 */
const stockIdemKeysOf = (orderId) => queryDb(
  `select idem_key from stock_ledger where order_id = ${orderId} order by id`
).split('\n').filter(Boolean)

/** 订单明细里的规格 id（扣的是哪个规格以明细为准，不假设页面顺序） */
const skuOfOrder = (orderId) => Number(queryDb(
  `select product_sku_id from order_item where order_id = ${orderId} order by id limit 1`
))

/** 这张单在该规格下的下单总量（守恒的上限） */
const orderedQtyOf = (orderId, skuId) => Number(queryDb(
  `select coalesce(sum(product_quantity), 0) from order_item where order_id = ${orderId} and product_sku_id = ${skuId}`
))

/** 该 SKU 在桶里的库存总和（权威值，只读） */
const bucketSumOf = (skuId) => Number(queryDb(
  `select coalesce(sum(stock), 0) from sku_bucket where sku_id = ${skuId}`
))

/** 桶的个数（配置是 8 个，被误删会少） */
const bucketCountOf = (skuId) => dbCount(`select count(*) from sku_bucket where sku_id = ${skuId}`)

/** SKU 展示缓存（sku.stock） */
const skuStockOf = (skuId) => Number(queryDb(`select stock from sku where id = ${skuId}`))

/* ------------------------------------------------------------------ 备货 / 清理 */

/** 只在库存确实不够时补货（理由同 8-订单删除守卫：别每次把演示库存打回去） */
async function ensureStock(page, request, { productId = 1, rowIndex = 0, need = 50 } = {}) {
  const { skus } = await getProductApi(request, productId)
  if ((skus[rowIndex]?.stock ?? 0) >= need) return false
  await adminSetSkuStock(page, productId, rowIndex, need)
  return true
}

/**
 * 清掉本用例自造的订单：先断掉删单守卫的依据，再交给真实删除接口（库存回补与台账清理由产品代码负责）。
 * 不在这里手写「补库存 + 删台账」：那是产品代码在做的事，测试再实现一遍就是两份口径。
 */
async function cleanupSelfMadeOrder(request, { orderId, orderSn, token }) {
  const id = orderId
    || (orderSn ? Number(queryDb(`select id from \`order\` where order_sn = '${orderSn}'`) || 0) : 0)
  if (!id) return
  if (dbCount(`select count(*) from \`order\` where id = ${id}`) === 0) return
  try {
    queryDb(`delete from delivery_logistics where delivery_id in
               (select id from delivery_order where order_id = ${id})`)
    queryDb(`delete from return_apply where order_id = ${id}`)
    queryDb(`update delivery_order set status = 0 where order_id = ${id} and status <> 9`)
    const res = await request.delete(`${API}/admin/orders/${id}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const body = await res.json()
    if (body.code !== 0) console.warn(`[9-库存台账与对账] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[9-库存台账与对账] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

test.describe('库存 › 库存台账与对账', () => {
  test('用例一：下单只留一行负数台账，幂等键带「订单+规格+原因」，落点桶不是空', async ({ page, request }) => {
    test.setTimeout(180_000)
    let orderSn = null
    let orderId = null
    let skuId = null
    let token = null
    const QTY = 2
    try {
      let productName
      await test.step('① 管理员登录（库存不够时顺手备货）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
      })

      await test.step('② 后台「新增订单」造一笔待审核订单（下单即扣库存）', async () => {
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`stock-ledger-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: QTY }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        skuId = skuOfOrder(orderId)
      })

      await test.step('③ 库：只有一行台账，负数、原因下单、关联单据为空、落点桶已记', async () => {
        expect(stockRowsOf(orderId), '下单只该留一行库存台账').toBe(1)
        expect(stockNetOf(orderId, skuId), `这一行应是 -${QTY}（下单扣减）`).toBe(-QTY)
        expect(stockReasonsOf(orderId), '原因码应是下单扣减').toBe('ORDER_CREATE')
        expect(stockBucketsOf(orderId), '扣减必须记下落点桶号，否则回补时找不到桶').toMatch(/^\d+$/)
        expect(
          Number(queryDb(`select count(*) from stock_ledger where order_id = ${orderId} and ref_type is null`)),
          '下单这条没有触发单据（ref_type/ref_id 为空），别乱填'
        ).toBe(1)
        const keys = stockIdemKeysOf(orderId)
        expect(keys.length, '应有且只有一把幂等键').toBe(1)
        expect(keys[0], `幂等键应是「订单:规格:原因:单据」，实际：${keys[0]}`)
          .toMatch(new RegExp(`(^|:)${orderId}:${skuId}:ORDER_CREATE:0$`))
      })

      await test.step('④ 台账页：类型选「库存」+ 业务ID 填订单ID → 恰好一行，且写明原因码', async () => {
        await gotoLedger(page)
        await expectLedgerRows(page, {
          type: '库存',
          bizId: orderId,
          rows: 1,
          message: `台账页应能按「库存 + 订单 ${orderId}」查到那唯一一行`
        })
        await expect(tid(page, 'ledger-list-table'), '这一行要能看出是下单扣减（原因码 ORDER_CREATE）')
          .toContainText('ORDER_CREATE')
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二：审核不通过回补一行、净额归零；同一条回补重放两次也只有一行（去重+守恒）', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let skuId = null
    let token = null
    const QTY = 3
    try {
      let productName
      await test.step('① 管理员登录 + 造一笔待审核订单', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`stock-refund-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: QTY }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        skuId = skuOfOrder(orderId)
        expect(stockNetOf(orderId, skuId), `前提：下单应扣掉 ${QTY} 件`).toBe(-QTY)
      })

      await test.step('② 点「审核不通过」→ 订单关闭、库存按台账回补', async () => {
        await auditRejectOrder(page, await searchOrderRow(page, orderSn))
        await expectToastSuccess(page, '审核不通过')
      })

      await test.step('③ 库：多了一行正数台账，原因审核不通过，净额回到 0', async () => {
        expect(stockRowsOf(orderId), '扣一行 + 补一行 = 两行台账').toBe(2)
        expect(stockReasonsOf(orderId), '原因码序列应是「下单扣减 → 审核不通过回补」')
          .toBe('ORDER_CREATE、AUDIT_REJECT')
        expect(stockNetOf(orderId, skuId), '扣补相抵后净额应为 0（这单的货回到了仓库）').toBe(0)
        expect(
          Number(queryDb(`select max(delta) from stock_ledger where order_id = ${orderId}`)),
          `回补那行应是 +${QTY}`
        ).toBe(QTY)
        expect(stockBucketsOf(orderId).split('、').length, '两行都要有落点桶（回补也要落在某一桶）').toBe(2)
        expect(stockBucketsOf(orderId), '两行都不该出现空桶号').not.toContain('-')
      })

      await test.step('④ 台账页：按「库存 + 订单ID」应看到两行（一扣一补），能看出两条原因', async () => {
        await gotoLedger(page)
        await expectLedgerRows(page, {
          type: '库存',
          bizId: orderId,
          rows: 2,
          message: '台账页应看到这一单的两行动账（下单 + 回补）'
        })
        const table = tid(page, 'ledger-list-table')
        await expect(table, '两行里应有下单扣减').toContainText('ORDER_CREATE')
        await expect(table, '两行里应有审核不通过回补').toContainText('AUDIT_REJECT')
      })

      await test.step('⑤ 同一条回补重放两次：接口被状态守卫挡在外面，台账一行都不多', async () => {
        // UI 上已经点不了（订单已关闭），只能从接口重放 —— 它验的是「守卫先于记账」：
        // 0 行写入 ⇒ 不会有人顺手再补一次货。
        for (const round of [1, 2]) {
          const res = await request.post(`${API}/admin/orders/${orderId}/audit-reject`, {
            headers: { Authorization: `Bearer ${token}` }
          })
          const body = await res.json()
          expect(body.code, `第 ${round} 次重放应被拒（只有待审核订单可以审核不通过），实际：${body.message}`).toBe(1)
        }
        expect(stockRowsOf(orderId), '重放两次也不该多一行台账').toBe(2)
        expect(stockNetOf(orderId, skuId), '净额仍应是 0，没被重复回补').toBe(0)
        // 守恒上限：回补总量不得超过下单量
        expect(
          Number(queryDb(`select coalesce(sum(delta),0) from stock_ledger
                           where order_id = ${orderId} and sku_id = ${skuId} and delta > 0`)),
          `回补总量不得超过下单量 ${orderedQtyOf(orderId, skuId)}`
        ).toBeLessThanOrEqual(orderedQtyOf(orderId, skuId))
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三：对账页三个数各是各的 —— 桶总和等于展示缓存、偏差 0、台账净额不是余额', async ({ page, request }) => {
    test.setTimeout(180_000)
    let orderSn = null
    let orderId = null
    let skuId = null
    let token = null
    const QTY = 4
    try {
      let productName
      await test.step('① 管理员登录 + 造一笔订单（让这个 SKU 至少有一行流水可看）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`reconcile-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: QTY }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        skuId = skuOfOrder(orderId)
        // 展示缓存是周期回写的，等它收敛再对比，否则比的是「还没回写」而不是「对不上」
        await expect.poll(() => skuStockOf(skuId), {
          timeout: 10_000,
          intervals: [200, 400, 800],
          message: '展示缓存应回写成桶的总和（回写是周期任务，给它一点时间）'
        }).toBe(bucketSumOf(skuId))
      })

      await test.step('② 对账页：输入规格ID → 权威值 / 展示缓存 / 偏差 三个数并排', async () => {
        await page.goto(`${ADMIN}/inventory-reconcile`)
        await expect(tid(page, 'reconcile-sku-input'), '对账页应打开（reconcile-sku-input）')
          .toBeVisible({ timeout: 20_000 })
        await tid(page, 'reconcile-sku-input').fill(String(skuId))
        await tid(page, 'reconcile-search-submit').click()

        const bucketSum = bucketSumOf(skuId)
        const skuStock = skuStockOf(skuId)
        await expect(tid(page, 'reconcile-bucket-sum'), '权威值应等于桶的总和')
          .toHaveText(String(bucketSum))
        await expect(tid(page, 'reconcile-sku-stock'), '展示缓存应等于 sku 上的库存')
          .toHaveText(String(skuStock))
        await expect(tid(page, 'reconcile-drift'), '偏差应显示 0（两者一致）')
          .toHaveText(String(bucketSum - skuStock))
        expect(bucketSum - skuStock, '前提：这个 SKU 此刻不该有偏差').toBe(0)
      })

      await test.step('③ 对账页：台账净额单独一块，并写明「不是余额」', async () => {
        const net = Number(queryDb(
          `select coalesce(sum(delta), 0) from stock_ledger where sku_id = ${skuId}`
        ))
        const netBlock = tid(page, 'reconcile-ledger-net')
        await expect(netBlock, '台账净额应是该规格全生命周期进出和').toContainText(String(net))
        await expect(netBlock, '页面必须写清这块不是余额，否则看数的人一定会拿它对平')
          .toContainText('不是余额')
        // 关键口径：净额是「进多少出多少全算」，与当前库存**本就不该对平**。
        // 所以这里不断言「两者不相等」（偶然相等是允许的），只钉住两件事：
        //   ① 页面显示的数就是库里 SUM(delta) 算出来的那个数；
        //   ② 页面把「不是余额」这句话写出来了 —— 否则看数的人一定会拿它对平。
        expect(net, '账净额应等于该规格全部流水的增减合计').toBe(Number(queryDb(
          `select coalesce(sum(delta), 0) from stock_ledger where sku_id = ${skuId}`
        )))
      })

      await test.step('④ 对账页的流水表与该规格的台账行数一致，且能看到落点桶', async () => {
        const rows = dbCount(`select count(*) from stock_ledger where sku_id = ${skuId}`)
        const table = tid(page, 'reconcile-ledger-table')
        await expect(table, '对账页应显示这个规格的库存流水').toBeVisible()
        const shown = Math.min(rows, 20)   // 页面只列最近 20 条
        await expect(table.locator('.el-table__body tr'), `对账页应列出最近 ${shown} 条流水`)
          .toHaveCount(shown)
        await expect(table, '流水里应能看出这条属于本用例造的订单（原因码/幂等键可读）')
          .toContainText('ORDER_CREATE')
      })

      await test.step('⑤ 偏差榜：阈值设 0 时，这个一致的 SKU 不该出现在榜上', async () => {
        const threshold = tid(page, 'reconcile-drift-threshold').locator('input')
        await threshold.fill('0')
        await threshold.press('Tab')
        await tid(page, 'reconcile-drift-refresh').click()
        await page.waitForTimeout(1200)
        const driftSkuIds = await tid(page, 'reconcile-drift-table')
          .locator('.el-table__body tr td:first-child').allInnerTexts()
        expect(driftSkuIds.map((s) => s.trim()),
          `偏差为 0 的规格 ${skuId} 不该出现在偏差榜里（榜单只收 |偏差| ≥ 阈值的行）`)
          .not.toContain(String(skuId))
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })
})
