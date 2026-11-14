import { test, expect } from '../../../support/test.js'
import { API, stamp } from '../../../support/env.js'
import { queryDb } from '../../../support/db.js'
import { memberNickOf, receiverOf, returnDescriptionOf } from '../../../support/fixtures.js'
import { memberLoginOf } from '../../../support/member-login.js'
import {
  adminSetSkuStock,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder
} from '../../../support/ui.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  createOrderViaUi,
  auditPassOrder,
  expectToastSuccess,
  expectToastError
} from '../../../support/admin-ui.js'
import { confirmDanger, dangerDialog } from '../../../support/danger-ui.js'
import { memberApplyReturn } from '../../../support/admin-ui-return.js'
import { listMembersApi, deleteMemberApi } from '../../../support/admin-ui-member.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../../support/assert.js'

/**
 * 业务 / 商家-履约 / 订单删除守卫
 *
 * 「删除订单」不是点一下就没了：后端先查三件事，任何一条成立就**拒删**，并用中文说清为什么、该怎么办。
 * 这三条守卫（`OrderPurgeService#blockReason`，真删与预演共用同一份）按此顺序判断：
 *   ① 有物流记录（含运单号）→ 拒删：可在履约页先作废物流，或改为关闭订单
 *   ② 有退货申请          → 拒删：退货申请是退款与对账的证据，不能随订单一并删除，请改为关闭订单
 *   ③ 有未取消且已出库的包裹 → 拒删：请先完成签收或改为关闭订单
 *   三条都不成立才允许删，删的时候按依赖顺序显式逐层删，并按库存台账净额回补库存。
 *
 * 为什么这四条用例要一起跑（这才是本场景的价值）：
 *   「拒删」的价值不只在提示文案，而在**被引用的数据一条都没少** —— 运单号、退货申请、
 *   已出库的包裹都是货与钱的凭据。所以每条用例除了断言文案原文，都直读库证明
 *   「订单还在、被它引用的行也还在」。只看 UI 是不够的：页面刷新一下就看不出来了。
 *
 * 预演与真删同口径怎么验：
 *   前端「删除」按钮走的是统一高危弹窗「预演 → 确认执行」，被守卫拦下时**预演阶段就失败**，
 *   所以 UI 上根本走不到真删那一步。于是用例二额外做两件事：
 *     · 再发一次 `?dryRun=true` 预演 → 拒因与 UI 那条**逐字相同**；
 *     · 再发一次**不带 dryRun 的真删**（这一步没有 UI 入口，只能从接口触发）→ 仍被同一条守卫拒绝、
 *       库里那些行一条没少。这就是「预演说不能删，真删一定不能删」的证据。
 *   （后端 `purge()` 内部还会再调一次 `blockReason` 做 fail-closed，接口这一段就是它的验证。）
 *
 * 数据自给自足：造的订单 / 发货单 / 退货申请 / 会员都在 finally 里按依赖顺序清掉 ——
 * 清理先断掉守卫依据（物流行 / 退货申请 / 包裹状态回落到未出库），再把删除交给**真实接口**，
 * 让产品的 purge 自己把库存回补、台账清干净（清理里不裸写库存）。
 */

/* ------------------------------------------------------------------ 库上的事实（只读） */

const dbCount = (sql) => Number(queryDb(sql))

/** 该订单号在 order 表里的行数（删成功=0，被拒=1） */
const orderRowsOf = (orderSn) => dbCount(`select count(*) from \`order\` where order_sn = '${orderSn}'`)

/** 订单明细行数 */
const itemsOf = (orderId) => dbCount(`select count(*) from order_item where order_id = ${orderId}`)

/** 库存台账行数（无外键，删干净后应为 0，否则会留下孤儿账） */
const ledgerRowsOf = (orderId) => dbCount(`select count(*) from stock_ledger where order_id = ${orderId}`)

/** 台账净额（负数=这单净扣了货）——「按台账净额回补」的基准 */
const ledgerNetOf = (orderId, skuId) => Number(queryDb(
  `select coalesce(sum(delta), 0) from stock_ledger where order_id = ${orderId} and sku_id = ${skuId}`
))

/** 该订单名下所有发货单的物流记录行数 */
const logisticsRowsOf = (orderId) => dbCount(
  `select count(*) from delivery_logistics where delivery_id in
     (select id from delivery_order where order_id = ${orderId})`
)

/** 该订单名下的运单号（升序、顿号连接）—— 用来逐字比对「运单号有没有变」 */
const trackNosOf = (orderId) => queryDb(
  `select coalesce(group_concat(track_no order by id separator '、'), '') from delivery_logistics
     where delivery_id in (select id from delivery_order where order_id = ${orderId})`
)

/** 该订单名下的发货单行数 */
const parcelsOf = (orderId) => dbCount(`select count(*) from delivery_order where order_id = ${orderId}`)

/** 该订单名下「未取消且已出库(>=3)」的包裹数 —— 与后端守卫同一个口径 */
const outboundParcelsOf = (orderId) => dbCount(
  `select count(*) from delivery_order where order_id = ${orderId} and status <> 9 and status >= 3`
)

/** 退货申请行数（退款与对账的证据，绝不能被删单带走） */
const returnsOf = (orderId) => dbCount(`select count(*) from return_apply where order_id = ${orderId}`)

/** 支付流水行数（按 order_sn 字符串关联，没有外键） */
const paymentsOf = (orderSn) => dbCount(`select count(*) from payment_record where order_sn = '${orderSn}'`)

/** SKU 当前展示库存（分桶模式下它是缓存，只能轮询等它收敛） */
const skuStockOf = async (request, productId, skuId) =>
  (await getProductApi(request, productId)).skus.find((s) => s.id === skuId).stock

/**
 * 备货：**只在库存确实不够时**才去 SKU 管理页把第 rowIndex 个规格补到 need 件。
 *
 * 为什么不无脑改：e2e 分片库与演示库平时都有足够库存（演示库是几十万件），
 * 每次「改成 50」既会把演示库存打回去，录片时还会多切进一刀与业务无关的 SKU 页面。
 * 判据用 portal 商品详情返回的展示库存（分桶模式下是缓存，做「够不够」的判断足够用）。
 * @returns {Promise<boolean>} 是否真的动了库存页
 */
async function ensureStock(page, request, { productId = 1, rowIndex = 0, need = 50 } = {}) {
  const { skus } = await getProductApi(request, productId)
  const stock = skus[rowIndex]?.stock ?? 0
  if (stock >= need) return false
  await adminSetSkuStock(page, productId, rowIndex, need)
  return true
}

/* ------------------------------------------------------------------ 清理（只动本用例自造的行） */

/**
 * 清掉本用例自造的一次性订单：**先断守卫依据，再走真实删除接口**。
 *
 * 为什么不在这里手写「删明细 + 删台账 + 补库存」：
 *   · 补库存、清台账本来就是产品代码 `purge()` 在做的（按台账净额回补），
 *     测试再实现一遍就是两份口径，早晚对不上；
 *   · 直接裸 SQL 删订单还会把「扣了库存却没补」的窟窿留在库里（本仓库真踩过）。
 *
 * 所以清理只做一件事：把三条守卫的依据断掉（只落在本用例自造的那几行上），
 * 然后让真实接口去删 —— 库存回补与台账清理由产品代码负责。
 */
async function cleanupSelfMadeOrder(request, { orderId, orderSn, token }) {
  const id = orderId
    || (orderSn ? Number(queryDb(`select id from \`order\` where order_sn = '${orderSn}'`) || 0) : 0)
  if (!id) return
  // 用例一自己就把单删掉了：这里没什么可清，别去调接口换一条「订单不存在」的噪声告警
  if (dbCount(`select count(*) from \`order\` where id = ${id}`) === 0) return
  try {
    queryDb(`delete from delivery_logistics where delivery_id in
               (select id from delivery_order where order_id = ${id})`)
    queryDb(`delete from return_apply where order_id = ${id}`)
    queryDb(`update delivery_order set status = 0 where order_id = ${id} and status <> 9`)
    const headers = { Authorization: `Bearer ${token}` }
    const res = await request.delete(`${API}/admin/orders/${id}`, { headers })
    const body = await res.json()
    if (body.code !== 0) {
      // 不假装清理干净：把真实原因打出来，避免「数据越攒越多但没人知道」
      console.warn(`[8-订单删除守卫] 清理未完成（orderId=${id}）：${body.message}`)
    }
  } catch (e) {
    console.warn(`[8-订单删除守卫] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

/** 清掉用例三自造的买家账号（走接口删更快也更稳；页面操作列第四个其实也有删除入口，
 *  但那是给用户的高危确认流程，收尾不需要走 UI。只删本用例建的那个登录名） */
async function cleanupSelfMadeMember(request, token, username) {
  if (!username) return
  try {
    const members = await listMembersApi(request, token, username)
    for (const m of members) await deleteMemberApi(request, token, m.id)
  } catch (e) {
    console.warn(`[8-订单删除守卫] 清理会员未完成（${username}）：${e.message}`)
  }
}

/**
 * 点「删除」并抓住预演阶段的真实响应：被守卫拦下时，前端是「toast + 弹窗自己收起」，
 * 所以既要拿到响应体里的原文，也要断言 UI 上确实没进到「确认执行」。
 * @returns {Promise<{status:number, body:any, toastText:string}>}
 */
async function clickDeleteExpectBlocked(page, { orderSn, orderId }) {
  const previewResp = page.waitForResponse(
    (r) => r.request().method() === 'DELETE'
      && r.url().includes(`/admin/orders/${orderId}`)
      && r.url().includes('dryRun'),
    { timeout: 20_000 }
  )
  await page.locator('.el-table__body tr').filter({ hasText: orderSn }).first()
    .getByTestId('order-row-delete').click()
  const resp = await previewResp
  const body = await resp.json()

  await expectToastError(page, '预演失败：')
  const toastText = (await page.locator('.el-message--error').first().innerText()).trim()
  // 预演失败 → 确认弹窗自己收起：用户根本没机会点「确认执行」
  await expect(dangerDialog(page), '被守卫拦下时确认弹窗应收起（不会再让你确认）').toBeHidden({ timeout: 15_000 })
  return { status: resp.status(), body, toastText }
}

/**
 * 重建后端 `blockReason` 拼出来的那句原文（用于逐字比对）。
 *
 * 抄自 `OrderPurgeService#blockReason`：
 *   三条依据全都不成立 → null（可删）
 *   只有「已出库包裹」这一条 → 专用句：`该订单有 N 个包裹已出库（货已离开仓库），不能删除；请先完成签收或改为关闭订单〔订单 SN，id=N〕`
 *   其余（含并存）→ `该订单 SN（id=N）有 A和 B和 C，无法删除<hint>`，
 *     A=`N 条物流记录（运单号 X、Y）`、B=`N 条退货申请`、C=`N 个包裹已出库（货已离开仓库）`，
 *     hint 优先级：有物流 → 「；可在履约页先作废物流，或改为关闭订单。」（退货/出库并存时也用它）
 */
function expectedBlockReason({ orderSn, orderId, logistics, trackNos, returns, outbound }) {
  if (outbound > 0 && logistics === 0 && returns === 0) {
    return `该订单有 ${outbound} 个包裹已出库（货已离开仓库），不能删除；请先完成签收或改为关闭订单`
      + `〔订单 ${orderSn}，id=${orderId}〕`
  }
  const parts = []
  if (logistics > 0) parts.push(`${logistics} 条物流记录（运单号 ${trackNos}）`)
  if (returns > 0) parts.push(`${returns} 条退货申请`)
  if (outbound > 0) parts.push(`${outbound} 个包裹已出库（货已离开仓库）`)
  const hint = logistics > 0
    ? '；可在履约页先作废物流，或改为关闭订单。'
    : outbound > 0 ? '；请先完成签收或改为关闭订单。'
      : '；退货申请是退款与对账的证据，不能随订单一并删除，请改为关闭订单。'
  return `该订单 ${orderSn}（id=${orderId}）有 ${parts.join('和 ')}，无法删除${hint}`
}

test.describe('业务 › 商家-履约 › 订单删除守卫', () => {
  test('用例一（对照）：干净订单可以删干净，明细/台账一并清掉且库存按台账净额回补', async ({ page, request }) => {
    test.setTimeout(180_000)
    let orderSn = null
    let orderId = null
    let skuId = null
    let stockAfterCreate = null
    let token = null
    const QTY = 2
    try {
      await test.step('① 管理员登录（库存不够时顺手备货）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
      })

      let productName
      await test.step('② 后台「新增订单」造一笔待审核订单（只有订单+明细，没有发货单/物流/退货）', async () => {
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`del-guard-clean-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: QTY }]
        })
        const order = await getAdminOrderBySnApi(request, token, orderSn)
        orderId = order.id
        // 扣的是哪个规格以订单明细为准（表单里 skuIndex=0 对应哪个规格由后端顺序决定，
        // 不在测试里假设它是 portal 接口返回的 skus[0]）
        skuId = Number(queryDb(
          `select product_sku_id from order_item where order_id = ${orderId} order by id limit 1`
        ))
        await page.waitForTimeout(400)   // 等展示缓存回写（分桶模式下 sku.stock 是缓存）
        stockAfterCreate = await skuStockOf(request, 1, skuId)
      })

      await test.step('③ 前提事实：这单确实「干净」（三条守卫依据都不成立），且建单记了 -2 的台账', async () => {
        expect(parcelsOf(orderId), '新增订单不该有发货单').toBe(0)
        expect(logisticsRowsOf(orderId), '新增订单不该有物流记录').toBe(0)
        expect(returnsOf(orderId), '新增订单不该有退货申请').toBe(0)
        expect(outboundParcelsOf(orderId), '新增订单不该有已出库包裹').toBe(0)
        expect(itemsOf(orderId), '这单应有 1 条订单明细').toBe(1)
        // 建单即扣库存并写 ORDER_CREATE 台账；「删单回补」的基准就是这个净额
        expect(ledgerNetOf(orderId, skuId), `建单应记一行净额 -${QTY} 的台账（回补基准）`).toBe(-QTY)
      })

      await test.step('④ 点「删除」→ 预演说明将删什么 → 确认执行 → 提示「订单已删除」', async () => {
        const row = await adminSearchOrderRow(page, orderSn)
        await row.getByTestId('order-row-delete').click()
        await confirmDanger(page, { willDo: '将删除订单' })
        await expectToastSuccess(page, '订单已删除')
      })

      await test.step('⑤ 列表里这行真的消失（页面事实）', async () => {
        await expect(
          page.locator('.el-table__body tr').filter({ hasText: orderSn }),
          '删除后订单列表里不应再有这一行'
        ).toHaveCount(0)
      })

      await test.step('⑥ 库：订单 / 明细 / 台账 / 发货单 / 流水 一条不剩（不是只看页面）', async () => {
        expect(orderRowsOf(orderSn), '库里该订单应 0 行').toBe(0)
        expect(itemsOf(orderId), '订单明细应一并清掉').toBe(0)
        expect(ledgerRowsOf(orderId), '库存台账应一并清掉（不留下孤儿台账）').toBe(0)
        expect(parcelsOf(orderId), '不应留下孤儿发货单').toBe(0)
        expect(logisticsRowsOf(orderId), '不应留下孤儿物流记录').toBe(0)
        expect(paymentsOf(orderSn), '不应留下孤儿支付流水').toBe(0)
      })

      await test.step('⑦ 库存按台账净额回补：删完比下单后多 2 件（台账净额的绝对值）', async () => {
        await expect.poll(() => skuStockOf(request, 1, skuId), {
          timeout: 5_000,
          intervals: [100, 200, 400],
          message: `删单应按台账净额回补 ${QTY} 件（下单后 ${stockAfterCreate} → 应回到 `
            + `${stockAfterCreate + QTY}）`
        }).toBe(stockAfterCreate + QTY)
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二：有物流记录（WMS 已回传出库）的订单删不掉，物流行没被误删，预演与真删同口径', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let token = null
    let blockedMessage = null
    let trackNoOfOrder = null
    try {
      let productName
      await test.step('① 管理员登录（库存不够时顺手备货）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
      })

      await test.step('② 造一笔待审核订单 → 审核通过（生成 1 个待下发包裹）', async () => {
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`del-guard-logi-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        const order = await getAdminOrderBySnApi(request, token, orderSn)
        orderId = order.id
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
      })

      await test.step('③ 下发 → 模拟 WMS 回传出库（这一步才写运单号）', async () => {
        const table = await openOrderDrawer(page, orderSn)
        const dRow = table.locator('.el-table__body tr').first()
        await clickDispatch(page, dRow)
        await clickWmsCallback(page, dRow, '出库', '已出库')
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '出库后订单应为已发货(2)').toBe(2)
      })

      await test.step('④ 前提事实：库里有 1 条物流记录（含运单号），包裹已出库', async () => {
        expect(logisticsRowsOf(orderId), '出库回传应写 1 条物流记录').toBe(1)
        expect(trackNosOf(orderId), '物流记录应带运单号（SF 开头）').toMatch(/SF\d{6,}/)
        trackNoOfOrder = trackNosOf(orderId)
        expect(outboundParcelsOf(orderId), '包裹应已出库').toBeGreaterThanOrEqual(1)
      })

      await test.step('⑤ 点「删除」→ 预演阶段就被拒，确认弹窗收起（走不到真删）', async () => {
        await adminSearchOrderRow(page, orderSn)
        const blocked = await clickDeleteExpectBlocked(page, { orderSn, orderId })
        expect(blocked.status, '预演请求本身是 HTTP 200（业务失败用 code 表达，不是 5xx）').toBe(200)
        expect(blocked.body.code, '守卫拒绝的业务码是 1').toBe(1)
        blockedMessage = blocked.body.message
        // UI 上的提示就是后端原话，一个字都不加工（只加固定前缀「预演失败：」）
        expect(blocked.toastText, 'toast 应是「预演失败：」+ 后端拒因原文')
          .toBe(`预演失败：${blockedMessage}`)
      })

      await test.step('⑥ 拒因原文的关键词都在：物流记录 / 运单号 / 无法删除 / 出路', async () => {
        const trackNo = trackNosOf(orderId)
        expect(blockedMessage, '拒因要点明是「物流记录」').toContain('物流记录')
        expect(blockedMessage, `拒因要带上运单号（用户据此能查这张运单）：${trackNo}`).toContain(trackNo)
        expect(blockedMessage, '拒因要说清「无法删除」').toContain('无法删除')
        expect(blockedMessage, '拒因要给出路：先作废物流，或改为关闭订单')
          .toContain('可在履约页先作废物流，或改为关闭订单')
        // 与后端拼装逻辑逐字一致（一字不差地钉住文案与判断口径）
        expect(blockedMessage, '拒因应逐字等于 blockReason 拼出来的那句原文').toBe(expectedBlockReason({
          orderSn,
          orderId,
          logistics: logisticsRowsOf(orderId),
          trackNos: trackNo,
          returns: returnsOf(orderId),
          outbound: outboundParcelsOf(orderId)
        }))
      })

      await test.step('⑦ 被引用的数据没被误删：订单 1 行、物流 1 行、运单号没变、包裹还在', async () => {
        const trackNoBefore = trackNosOf(orderId)
        expect(orderRowsOf(orderSn), '订单必须还在（被拒删不能顺手删单）').toBe(1)
        expect(logisticsRowsOf(orderId), '物流记录必须还在（删单不能把运单号一起销毁）').toBe(1)
        expect(trackNosOf(orderId), '运单号一个字都不能变').toBe(trackNoBefore)
        expect(parcelsOf(orderId), '发货单必须还在').toBeGreaterThanOrEqual(1)
        expect(
          (await getAdminOrderBySnApi(request, token, orderSn)).id,
          '订单仍可按单号查到'
        ).toBe(orderId)
      })

      await test.step('⑦b 界面证据：抽屉里那个包裹还在，仍挂着同一个运单号（被拒后数据没变，看得见）', async () => {
        const table = await openOrderDrawer(page, orderSn)
        const parcelRow = table.locator('.el-table__body tr').first()
        await expect(parcelRow, '抽屉里这个包裹仍应是「已出库」').toContainText('已出库')
        await expect(parcelRow, '运单号仍挂在这个包裹上（没被删单带走）').toContainText(trackNoOfOrder)
        // 抽屉故意不关：留下来当「被拒后数据没变」的屏幕证据（顺带也是录片的最后一帧）
      })

      await test.step('⑧ 再预演一次（接口 dryRun）→ 拒因与上一条逐字相同', async () => {
        const again = await request.delete(`${API}/admin/orders/${orderId}?dryRun=true`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        const againBody = await again.json()
        expect(againBody.code, '预演再跑一次仍是被守卫拒绝').toBe(1)
        expect(againBody.message, '预演与上一次逐字相同（同一份 blockReason）').toBe(blockedMessage)
      })

      await test.step('⑨ 真删一次（不带 dryRun）→ 仍被同一条守卫拒绝，库里那条行一条没少', async () => {
        // 这一步在 UI 上没有入口（前端预演就停了），只能从接口触发；
        // 它验的是后端 purge() 内部那道 fail-closed 守卫：预演说不能删，真删一定不能删。
        const real = await request.delete(`${API}/admin/orders/${orderId}`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        const realBody = await real.json()
        expect(realBody.code, '真删也被拒（业务码 1）').toBe(1)
        expect(realBody.message, '真删的拒因与预演逐字相同（同一条守卫）').toBe(blockedMessage)
        expect(orderRowsOf(orderSn), '真删被拒后订单必须完好').toBe(1)
        expect(logisticsRowsOf(orderId), '真删被拒后物流记录必须完好').toBe(1)
        expect(itemsOf(orderId), '真删被拒后订单明细必须完好').toBeGreaterThanOrEqual(1)
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三：有退货申请的订单删不掉，退货申请作为证据没被销毁', async ({ page, request }) => {
    test.setTimeout(300_000)
    let orderSn = null
    let orderId = null
    let username = null
    let token = null
    let blockedMessage = null
    try {
      let skuId
      let productName
      await test.step('① 管理员登录（库存不够时顺手备货）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        const { product, skus } = await getProductApi(request, 1)
        skuId = skus[0].id
        productName = product.name
      })

      await test.step('② 新买家注册 → 加购 → 结算支付（走到「待审核」）', async () => {
        username = memberLoginOf(stamp())
        await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
        const { skus } = await getProductApi(request, 1)
        await addSpecToCart(page, 1, skus[0].specText, skuId)
        orderSn = await submitCartOrder(page, receiverOf(username))
        const order = await getAdminOrderBySnApi(request, token, orderSn)
        orderId = order.id
        expect(order.status, '支付后订单应为待审核(7)').toBe(7)
      })

      await test.step('③ 管理员审核通过 → 下发 → WMS 出库（有申请退货入口）', async () => {
        await gotoOrderList(page)
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        const table = await openOrderDrawer(page, orderSn)
        const dRow = table.locator('.el-table__body tr').first()
        await clickDispatch(page, dRow)
        await clickWmsCallback(page, dRow, '出库', '已出库')
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '出库后订单应为已发货(2)').toBe(2)
      })

      await test.step('④ 买家申请退货 → 生成 1 条退货申请，订单转退款中', async () => {
        await memberApplyReturn(page, orderSn, { description: returnDescriptionOf(orderSn) })
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '申请退货后订单应为退款中(5)').toBe(5)
        expect(returnsOf(orderId), '库里应有 1 条退货申请').toBe(1)
      })

      await test.step('⑤ 点「删除」→ 被拒，文案含「退货申请」', async () => {
        await adminSearchOrderRow(page, orderSn)
        const blocked = await clickDeleteExpectBlocked(page, { orderSn, orderId })
        expect(blocked.body.code, '守卫拒绝的业务码是 1').toBe(1)
        expect(blocked.toastText, 'toast 应是「预演失败：」+ 后端拒因原文')
          .toBe(`预演失败：${blocked.body.message}`)
        blockedMessage = blocked.body.message
        expect(blockedMessage, '拒因必须点明「退货申请」。实际原文：' + blockedMessage)
          .toContain('退货申请')
        expect(blockedMessage, '拒因要说清「无法删除」').toContain('无法删除')
        expect(blockedMessage, '拒因要与 blockReason 拼出来的原文逐字一致').toBe(expectedBlockReason({
          orderSn,
          orderId,
          logistics: logisticsRowsOf(orderId),
          trackNos: trackNosOf(orderId),
          returns: returnsOf(orderId),
          outbound: outboundParcelsOf(orderId)
        }))
      })

      await test.step('⑥ 证据链没被销毁：订单还在、退货申请还在（仍是待处理）、物流行还在', async () => {
        expect(orderRowsOf(orderSn), '订单必须还在').toBe(1)
        expect(returnsOf(orderId), '退货申请必须还在（它是退款与对账的证据）').toBe(1)
        expect(
          Number(queryDb(`select max(status) from return_apply where order_id = ${orderId}`)),
          '退货申请状态不应被删除动作改动（仍是待处理 0）'
        ).toBe(0)
        expect(logisticsRowsOf(orderId), '物流记录必须还在').toBe(1)
      })

      await test.step('⑥b 界面证据：订单详情里那条退货申请还在（仍是待处理），不是空状态', async () => {
        await openOrderDrawer(page, orderSn)
        const drawer = page.getByTestId('order-detail-drawer')
        await expect(drawer.getByText('退货申请', { exact: true }).first(),
          '抽屉里应仍有「退货申请」区块').toBeVisible()
        await expect(drawer, '退货申请区块不能变成空状态（无退货申请）').not.toContainText('无退货申请')
        await expect(drawer, '那条申请仍应是「待处理」').toContainText('待处理')
        // 抽屉故意不关：留下来当「证据链没被销毁」的屏幕证据
      })

      await test.step('⑦ 真删一次（不带 dryRun）→ 同样被拒，证据链仍然完整', async () => {
        const real = await request.delete(`${API}/admin/orders/${orderId}`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        const realBody = await real.json()
        expect(realBody.code, '真删被拒（业务码 1）').toBe(1)
        expect(realBody.message, '真删的拒因与预演逐字相同').toBe(blockedMessage)
        expect(orderRowsOf(orderSn), '真删被拒后订单仍在').toBe(1)
        expect(returnsOf(orderId), '真删被拒后退货申请仍在').toBe(1)
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
      await cleanupSelfMadeMember(request, token, username)
    }
  })

  test('用例四：包裹已出库但物流未回传的订单删不掉，包裹没被误删', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let token = null
    let blockedMessage = null
    let parcelCountBefore = 0
    try {
      let productName
      await test.step('① 管理员登录（库存不够时顺手备货）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
      })

      await test.step('② 造一笔待审核订单 → 审核通过（生成待下发包裹）', async () => {
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`del-guard-out-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        const order = await getAdminOrderBySnApi(request, token, orderSn)
        orderId = order.id
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        parcelCountBefore = parcelsOf(orderId)
        expect(parcelCountBefore, '审核通过应生成待下发包裹').toBeGreaterThanOrEqual(1)
      })

      await test.step('③ 复现「已出库但物流缺行」：把本用例自造的包裹推进到已出库，不写运单', async () => {
        // 为什么要改库：真实链路里「出库回传」必然同时写运单
        // （DeliveryFlowService 出库事件里 saveLogisticsIfAbsent），所以「已出库却没有物流行」
        // 只会由历史数据缺行造成 —— 正是这条守卫最容易被误判成「能删」的那一格。
        // 这里只推进本用例自造的那一行，不碰任何种子数据，finally 里连订单一起清掉。
        queryDb(`update delivery_order set status = 3 where order_id = ${orderId}`)
        expect(outboundParcelsOf(orderId), '包裹应为已出库(3)').toBe(1)
        expect(logisticsRowsOf(orderId), '前提：仍然没有物流回传行').toBe(0)
        expect(returnsOf(orderId), '前提：没有退货申请').toBe(0)
      })

      await test.step('④ 点「删除」→ 被拒，文案含「已出库」并给出「完成签收 / 改为关闭」的出路', async () => {
        await adminSearchOrderRow(page, orderSn)
        const blocked = await clickDeleteExpectBlocked(page, { orderSn, orderId })
        expect(blocked.body.code, '守卫拒绝的业务码是 1').toBe(1)
        expect(blocked.toastText, 'toast 应是「预演失败：」+ 后端拒因原文')
          .toBe(`预演失败：${blocked.body.message}`)
        blockedMessage = blocked.body.message
        expect(blockedMessage, '拒因必须点明「已出库」').toContain('已出库')
        expect(blockedMessage, '拒因要说清「不能删除」').toContain('不能删除')
        expect(blockedMessage, '拒因要给出路：请先完成签收或改为关闭订单')
          .toContain('请先完成签收或改为关闭订单')
        expect(blockedMessage, '这一格（只有已出库、无物流）走的是专用文案，不该提到物流记录')
          .not.toContain('物流记录')
        expect(blockedMessage, '拒因要与 blockReason 拼出来的原文逐字一致').toBe(expectedBlockReason({
          orderSn,
          orderId,
          logistics: 0,
          trackNos: '',
          returns: 0,
          outbound: outboundParcelsOf(orderId)
        }))
      })

      await test.step('⑤ 订单还在、包裹还在（没有被误删）', async () => {
        expect(orderRowsOf(orderSn), '订单必须还在').toBe(1)
        expect(parcelsOf(orderId), '包裹必须还在（一个都不能少）').toBe(parcelCountBefore)
        expect(
          Number(queryDb(`select max(status) from delivery_order where order_id = ${orderId}`)),
          '包裹状态不应被删除动作改动（仍是已出库 3）'
        ).toBe(3)
      })

      await test.step('⑥ 真删一次（不带 dryRun）→ 同样被拒，包裹仍然完好', async () => {
        const real = await request.delete(`${API}/admin/orders/${orderId}`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        const realBody = await real.json()
        expect(realBody.code, '真删被拒（业务码 1）').toBe(1)
        expect(realBody.message, '真删的拒因与预演逐字相同').toBe(blockedMessage)
        expect(orderRowsOf(orderSn), '真删被拒后订单仍在').toBe(1)
        expect(parcelsOf(orderId), '真删被拒后包裹仍在').toBe(parcelCountBefore)
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })
})
