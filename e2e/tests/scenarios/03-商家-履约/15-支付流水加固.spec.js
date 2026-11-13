import { test, expect } from '../../support/test.js'
import { API, ADMIN, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { tid, tidPrefix } from '../../support/tid.js'
import { memberNickOf, receiverOf } from '../../support/fixtures.js'
import { memberLoginOf } from '../../support/member-login.js'
import {
  adminLoginHardened,
  adminAuditOrderPass,
  returnApplyRow,
  returnApplyStatusOf,
  reloadReturnApplyList,
  auditReturnApply,
  expectToastSuccess,
  memberApplyReturn,
} from '../../support/admin-ui-return.js'
import {
  adminSetSkuStock,
  adminSearchOrderRow,
  openOrderDrawer,
  clickDispatch,
  clickWmsCallback,
  memberRegisterAndLogin,
  addSpecToCart,
  submitCartOrder,
} from '../../support/ui.js'
import { adminTokenOf, getProductApi, getAdminOrderBySnApi } from '../../support/assert.js'

/**
 * 03-商家-履约 / 15-支付流水加固
 *
 * 这个场景要证明的一件事：
 *   **钱进来的每一次、出去的每一次都能对上订单，也能对上前一笔 ——
 *     而且同一笔钱不会被记两次（渠道流水号 + 本地幂等键两道闸）。**
 *
 * ── 本文件此前为什么是 fixme（两个**真实缺口**，不是过期理由）────────────────
 *   ① 台账查询的六类型里没有 PAYMENT（订单/包裹/售后/审计/WMS/库存），
 *      于是「页面上看得见支付流水」这句话根本写不出来 —— 只有直查库的用例不算场景验证；
 *   ② 没有可重放的渠道回调：模拟支付的 trade_no 是本地按时间戳生成的，
 *      「第三方把同一笔通知又发了一次」这个场景造不出来，uk_channel_trade 只能是注释里的声明。
 *
 *   本轮把两件事都补上了（后端 + 前端 + 开关），三条用例才写实：
 *   · 台账 `GET /admin/ledgers?type=PAYMENT`（LedgerSql.PAYMENT_BRANCH）＋ 前端类型下拉「支付流水」；
 *   · `POST /admin/payments/{id}/replay-channel-callback`（用原 trade_no 再走一遍渠道回调，
 *     受 `qiqi.payment.sandbox-endpoints-enabled` 控制，默认 false，只有 dev/test/e2e 打开；
 *     写权限 payment:write 不可授予 ⇒ API Key 403，只有人工 JWT 能调）。
 *
 * ── 三条用例各钉住一件事 ─────────────────────────────────────────────────
 *   ① 页面上看得见：台账页选「支付流水」+ 业务ID（订单ID）→ 那一行带着订单号、渠道流水号、操作人；
 *   ② 重复通知被挡：重放渠道回调两次 → 都是「重复通知，已忽略」，库里仍 1 行、金额不变，
 *      并且 uk_channel_trade 是**活闸**（直插同 channel+trade_no 会被库拒绝，不是代码自觉）；
 *   ③ 退款可溯源：退款流水带 `{orderSn}:REFUND:{returnApplyId}` 幂等键，
 *      origin_flow_id 指回原支付流水（退的是哪一笔钱，靠这一列回答）。
 *
 * ── 两条口径（写断言前先读）──────────────────────────────────────────────
 *   · `payment_record` 与 `order` **没有外键**：order_sn 是唯一对账键，order_id 允许悬空；
 *     删单时由 OrderPurgeService 按 order_sn 显式清理流水（不是级联），
 *     所以本文件的清理要么走删单接口、要么按单号显式删。
 *   · 台账按 operatorType 筛选时 PAYMENT 支**照实过滤**（`p.operator_type = #{operatorType}`，见
 *     `LedgerSql#PAYMENT_FILTER`）：`payment_record` 有 operator 三列且落的是真值（ActorResolver 记
 *     「谁点的这个动作」：买家付 MEMBER、管理员批退货触发的退款 ADMIN）。**只有 STOCK 支整支不参与**
 *     （`1 = 0`）—— 那张表没有 operator 列，它给的是编出来的 'SYSTEM' 常量。
 *     本文件的三条用例不筛 operatorType，这条口径只作写断言时的背景。
 */

// ---------------------------------------------------------------------------
// 只读断言通道（直查库）
// ---------------------------------------------------------------------------

const dbOne = (sql) => queryDb(sql).split('\t')
const dbNum = (sql) => Number(queryDb(sql) || 0)

/** 该订单的支付流水（type=1，应当恰好一条） */
const payFlowOf = (orderSn) => dbOne(
  `select id, trade_no, operator_name, operator_type, channel, amount
     from payment_record where order_sn = '${orderSn}' and type = 1 order by id limit 1`
)

/** 该订单在支付流水表里的行数 / 金额合计（「钱有没有被记两遍」的两个事实） */
const flowCountOf = (orderSn) => dbNum(`select count(*) from payment_record where order_sn = '${orderSn}'`)
const flowSumOf = (orderSn) =>
  Number(queryDb(`select coalesce(sum(amount), 0) from payment_record where order_sn = '${orderSn}'`))

/** 该订单的退款流水（type=2） */
const refundFlowsOf = (orderSn) => dbNum(
  `select count(*) from payment_record where order_sn = '${orderSn}' and type = 2`
)
const refundFlowOf = (orderSn) => dbOne(
  `select id, idem_key, ref_type, ref_id, origin_flow_id, amount
     from payment_record where order_sn = '${orderSn}' and type = 2 order by id limit 1`
)

/** 退款申请 id（退货审核通过时写进退款流水 ref_id 的那张申请） */
const returnApplyIdOf = (orderId) => dbNum(
  `select id from return_apply where order_id = ${orderId} order by id limit 1`
)

// ---------------------------------------------------------------------------
// 台账页（UI 侧）
// ---------------------------------------------------------------------------

const gotoLedger = async (page) => {
  await page.goto(`${ADMIN}/ledger`)
  await expect(tid(page, 'ledger-list-table'), '台账查询页应打开（ledger-list-table）').toBeVisible({ timeout: 20_000 })
}

/**
 * 用筛选区收敛到「支付流水 + 某订单ID」。
 *
 * 顺带断言类型下拉里**确实有**「支付流水」这一项 —— 这正是本轮前端改动（constants/status.js →
 * LEDGER_TYPE_OPTIONS 自动长出这一项）的 DOM 证据：选得到才说明下拉加上了。
 */
const filterLedgerByPayment = async (page, orderId) => {
  await tid(page, 'ledger-type-select').click()
  const option = page.locator('.el-select-dropdown__item:visible').filter({ hasText: '支付流水' }).first()
  await expect(option, '台账页的类型下拉里必须有「支付流水」（本轮新增的第七类）').toBeVisible({ timeout: 10_000 })
  await option.click()
  await tid(page, 'ledger-biz-id-input').fill(String(orderId))
  await tid(page, 'ledger-search-submit').click()
  await page.waitForTimeout(1200)
}

const ledgerRows = (page) => tidPrefix(page, 'ledger-row-')

// ---------------------------------------------------------------------------
// 数据构造（真实 UI 全链路，不碰接口推进状态）
// ---------------------------------------------------------------------------

/**
 * 造一张「已支付」的订单：管理员备货 → 新买家注册 → 加购 → 结算支付。
 *
 * 结算页提交后会自动模拟支付（见 ui.js submitCartOrder），订单直接进「待审核(7)」，
 * 同时写下一条 type=1 的支付流水 —— 这就是本条用例要证明「页面上看得见」的那笔钱。
 *
 * @returns {{ orderSn, orderId, username, totalAmount }}
 */
async function preparePaidOrder(page, request) {
  const username = memberLoginOf(stamp())
  let orderSn
  let orderId
  let totalAmount

  await test.step('① 管理员先把 1 号商品首个 SKU 备到 50 件', async () => {
    await adminSetSkuStock(page, 1, 0, 50)
  })

  await test.step('② 新买家注册 → 加购一件 → 结算（自动模拟支付）', async () => {
    await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
    const { skus } = await getProductApi(request, 1)
    await addSpecToCart(page, 1, skus[0].specText, skus[0].id)
    orderSn = await submitCartOrder(page, receiverOf(username))
    // 订单 id / 金额从库里读：此时页面已经在买家端，admin 的 localStorage 取不到 admin token
    orderId = dbNum(`select id from \`order\` where order_sn = '${orderSn}'`)
    totalAmount = Number(queryDb(`select total_amount from \`order\` where order_sn = '${orderSn}'`))
  })

  return { orderSn, orderId, username, totalAmount }
}

/** 清理：删单会带走退货申请（外键级联）与支付流水（OrderPurgeService 按 order_sn 显式删） */
async function cleanupSelfMadeOrder(request, { orderId, orderSn, token }) {
  const id = orderId
    || (orderSn ? Number(queryDb(`select id from \`order\` where order_sn = '${orderSn}'`) || 0) : 0)
  if (!id) return
  if (dbNum(`select count(*) from \`order\` where id = ${id}`) === 0) return
  try {
    // 删单守卫要求「没有在途包裹 / 待处理申请」，所以先把这些压平（只改自造数据）
    queryDb(`delete from delivery_logistics where delivery_id in
               (select id from delivery_order where order_id = ${id})`)
    queryDb(`delete from return_apply where order_id = ${id}`)
    queryDb(`update delivery_order set status = 0 where order_id = ${id} and status <> 9`)
    const res = await request.delete(`${API}/admin/orders/${id}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const body = await res.json()
    if (body.code !== 0) console.warn(`[15-支付流水加固] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[15-支付流水加固] 清理抛错（orderId=${id}）：${e.message}`)
  }
  // 兜底：payment_record 与 order 没有外键，万一删单路径没带走流水，这里按单号补一刀
  if (orderSn) queryDb(`delete from payment_record where order_sn = '${orderSn}'`)
}

/** 重放一次渠道回调（人工 JWT）：返回 { status, body } */
async function replayChannelCallback(request, token, paymentId) {
  const res = await request.post(`${API}/admin/payments/${paymentId}/replay-channel-callback`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return { status: res.status(), body: await res.json() }
}

// ---------------------------------------------------------------------------

test.describe('03-商家-履约 › 15-支付流水加固', () => {
  test('用例一：台账页选「支付流水」能看到这笔钱 —— 订单号 / 渠道流水号 / 操作人都在行上', async ({ page, request }) => {
    let orderSn = null
    let orderId = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)

      const ctx = await preparePaidOrder(page, request)
      orderSn = ctx.orderSn
      orderId = ctx.orderId

      const [payId, tradeNo, operatorName, operatorType, channel, amount] = payFlowOf(orderSn)
      console.log(`[15-①] 订单 ${orderSn}(id=${orderId}) 支付流水 id=${payId} trade_no=${tradeNo} `
        + `operator=${operatorName}/${operatorType} channel=${channel} amount=${amount}`)

      await test.step('③ 库里先确认事实：一条 type=1 支付流水，带渠道流水号与操作人', async () => {
        expect(flowCountOf(orderSn), '支付后应恰好有一条支付流水').toBe(1)
        expect(tradeNo, '支付流水必须有渠道流水号（trade_no）').toBeTruthy()
        expect(operatorName, '支付流水必须记下操作人（谁点的支付）').toBeTruthy()
        expect(operatorType, '买家用 portal 支付 → 操作人类型是 MEMBER').toBe('MEMBER')
        expect(Number(amount), '流水金额应等于订单总额').toBeCloseTo(ctx.totalAmount, 2)
      })

      await test.step('④ 页面上看得见：类型下拉「支付流水」+ 业务ID = 订单ID → 恰好一行，三样都在', async () => {
        await gotoLedger(page)
        await filterLedgerByPayment(page, orderId)

        const table = tid(page, 'ledger-list-table')
        // 类型列：这一行的类型标签就是「支付流水」
        await expect(table, '筛选后表格里应有「支付流水」类型的行').toContainText('支付流水')
        // 业务单号列：订单号快照 + ID
        await expect(table, `行上必须能看到订单号 ${orderSn}`).toContainText(orderSn)
        await expect(table, `行上必须能看到业务ID ${orderId}`).toContainText(`ID ${orderId}`)
        // 原因列：中文语义（钱进/钱出），下面那行小字是渠道流水号
        await expect(table, '原因列应显示「支付入账」').toContainText('支付入账')
        await expect(table, `原因下面应能看到渠道流水号 ${tradeNo}`).toContainText(tradeNo)
        // 操作人列：名字 + 类型标签
        await expect(table, `操作人列应显示操作人 ${operatorName}`).toContainText(operatorName)
        await expect(table, '操作人类型标签应是「会员」').toContainText('会员')
        // 收敛到一行：这条单只有一笔支付
        await expect(ledgerRows(page), '该订单应恰好一行支付流水').toHaveCount(1)
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二：渠道重复通知不会变成两笔钱 —— uk_channel_trade 是最后一道闸', async ({ page, request }) => {
    test.setTimeout(180_000)
    let orderSn = null
    let orderId = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)

      const ctx = await preparePaidOrder(page, request)
      orderSn = ctx.orderSn
      orderId = ctx.orderId

      const [payId, tradeNo, , , channel] = payFlowOf(orderSn)
      const rowsBefore = flowCountOf(orderSn)
      const sumBefore = flowSumOf(orderSn)

      await test.step('③ 重放渠道回调两次：都是 code=0「重复通知，已忽略」（重复是幂等的）', async () => {
        const first = await replayChannelCallback(request, token, payId)
        const second = await replayChannelCallback(request, token, payId)
        console.log(`[15-②] 重放 #1 → HTTP ${first.status} ${JSON.stringify(first.body)}`)
        console.log(`[15-②] 重放 #2 → HTTP ${second.status} ${JSON.stringify(second.body)}`)

        for (const [i, r] of [first, second].entries()) {
          expect(r.status, `第 ${i + 1} 次重放的 HTTP 状态`).toBe(200)
          expect(r.body.code, `第 ${i + 1} 次重放：重复通知不是错误，code=0`).toBe(0)
          expect(r.body.message, `第 ${i + 1} 次重放必须给出明确文案`).toBe('重复通知，已忽略')
          expect(r.body.data?.outcome, '结果口径：DUPLICATE').toBe('DUPLICATE')
          expect(r.body.data?.recorded, '这一次没有记账').toBe(false)
          expect(r.body.data?.tradeNo, '回报里带着被重放的渠道流水号').toBe(tradeNo)
        }
      })

      await test.step('④ 库里的事实：仍是 1 行、金额总和不变（只看返回码不够）', async () => {
        expect(flowCountOf(orderSn), '重放两次之后仍然只有一条流水').toBe(rowsBefore)
        expect(flowCountOf(orderSn), '前提：本来就只有一条').toBe(1)
        expect(flowSumOf(orderSn), '金额总和也不许变（否则可能是「改了那一行」而不是「没多记」）')
          .toBeCloseTo(sumBefore, 2)
        expect(refundFlowsOf(orderSn), '重放支付通知不该产生退款流水').toBe(0)
      })

      await test.step('⑤ uk_channel_trade 是活闸：索引在库上，且直插同一个 (channel, trade_no) 会被拒', async () => {
        const idx = queryDb(
          `select group_concat(column_name order by seq_in_index) from information_schema.statistics
            where table_schema = database() and table_name = 'payment_record'
              and index_name = 'uk_channel_trade' and non_unique = 0`
        )
        expect(idx, 'payment_record 上必须有唯一索引 uk_channel_trade(channel, trade_no)').toBe('channel,trade_no')

        // 直接 SQL 再插一条「同 channel + 同 trade_no、但换一个幂等键」→ 必须被库拒绝。
        // 这一步排掉一种误判：上面两次重放也可能是被 uk_idem 挡的（两条唯一键会同时被撞），
        // 换掉幂等键后还能被拒，说明 channel+trade_no 这道闸**自己**是活的。
        // （这条 INSERT 注定失败，不会写进任何行；它只是把「约束存在」变成「约束在跑」。）
        let probeError = null
        try {
          queryDb(`insert into payment_record (order_sn, order_id, amount, type, channel, trade_no, idem_key)
                   values ('${orderSn}', ${orderId}, ${sumBefore}, 1, ${channel}, '${tradeNo}', '${orderSn}:PAY:E2E-PROBE')`)
        } catch (e) {
          probeError = `${e.message || ''} ${e.stderr || ''}`
        }
        console.log(`[15-②] 直插同 (channel=${channel}, trade_no=${tradeNo}) + 新幂等键 → ${probeError || '竟然成功了'}`)
        expect(probeError, '同 (channel, trade_no) 直插必须被库拒绝（uk_channel_trade）').toBeTruthy()
        expect(String(probeError), '撞的必须是 uk_channel_trade，而不是别的键').toContain('uk_channel_trade')
        expect(flowCountOf(orderSn), '被拒之后仍然只有一条流水').toBe(1)
      })

      await test.step('⑥ 页面同样只看得见一行（钱没有被记两遍，人在页面上也能确认）', async () => {
        await gotoLedger(page)
        await filterLedgerByPayment(page, orderId)
        await expect(ledgerRows(page), '台账页「支付流水」下仍然只有一行').toHaveCount(1)
        await expect(tid(page, 'ledger-list-table'), '这一行仍然是那笔支付').toContainText(tradeNo)
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三：退款流水带 {orderSn}:REFUND:{returnApplyId} 幂等键，并把 origin_flow_id 指回原支付流水', async ({ page, request }) => {
    test.setTimeout(180_000)
    let orderSn = null
    let orderId = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)

      const ctx = await preparePaidOrder(page, request)
      orderSn = ctx.orderSn
      orderId = ctx.orderId
      const [payId] = payFlowOf(orderSn)

      await test.step('③ 审核通过 → 下发 → 模拟 WMS 出库（订单变已发货，才有退货入口）', async () => {
        const row = await adminSearchOrderRow(page, orderSn)
        await adminAuditOrderPass(page, row)
        const deliveryTable = await openOrderDrawer(page, orderSn)
        const dRow = deliveryTable.locator('.el-table__body tr').first()
        await clickDispatch(page, dRow)
        await clickWmsCallback(page, dRow, '出库', '已出库')
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '出库后订单应为已发货(2)').toBe(2)
      })

      await test.step('④ 买家申请退货 → 订单转退款中', async () => {
        await memberApplyReturn(page, orderSn)
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '申请后订单应为退款中(5)').toBe(5)
      })

      await test.step('⑤ 后台审核通过 → 触发退款入账', async () => {
        await auditReturnApply(page, orderSn, { result: '通过' })
        await expectToastSuccess(page, '审核完成')
        await reloadReturnApplyList(page)
        const row = await returnApplyRow(page, orderSn)
        expect(await returnApplyStatusOf(row), '申请应显示「已通过」').toBe('已通过')
        expect((await getAdminOrderBySnApi(request, token, orderSn)).status, '审核通过后订单应为已退款(6)').toBe(6)
      })

      await test.step('⑥ 溯源字段：幂等键 + origin_flow_id + 触发单据三件都在', async () => {
        const applyId = returnApplyIdOf(orderId)
        expect(applyId, '库里应有这张退货申请').toBeGreaterThan(0)
        expect(refundFlowsOf(orderSn), '审核通过后应恰好有一条退款流水').toBe(1)

        const [refundId, idemKey, refType, refId, originFlowId, refundAmount] = refundFlowOf(orderSn)
        console.log(`[15-③] 退款流水 id=${refundId} idem_key=${idemKey} ref=${refType}#${refId} `
          + `origin_flow_id=${originFlowId}（原支付流水 id=${payId}）金额=${refundAmount}`)

        // ① 幂等键口径：{orderSn}:REFUND:{退货申请id}
        expect(idemKey, '退款幂等键必须带申请 id —— 同一张申请重放不该退第二条')
          .toBe(`${orderSn}:REFUND:${applyId}`)
        // ② 溯源：这笔退款退的是哪一笔钱
        expect(Number(originFlowId), 'origin_flow_id 必须指回原支付流水')
          .toBe(Number(payId))
        expect(dbNum(`select count(*) from payment_record where id = ${originFlowId} and type = 1`),
          'origin_flow_id 指向的那条支付流水必须真的在库里（不是悬空 id）').toBe(1)
        // ③ 触发单据：谁让它退的
        expect(refType, '退款流水的触发单据类型').toBe('RETURN_APPLY')
        expect(Number(refId), '触发单据 id = 那张退货申请').toBe(applyId)
        expect(Number(refundAmount), '退款金额应等于订单实付').toBeCloseTo(ctx.totalAmount, 2)
        // ④ 支付那条没有被复制
        expect(flowCountOf(orderSn), '一条支付 + 一条退款 = 2 行').toBe(2)
      })

      await test.step('⑦ 页面上同样读得到溯源：退款行摘要里带着「原流水 #<支付流水id>」', async () => {
        await gotoLedger(page)
        await filterLedgerByPayment(page, orderId)
        const table = tid(page, 'ledger-list-table')
        await expect(ledgerRows(page), '该订单应有支付 + 退款两行').toHaveCount(2)
        await expect(table, '退款行的原因列应显示「退款入账」').toContainText('退款入账')
        await expect(table, `退款行的摘要应能读回原支付流水 #${payId}（页面上也能溯源）`)
          .toContainText(`原流水 #${payId}`)
        await expect(table, '退款行的关联单据应显示 RETURN_APPLY').toContainText('RETURN_APPLY')
      })
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })
})
