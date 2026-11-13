import { test, expect } from '../../support/test.js'
import { ADMIN, API, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { tid, tidPrefix } from '../../support/tid.js'
import { memberNickOf, receiverOf, returnDescriptionOf } from '../../support/fixtures.js'
import { memberLoginOf } from '../../support/member-login.js'
import {
  adminSetSkuStock,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder
} from '../../support/ui.js'
import {
  adminLoginHardened,
  adminAuditOrderPass,
  auditReturnApply,
  expectToastSuccess,
  memberApplyReturn
} from '../../support/admin-ui-return.js'
import { deleteMemberApi } from '../../support/admin-ui-member.js'
import {
  adminTokenOf,
  getProductApi,
  getAdminOrderBySnApi,
  listDeliveriesApi,
  aggregateOrderStatusOf,
  DELIVERY_CANCELLED,
  ORDER_STATUS_TEXT,
  deliveryStatusText
} from '../../support/assert.js'

/**
 * 03-商家-履约 / 13-售后台账
 *
 * 这个场景要证明的一件事：
 *   **一张退货申请走完全程也只留几行台账，每行都带着订单号 ——
 *     所以订单被物理删除、申请行跟着消失之后，售后流水仍然能按订单反查。**
 *
 * ════════════════════════════════════════════════════════════════════════════
 * 一、bizId 口径（本 spec 的第一条交付物：把双口径显式钉住，不靠文档承诺）
 * ════════════════════════════════════════════════════════════════════════════
 * 售后台账的接口是 `GET /admin/ledgers?type=RETURN_STATUS&bizId=<值>`，而 bizId 在售后分支里
 * **同时**匹配两个东西（见 `LedgerSql.java:232-233` / 视图 RETURN 分支）：
 *
 *     AND (v.biz_id = #{bizId} OR v.order_id = #{bizId})
 *          ↑ 退货申请 id（biz_id）      ↑ return_status_ledger 的 order_id 冗余列
 *
 * **规范用法 = 订单 id**（用户视角是「看某订单的售后流水」，而且这也是删单后唯一还通的那条路）；
 * 申请 id 作为兼容口径保留。两种都必须真的能用 —— 所以这里不是「写个注释说支持」，
 * 而是**在用例一里对两种入参各查一次**（`expectReturnLedgerOnPage` 两次调用），
 * 任一口径退化都会立刻红。
 *
 * 顺带把「一个输入框两种含义」的副作用也钉住：按订单 id 查时，若**另有**一张申请的
 * return_apply_id 恰好等于这个订单 id，它的行也会被命中 —— 这是接口设计（一个输入框同时收
 * 订单 id 与售后 id），不是 bug。所以页面行数断言一律与**同口径的库查询**对撞
 * （`mirroredReturnRowsOf`），而不是写死一个「2」，免得把设计当成缺陷。
 *
 * ════════════════════════════════════════════════════════════════════════════
 * 二、两张台账别混：售后台账记「申请怎么被审的」，订单台账记「订单怎么被推着走的」
 * ════════════════════════════════════════════════════════════════════════════
 * 本页（type=RETURN_STATUS）的 from/to 是**退货申请**的状态：
 *   0 待处理 / 1 已通过 / 2 已拒绝（`v_biz_status_timeline` 的 RETURN 分支 + `constants/status.js`
 *   的 RETURN_STATUS_TEXT）。所以它的一行是这样长出来的：
 *
 *   ① 买家提交申请 → `NULL → 0`，reason **RETURN_CREATE**，operator MEMBER
 *      （`PortalReturnController.java:87` → `StatusLedgerWriter#returnBirth`）
 *   ② 审核通过     → `0 → 1`，reason **RETURN_APPROVE**，operator ADMIN
 *   ③ 审核拒绝     → `0 → 2`，reason **RETURN_REJECT**，operator ADMIN
 *      （②③ 都是 `ReturnApplyController.java:234` → `ReturnStatusService#change`）
 *
 * 而「订单进 5 退款中（reason=RETURN_APPLY）」「订单 5→6（RETURN_APPROVE）」「拒绝后订单
 * 回聚合状态（DELIVERY_AGGREGATE）」是**订单台账**（type=ORDER_STATUS）里的行
 * （`PortalReturnController.java:94`、`ReturnApplyController.java:277-290`）。
 * 两张台账互不替代：一个回答「退款审批走到哪一步」，一个回答「订单状态被谁改掉了」。
 * 本 spec 两边都断，就是为了把这个分工写成可执行的契约（用例一/二都顺带断订单台账那一行）。
 *
 * ════════════════════════════════════════════════════════════════════════════
 * 三、「拒绝后订单回哪个状态」—— 查过实现，不是常量 1，也不是写死的 2
 * ════════════════════════════════════════════════════════════════════════════
 * `ReturnApplyController.java:280`：有未取消包裹 → `recomputeOrderStatusAfterReturnReject`
 * （交包裹聚合推导）；只有一张包裹都没有的直发单才硬写 1 已付款。
 * 聚合规则在 `DeliveryFlowService.java:534-549`：
 *   全部子单 ≥ 已签收(5) → 3 已完成；全部 ≥ 已出库(3) → **2 已发货**；部分出库 → 9 部分发货；
 *   一张都没出库 → 8 待发货（仅拒绝这条路径会写）。
 * 本 spec 的前置步骤③ 只做到「出库」（不签收），所以**拒绝后订单 = 2 已发货**；
 * 但断言仍然照 `aggregateOrderStatusOf(未取消包裹)` 现算一遍再对撞（support/assert.js），
 * 这样后端口径变了断言会自己跟上，而不是留一条过期常量。
 *
 * ════════════════════════════════════════════════════════════════════════════
 * 四、order_id 冗余列（用例三，本页最该证的那件事）
 * ════════════════════════════════════════════════════════════════════════════
 * `return_status_ledger` 有 `order_id` 冗余列（`sql/schema.sql:639`：「return_apply 随订单
 * 级联删除/物理删单后，仍能从订单反查售后流水」），且**故意没有外键** —— 证据不随申请级联消失。
 * `OrderPurgeService#purge` 删单时会删物流、退货申请、包裹、订单明细、支付流水、库存台账，
 * 但**不碰** return_status_ledger；于是删单之后：
 *   · `order` 0 行、`return_apply` 0 行（申请跟着没了）
 *   · return_status_ledger 一行不少，每行 order_id 仍等于那个订单 id
 *   · 台账页按**订单 id** 仍查得到（走的就是 order_id 冗余列，`delivery_order` 那座桥已经断了）
 *
 * 已知的一处「断链」现状（如实断言，不当成缺陷掩盖）：return_status_ledger **没有 order_sn
 * 快照列**，而 RETURN 分支的 bizSn 是 `LEFT JOIN order o ON o.id = v.order_id` 取到的
 * （`LedgerSql.java` 的 RETURN_STATUS_BRANCH）。订单被物理删除后这个 JOIN 必然为空 →
 * 页面「业务单号」列显示「—」，订单号只剩在**摘要**里
 * （summary = `售后 #12（订单 34）：提交 → 待处理`，COALESCE 到 CAST(v.order_id AS CHAR)）。
 * 所以用例三对页面的断言是：行/状态/操作人/摘要里的订单 id 都在 + 业务单号列是「—」
 * （与删单前那次查询形成对照，用例一/二断的是「删单前业务单号列显示订单号」）。
 *
 * ── 为什么直查库 ──────────────────────────────────────────────────────────
 * 页面只显示时间/类型/单号/变更/原因/操作人/摘要，看不到 order_id 冗余列、幂等键、ref 列；
 * 而「每行都带着订单号」「幂等键 = 申请ID:流转序号且序号互不相同」正是本场景的全部论点。
 * 页面负责证明「人看得见」，库负责证明「写对了」。
 */

/* ------------------------------------------------------------------ 库上的事实（只读） */

const dbCount = (sql) => Number(queryDb(sql))

/** 该订单的退货申请 id（本场景一张单只留一条申请） */
const applyIdOf = (orderId) => Number(queryDb(
  `select id from return_apply where order_id = ${orderId} order by id limit 1`
))

/** 该申请在售后台账里的行数 / 行 id（行 id 用来定位台账页的行锚点） */
const returnLedgerRowsOf = (applyId) => dbCount(
  `select count(*) from return_status_ledger where return_apply_id = ${applyId}`
)

const returnLedgerIdsOf = (applyId) => queryDb(
  `select coalesce(group_concat(id order by id separator ','), '')
     from return_status_ledger where return_apply_id = ${applyId}`
).split(',').filter(Boolean).map(Number)

/** 原因码序列 —— 这就是「这张申请被审到哪一步」 */
const returnReasonsOf = (applyId) => queryDb(
  `select coalesce(group_concat(reason order by id separator '、'), '')
     from return_status_ledger where return_apply_id = ${applyId}`
)

/** 状态流转序列：`前态→后态`，出生行的前态记作「空」 */
const returnFlowsOf = (applyId) => queryDb(
  `select coalesce(group_concat(concat(coalesce(from_status, '空'), '→', to_status) order by id separator '、'), '')
     from return_status_ledger where return_apply_id = ${applyId}`
)

/** 订单号冗余列序列：每一行都该带着它（删单后这是唯一的反查通道） */
const returnOrderIdSnapshotsOf = (applyId) => queryDb(
  `select coalesce(group_concat(coalesce(order_id, 'NULL') order by id separator '、'), '')
     from return_status_ledger where return_apply_id = ${applyId}`
)

/** 幂等键列表：格式 {申请ID}:{流转序号} —— 序号必须各不相同 */
const returnIdemKeysOf = (applyId) => queryDb(
  `select coalesce(group_concat(idem_key order by id separator ','), '')
     from return_status_ledger where return_apply_id = ${applyId}`
).split(',').filter(Boolean)

/** 某条原因那一行（售后台账） */
const returnRowOfReason = (applyId, reason) => {
  const cells = queryDb(
    `select coalesce(from_status, 'NULL'), to_status, coalesce(reason_detail, ''),
            operator_type, coalesce(operator_name, ''), coalesce(ref_type, 'NULL'),
            coalesce(ref_id, 'NULL'), coalesce(order_id, 'NULL'), coalesce(event_key, 'NULL')
       from return_status_ledger where return_apply_id = ${applyId} and reason = '${reason}'
      order by id limit 1`
  ).split('\t')
  const [fromStatus, toStatus, reasonDetail, operatorType, operatorName, refType, refId, orderId, eventKey] = cells
  return { fromStatus, toStatus, reasonDetail, operatorType, operatorName, refType, refId, orderId, eventKey }
}

/**
 * 某条原因那一行（订单台账 —— 与本页是两张账，见文件头第二节）。
 *
 * 同一个原因码在一张订单上可能有多行（DELIVERY_AGGREGATE 每次包裹回传/聚合都会写一行，
 * 出库时一行 8→2、退货被拒时又一行 5→2），所以这里必须显式选「第一行」还是「最后一行」，
 * 不许靠 limit 1 的默认顺序猜。
 */
const orderRowOfReason = (orderId, reason, { last = false } = {}) => {
  const cells = queryDb(
    `select coalesce(from_status, 'NULL'), to_status, operator_type,
            coalesce(operator_name, ''), coalesce(ref_type, 'NULL'), coalesce(ref_id, 'NULL')
       from order_status_ledger where order_id = ${orderId} and reason = '${reason}'
      order by id ${last ? 'desc' : 'asc'} limit 1`
  ).split('\t')
  const [fromStatus, toStatus, operatorType, operatorName, refType, refId] = cells
  return { fromStatus, toStatus, operatorType, operatorName, refType, refId }
}

/**
 * 接口口径的**镜像**：RETURN 分支的过滤是 `v.biz_id = X OR v.order_id = X`
 * （LedgerSql.java:232-233，v.biz_id 即 return_apply_id）。页面行数拿它来对撞，
 * 这样「一个输入框两种含义」的副作用（别的申请的 id 恰好等于这个订单 id）不会被写成假红。
 */
const mirroredReturnRowsOf = (bizId) => dbCount(
  `select count(*) from return_status_ledger where return_apply_id = ${bizId} or order_id = ${bizId}`
)

/** 支付流水（本库口径与订单同库：type 1=支付 2=退款） */
const paymentRecordsOf = (orderSn) => queryDb(
  `select type, amount, coalesce(trade_no, '') from payment_record where order_sn = '${orderSn}' order by id`
).split('\n').filter(Boolean).map((line) => {
  const [type, amount, tradeNo] = line.split('\t')
  return { type: Number(type), amount: Number(amount), tradeNo }
})

/* ------------------------------------------------------------------ 台账页（UI 侧） */

const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

const pageRowCount = (page) => tidPrefix(page, 'ledger-row-').count()

/** 台账页按「类型 + 业务ID」收敛一次，返回表格 locator（每次重新进页面，避免残留筛选状态） */
async function searchLedger(page, { type, bizId }) {
  await gotoLedger(page)
  if (type) {
    await tid(page, 'ledger-type-select').click()
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: type }).first().click()
    await page.waitForTimeout(200)
  }
  if (bizId !== undefined && bizId !== null) await tid(page, 'ledger-biz-id-input').fill(String(bizId))
  await tid(page, 'ledger-search-submit').click()
  return tid(page, 'ledger-list-table')
}

/** 行锚点所在的 <tr>（锚点挂在时间格上，见 admin-web/src/views/ledger/index.vue 的 rowKey 注释） */
const ledgerRowOf = (page, ledgerId) =>
  page.locator('.el-table__body tr').filter({ has: tid(page, `ledger-row-RETURN_STATUS-${ledgerId}`) })

/**
 * 台账页断言：类型「售后状态」+ bizId → 行数与**同口径库查询**一致、我们那几行都在、
 * 指定的文案都能在表里读到。
 *
 * @param bizId  规范口径传**订单 id**；用例一还会再传一次**申请 id** 证双口径
 * @param labels [[文本, 失败说明], …] 表内应能读到的文案（原因码 / 中文状态 / 操作人…）
 */
async function expectReturnLedgerOnPage(page, { bizId, ledgerIds, rows, labels = [], message }) {
  const table = await searchLedger(page, { type: '售后状态', bizId })
  await expect.poll(() => pageRowCount(page), {
    timeout: 20_000,
    intervals: [400, 800, 1500],
    message: `${message}（类型=售后状态、业务ID=${bizId}：行数应为 ${rows}）`
  }).toBe(rows)
  for (const id of ledgerIds) {
    await expect(
      tid(page, `ledger-row-RETURN_STATUS-${id}`),
      `${message}（业务ID=${bizId} 查到的行里应含台账行 ${id}）`
    ).toBeVisible({ timeout: 20_000 })
  }
  for (const [text, why] of labels) await expect(table, why).toContainText(text)
  return table
}

/** 「业务单号」列（第 3 列）的整格文本：上面一行是单号快照，下面一行是「ID <售后ID>」 */
const bizSnCellTextOf = async (page, ledgerId) =>
  (await ledgerRowOf(page, ledgerId).locator('td').nth(2).innerText()).trim()

/* ------------------------------------------------------------------ 备货 / 清理 */

async function ensureStock(page, request, { productId = 1, rowIndex = 0, need = 50 } = {}) {
  const { skus } = await getProductApi(request, productId)
  if ((skus[rowIndex]?.stock ?? 0) >= need) return false
  await adminSetSkuStock(page, productId, rowIndex, need)
  return true
}

/** 清理本用例造的订单（真删）：先断掉删单守卫的依据，再走 DELETE /admin/orders/{id} */
async function cleanupSelfMadeOrder(request, { orderId, orderSn, token }) {
  const id = orderId
    || (orderSn ? Number(queryDb(`select id from \`order\` where order_sn = '${orderSn}'`) || 0) : 0)
  if (!id || !token) return
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
    if (body.code !== 0) console.warn(`[13-售后台账] 订单清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[13-售后台账] 订单清理抛错（orderId=${id}）：${e.message}`)
  }
}

/** 清理买家账号（会员列表没有删除入口，只能走接口 —— 见 admin-ui-member.js 头部） */
async function cleanupBuyer(request, { token, username }) {
  if (!token || !username) return
  const memberId = Number(queryDb(`select id from member where username = '${username}'`) || 0)
  if (!memberId) return
  try {
    const { body } = await deleteMemberApi(request, token, memberId)
    if (body.code !== 0) console.warn(`[13-售后台账] 买家清理未完成（${username}）：${body.message}`)
  } catch (e) {
    console.warn(`[13-售后台账] 买家清理抛错（${username}）：${e.message}`)
  }
}

/**
 * 造一笔「已出库 + 已申请退货」的订单（三条用例共用前置）：
 *   ① 管理员备货 → ② 新买家注册/加购/结算支付（订单=待审核）
 *   → ③ 审核通过 + 下发 + 模拟WMS出库（订单=已发货，才有申请退货入口）
 *   → ④ 买家申请退货（订单转退款中，售后台账写出生行）
 */
async function prepareShippedOrderWithReturn(page, request, label) {
  let token
  let username
  let orderSn
  let orderId
  let applyId
  let skuId
  let totalAmount
  let buyerNote

  await test.step(`${label} · ① 管理员登录 + 备货`, async () => {
    await adminLoginHardened(page)
    token = await adminTokenOf(page)
    await ensureStock(page, request)
  })

  await test.step(`${label} · ② 新买家注册 → 加购一件 → 结算支付`, async () => {
    username = memberLoginOf(`${stamp()}-${label}`)
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    skuId = skus[0].id
    await addSpecToCart(page, 1, skus[0].specText)
    orderSn = await submitCartOrder(page, receiverOf(username))
    const order = await getAdminOrderBySnApi(request, token, orderSn)
    expect(order, `结算后应能按单号查到订单 ${orderSn}`).toBeTruthy()
    orderId = order.id
    totalAmount = Number(order.totalAmount)
  })

  await test.step(`${label} · ③ 审核通过 → 下发 → 模拟WMS出库（订单=已发货）`, async () => {
    const row = await adminSearchOrderRow(page, orderSn)
    await adminAuditOrderPass(page, row)
    const deliveryTable = await openOrderDrawer(page, orderSn)
    const dRow = deliveryTable.locator('.el-table__body tr').first()
    await clickDispatch(page, dRow)
    await clickWmsCallback(page, dRow, '出库', '已出库')
    expect(
      (await getAdminOrderBySnApi(request, token, orderSn)).status,
      '出库后订单应为已发货(2)：这是「可以申请退货」的前提'
    ).toBe(2)
  })

  await test.step(`${label} · ④ 买家申请退货 → 订单转退款中(5)，售后台账写出生行`, async () => {
    buyerNote = returnDescriptionOf(orderSn)          // 买家原说明：它会一路落到台账的 reason_detail
    await memberApplyReturn(page, orderSn, { description: buyerNote })
    applyId = applyIdOf(orderId)
    expect(applyId, '申请落库后应能查到申请 id（台账的业务主键）').toBeGreaterThan(0)
    expect(
      (await getAdminOrderBySnApi(request, token, orderSn)).status,
      '申请退货后订单应联动为退款中(5)'
    ).toBe(5)
  })

  return { token, username, orderSn, orderId, applyId, skuId, totalAmount, buyerNote }
}

test.describe('03-商家-履约 › 13-售后台账', () => {
  test('用例一：买家申请退货留出生行（NULL→待处理 / RETURN_CREATE / 操作人 MEMBER），台账页按订单ID与申请ID都查得到', async ({ page, request }) => {
    test.setTimeout(240_000)
    const ctx = {}
    try {
      await test.step('前置：下单 → 审核 → 出库 → 申请退货', async () => {
        Object.assign(ctx, await prepareShippedOrderWithReturn(page, request, '13-售后台账/用例一'))
      })

      await test.step('⑤ 库：售后台账只多一行出生行 —— 前态 NULL、后态待处理、原因 RETURN_CREATE、操作人 MEMBER', async () => {
        expect(returnLedgerRowsOf(ctx.applyId), '提交申请只该留一行出生行').toBe(1)
        expect(returnFlowsOf(ctx.applyId), '出生行的流转是「空 → 待处理(0)」').toBe('空→0')
        expect(returnReasonsOf(ctx.applyId), '出生行的原因码是 RETURN_CREATE（不是 RETURN_APPLY —— 那是订单台账）')
          .toBe('RETURN_CREATE')

        const born = returnRowOfReason(ctx.applyId, 'RETURN_CREATE')
        expect(born.fromStatus, '出生行的前态必须是空的（申请刚出生，没有前态）').toBe('NULL')
        expect(born.toStatus, '出生行的后态应是待处理(0)').toBe('0')
        expect(born.operatorType, '买家自助申请，操作人类型必须是 MEMBER').toBe('MEMBER')
        expect(born.operatorName, '操作人名字快照应是买家登录名').toBe(ctx.username)
        expect(born.reasonDetail, '出生行应带「买家自助申请」的来源说明').toBe('买家自助申请')
        expect(born.refType, '出生事件没有触发单据（refType 为空是事实，不是缺列）').toBe('NULL')
        expect(born.eventKey, '买家申请不接受外部事件号，event_key 应为空').toBe('NULL')
        expect(born.orderId, 'order_id 冗余列必须是这张订单的 id（删单后按订单反查的全部依据）')
          .toBe(String(ctx.orderId))
      })

      await test.step('⑥ 库：订单台账那一边另记一行「2 已发货 → 5 退款中 / RETURN_APPLY」——两张账分工不同', async () => {
        const pushed = orderRowOfReason(ctx.orderId, 'RETURN_APPLY')
        expect(pushed.fromStatus, '订单进退款中前一态是已发货(2)').toBe('2')
        expect(pushed.toStatus, '订单进退款中后一态是退款中(5)').toBe('5')
        expect(pushed.refType, '订单台账这一行带着触发单据：退货申请').toBe('RETURN_APPLY')
        expect(pushed.refId, '触发单据 id 就是这张申请').toBe(String(ctx.applyId))
        // 这就是本 spec 要钉住的口径：5 / RETURN_APPLY 属于**订单台账**，
        // 售后台账那一行是 NULL→0 / RETURN_CREATE。两张账互不替代。
        expect(returnReasonsOf(ctx.applyId), '售后台账里不该出现 ORDER 侧的原因码').not.toContain('RETURN_APPLY')
      })

      await test.step('⑦ 库：幂等键 = 申请ID:流转序号，且记账时间非空', async () => {
        const keys = returnIdemKeysOf(ctx.applyId)
        expect(keys.length, '一行出生行 = 一把幂等键').toBe(1)
        expect(keys[0], `幂等键应是「申请ID:流转序号」，实际：${keys[0]}`)
          .toMatch(new RegExp(`^${ctx.applyId}:\\d+$`))
        expect(Number(queryDb(`select count(*) from return_status_ledger
                                where return_apply_id = ${ctx.applyId} and created_at is null`)),
          '只追加表必须有记账时间').toBe(0)
      })

      await test.step('⑧ 台账页：类型「售后状态」+ **订单 id**（规范口径）→ 查得到这一行，且能看出待处理与操作人', async () => {
        const ledgerIds = returnLedgerIdsOf(ctx.applyId)
        const mirrored = mirroredReturnRowsOf(ctx.orderId)
        expect(mirrored, '按订单 id 的同口径库查询至少应命中本申请这一行').toBeGreaterThanOrEqual(1)
        if (mirrored > ledgerIds.length) {
          console.log(`[13-售后台账/用例一] 按订单 ${ctx.orderId} 查命中 ${mirrored} 行 > 本申请 ${ledgerIds.length} 行：` +
            '另有申请的 return_apply_id 恰好等于这个订单 id（一个输入框两种含义，见文件头第一节）')
        }
        const table = await expectReturnLedgerOnPage(page, {
          bizId: ctx.orderId,
          ledgerIds,
          rows: mirrored,
          labels: [
            ['售后状态', '类型列应显示「售后状态」'],
            ['待处理', '页面应把后态 0 翻成「待处理」'],
            ['RETURN_CREATE', '页面应显示原因码 RETURN_CREATE'],
            [ctx.username, '操作人列应显示买家登录名的快照（谁申请的）'],
            ['会员', '操作人类型 MEMBER 应翻成「会员」'],
            ['买家自助申请', '原因补充应显示「买家自助申请」']
          ],
          message: '台账页应能按订单 id 查到这张申请的售后流水'
        })
        // 删单前「业务单号」列是订单号快照（来自 RETURN 分支的 LEFT JOIN order）
        const cell = await bizSnCellTextOf(page, ledgerIds[0])
        expect(cell, `删单前业务单号列应显示订单号 ${ctx.orderSn}，实际：${cell}`).toContain(ctx.orderSn)
        expect(cell, `业务单号列的次行应是「ID <售后ID>」= ID ${ctx.applyId}，实际：${cell}`).toContain(`ID ${ctx.applyId}`)
        await expect(table, '摘要列应能读出售后与订单的对应关系').toContainText(`售后 #${ctx.applyId}`)
      })

      await test.step('⑨ 双口径：同一个输入框换成**申请 id** 也能查到同样的行（接口两种都支持，这里钉住）', async () => {
        const ledgerIds = returnLedgerIdsOf(ctx.applyId)
        await expectReturnLedgerOnPage(page, {
          bizId: ctx.applyId,
          ledgerIds,
          rows: mirroredReturnRowsOf(ctx.applyId),
          labels: [['RETURN_CREATE', '按申请 id 查时同样应显示原因码']],
          message: '售后台账的 bizId 兼容口径：按退货申请 id 查'
        })
      })
    } finally {
      await cleanupSelfMadeOrder(request, ctx)
      await cleanupBuyer(request, ctx)
    }
  })

  test('用例二：审核通过再留一行（待处理→已通过 / RETURN_APPROVE）+ 支付流水出现退款行，按订单ID查到两行', async ({ page, request }) => {
    test.setTimeout(240_000)
    const ctx = {}
    try {
      await test.step('前置：下单 → 审核 → 出库 → 申请退货（申请待处理）', async () => {
        Object.assign(ctx, await prepareShippedOrderWithReturn(page, request, '13-售后台账/用例二'))
        expect(returnLedgerRowsOf(ctx.applyId), '审核前应只有出生行').toBe(1)
      })

      await test.step('⑤ 管理员在「退货申请」页审核通过', async () => {
        await auditReturnApply(page, ctx.orderSn, { result: '通过' })
        await expectToastSuccess(page, '审核完成')
      })

      await test.step('⑥ 库：售后台账两行 —— 出生行 + 审核通过行（0→1 / RETURN_APPROVE / 操作人 ADMIN）', async () => {
        expect(returnLedgerRowsOf(ctx.applyId), '审核通过只该多一行').toBe(2)
        expect(returnReasonsOf(ctx.applyId), '原因码序列应是「提交 → 审核通过」')
          .toBe('RETURN_CREATE、RETURN_APPROVE')
        expect(returnFlowsOf(ctx.applyId), '流转序列应是「空→0、0→1」').toBe('空→0、0→1')

        const pass = returnRowOfReason(ctx.applyId, 'RETURN_APPROVE')
        expect(pass.fromStatus, '审核通过这一行从「待处理」改出来').toBe('0')
        expect(pass.toStatus, '审核通过这一行落到「已通过(1)」').toBe('1')
        expect(pass.operatorType, '审核是后台动作，操作人类型必须是 ADMIN').toBe('ADMIN')
        expect(pass.operatorName, '操作人名字快照应是审核的管理员').toBe('admin')
        expect(pass.orderId, 'order_id 冗余列必须仍指着这张订单').toBe(String(ctx.orderId))
        // 审核意见走 reason_detail（自由文本列）。这里的事实（读实现确认过，不是猜）：
        // 审核弹窗是「整行回填」的 —— `<el-input v-model="form.description">` 里装的正是买家原说明
        // （admin-web/src/views/return-apply/index.vue:57-58 + :146 的 Object.assign(form, row)），
        // 点「通过」时不改写就直接提交，于是 `description` 非空 → 后端把它连同状态一起写进
        // return_apply.description 与售后台账的 reason_detail（ReturnApplyController.java:229-236）。
        // 所以通过行的 reason_detail = 买家原说明，而不是空串。
        expect(pass.reasonDetail, '通过行的 reason_detail 应是买家原说明（弹窗整行回填、通过时没改写）')
          .toBe(ctx.buyerNote)
      })

      await test.step('⑦ 库：每一行都带着订单 id，幂等键两把序号不同（同一瞬重放只会撞同一把键）', async () => {
        expect(returnOrderIdSnapshotsOf(ctx.applyId),
          '两行的 order_id 冗余列都必须等于这张订单的 id')
          .toBe(`${ctx.orderId}、${ctx.orderId}`)
        expect(dbCount(`select count(*) from return_status_ledger
                         where return_apply_id = ${ctx.applyId} and order_id is null`),
          '售后台账不许有 order_id 为空的行走漏（漏一行就少一条可反查的证据）').toBe(0)

        const keys = returnIdemKeysOf(ctx.applyId)
        expect(keys.length, '两次流转两把幂等键').toBe(2)
        for (const k of keys) {
          expect(k, `幂等键应是「申请ID:流转序号」，实际：${k}`)
            .toMatch(new RegExp(`^${ctx.applyId}:\\d+$`))
        }
        expect(new Set(keys).size, '两把键必须互不相同（否则就是同一行被写了两次）').toBe(2)
      })

      await test.step('⑧ 联动：订单变已退款(6) + 支付流水出现一条退款行（金额=实付，指向原支付）', async () => {
        expect((await getAdminOrderBySnApi(request, ctx.token, ctx.orderSn)).status,
          '审核通过后订单应为已退款(6)').toBe(6)
        const closed = orderRowOfReason(ctx.orderId, 'RETURN_APPROVE')
        expect(closed.fromStatus, '订单台账这一行从退款中(5)改出来').toBe('5')
        expect(closed.toStatus, '订单台账这一行落到已退款(6)').toBe('6')

        const records = paymentRecordsOf(ctx.orderSn)
        const refunds = records.filter((r) => r.type === 2)
        expect(refunds.length, '审核通过后应恰好有一条退款流水').toBe(1)
        expect(refunds[0].amount, '退款金额应等于订单实付').toBeCloseTo(ctx.totalAmount, 2)
        expect(refunds[0].tradeNo, '退款流水号应带 MOCKREF 前缀').toMatch(/^MOCKREF/)
        expect(records.filter((r) => r.type === 1).length, '支付流水应仍是那一条，没有被复制').toBe(1)
      })

      await test.step('⑨ 台账页：类型「售后状态」+ 订单 id → 两行，能看出「待处理 → 已通过」', async () => {
        const ledgerIds = returnLedgerIdsOf(ctx.applyId)
        expect(ledgerIds.length, '库里应有这两行').toBe(2)
        const table = await expectReturnLedgerOnPage(page, {
          bizId: ctx.orderId,
          ledgerIds,
          rows: mirroredReturnRowsOf(ctx.orderId),
          labels: [
            ['待处理', '页面应能看到出生行落到的「待处理」'],
            ['已通过', '页面应能看到审核通过后的「已通过」'],
            ['RETURN_CREATE', '页面应显示出生行的原因码'],
            ['RETURN_APPROVE', '页面应显示审核通过的原因码'],
            ['管理员', '操作人类型 ADMIN 应翻成「管理员」'],
            ['admin', '操作人列应显示审核管理员的登录名']
          ],
          message: '台账页应能按订单 id 看到这张申请的两行售后流水'
        })
        // 删单前业务单号列仍是订单号（RETURN 分支 LEFT JOIN order 取 bizSn）
        const cell = await bizSnCellTextOf(page, ledgerIds[1])
        expect(cell, `业务单号列应显示订单号 ${ctx.orderSn}，实际：${cell}`).toContain(ctx.orderSn)
        await expect(table, '摘要列应能读出售后与订单的对应关系').toContainText(`售后 #${ctx.applyId}`)
      })
    } finally {
      await cleanupSelfMadeOrder(request, ctx)
      await cleanupBuyer(request, ctx)
    }
  })

  test('用例三：审核拒绝 → 订单回包裹聚合状态；订单被物理删除后，售后台账仍能按订单ID反查（order_id 冗余列的理由）', async ({ page, request }) => {
    test.setTimeout(300_000)
    const ctx = {}
    let beforeIds = []
    let beforeReasons = ''
    let orderSnForLog = null
    let rejectReason = ''
    try {
      await test.step('前置：下单 → 审核 → 出库 → 申请退货（申请待处理）', async () => {
        Object.assign(ctx, await prepareShippedOrderWithReturn(page, request, '13-售后台账/用例三'))
        orderSnForLog = ctx.orderSn
      })

      await test.step('⑤ 管理员审核「拒绝」并填拒绝理由', async () => {
        // 拒绝理由必须换成我们自己写的那句：它会被断言进售后台账的 reason_detail
        rejectReason = '商品已拆封使用，影响二次销售，不符合退货条件'
        await auditReturnApply(page, ctx.orderSn, { result: '拒绝', description: rejectReason })
        await expectToastSuccess(page, '审核完成')
      })

      await test.step('⑥ 库：两行 —— 出生行 + 拒绝行（0→2 / RETURN_REJECT / 操作人 ADMIN，拒绝理由进 reason_detail）', async () => {
        expect(returnLedgerRowsOf(ctx.applyId), '审核拒绝只该多一行').toBe(2)
        expect(returnReasonsOf(ctx.applyId), '原因码序列应是「提交 → 审核拒绝」')
          .toBe('RETURN_CREATE、RETURN_REJECT')
        expect(returnFlowsOf(ctx.applyId), '流转序列应是「空→0、0→2」').toBe('空→0、0→2')

        const reject = returnRowOfReason(ctx.applyId, 'RETURN_REJECT')
        expect(reject.fromStatus, '拒绝这一行从「待处理」改出来').toBe('0')
        expect(reject.toStatus, '拒绝这一行落到「已拒绝(2)」').toBe('2')
        expect(reject.operatorType, '审核是后台动作，操作人类型必须是 ADMIN').toBe('ADMIN')
        expect(reject.operatorName, '操作人名字快照应是审核的管理员').toBe('admin')
        expect(reject.reasonDetail, '拒绝理由应落在 reason_detail（自由文本）里').toContain(rejectReason)
        expect(reject.orderId, 'order_id 冗余列必须仍指着这张订单').toBe(String(ctx.orderId))

        beforeIds = returnLedgerIdsOf(ctx.applyId)
        beforeReasons = returnReasonsOf(ctx.applyId)
      })

      await test.step('⑦ 订单回到「包裹聚合」推导出来的状态（本用例只做到出库 → 2 已发货，不是常量 1）', async () => {
        // 依据：ReturnApplyController.java:280（有未取消包裹 → 交聚合）+ DeliveryFlowService.java:534-549
        // （全部子单 ≥ 已出库 → 2 已发货；全部 ≥ 已签收 → 3 已完成；都没出库 → 8 待发货）
        const orderAfter = await getAdminOrderBySnApi(request, ctx.token, ctx.orderSn)
        const deliveries = await listDeliveriesApi(request, ctx.token, orderAfter.id)
        const active = deliveries.filter((d) => d.status !== DELIVERY_CANCELLED)
        const expected = aggregateOrderStatusOf(active)
        console.log(`[13-售后台账/用例三] 拒绝后订单 ${ctx.orderSn}(id=${ctx.orderId}) status=${orderAfter.status}` +
          `(${ORDER_STATUS_TEXT[orderAfter.status]})；包裹 ${deliveries.length} 张` +
          `[${deliveries.map((d) => `${d.deliverySn}:${deliveryStatusText(d.status)}`).join(', ')}]` +
          `；未取消 ${active.length} 张 → 聚合期望 ${expected}(${ORDER_STATUS_TEXT[expected]})`)

        expect(active.length, '断言前提：前置步骤③ 出库后应留下未取消的包裹（否则会退化成直发单路径）')
          .toBeGreaterThan(0)
        expect(expected, '本用例只做到「出库」不签收，聚合结果就是已发货(2)').toBe(2)
        expect(orderAfter.status,
          `拒绝后订单应由包裹聚合推导：未取消包裹 `
          + `[${active.map((d) => deliveryStatusText(d.status)).join(', ')}] → 期望 `
          + `${expected}(${ORDER_STATUS_TEXT[expected]})，实际 ${orderAfter.status}(${ORDER_STATUS_TEXT[orderAfter.status]})`)
          .toBe(expected)
        expect(orderAfter.status, '拒绝不是退款：订单不该是已付款(1)（包裹已出库，父单不可能退回到没发货）').not.toBe(1)
        expect(orderAfter.status, '审核已拒绝，订单不该仍停在退款中(5)').not.toBe(5)

        // 订单台账那一边：5 → 聚合值，原因码是 DELIVERY_AGGREGATE（不是 RETURN_REJECT —— 那是申请侧的）
        // 注意：同一张订单的订单台账里 DELIVERY_AGGREGATE 不止一行 —— 出库把父单 8→2 时也写过一行。
        // 拒绝这一行是**最后**写的那行（5→2），所以要取 last，而不是 limit 1 的默认顺序。
        const aggregateRow = orderRowOfReason(ctx.orderId, 'DELIVERY_AGGREGATE', { last: true })
        expect(aggregateRow.fromStatus, '订单台账这一行从退款中(5)改出来').toBe('5')
        expect(aggregateRow.toStatus, '订单台账这一行落到聚合值(2)').toBe('2')
        expect(returnReasonsOf(ctx.applyId), '售后台账里不该出现聚合原因码').not.toContain('DELIVERY_AGGREGATE')
      })

      await test.step('⑧ 台账页（删单前）：按订单 id 能看到两行，且能看出「已拒绝」这一条', async () => {
        await expectReturnLedgerOnPage(page, {
          bizId: ctx.orderId,
          ledgerIds: beforeIds,
          rows: mirroredReturnRowsOf(ctx.orderId),
          labels: [
            ['已拒绝', '页面应把后态 2 翻成「已拒绝」'],
            ['RETURN_REJECT', '页面应显示审核拒绝的原因码'],
            ['不符合退货条件', '拒绝理由应显示在原因补充里'],
            ['管理员', '操作人类型 ADMIN 应翻成「管理员」']
          ],
          message: '台账页应能按订单 id 看到「提交 → 拒绝」两行'
        })
      })

      await test.step('⑨ 断掉删单守卫的依据（物流行 / 退货申请 / 未取消且已出库的包裹），然后真删这张单', async () => {
        // 守卫口径见 OrderPurgeService#blockReason：有物流 / 有退货申请 / 有未取消且已出库的包裹 → 拒删。
        // 这里不是「绕过守卫」，而是把这张单还原成「还没出货」的形状 —— 守卫拒删的理由本身就不成立。
        // （删掉 return_apply 行正是本用例要证明的「申请随订单消失」的前置。）
        queryDb(`delete from delivery_logistics
                  where delivery_id in (select id from delivery_order where order_id = ${ctx.orderId})`)
        queryDb(`delete from return_apply where order_id = ${ctx.orderId}`)
        queryDb(`update delivery_order set status = 0 where order_id = ${ctx.orderId} and status <> 9`)
        expect(dbCount(`select count(*) from return_apply where order_id = ${ctx.orderId}`),
          '退货申请行应已清空（删单守卫的依据之一）').toBe(0)

        const res = await request.delete(`${API}/admin/orders/${ctx.orderId}`, {
          headers: { Authorization: `Bearer ${ctx.token}` }
        })
        const body = await res.json()
        expect(body.code, `删单应成功，实际：${body.message}`).toBe(0)
        ctx.orderSn = null      // 已经删掉了，finally 不用再清
      })

      await test.step('⑩ 库：订单与申请都没了 —— 证据的目标消失了', async () => {
        expect(dbCount(`select count(*) from \`order\` where id = ${ctx.orderId}`), '订单应被物理删除').toBe(0)
        expect(dbCount(`select count(*) from return_apply where order_id = ${ctx.orderId}`),
          '退货申请应随订单一起消失').toBe(0)
        expect(dbCount(`select count(*) from delivery_order where order_id = ${ctx.orderId}`),
          '订单名下的包裹也应被物理删除（delivery_order 这座桥断了）').toBe(0)
      })

      await test.step('⑪ 关键断言：售后台账一行不少，每行的 order_id 冗余仍等于那个订单 id', async () => {
        expect(returnLedgerRowsOf(ctx.applyId), '申请行没了，但售后台账一行都不能少').toBe(beforeIds.length)
        expect(returnLedgerIdsOf(ctx.applyId), '台账行的 id 也没变（不是删了又补的新行）').toEqual(beforeIds)
        expect(returnReasonsOf(ctx.applyId), '它走过的每一步都还读得出来').toBe(beforeReasons)

        const snaps = returnOrderIdSnapshotsOf(ctx.applyId).split('、')
        expect(snaps.length).toBe(beforeIds.length)
        expect(snaps.every((s) => s === String(ctx.orderId)),
          `每一行的 order_id 冗余必须仍是 ${ctx.orderId}（申请与订单都没了，这是唯一的反查通道），`
          + `实际：${snaps.join('、')}`).toBe(true)
        expect(dbCount(`select count(*) from return_status_ledger
                         where return_apply_id = ${ctx.applyId} and order_id is null`),
          '不许有 order_id 为空的售后台账行').toBe(0)
      })

      await test.step('⑫ 台账页：按订单 id 仍查得到这两行（走的就是 order_id 冗余列）', async () => {
        const mirrored = mirroredReturnRowsOf(ctx.orderId)
        const table = await expectReturnLedgerOnPage(page, {
          bizId: ctx.orderId,
          ledgerIds: beforeIds,
          rows: mirrored,
          labels: [
            ['已拒绝', '删单后页面仍应把后态 2 翻成「已拒绝」'],
            ['RETURN_REJECT', '删单后页面仍应显示审核拒绝的原因码'],
            ['管理员', '删单后操作人快照与类型不应丢']
          ],
          message: '订单与申请都已被物理删除，仍应能按订单 id 查到售后流水'
        })

        // 单号快照：return_status_ledger 没有 order_sn 列，bizSn 是 LEFT JOIN order 取来的，
        // 订单删掉后这个 JOIN 必然为空 → 「业务单号」列只剩「—」+「ID <售后ID>」，
        // 订单号只保留在摘要里（COALESCE 到 CAST(order_id AS CHAR)）。这是现状，不是缺陷掩盖。
        const cell = await bizSnCellTextOf(page, beforeIds[0])
        expect(cell, `删单后业务单号列应显示「—」（没有 order_sn 快照列），实际：${cell}`)
          .toContain('—')
        expect(cell, `业务单号列的次行仍是「ID <售后ID>」= ID ${ctx.applyId}，实际：${cell}`)
          .toContain(`ID ${ctx.applyId}`)
        await expect(table, `摘要列应把订单 id 兜住（订单号已随订单删除）：售后 #${ctx.applyId}（订单 ${ctx.orderId}）`)
          .toContainText(`订单 ${ctx.orderId}`)
        await expect(table, `订单号 ${orderSnForLog} 已随订单物理删除，页面上不该再出现`)
          .not.toContainText(orderSnForLog)
      })
    } finally {
      await cleanupSelfMadeOrder(request, ctx)
      await cleanupBuyer(request, ctx)
    }
  })
})
