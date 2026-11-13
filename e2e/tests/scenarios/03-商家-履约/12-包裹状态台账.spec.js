import { test, expect } from '../../support/test.js'
import { API, ADMIN, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { tid } from '../../support/tid.js'
import { receiverOf } from '../../support/fixtures.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  createOrderViaUi,
  auditPassOrder,
} from '../../support/admin-ui.js'
import { adminSetSkuStock, openOrderDrawer, clickDispatch, clickWmsCallback } from '../../support/ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../support/assert.js'

/**
 * 03-商家-履约 / 12-包裹状态台账
 *
 * 这个场景要证明的一件事：
 *   **一头包裹自己的每一次流转都留了一行，行里带着包裹单号的快照 ——
 *     所以订单被物理删除、包裹行跟着消失之后，单号与「它到过哪一步」仍然读得出来。**
 *
 * 一行 = 一次流转：前态 / 后态 / 原因码 / 操作人 / 触发单据 / 幂等键 = 包裹ID:流转序号。
 * 原因码序列：拆单出生 → 下发 → 拣货 / 出库 / 揽收 / 签收（仓库回传）→ 取消。
 *
 * ── 台账里的两个已登记冗余列（本场景的关键）──────────────────────────────
 *   ① {@code delivery_sn} 快照：包裹行被 OrderPurgeService 物理删除后，单号只留在台账里；
 *   ② {@code order_id} 冗余：包裹行一删，`delivery_order` 这座「台账 → 订单」的桥就断了 ——
 *      没有这一列，删单之后就再也查不出「这张订单的几个包裹是怎么走的」。
 *   台账接口的包裹分支正是靠 ② 实现「按订单 id 查包裹流水」（见 LedgerSql.DELIVERY_STATUS_FILTER），
 *   页面的「业务单号」列消费的也是 ① （后端在包裹分支把 delivery_sn 映射进 bizSn）。
 *
 * 三条用例：
 *   ① 正常链路：出生 → 下发 → 拣货 → 出库，四步四行，每行都带单号快照与订单ID；
 *   ② 仓库重投同一个回传事件：业务侧挡在前面，台账一行都不多（事件号是第二道闸）；
 *   ③ 订单被物理删除之后：包裹行没了，台账一行不少，按订单 id 仍查得到、仍认得出单号。
 *
 * ── 为什么直查库 ──────────────────────────────────────────────────────────
 *   「每一行的 delivery_sn / order_id 都不是空」「幂等键 = 包裹ID:序号，序号互不相同」
 *   这类事实页面显示不出来（台账页只有时间/类型/单号/变更/原因/操作人/摘要）。
 *   页面负责证明「人看得见」，库负责证明「写对了」。
 */

/* ------------------------------------------------------------------ 台账页（UI 侧） */

const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

/**
 * 台账页：类型「包裹状态」+ 业务ID = **订单 id** → 断言这个包裹的每一行都看得见。
 *
 * 为什么按「行锚点 + 单号」断，而不是断总行数：
 *   包裹分支的 bizId 过滤是 {@code v.biz_id = bizId OR v.order_id = bizId OR ...}（一个参数两种含义），
 *   所以按订单 id 查时，若另有一个「包裹 id 恰好等于这个订单 id」的包裹，它的行也会出现 ——
 *   那是接口的设计（一个输入框同时收订单 id 与包裹 id），不是 bug。
 *   于是这里断三件事：① 本包裹的每一行（按 type-id 锚点）都在；② 业务单号列显示的是单号快照；
 *   ③ 页面上带这个单号的行数 = 库里这个包裹的行数（不多不少，且不被别的包裹的行混进来）。
 */
const expectDeliveryLedgerOnPage = async (page, { orderId, deliverySn, ledgerIds, message }) => {
  await gotoLedger(page)
  await tid(page, 'ledger-type-select').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '包裹状态' }).first().click()
  await tid(page, 'ledger-biz-id-input').fill(String(orderId))
  await tid(page, 'ledger-search-submit').click()

  for (const id of ledgerIds) {
    await expect(
      tid(page, `ledger-row-DELIVERY_STATUS-${id}`),
      `${message}（按订单 ${orderId} 查，包裹 ${deliverySn} 的台账行 ${id} 应可见）`
    ).toBeVisible({ timeout: 20_000 })
  }

  const table = tid(page, 'ledger-list-table')
  // 「业务单号」列必须显示单号快照（不是「—」，也不是「ID 34」）
  await expect(table, `台账页「业务单号」列应显示包裹单号快照 ${deliverySn}`).toContainText(deliverySn)

  await expect.poll(
    () => table.locator('.el-table__body tr').filter({ hasText: deliverySn }).count(),
    { timeout: 20_000, intervals: [400, 800, 1500], message: `页面上带单号 ${deliverySn} 的行数应与库里一致` }
  ).toBe(ledgerIds.length)
}

/* ------------------------------------------------------------------ 库上的事实（只读） */

const dbCount = (sql) => Number(queryDb(sql))

/** 该订单的包裹 id（升序）—— 一行一个包裹 */
const deliveryIdsOf = (orderId) => queryDb(
  `select group_concat(id order by id separator ',') from delivery_order where order_id = ${orderId}`
).split(',').filter(Boolean).map(Number)

const deliveryRowCountOf = (orderId) =>
  dbCount(`select count(*) from delivery_order where order_id = ${orderId}`)

/** 包裹单号（必须是 DL 开头；删单之后这是唯一线索） */
const deliverySnOf = (deliveryId) =>
  queryDb(`select delivery_sn from delivery_order where id = ${deliveryId}`)

/** 包裹状态（0 待下发 / 1 已下发 / 2 已拣货 / 3 已出库 …） */
const deliveryStatusOf = (deliveryId) =>
  Number(queryDb(`select status from delivery_order where id = ${deliveryId}`))

/** 包裹表上的流转序号（与台账幂等键里的序号同源） */
const deliveryStatusSeqOf = (deliveryId) =>
  Number(queryDb(`select status_seq from delivery_order where id = ${deliveryId}`))

/** 该包裹在包裹台账里的行 id（升序）—— 台账页的行锚点用得上 */
const ledgerIdsOf = (deliveryId) => queryDb(
  `select group_concat(id order by id separator ',') from delivery_status_ledger where delivery_id = ${deliveryId}`
).split(',').filter(Boolean).map(Number)

/** 该包裹在包裹台账里的行数 */
const ledgerRowsOf = (deliveryId) =>
  dbCount(`select count(*) from delivery_status_ledger where delivery_id = ${deliveryId}`)

/** 原因码序列 */
const reasonsOf = (deliveryId) => queryDb(
  `select coalesce(group_concat(reason order by id separator '、'), '')
     from delivery_status_ledger where delivery_id = ${deliveryId}`
)

/** 状态序列：`前态→后态`，出生行前态记作「空」 */
const flowsOf = (deliveryId) => queryDb(
  `select coalesce(group_concat(concat(coalesce(from_status, '空'), '→', to_status) order by id separator '、'), '')
     from delivery_status_ledger where delivery_id = ${deliveryId}`
)

/** 单号快照序列（台账里每一行都该带着它） */
const snSnapshotsOf = (deliveryId) => queryDb(
  `select coalesce(group_concat(delivery_sn order by id separator '、'), '')
     from delivery_status_ledger where delivery_id = ${deliveryId}`
)

/** 幂等键列表（每行一个）—— 格式 包裹ID:流转序号 */
const idemKeysOf = (deliveryId) => queryDb(
  `select idem_key from delivery_status_ledger where delivery_id = ${deliveryId} order by id`
).split('\n').filter(Boolean)

/** 流转序号序列（从幂等键尾段取） */
const seqsOf = (deliveryId) => queryDb(
  `select coalesce(group_concat(substring_index(idem_key, ':', -1) order by id separator '、'), '')
     from delivery_status_ledger where delivery_id = ${deliveryId}`
)

/** 某条原因那一行的关键列 */
const rowOfReason = (deliveryId, reason) => {
  const [fromStatus, toStatus, operatorType, operatorName, refType, refId] = queryDb(
    `select coalesce(from_status, 'NULL'), to_status, operator_type, coalesce(operator_name, ''),
            coalesce(ref_type, 'NULL'), coalesce(ref_id, 'NULL')
       from delivery_status_ledger where delivery_id = ${deliveryId} and reason = '${reason}'
      order by id limit 1`
  ).split('\t')
  return { fromStatus, toStatus, operatorType, operatorName, refType, refId }
}

/** 台账里 order_id 冗余列指对这个订单的行数（「按订单反查」能成立的全部依据） */
const ledgerRowsLinkedToOrder = (deliveryId, orderId) =>
  dbCount(`select count(*) from delivery_status_ledger
            where delivery_id = ${deliveryId} and order_id = ${orderId}`)

/** 该订单的物流/退货行数（删单守卫的依据，用例三要先把它们断掉） */
const logisticsCountOf = (orderId) => dbCount(
  `select count(*) from delivery_logistics
    where delivery_id in (select id from delivery_order where order_id = ${orderId})`
)

const returnApplyCountOf = (orderId) =>
  dbCount(`select count(*) from return_apply where order_id = ${orderId}`)

/* ------------------------------------------------------------------ 备货 / 清理 */

async function ensureStock(page, request, { productId = 1, rowIndex = 0, need = 50 } = {}) {
  const { skus } = await getProductApi(request, productId)
  if ((skus[rowIndex]?.stock ?? 0) >= need) return false
  await adminSetSkuStock(page, productId, rowIndex, need)
  return true
}

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
    if (body.code !== 0) console.warn(`[12-包裹状态台账] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[12-包裹状态台账] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

/** 造单 + 审核通过：得到一个「待下发」的包裹，其出生行已落台账 */
async function makeOrderWithDelivery(page, request, tag) {
  await adminLoginHardened(page)
  const token = await adminTokenOf(page)
  await ensureStock(page, request)
  const productName = (await getProductApi(request, 1)).product.name
  await gotoOrderList(page)
  const orderSn = await createOrderViaUi(page, {
    receiver: receiverOf(`${tag}-${stamp()}`),
    status: '待审核',
    items: [{ productName, skuIndex: 0, quantity: 1 }]
  })
  const orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
  await auditPassOrder(page, await searchOrderRow(page, orderSn))
  const deliveryIds = deliveryIdsOf(orderId)
  expect(deliveryIds.length, `审核通过应为订单 ${orderId} 生成一个包裹，实际 ${deliveryIds.length} 个`).toBe(1)
  return { token, orderSn, orderId, deliveryId: deliveryIds[0] }
}

/** 下发 + 仓库回传拣货 + 出库（真实点击），把包裹推到「已出库」 */
async function pushDeliveryToOutbound(page, orderSn) {
  const table = await openOrderDrawer(page, orderSn)
  await clickDispatch(page, table.locator('.el-table__body tr').first())
  await clickWmsCallback(page, tid(page, 'order-detail-delivery-table').locator('.el-table__body tr').first(),
    '拣货', '已拣货')
  await clickWmsCallback(page, tid(page, 'order-detail-delivery-table').locator('.el-table__body tr').first(),
    '出库', '已出库')
}

test.describe('03-商家-履约 › 12-包裹状态台账', () => {
  test('用例一：一头包裹从拆单到出库，每一步一行，且每行都带单号快照与订单ID', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let deliveryId = null
    let token = null
    let deliverySn = null
    let ledgerIds = []
    try {
      await test.step('① 管理员登录 + 造单 + 审核通过（拆单出生一个待下发包裹）', async () => {
        const made = await makeOrderWithDelivery(page, request, 'delivery-ledger')
        token = made.token
        orderSn = made.orderSn
        orderId = made.orderId
        deliveryId = made.deliveryId
        deliverySn = deliverySnOf(deliveryId)
        expect(deliverySn, '包裹单号应有值（DL 开头）').toMatch(/^DL\d+/)
      })

      await test.step('② 库：出生只有一行 —— 前态空、后态待下发、原因拆单出生、快照与订单ID都在', async () => {
        expect(ledgerRowsOf(deliveryId), '刚出生的包裹只能有一行台账（出生）').toBe(1)
        const birth = rowOfReason(deliveryId, 'DELIVERY_CREATE')
        expect(birth.fromStatus, '出生行的前态必须是空（不是 0，0 是「待下发」这个真实状态）').toBe('NULL')
        expect(Number(birth.toStatus), '出生行的后态是待下发(0)').toBe(0)
        expect(birth.operatorType, '出生是管理员审核通过触发的').toBe('ADMIN')
        expect(birth.operatorName, '出生行必须留下操作人快照（页面上那一列不能是空的）').not.toBe('')
        expect(snSnapshotsOf(deliveryId), '出生行就要带单号快照（删单后这是唯一线索）').toBe(deliverySn)
        expect(ledgerRowsLinkedToOrder(deliveryId, orderId),
          '出生行的 order_id 冗余列必须指对这个订单（否则删单后按订单查不到）').toBe(1)
        expect(idemKeysOf(deliveryId)[0], '出生行的幂等键是「包裹ID:0」').toBe(`${deliveryId}:0`)
      })

      await test.step('③ UI 下发 → 库里多一行「待下发→已下发」，原因 DELIVERY_DISPATCH', async () => {
        const table = await openOrderDrawer(page, orderSn)
        await clickDispatch(page, table.locator('.el-table__body tr').first())
        expect(deliveryStatusOf(deliveryId), '下发后包裹应为已下发(1)').toBe(1)
        expect(ledgerRowsOf(deliveryId), '下发只留一行').toBe(2)
        expect(reasonsOf(deliveryId), '原因码序列应为「拆单出生 → 下发」').toBe('DELIVERY_CREATE、DELIVERY_DISPATCH')
        expect(flowsOf(deliveryId), '第二条流转必须是 0→1').toBe('空→0、0→1')
        expect(rowOfReason(deliveryId, 'DELIVERY_DISPATCH').operatorType, '下发是管理员点的').toBe('ADMIN')
        expect(ledgerRowsLinkedToOrder(deliveryId, orderId), '流转行的 order_id 也必须写对').toBe(2)
      })

      await test.step('④ UI 回传「拣货」→ 多一行 1→2（原因 WMS_PICK）', async () => {
        await clickWmsCallback(page, tid(page, 'order-detail-delivery-table').locator('.el-table__body tr').first(),
          '拣货', '已拣货')
        expect(deliveryStatusOf(deliveryId), '回传拣货后包裹应为已拣货(2)').toBe(2)
        expect(ledgerRowsOf(deliveryId), '拣货只留一行').toBe(3)
        expect(reasonsOf(deliveryId)).toBe('DELIVERY_CREATE、DELIVERY_DISPATCH、WMS_PICK')
      })

      await test.step('⑤ UI 回传「出库」→ 多一行 2→3（原因 WMS_OUTBOUND）', async () => {
        await clickWmsCallback(page, tid(page, 'order-detail-delivery-table').locator('.el-table__body tr').first(),
          '出库', '已出库')
        expect(deliveryStatusOf(deliveryId), '回传出库后包裹应为已出库(3)').toBe(3)
        expect(ledgerRowsOf(deliveryId), '出库只留一行').toBe(4)
        expect(reasonsOf(deliveryId), '四步四行，一行不多一行不少')
          .toBe('DELIVERY_CREATE、DELIVERY_DISPATCH、WMS_PICK、WMS_OUTBOUND')
        expect(flowsOf(deliveryId), '状态链必须是 空→0 → 1 → 2 → 3').toBe('空→0、0→1、1→2、2→3')
      })

      await test.step('⑥ 库：每行都带同一个单号快照；幂等键序号互不相同且与包裹的 status_seq 一致', async () => {
        const snaps = snSnapshotsOf(deliveryId).split('、')
        expect(snaps.length, '四行四份快照').toBe(4)
        expect(snaps.every((s) => s === deliverySn), `每一行的单号快照都必须等于 ${deliverySn}，实际：${snaps.join('、')}`).toBe(true)

        const keys = idemKeysOf(deliveryId)
        expect(keys.length, '四行四把键').toBe(4)
        for (const k of keys) {
          expect(k, `幂等键应是「包裹ID:流转序号」，实际：${k}`).toMatch(new RegExp(`^${deliveryId}:\\d+$`))
        }
        expect(new Set(keys).size, '四把键不能有重复（重复 = 同一流转被记了两次）').toBe(4)
        expect(seqsOf(deliveryId), '流转序号必须从 0 起连续').toBe('0、1、2、3')
        expect(deliveryStatusSeqOf(deliveryId), '包裹表上的 status_seq 应与台账最后一条序号一致').toBe(3)
      })

      await test.step('⑦ 台账页：类型「包裹状态」+ 订单 id → 四行，业务单号列显示单号快照', async () => {
        ledgerIds = ledgerIdsOf(deliveryId)
        expect(ledgerIds.length, '库里有四行').toBe(4)
        await expectDeliveryLedgerOnPage(page, {
          orderId,
          deliverySn,
          ledgerIds,
          message: '按订单 id 应能在台账页看到这个包裹的四步'
        })
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二：仓库重投同一个回传事件，台账一行都不多 —— 事件号是第二道闸', async ({ page, request }) => {
    test.setTimeout(300_000)
    let orderSn = null
    let orderId = null
    let deliveryId = null
    let token = null
    let deliverySn = null
    try {
      await test.step('① 造单 → 审核通过 → 下发 → 拣货 → 出库（此时台账四行）', async () => {
        const made = await makeOrderWithDelivery(page, request, 'delivery-ledger-replay')
        token = made.token
        orderSn = made.orderSn
        orderId = made.orderId
        deliveryId = made.deliveryId
        deliverySn = deliverySnOf(deliveryId)
        await pushDeliveryToOutbound(page, orderSn)
        expect(ledgerRowsOf(deliveryId), '前提：四步四行').toBe(4)
      })

      await test.step('② 从接口把同一个出库事件重放两次（并发/重投的真实形状）', async () => {
        for (const round of [1, 2]) {
          const res = await request.post(
            `${API}/admin/deliveries/${deliveryId}/wms-callback?event=outbound`,
            { headers: { Authorization: `Bearer ${token}` } }
          )
          const body = await res.json()
          expect(body.code, `第 ${round} 次重放应被当作幂等直接返回成功，实际：${body.message}`).toBe(0)
        }
      })

      await test.step('③ 库：行数、原因码、状态、序号全部没变 —— 台账不记「没发生的事」', async () => {
        expect(ledgerRowsOf(deliveryId), '重放两次也不该多一行').toBe(4)
        expect(reasonsOf(deliveryId), '原因码序列不变')
          .toBe('DELIVERY_CREATE、DELIVERY_DISPATCH、WMS_PICK、WMS_OUTBOUND')
        expect(flowsOf(deliveryId), '状态链不变').toBe('空→0、0→1、1→2、2→3')
        expect(deliveryStatusOf(deliveryId), '包裹仍应是已出库，没有被回退').toBe(3)
        expect(deliveryStatusSeqOf(deliveryId), '包裹的 status_seq 没有被重放推高').toBe(3)
        const keys = idemKeysOf(deliveryId)
        expect(new Set(keys).size, `幂等键仍应四把互不相同，实际：${keys.join('、')}`).toBe(4)
      })

      await test.step('④ 台账页：按订单 id 查出来的仍是四行（页面口径与库一致）', async () => {
        await expectDeliveryLedgerOnPage(page, {
          orderId,
          deliverySn,
          ledgerIds: ledgerIdsOf(deliveryId),
          message: '重放之后台账页仍是四行'
        })
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三：订单被物理删除之后，包裹台账里仍读得出单号与它到过的每一步', async ({ page, request }) => {
    test.setTimeout(300_000)
    let orderSn = null
    let orderId = null
    let deliveryId = null
    let token = null
    let deliverySn = null
    let beforeIds = []
    let beforeReasons = ''
    try {
      await test.step('① 造单 → 审核通过 → 下发 → 拣货 → 出库（让包裹走到「已出库」）', async () => {
        const made = await makeOrderWithDelivery(page, request, 'delivery-ledger-deleted')
        token = made.token
        orderSn = made.orderSn
        orderId = made.orderId
        deliveryId = made.deliveryId
        deliverySn = deliverySnOf(deliveryId)
        await pushDeliveryToOutbound(page, orderSn)
        beforeIds = ledgerIdsOf(deliveryId)
        beforeReasons = reasonsOf(deliveryId)
        expect(beforeIds.length, '前提：删单前有四行台账').toBe(4)
        expect(beforeReasons).toBe('DELIVERY_CREATE、DELIVERY_DISPATCH、WMS_PICK、WMS_OUTBOUND')
      })

      await test.step('② 断掉删单守卫的依据（物流行 / 退货申请 / 未取消且已出库的包裹）', async () => {
        // 守卫口径见 OrderPurgeService#blockReason：有物流 / 有退货 / 有未取消且已出库的包裹 → 拒删。
        // 这里不是「绕过守卫」，而是把这张单做成「还没出货」的形状 —— 守卫拒删的理由本身就不成立。
        queryDb(`delete from delivery_logistics
                  where delivery_id in (select id from delivery_order where order_id = ${orderId})`)
        queryDb(`delete from return_apply where order_id = ${orderId}`)
        queryDb(`update delivery_order set status = 0 where order_id = ${orderId} and status <> 9`)
        expect(logisticsCountOf(orderId), '物流行应已清空').toBe(0)
        expect(returnApplyCountOf(orderId), '退货申请应已清空').toBe(0)
      })

      await test.step('③ 真删这张单（DELETE /admin/orders/{id}）', async () => {
        const res = await request.delete(`${API}/admin/orders/${orderId}`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        const body = await res.json()
        expect(body.code, `删单应成功，实际：${body.message}`).toBe(0)
        orderSn = null   // 已经删掉了，finally 不用再清
      })

      await test.step('④ 库：包裹行没了 —— 证据的目标消失了', async () => {
        expect(deliveryRowCountOf(orderId), '订单名下的包裹应被物理删除').toBe(0)
        expect(dbCount(`select count(*) from delivery_order where id = ${deliveryId}`),
          '这个包裹自己也不该留在 delivery_order 里').toBe(0)
      })

      await test.step('⑤ 关键断言：台账一行不少，每行的单号快照与订单ID仍在', async () => {
        expect(ledgerRowsOf(deliveryId), '包裹行没了，但台账一行都不能少').toBe(beforeIds.length)
        expect(ledgerIdsOf(deliveryId), '台账行的 id 也没变（不是删了又补的新行）').toEqual(beforeIds)
        expect(reasonsOf(deliveryId), '它到过的每一步都还读得出来').toBe(beforeReasons)

        const snaps = snSnapshotsOf(deliveryId).split('、')
        expect(snaps.length).toBe(beforeIds.length)
        expect(snaps.every((s) => s === deliverySn),
          `单号快照必须仍是 ${deliverySn}（包裹表已删，这里是唯一线索），实际：${snaps.join('、')}`).toBe(true)

        // 桥（delivery_order）断了，order_id 冗余列就是「按订单反查」的唯一通道
        expect(ledgerRowsLinkedToOrder(deliveryId, orderId),
          '台账每一行都必须仍指对这个订单 id').toBe(beforeIds.length)
        expect(dbCount(`select count(*) from delivery_status_ledger
                         where delivery_id = ${deliveryId} and order_id is null`),
          '不许有 order_id 为空的包裹台账行').toBe(0)
      })

      await test.step('⑥ 台账页：按订单 id 仍查得到这四行，业务单号列仍显示单号（不是「—」）', async () => {
        await expectDeliveryLedgerOnPage(page, {
          orderId,
          deliverySn,
          ledgerIds: beforeIds,
          message: '包裹行已删，仍应能按订单 id 查到它的四步'
        })
      })
    } finally {
      // 订单已在③被删（orderSn 置空即是这个意思）；这里只确认没有残留的半截数据
      if (orderId && deliveryRowCountOf(orderId) > 0) {
        await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
      }
    }
  })
})
