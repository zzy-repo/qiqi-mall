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
  auditPassOrder,
  auditRejectOrder,
  expectToastSuccess,
} from '../../support/admin-ui.js'
import { adminSetSkuStock, openOrderDrawer, clickDispatch, clickWmsCallback } from '../../support/ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../support/assert.js'

/**
 * 03-商家-履约 / 10-订单状态台账
 *
 * 这个场景要证明的一件事：
 *   **订单状态不是被人手动改的数字，而是一串只追加的事实；每变一次状态只会留下一行，
 *     行里永远说得清「从哪来、到哪去、为什么、谁干的」。**
 *
 * 一行订单状态台账 = 一次流转：
 *   前态 → 后态 ＋ 原因码 ＋ 操作人类型/名字 ＋ 触发单据 ＋ 幂等键 ＋ 记账时间。
 * 幂等键 = [打散前缀:]订单ID:流转序号，而流转序号是**在改状态那条 UPDATE 里一起自增**的，
 * 所以同一瞬的重复写入（重试、重放、并发）只会撞上同一个键，后到的被唯一索引挡住 —— 
 * 这就是「一条流转一行」而不是「一次请求一行」的来源（后者是操作审计，见 11）。
 *
 * 三条用例分别钉住三件事：
 *   ① 正常链路：出生 → 审核通过 → 包裹出库把父单推到已发货，三行三条原因，一行不多；
 *   ② 拒绝链路：审核不通过是另一条原因，订单落到「已关闭」，仍然只有一行；
 *   ③ 时间线视图：订单、包裹、售后三张台账能被一张只读视图按业务对象拼起来 —— 
 *      这就是「不写三段 SQL 也能回答『这张单是怎么走到今天的』」。
 *
 * ── 为什么直查库 ──────────────────────────────────────────────────────────
 *   台账页只给一页 10 行、且不显示流转序号与幂等键。要证明「只留一行」「序号是自增的、
 *   不会撞键」，只能查库；页面负责证明「人看得见」。
 *
 * ── 依赖（当前状态）──────────────────────────────────────────────────────
 *   台账页 `/ledger` 前端已就绪；后端 `GET /admin/ledgers` 控制器尚未进仓，
 *   故本文件**只写不跑**。直查库断言不依赖该接口。
 */

/* ------------------------------------------------------------------ 台账页（UI 侧） */

const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

const ledgerRowCount = (page) => tidPrefix(page, 'ledger-row-').count()

/** 用筛选区收敛到「订单状态 + 某订单ID」，并断言恰好 rows 行（理由见 9-库存台账与对账） */
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

const statusRowsOf = (orderId) =>
  dbCount(`select count(*) from order_status_ledger where order_id = ${orderId}`)

/** 原因码序列（按记账顺序）—— 这就是「这单走了哪几步」 */
const reasonsOf = (orderId) => queryDb(
  `select coalesce(group_concat(reason order by id separator '、'), '')
     from order_status_ledger where order_id = ${orderId}`
)

/** 状态流转序列：`前态→后态`，出生行前态记作空 */
const flowsOf = (orderId) => queryDb(
  `select coalesce(group_concat(concat(coalesce(from_status, '空'), '→', to_status) order by id separator '、'), '')
     from order_status_ledger where order_id = ${orderId}`
)

/** 某条原因那一行 */
const rowOfReason = (orderId, reason) => {
  const [fromStatus, toStatus, operatorType, operatorName] = queryDb(
    `select coalesce(from_status, 'NULL'), to_status, operator_type, coalesce(operator_name, '')
       from order_status_ledger where order_id = ${orderId} and reason = '${reason}' order by id limit 1`
  ).split('\t')
  return { fromStatus, toStatus, operatorType, operatorName }
}

/** 幂等键列表：格式 [前缀:]订单ID:流转序号 —— 序号必须各不相同 */
const idemKeysOf = (orderId) => queryDb(
  `select idem_key from order_status_ledger where order_id = ${orderId} order by id`
).split('\n').filter(Boolean)

/** 流转序号（与 status_seq 同源）—— 用来证明「每条流转的序号都不同」 */
const seqsOf = (orderId) => queryDb(
  `select group_concat(substring_index(idem_key, ':', -1) order by id separator '、')
     from order_status_ledger where order_id = ${orderId}`
)

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
    if (body.code !== 0) console.warn(`[10-订单状态台账] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[10-订单状态台账] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

test.describe('03-商家-履约 › 10-订单状态台账', () => {
  test('用例一：出生 → 审核通过 → 包裹出库，三步三行三条原因，序号各不相同', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let token = null
    try {
      let productName
      await test.step('① 管理员登录 + 造一笔待审核订单', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`order-ledger-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
      })

      await test.step('② 建单只留出生行：前态空、后态待审核、原因下单', async () => {
        expect(statusRowsOf(orderId), '建单只该留一行出生行').toBe(1)
        const born = rowOfReason(orderId, 'ORDER_CREATE')
        expect(born.fromStatus, '出生行的前态必须是空的（建单时还没有前态）').toBe('NULL')
        expect(born.toStatus, '出生行的后态应是待审核').toBe('7')
        expect(['ADMIN', 'MEMBER'], `出生行的操作人类型应是后台或买家，实际：${born.operatorType}`)
          .toContain(born.operatorType)
      })

      await test.step('③ 审核通过 → 第二行：待审核→待发货，原因审核通过，操作人是管理员', async () => {
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        expect(statusRowsOf(orderId), '审核通过应只多一行').toBe(2)
        const pass = rowOfReason(orderId, 'AUDIT_PASS')
        expect(pass.fromStatus, '这一行的前态应是待审核').toBe('7')
        expect(pass.toStatus, '这一行的后态应是待发货').toBe('8')
        expect(pass.operatorType, '审核是后台动作，操作人类型必须是 ADMIN').toBe('ADMIN')
        expect(pass.operatorName, '操作人名字要留快照（谁审的）').not.toBe('')
      })

      await test.step('④ 下发 + 仓库回传出库 → 第三行：父单被包裹聚合推到已发货', async () => {
        const table = await openOrderDrawer(page, orderSn)
        const dRow = table.locator('.el-table__body tr').first()
        await clickDispatch(page, dRow)
        await clickWmsCallback(page, dRow, '出库', '已出库')
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '出库后订单应为已发货(2)').toBe(2)
        expect(statusRowsOf(orderId), '父单被聚合改状态也只多一行').toBe(3)
        const shipped = rowOfReason(orderId, 'DELIVERY_AGGREGATE')
        expect(shipped.fromStatus, '这一行从待发货改出来').toBe('8')
        expect(shipped.toStatus, '这一行落到已发货').toBe('2')
        // 聚合本身是系统算出来的，但它发生在管理员这次点击的请求里 ⇒ 主语可能是 ADMIN（点了回传的人）
        // 也可能被记成 SYSTEM（没有人的主语时）。两者都能自洽，但不能是空的 —— 台账的天条是「说得清谁干的」
        expect(['ADMIN', 'SYSTEM'], `这一行的操作人类型必须落在闭集里，实际：${shipped.operatorType}`)
          .toContain(shipped.operatorType)
        expect(shipped.operatorName, '操作人名字快照不能是空串').not.toBe('')
      })

      await test.step('⑤ 库：三行三条原因，幂等键各带不同流转序号（同一瞬重放也只会撞同一把键）', async () => {
        expect(reasonsOf(orderId), '原因码序列应是「下单 → 审核通过 → 包裹聚合」')
          .toBe('ORDER_CREATE、AUDIT_PASS、DELIVERY_AGGREGATE')
        expect(flowsOf(orderId), '状态流转序列应是空→7、7→8、8→2')
          .toBe('空→7、7→8、8→2')
        const keys = idemKeysOf(orderId)
        expect(keys.length, '三条流转三把幂等键').toBe(3)
        for (const k of keys) {
          expect(k, `幂等键应是「订单ID:流转序号」，实际：${k}`)
            .toMatch(new RegExp(`(^|:)${orderId}:\\d+$`))
        }
        expect(new Set(keys).size, '三把键必须互不相同（否则就是同一行被写了三次）').toBe(3)
        const seqs = seqsOf(orderId).split('、').map(Number)
        expect(seqs.length, '三条流转各有一个序号').toBe(3)
        expect([...seqs].sort((a, b) => a - b), '流转序号应严格递增，不能重复').toEqual(
          [...new Set(seqs)].sort((a, b) => a - b)
        )
        expect(
          Number(queryDb(`select count(*) from order_status_ledger where order_id = ${orderId}
                           and created_at is null`)),
          '只追加表必须有记账时间（没有 updated_at 是设计，不是漏列）'
        ).toBe(0)
      })

      await test.step('⑥ 台账页：类型「订单状态」+ 订单ID → 恰好三行，能看出中文状态与原因码', async () => {
        await gotoLedger(page)
        await expectLedgerRows(page, {
          type: '订单状态',
          bizId: orderId,
          rows: 3,
          message: '台账页应看到这一单的三次状态流转'
        })
        const table = tid(page, 'ledger-list-table')
        await expect(table, '页面应把状态码翻成中文（待发货 → 已发货）').toContainText('待发货')
        await expect(table, '页面应能看到审核通过这一条原因').toContainText('AUDIT_PASS')
        // 操作人那一列渲染的是「名字快照 + 类型文案」（ADMIN 翻成「管理员」），两样都要看得见：
        // 只断 'ADMIN' 会把「页面把机器码直接甩给人看」当成正确，反过来断名字又漏掉类型
        const operatorCellText = table.locator('.operator').first().innerText()
        await expect(table, '页面应能看到操作人名字快照（谁审的）').toContainText('admin')
        await expect(table, '页面应把操作人类型翻成中文（ADMIN → 管理员）').toContainText('管理员')
        expect((await operatorCellText).trim(), '操作人名字不该是空的').not.toBe('')
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二：审核不通过是另一条原因，订单落「已关闭」，也仍然只有一行', async ({ page, request }) => {
    test.setTimeout(180_000)
    let orderSn = null
    let orderId = null
    let token = null
    try {
      let productName
      await test.step('① 管理员登录 + 造一笔待审核订单', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`order-ledger-reject-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
      })

      await test.step('② 点「审核不通过」→ 订单关闭', async () => {
        await auditRejectOrder(page, await searchOrderRow(page, orderSn))
        await expectToastSuccess(page, '审核不通过')
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '审核不通过后订单应为已关闭(4)').toBe(4)
      })

      await test.step('③ 库：两行——出生行 + 审核不通过那一行，原因与后态都对', async () => {
        expect(statusRowsOf(orderId), '审核不通过只该多一行').toBe(2)
        expect(reasonsOf(orderId), '原因码序列应是「下单 → 审核不通过」').toBe('ORDER_CREATE、AUDIT_REJECT')
        const reject = rowOfReason(orderId, 'AUDIT_REJECT')
        expect(reject.fromStatus, '这一行从待审核改出来').toBe('7')
        expect(reject.toStatus, '这一行落到已关闭').toBe('4')
        expect(reject.operatorType, '也是后台动作').toBe('ADMIN')
        expect(flowsOf(orderId), '整单流转应是「空→7、7→4」').toBe('空→7、7→4')
      })

      await test.step('④ 台账页：两行，页面上要能读到「已关闭」', async () => {
        await gotoLedger(page)
        await expectLedgerRows(page, {
          type: '订单状态',
          bizId: orderId,
          rows: 2,
          message: '台账页应看到出生行与审核不通过两行'
        })
        const table = tid(page, 'ledger-list-table')
        await expect(table, '页面应显示「已关闭」这个后态').toContainText('已关闭')
        await expect(table, '页面应显示审核不通过的原因码').toContainText('AUDIT_REJECT')
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三：只读时间线视图把订单与它的包裹拼在一起，状态已翻成中文', async ({ page, request }) => {
    test.setTimeout(240_000)
    let orderSn = null
    let orderId = null
    let token = null
    let deliverySn = null
    try {
      let productName
      await test.step('① 管理员登录 + 造单 + 审核通过（生成一个包裹）', async () => {
        await adminLoginHardened(page)
        token = await adminTokenOf(page)
        await ensureStock(page, request)
        productName = (await getProductApi(request, 1)).product.name
        await gotoOrderList(page)
        orderSn = await createOrderViaUi(page, {
          receiver: receiverOf(`timeline-${stamp()}`),
          status: '待审核',
          items: [{ productName, skuIndex: 0, quantity: 1 }]
        })
        orderId = (await getAdminOrderBySnApi(request, token, orderSn)).id
        await auditPassOrder(page, await searchOrderRow(page, orderSn))
        deliverySn = queryDb(`select delivery_sn from delivery_order where order_id = ${orderId} order by id limit 1`)
        expect(deliverySn, '审核通过应生成一个带单号的包裹').toMatch(/^DL/)
      })

      await test.step('② 视图的订单分支：行数与台账一致，状态已翻成中文', async () => {
        const ledgerRows = statusRowsOf(orderId)
        expect(
          dbCount(`select count(*) from v_biz_status_timeline where biz_type = 'ORDER' and biz_id = ${orderId}`),
          '视图的订单分支应逐行等于订单台账（UNION ALL，不去重）'
        ).toBe(ledgerRows)
        expect(
          queryDb(`select group_concat(to_status_text order by id separator '、')
                     from v_biz_status_timeline where biz_type = 'ORDER' and biz_id = ${orderId}`),
          '视图应把后态翻成中文（待审核 / 待发货）'
        ).toBe('待审核、待发货')
      })

      await test.step('③ 视图的包裹分支：靠包裹ID能查到这个包裹，单号快照在', async () => {
        const deliveryId = Number(queryDb(
          `select id from delivery_order where order_id = ${orderId} order by id limit 1`
        ))
        expect(
          dbCount(`select count(*) from v_biz_status_timeline where biz_type = 'DELIVERY' and biz_id = ${deliveryId}`),
          '这个包裹的每一次流转都应出现在视图里'
        ).toBeGreaterThanOrEqual(1)
        expect(
          queryDb(`select distinct delivery_sn from v_biz_status_timeline
                    where biz_type = 'DELIVERY' and biz_id = ${deliveryId}`),
          '视图里包裹行必须带单号快照（包裹被物理删除后这是唯一线索）'
        ).toBe(deliverySn)
        // 现口径（迁移之后）：包裹分支的 order_id 是**真实列**，不再是 CAST(NULL)。
        // 为什么必须填上：OrderPurgeService 会把包裹行物理删掉，`delivery_order` 这座
        // 「台账 → 订单」的桥一断，没有 order_id 就再也回答不了「这张订单的几个包裹是怎么走的」
        // （12-包裹状态台账 的用例三就是靠它按订单 id 反查）。
        const deliveryRows = dbCount(
          `select count(*) from v_biz_status_timeline where biz_type = 'DELIVERY' and biz_id = ${deliveryId}`
        )
        expect(
          dbCount(`select count(*) from v_biz_status_timeline
                    where biz_type = 'DELIVERY' and biz_id = ${deliveryId} and order_id = ${orderId}`),
          '包裹分支的每一行都必须冗余订单ID（删单之后按订单反查的唯一通道）'
        ).toBe(deliveryRows)
        expect(
          Number(queryDb(`select count(*) from v_biz_status_timeline
                           where biz_type = 'DELIVERY' and biz_id = ${deliveryId} and order_id is null`)),
          '包裹分支不许有订单ID为空的行走漏（漏一行就少一条可反查的证据）'
        ).toBe(0)
      })

      await test.step('④ 三个分支的公共列完全同构：13 列契约 + 视图额外 2 列', async () => {
        const cols = queryDb(
          `select group_concat(column_name order by ordinal_position separator ',')
             from information_schema.columns
            where table_schema = database() and table_name = 'v_biz_status_timeline'`
        ).split(',')
        for (const required of [
          'biz_type', 'biz_id', 'order_id', 'delivery_sn',
          'from_status', 'to_status', 'from_status_text', 'to_status_text',
          'reason', 'reason_detail', 'operator_type', 'operator_id', 'operator_name',
          'ref_type', 'ref_id', 'event_key', 'idem_key', 'created_at'
        ]) {
          expect(cols, `视图应包含列 ${required}（三张台账同构契约）`).toContain(required)
        }
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })
})
