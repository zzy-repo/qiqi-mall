import { test, expect } from '../../../support/test.js'
import { API, ADMIN, stamp } from '../../../support/env.js'
import { queryDb } from '../../../support/db.js'
import { tid, tidPrefix } from '../../../support/tid.js'
import { receiverOf } from '../../../support/fixtures.js'
import {
  adminLoginHardened,
  gotoOrderList,
  searchOrderRow,
  createOrderViaUi,
  auditPassOrder,
  expectToastSuccess,
} from '../../../support/admin-ui.js'
import { adminSetSkuStock, openOrderDrawer, clickDispatch, clickWmsCallback } from '../../../support/ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../../support/assert.js'

/**
 * 台账与取证 / 台账七类 / WMS交换台账
 *
 * 这个场景要证明的一件事：
 *   **我们和仓库之间的每一次往返都留了一行，先记「发了什么」，再记「对方回了什么」——
 *     所以「这一单到底发没发、发了几次、每次多久、对方怎么回的」不用翻日志就能回答。**
 *
 * 两阶段（write-ahead）：
 *   ① 外呼之前先记 REQ 行（报文快照 + 第几次尝试，回执那几列留空）；
 *   ② 拿到回执后再追加 ACK 行（结果 / 往返毫秒 / 回执报文）。
 * 为什么顺序不能反：货单一旦发出去就收不回来。先发后记，一旦「发出去了但记不上」，
 * 库里就是一个黑洞 —— 仓库认为收到了，我们一条记录都没有。
 *
 * 幂等键 = 包裹ID:方向:尝试号（REQ 与 ACK 各占一行、同表 ⇒ 键里必须带方向，
 * 否则同一次尝试的两行会互相撞键）。重放同一次尝试 → 撞唯一键 → 静默跳过，不会有两行。
 *
 * 三条用例：
 *   ① 下发 = 两行（REQ 留空回执列 + ACK 带结果与耗时），一行不多一行不少；
 *   ② 仓库回传出库 = 再追加一行 ACK（业务幂等在前面挡重复事件，台账只记真实发生的那次）；
 *   ③ 同一事件从接口重放两次 → 业务侧直接返回，交换台账一行都不多（台账不记「没发生的事」）。
 *
 * ── 为什么直查库 ──────────────────────────────────────────────────────────
 *   「REQ 行的回执列必须是空的」「ACK 行必须有耗时」这类事实页面根本显示不出来
 *   （台账页只有时间/类型/单号/变更/原因/操作人/摘要）。页面负责证明「人看得见」。
 *
 * ── 依赖（当前状态）──────────────────────────────────────────────────────
 *   台账页 `/ledger` 前端已就绪；后端 `GET /admin/ledgers` 控制器尚未进仓，故**只写不跑**。
 */

/* ------------------------------------------------------------------ 台账页（UI 侧） */

const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

const ledgerRowCount = (page) => tidPrefix(page, 'ledger-row-').count()

/** 收敛到「WMS 回调 + 某个包裹ID」并断言行数 */
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
    await expect(tid(page, 'ledger-list-table'), `台账页收敛到的每一行都应是包裹ID ${bizId}`)
      .toContainText(`ID ${bizId}`)
  }
}

/* ------------------------------------------------------------------ 库上的事实（只读） */

const dbCount = (sql) => Number(queryDb(sql))

/** 这个包裹在交换台账里的行数 */
const exchangeRowsOf = (deliveryId) =>
  dbCount(`select count(*) from wms_exchange_ledger where delivery_id = ${deliveryId}`)

/** 方向序列（按记账顺序）：REQ 与 ACK 交替，就是「一问一答」的形状 */
const directionSeqOf = (deliveryId) => queryDb(
  `select coalesce(group_concat(direction order by id separator '、'), '')
     from wms_exchange_ledger where delivery_id = ${deliveryId}`
)

/** 某个方向那一行（最近一次）的关键列 */
const rowOfDirection = (deliveryId, direction) => {
  const raw = queryDb(
    `select coalesce(result, 'NULL'), coalesce(latency_ms, 'NULL'),
            coalesce(request_payload, 'NULL'), coalesce(response_payload, 'NULL'), idem_key, attempt_no
       from wms_exchange_ledger where delivery_id = ${deliveryId} and direction = '${direction}'
      order by id ${direction === 'REQ' ? 'asc' : 'desc'} limit 1`
  )
  const [result, latency, req, resp, idemKey, attemptNo] = raw.split('\t')
  return { result, latency, req, resp, idemKey, attemptNo: Number(attemptNo) }
}

/** 幂等键列表（每行一个）—— 必须带方向，否则 REQ 与 ACK 会互相撞键 */
const idemKeysOf = (deliveryId) => queryDb(
  `select idem_key from wms_exchange_ledger where delivery_id = ${deliveryId} order by id`
).split('\n').filter(Boolean)

/** 该订单的包裹 id（本场景只用第一个包裹；多仓拆单的用例在 2-跨仓拆单 里） */
const deliveryIdOf = (orderId) => Number(queryDb(
  `select id from delivery_order where order_id = ${orderId} order by id limit 1`
))

/** 包裹状态（0 待下发 / 1 已下发 / 2 已拣货 / 3 已出库 …） */
const deliveryStatusOf = (deliveryId) =>
  Number(queryDb(`select status from delivery_order where id = ${deliveryId}`))

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
    if (body.code !== 0) console.warn(`[14-WMS交换台账] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[14-WMS交换台账] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

test.describe('台账与取证 › 台账七类 › WMS交换台账', () => {
  test('用例一：下发就是两行 —— 先记下发行（回执列留空），再追加回传行（结果＋耗时）', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let deliveryId = null
    let token = null
    try {
      let productName
      await test.step('① 管理员登录 + 造单 + 审核通过（生成一个待下发包裹）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`wms-ledger-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        deliveryId = deliveryIdOf(orderId)
        expect(deliveryId, '审核通过应生成一个包裹').toBeGreaterThan(0)
        expect(exchangeRowsOf(deliveryId), '还没下发，交换台账应是空的').toBe(0)
      })

      await test.step('② 点「下发」→ 包裹变已下发', async () => {
        const table = await openOrderDrawer(page, orderSn)
        await clickDispatch(page, table.locator('.el-table__body tr').first())
        expect(deliveryStatusOf(deliveryId), '下发后包裹应为已下发(1)').toBe(1)
      })

      await test.step('③ 库：正好两行，方向是「一问一答」', async () => {
        expect(exchangeRowsOf(deliveryId), '一次外发最多两行（REQ + ACK）').toBe(2)
        expect(directionSeqOf(deliveryId), '记账顺序必须是先 REQ 后 ACK（write-ahead）').toBe('REQ、ACK')
      })

      await test.step('④ 库：REQ 行回执列必须留空，报文快照必须有', async () => {
        const req = rowOfDirection(deliveryId, 'REQ')
        expect(req.attemptNo, '第一次外发的尝试号是 1').toBe(1)
        expect(req.result, 'REQ 行还没定论，结果必须是空（不能提前写成功）').toBe('NULL')
        expect(req.latency, 'REQ 行还没有往返耗时').toBe('NULL')
        expect(req.resp, 'REQ 行落库时回执还不存在，必须是空').toBe('NULL')
        expect(req.req, 'REQ 行必须留下「我要发什么」的报文快照').not.toBe('NULL')
        expect(req.req, '报文里应带包裹单号（事后能对上这张单）').toMatch(/DL\d+/)
      })

      await test.step('⑤ 库：ACK 行有结果与耗时，且不再重复报文里的「请求」部分', async () => {
        const ack = rowOfDirection(deliveryId, 'ACK')
        expect(ack.attemptNo, '同一次尝试的 ACK 与 REQ 用同一个尝试号').toBe(rowOfDirection(deliveryId, 'REQ').attemptNo)
        expect(ack.result, '下发回执应记为成功').toBe('SUCCESS')
        expect(ack.latency, 'ACK 行必须记往返耗时（这是「慢在哪」的唯一证据）').not.toBe('NULL')
        expect(ack.resp, 'ACK 行必须有回执报文').not.toBe('NULL')
        expect(ack.req, 'ACK 行是新的一行，不该覆盖/复制 REQ 行的请求报文').toBe('NULL')
      })

      await test.step('⑥ 库：幂等键带方向，两行的键不会互相撞', async () => {
        const keys = idemKeysOf(deliveryId)
        expect(keys.length, '两行两把键').toBe(2)
        for (const k of keys) {
          expect(k, `幂等键应是「包裹ID:方向:尝试号」，实际：${k}`)
            .toMatch(new RegExp(`^${deliveryId}:(REQ|ACK):\\d+$`))
        }
        expect(keys.some((k) => k.includes(':REQ:')), '必须有一把 REQ 的键').toBe(true)
        expect(keys.some((k) => k.includes(':ACK:')), '必须有一把 ACK 的键').toBe(true)
        expect(new Set(keys).size, '两把键不能相同（同表两行，方向必须在键里）').toBe(2)
      })

      await test.step('⑦ 台账页：类型「WMS 回调」+ 包裹ID → 两行', async () => {
        await gotoLedger(page)
        await expectLedgerRows(page, {
          type: 'WMS',
          bizId: deliveryId,
          rows: 2,
          message: '台账页应看到这个包裹的一次一问一答（两行）'
        })
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二：仓库回传出库再追加一行 ACK，业务幂等挡在前面 —— 台账只记真实发生的那次', async ({ page, request }) => {
    test.setTimeout(300_000)
    let orderSn = null
    let orderId = null
    let deliveryId = null
    let token = null
    try {
      let productName
      await test.step('① 管理员登录 + 造单 + 审核通过 + 下发（已有两行）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`wms-callback-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        deliveryId = deliveryIdOf(orderId)
        const table = await openOrderDrawer(page, orderSn)
        await clickDispatch(page, table.locator('.el-table__body tr').first())
        expect(exchangeRowsOf(deliveryId), '前提：下发后应有两行').toBe(2)
      })

      await test.step('② 模拟仓库回传出库 → 包裹变已出库', async () => {
        const table = tid(page, 'order-detail-delivery-table')
        await clickWmsCallback(page, table.locator('.el-table__body tr').first(), '出库', '已出库')
        expect(deliveryStatusOf(deliveryId), '回传出库后包裹应为已出库(3)').toBe(3)
      })

      await test.step('③ 库：多了一行 ACK（尝试号按 ACK 方向自增，不撞下发那两行）', async () => {
        expect(exchangeRowsOf(deliveryId), '回传也是一次交换，应再多一行 ACK').toBe(3)
        expect(directionSeqOf(deliveryId), '方向序列应是 REQ、ACK、ACK').toBe('REQ、ACK、ACK')
        const ackNumbers = queryDb(
          `select group_concat(attempt_no order by id separator '、') from wms_exchange_ledger
            where delivery_id = ${deliveryId} and direction = 'ACK'`
        ).split('、').map(Number)
        expect(new Set(ackNumbers).size, `ACK 方的尝试号必须递增不重复，实际：${ackNumbers}`).toBe(ackNumbers.length)
        expect(ackNumbers, 'ACK 的尝试号应从 1 开始').toContain(1)
      })

      await test.step('④ 库：回传行的报文里有事件名与前后状态，结果记为成功', async () => {
        const latest = queryDb(
          `select concat_ws('｜', coalesce(request_payload, 'NULL'), coalesce(result, 'NULL'),
                            coalesce(latency_ms, 'NULL'))
             from wms_exchange_ledger where delivery_id = ${deliveryId} and direction = 'ACK'
            order by id desc limit 1`
        ).split('｜')
        expect(latest[1], '回传行结果应记为成功').toBe('SUCCESS')
        expect(latest[0], '回传行的报文里应写清是哪个事件').toContain('outbound')
        expect(latest[0], '回传行的报文里应带包裹单号（事后能对上这张单）').toMatch(/DL\d+/)
        // 回传是对方推来的，我们这一侧的往返耗时只在「我们主动外呼」时才有
        expect(latest[2], '回传行没有往返耗时（耗时属于外呼那一侧）').toBe('NULL')
      })

      await test.step('⑤ 台账页：同一个包裹现在三行', async () => {
        await gotoLedger(page)
        await expectLedgerRows(page, {
          type: 'WMS',
          bizId: deliveryId,
          rows: 3,
          message: '台账页应看到这个包裹的一问一答再加一次回传'
        })
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三：同一个回传事件重放两次，交换台账一行都不多（不记没发生的事）', async ({ page, request }) => {
    test.setTimeout(300_000)
    let orderSn = null
    let orderId = null
    let deliveryId = null
    let token = null
    try {
      let productName
      await test.step('① 管理员登录 + 造单 + 审核通过 + 下发 + 回传出库', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`wms-replay-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        deliveryId = deliveryIdOf(orderId)
        const table = await openOrderDrawer(page, orderSn)
        await clickDispatch(page, table.locator('.el-table__body tr').first())
        await clickWmsCallback(page, table.locator('.el-table__body tr').first(), '出库', '已出库')
        expect(exchangeRowsOf(deliveryId), '前提：下发的两行 + 回传的一行').toBe(3)
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

      await test.step('③ 库：交换台账与包裹状态都没变 —— 台账只记真实发生的那一次', async () => {
        expect(exchangeRowsOf(deliveryId), '重放两次也不该多一行交换台账').toBe(3)
        expect(directionSeqOf(deliveryId), '方向序列仍是 REQ、ACK、ACK').toBe('REQ、ACK、ACK')
        expect(deliveryStatusOf(deliveryId), '包裹仍应是已出库，没有被回退').toBe(3)
      })

      await test.step('④ 库：包裹状态台账也没有多出一行（两张台账口径一致）', async () => {
        const rows = dbCount(
          `select count(*) from delivery_status_ledger where delivery_id = ${deliveryId}`
        )
        // 出生 + 下发 + 出库 = 三行；重放不产生第四行
        expect(rows, '包裹状态台账应是「出生、下发、出库」三行').toBe(3)
        expect(
          queryDb(`select group_concat(reason order by id separator '、')
                     from delivery_status_ledger where delivery_id = ${deliveryId}`),
          '原因码序列应是「拆单出生 → 下发 → 仓库回传出库」'
        ).toBe('DELIVERY_CREATE、DELIVERY_DISPATCH、WMS_OUTBOUND')
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })
})
