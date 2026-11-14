/**
 * 自动审核场景（业务/订单-自动审核）的**造数 / 复位 / 库上事实** —— 五个 spec 共用这一份。
 *
 * ── 为什么六笔单走 seed SQL，而不是每个用例各自建单 ────────────────────────
 * 六笔演示单（QT-1001..QT-1006）的关键事实有两类**页面上没有入口**：
 *   · 订单侧：渠道 / 店铺 / 毛利 / 客服备注 / 多地址标记（`order.channel|shop_name|gross_profit|cs_remark`）
 *     —— 后台「新增订单」弹窗只能填会员/收货人/明细/状态，填不了这些列；
 *   · 会员侧：客户标签（`member.customer_tag`，直营-小单策略的唯一命中依据）。
 * 所以这份数据只能由 `sql/seed-order-audit-demo.sql` 铺（它本身就是幂等的：已存在的策略/单号整条跳过）。
 * 造数口径沿用 support/seed-api.js 的三条自我约束：**只摆起点的状态，绝不替断言**。
 *
 * ── 为什么要「复位基线」而不是「清理自造订单」────────────────────────────
 * 这六笔单是被测样本，不是一次性数据：跑过一轮以后 QT-1001 已经是 8 待发货、QT-1005 已经冻上、
 * QT-1006 已经被人工关单。用例要能**重复跑**，就必须把样本还原成 seed 基线
 * （status=7 / status_seq=0 / 无决策、无标、无状态台账、退款申请回到待处理、演示 SKU 库存回 seed 值）。
 * 复原只碰「本用例确实动过的那几行」，不 TRUNCATE、不碰别的单 —— 所以它也是幂等的。
 *
 * ── 为什么还要顺手关掉定时任务 ────────────────────────────────────────────
 * 后端 `AuditScheduleJob` 默认开启（`qiqi.audit.schedule-enabled=true`，启动 5 分钟后每分钟醒一次），
 * 一旦距上一轮 run 超过 `order_setting.audit_run_interval_min`（默认 30 分钟）就会**自己跑一轮**，
 * 把 status=7 的种子单吃掉 —— 于是「第 1 轮 / 第 2 轮」的断言会变成第 7 轮、第 8 轮。
 * 后端在注释里明确写了「集成测试可以在自己的上下文里关掉它」，这里就是照那句话做的：
 * 记录原值 → 置 0 → 用例结束原样写回（见 quietAuditSchedule / restoreAuditSchedule）。
 */
import { readFileSync } from 'node:fs'
import { queryDb, execSql } from './db.js'

/** 六笔演示单（与 sql/seed-order-audit-demo.sql、交付表格逐字一致） */
export const DEMO_ORDER_SNS = ['QT-1001', 'QT-1002', 'QT-1003', 'QT-1004', 'QT-1005', 'QT-1006']

/** 演示商品名（seed 里 9 个 SKU 都挂在它下面） */
export const DEMO_PRODUCT_NAME = '自动审核演示商品'

/** 策略名 → 与 seed 一致；用例里要断言「命中了哪条」时一律用这里的常量，不写裸字符串 */
export const POLICY_NAME = {
  AUTO_DIRECT: '直营-小单',
  AUTO_TAOBAO: '淘宝-标准单',
  AUTO_DOUYIN: '抖音-标准单',
  BLOCK_PROFIT: '毛利不为正',
  BLOCK_ADDR: '双地址',
  EXCEPTION_RETURN: '退款申请冻结',
  EXCEPTION_REMARK: '平台备注冻结'
}

/** seed 里 AUTO/BLOCK/EXCEPTION 三类策略共 7 条 + 1 条全局排除（scope=ALL_AUTO） */
export const DEMO_POLICY_COUNT = 7

const SEED_SQL = new URL('../../../sql/seed-order-audit-demo.sql', import.meta.url)

/* ------------------------------------------------------------------ SQL 小工具 */

const q = (v) => `'${String(v).replace(/'/g, "''")}'`
const inList = (values) => values.map(q).join(', ')

/** 单值查询（无结果返回 ''） */
const one = (sql) => queryDb(sql)

/** 计数查询（无结果返回 0） */
const countOf = (sql) => Number(one(sql) || 0)

/**
 * 多列查询的行尾哨兵（由 rowsOf 自动加，调用方不用管）。
 *
 * 为什么非要有它（真实踩过）：support/db.js 的 queryDb 用 `mysql -N -B`（制表符分隔）并对输出
 * 做了 `.trim()`。当**最后一列是空串/NULL** 时，末尾那个制表符正好被 trim 掉，`split('\t')`
 * 于是少一格 → 最后一个字段变成 undefined。
 * 实测：QT-1001 的 `order.cs_remark` 是 NULL → `orderFactOf('QT-1001').csRemark === undefined`
 * （不是 null），断言「QT-1001 不应有客服备注」拿到 undefined 而红。
 * 在**最外层**补一个恒非空的哨兵列后，空尾列不会再被吃掉；解析时弹掉哨兵并校验它确实在
 * （形状不对就当场抛，绝不静默少列）。
 */
const EOR = '{EOR}'

/**
 * 多行查询 → 二维数组（每行按 \t 切开；无行返回 []）。
 *
 * 实现：把调用方的 SELECT **包进一层派生表**，在最外层拼哨兵列 ——
 *   `SELECT *, '{EOR}' FROM (<你的 SQL>) AS _e2e_row`
 * 为什么不直接把 `, '{EOR}'` 拼在语句末尾：调用方的语句常带 `WHERE … LIMIT 1` / `ORDER BY`，
 * 拼在末尾会变成 `LIMIT 1, '{EOR}'` 直接语法错误（实测踩过）。
 * 派生表要求列名唯一 —— 本文件所有 SELECT 的列名/表达式名都互不相同，满足要求。
 */
const rowsOf = (sql) => {
  const text = queryDb(`SELECT *, '${EOR}' FROM (${sql}) AS _e2e_row`)
  if (!text) return []
  return text.split('\n').filter(Boolean).map((line) => {
    const cells = line.split('\t')
    const tail = cells.pop()
    if (tail !== EOR) {
      throw new Error(`行尾哨兵缺失（mysql 输出形状异常 / 少一列）：${line.slice(0, 160)}`)
    }
    return cells
  })
}

/** 单行多列：多行里取第一行，取不到直接抛（定位不到行的话，后面所有断言都在测空气） */
const rowOf = (sql, what) => {
  const rows = rowsOf(sql)
  if (!rows.length) throw new Error(`查不到 ${what}（SQL 返回 0 行）`)
  return rows[0]
}

/* ------------------------------------------------------------------ ① 造数：铺 seed 基线 */

/**
 * 幂等铺 seed：策略集（7 条 + 全局排除）+ 六笔演示单 + 演示商品/SKU/库存桶。
 *
 * 已经有 7 条策略且六笔单齐全时直接跳过（省掉一次全文件执行）；
 * 否则把 sql/seed-order-audit-demo.sql 整份跑一遍（它自己逐条判存在，重复执行只补缺）。
 * 跑完**回读校验**：造数不成功必须响亮的红，不能让后面的断言去猜「为什么队列是空的」。
 *
 * @returns {{applied: boolean, policies: number, orders: number}}
 */
export function ensureAuditDemoSeed() {
  const policies = countOf('SELECT COUNT(*) FROM audit_policy')
  const orders = countOf(`SELECT COUNT(*) FROM \`order\` WHERE order_sn IN (${inList(DEMO_ORDER_SNS)})`)
  if (policies >= DEMO_POLICY_COUNT && orders === DEMO_ORDER_SNS.length) {
    return { applied: false, policies, orders }
  }

  execSql(readFileSync(SEED_SQL, 'utf8'))

  const afterPolicies = countOf('SELECT COUNT(*) FROM audit_policy')
  const afterOrders = countOf(`SELECT COUNT(*) FROM \`order\` WHERE order_sn IN (${inList(DEMO_ORDER_SNS)})`)
  const afterGlobals = countOf("SELECT COUNT(*) FROM audit_policy_condition WHERE scope = 'ALL_AUTO'")
  if (afterPolicies < DEMO_POLICY_COUNT) {
    throw new Error(`seed 后策略只有 ${afterPolicies} 条（应 ≥ ${DEMO_POLICY_COUNT}）—— sql/seed-order-audit-demo.sql 没跑成功？`)
  }
  if (afterOrders !== DEMO_ORDER_SNS.length) {
    throw new Error(`seed 后六笔演示单只到 ${afterOrders} 笔（应 ${DEMO_ORDER_SNS.length}）—— 单据造数失败`)
  }
  if (afterGlobals < 1) {
    throw new Error('seed 后没有全局排除行（scope=ALL_AUTO, 客服备注含「改」）—— QT-1002 的留人工结论将无从解释')
  }
  return { applied: true, policies: afterPolicies, orders: afterOrders }
}

/* ------------------------------------------------------------------ ② 复位：还原成 seed 基线 */

/** 演示 SKU 里「故意留 0 库存」的那一个（seed 的 OFFSET 7 = 第 8 条；QT-1006 的库存不足要靠它） */
function demoSkuIds() {
  const ids = one(
    `SELECT GROUP_CONCAT(s.id ORDER BY s.id) FROM sku s JOIN product p ON p.id = s.product_id `
    + `WHERE p.name = ${q(DEMO_PRODUCT_NAME)}`
  )
  return ids ? ids.split(',').map(Number) : []
}

/**
 * 把六笔演示单还原成 seed 基线（**可重复跑**）：
 *   · 删决策 / 删标 / 删状态台账（都是本用例跑 run 时留下的，seed 里本来没有）；
 *   · 退款申请回到「待处理 0」，并删掉售后台账（QT-1005 第一轮冻结的触发事实要能复现）；
 *   · 订单回 status=7、status_seq=0（决策的 order_status_seq 与台账幂等键都依赖它）；
 *   · 演示 SKU 的库存与桶回到 seed 值（QT-1006 的人工关单会回补库存，不回滚会逐轮漂移）。
 *
 * 不 TRUNCATE、不碰非演示数据 —— 只按订单号收敛到自己这六笔单上。
 * @returns {number[]} 复位到的订单 id
 */
export function resetAuditDemoBaseline({ sns = DEMO_ORDER_SNS } = {}) {
  const ids = orderIdsOf(sns)
  if (!ids.length) return []
  const idList = ids.join(', ')

  execSql(`
    DELETE FROM order_audit_decision WHERE order_id IN (${idList});
    DELETE FROM order_audit_tag WHERE order_id IN (${idList});
    DELETE FROM order_status_ledger WHERE order_id IN (${idList});
    DELETE FROM return_status_ledger WHERE order_id IN (${idList});
    UPDATE return_apply SET status = 0, status_seq = 0 WHERE order_id IN (${idList});
    UPDATE \`order\` SET status = 7, status_seq = 0, version = version + 1 WHERE id IN (${idList});
  `)

  const skus = demoSkuIds()
  if (skus.length >= 8) {
    // seed：9 个 SKU 全是 1000，只有第 8 个（OFFSET 7）故意是 0
    execSql(`
      UPDATE sku_bucket SET stock = 1000 WHERE sku_id IN (${skus.join(', ')});
      UPDATE sku_bucket SET stock = 0 WHERE sku_id = ${skus[7]};
      UPDATE sku SET stock = 1000 WHERE id IN (${skus.join(', ')});
      UPDATE sku SET stock = 0 WHERE id = ${skus[7]};
    `)
  }

  // 回读自证：无论成败，复位后的基线必须真的是「六笔待审核、零决策」
  const stillDirty = countOf(`SELECT COUNT(*) FROM order_audit_decision WHERE order_id IN (${idList})`)
  const notPending = countOf(`SELECT COUNT(*) FROM \`order\` WHERE id IN (${idList}) AND status <> 7`)
  if (stillDirty !== 0 || notPending !== 0) {
    throw new Error(`复位基线失败：仍有 ${stillDirty} 条决策、${notPending} 笔单不在待审核(7)`)
  }
  return ids
}

/* ------------------------------------------------------------------ ③ 定时任务开关（见文件头最后一段） */

let savedAutoAuditEnabled = null

/** 暂停定时自动审核（记住原值，afterAll 里原样写回）；没有配置行时补一行 0 */
export function quietAuditSchedule() {
  const current = one('SELECT auto_audit_enabled FROM order_setting ORDER BY id LIMIT 1')
  savedAutoAuditEnabled = current === '' ? 'NEW' : current
  if (current === '') {
    execSql('INSERT INTO order_setting (id, auto_confirm_days, auto_close_minutes, auto_audit_enabled) VALUES (1, 7, 30, 0)')
  } else {
    execSql('UPDATE order_setting SET auto_audit_enabled = 0')
  }
  const now = one('SELECT auto_audit_enabled FROM order_setting ORDER BY id LIMIT 1')
  if (String(now) !== '0') throw new Error(`关闭定时自动审核失败：auto_audit_enabled=${now}`)
}

/** 还原定时自动审核开关（原值是新插的行就删掉，保持「没有配置行」的原样） */
export function restoreAuditSchedule() {
  const saved = savedAutoAuditEnabled
  savedAutoAuditEnabled = null
  if (saved === null) return
  if (saved === 'NEW') {
    execSql('DELETE FROM order_setting WHERE id = 1')
    return
  }
  execSql(`UPDATE order_setting SET auto_audit_enabled = ${Number(saved) || 0}`)
}

/* ------------------------------------------------------------------ ④ 造数的「处理动作」（页面无合法入口的那些） */

/**
 * 把 QT-1005 的待处理退款申请置为「已拒绝」。
 *
 * 为什么这一步走库而不是 UI：seed 造的现场是「status=7 待审核 且 有一笔待处理退款申请」，
 * 而这个组合在业务上**不经过任何后端入口**（买家只能在 1/2/8 状态申请退货，申请时会先把单推到 5 退款中）。
 * 于是后台「退货审核」两条路都走不通：
 *   · 通过 → 守卫要求订单当前必须是 5，这里是 7，直接失败；
 *   · 拒绝 → OrderStatusService.change(order, 1, …)，而 7→1 不在 TRANSITIONS 里，抛「非法的订单状态流转」。
 * 所以「处理申请」这一步只能当**造数**：把申请置成已拒绝（status=2），让 HAS_RETURN_APPLY 变否；
 * 「解冻」那一步仍然走 UI（见 audit-ui.js 的 unfreezeRow），那才是被测交互。
 * 这条路径的不可达性已作为接口/前端缺口登记在交付说明里。
 *
 * @returns {number} 被置为已拒绝的 return_apply.id
 */
export function rejectDemoReturnApply(orderSn) {
  const orderId = orderIdOf(orderSn)
  const applyId = Number(one(
    `SELECT id FROM return_apply WHERE order_id = ${orderId} AND status = 0 ORDER BY id LIMIT 1`
  ))
  if (!applyId) {
    throw new Error(`订单 ${orderSn} 没有待处理退款申请（QT-1005 的冻结前置事实缺失，先复位基线）`)
  }
  execSql(`UPDATE return_apply SET status = 2, status_seq = status_seq + 1 WHERE id = ${applyId}`)
  const left = countOf(`SELECT COUNT(*) FROM return_apply WHERE order_id = ${orderId} AND status = 0`)
  if (left !== 0) throw new Error(`退款申请 ${applyId} 没处理干净：仍有 ${left} 笔待处理`)
  return applyId
}

/* ------------------------------------------------------------------ ⑤ 库上的事实（只读） */

/** 订单 id（不存在直接抛：定位不到行的话，后面所有断言都在测空气） */
export function orderIdOf(orderSn) {
  const id = Number(one(`SELECT id FROM \`order\` WHERE order_sn = ${q(orderSn)} LIMIT 1`))
  if (!id) throw new Error(`库里没有订单 ${orderSn}（先 ensureAuditDemoSeed）`)
  return id
}

/** 多笔单的 id（保持入参顺序，缺的会被跳过） */
export function orderIdsOf(sns = DEMO_ORDER_SNS) {
  return sns.map((sn) => Number(one(`SELECT id FROM \`order\` WHERE order_sn = ${q(sn)} LIMIT 1`))).filter(Boolean)
}

/** 订单上的判定输入事实 + 当前状态 */
export function orderFactOf(orderSn) {
  // 必须走 rowOf（带行尾哨兵）：cs_remark 为 NULL 时末列为空串，直接 split 会把最后一列吃掉
  const [id, status, statusSeq, channel, shopName, totalAmount, grossProfit, csRemark] = rowOf(
    `SELECT id, status, status_seq, IFNULL(channel, ''), IFNULL(shop_name, ''), `
    + `IFNULL(total_amount, ''), IFNULL(gross_profit, ''), IFNULL(cs_remark, '') `
    + `FROM \`order\` WHERE order_sn = ${q(orderSn)} LIMIT 1`,
    `订单 ${orderSn}`
  )
  return {
    id: Number(id),
    status: Number(status),
    statusSeq: Number(statusSeq),
    channel,
    shopName,
    totalAmount: totalAmount === '' ? null : Number(totalAmount),
    grossProfit: grossProfit === '' ? null : Number(grossProfit),
    csRemark: csRemark === '' ? null : csRemark
  }
}

/**
 * 订单明细的派生事实：商品种类数（**去重 SKU 数**，与引擎 `qtyBySku.size()` 同口径）+ 总件数。
 * 这两个数是 ITEM_KIND_COUNT / ITEM_QTY_TOTAL 两个字段的来源，也是队列「商品种类数」列的口径。
 */
export function orderItemStatsOf(orderSn) {
  const orderId = orderIdOf(orderSn)
  const [kinds, qty, lines] = rowOf(
    `SELECT COUNT(DISTINCT product_sku_id), COALESCE(SUM(product_quantity), 0), COUNT(*) `
    + `FROM order_item WHERE order_id = ${orderId}`,
    `订单 ${orderSn} 的明细统计`
  )
  return { orderId, kinds: Number(kinds), qty: Number(qty), lines: Number(lines) }
}

/** 决策台账（每轮一行，按 round_no 升序）。
 * 字段名对齐 order_audit_decision 的列，值统一是**字符串**或 null —— 断言里自己 Number()。
 */
export function decisionsOf(orderId) {
  return rowsOf(
    `SELECT round_no, result, IFNULL(matched_policy_name, ''), IFNULL(matched_policy_version_no, ''), `
    + `IFNULL(would_be_auto_policy_name, ''), IFNULL(downgrade_reason, ''), run_id, order_status_seq, id, `
    + `IFNULL(JSON_UNQUOTE(JSON_EXTRACT(evidence_json, '$.reason')), '') `
    + `FROM order_audit_decision WHERE order_id = ${orderId} ORDER BY round_no ASC, id ASC`
  ).map(([roundNo, result, matched, versionNo, wouldBe, downgrade, runId, statusSeq, id, reason]) => ({
    roundNo: Number(roundNo),
    result,
    matchedPolicyName: matched || null,
    matchedPolicyVersionNo: versionNo === '' ? null : Number(versionNo),
    wouldBePolicyName: wouldBe || null,
    downgradeReason: downgrade || null,
    runId: Number(runId),
    orderStatusSeq: statusSeq === '' ? null : Number(statusSeq),
    decisionId: Number(id),
    reason
  }))
}

/** 最近一轮决策（队列页显示的就是它） */
export function latestDecisionOf(orderId) {
  const all = decisionsOf(orderId)
  return all.length ? all[all.length - 1] : null
}

/**
 * 决策行上的三段 JSON 留痕，**已 parse 成结构**（不是字符串）。
 *
 * 为什么必须是结构：MySQL 的 JSON 列在 SELECT 出来时会被规范化输出成
 * `{"policy_name": "直营-小单"}`（键与值之间有空格、键序也按长度重排），
 * 所以「字符串 contains `"policy_name":"直营-小单"`」这种断言一定红（实测踩过）。
 * 结构化的好处是断言能落到字段上：`ev.would_be.policy_name === '直营-小单'`、
 * `cond.expected === '300.00'`、`cond.satisfied === false`、`cond.evaluated === true`。
 *
 * @param column miss_reason_json | matched_condition_json | excluded_by_json | evidence_json
 * @returns {object|Array} 解析后的对象；列为 NULL 时抛（不是静默返回 null，免得断言空转）
 */
export function decisionJsonOf(orderId, roundNo, column = 'miss_reason_json') {
  const allowed = ['miss_reason_json', 'matched_condition_json', 'excluded_by_json', 'evidence_json']
  if (!allowed.includes(column)) throw new Error(`decisionJsonOf 不支持列 ${column}`)
  const text = one(
    `SELECT IFNULL(${column}, '') FROM order_audit_decision WHERE order_id = ${orderId} AND round_no = ${roundNo}`
  )
  if (!text) throw new Error(`订单 ${orderId} 第 ${roundNo} 轮的 ${column} 是空的（这条决策没留痕？）`)
  try {
    return JSON.parse(text)
  } catch (e) {
    throw new Error(`订单 ${orderId} 第 ${roundNo} 轮的 ${column} 不是合法 JSON：${e.message}`)
  }
}

/* ------------------------------------------------------------------ ⑤b 结构化留痕的取用小工具
 *
 * 三段 JSON 的公共形状是「策略组 → 条件数组」：
 *   [{ policyId, policyName, reasons: [人话…], conditions: [{ seq, fieldCode, fieldLabel, opCode,
 *      expected, actual, satisfied, evaluated, reason }] }]
 * 断言只要「某策略的某字段那条条件」，别写字符串匹配。
 */

/** 取某个策略组（按策略名；找不到直接抛，带出现过的策略名便于排查） */
export function missGroupOf(groups, policyName) {
  const list = Array.isArray(groups) ? groups : [groups]
  const hit = list.find((g) => String(g.policyName || g.policy_name) === policyName)
  if (!hit) {
    throw new Error(`留痕里没有策略「${policyName}」，实际有：${list.map((g) => g.policyName || g.policy_name).join('、')}`)
  }
  return hit
}

/** 取策略组里某个字段的那条条件（按 fieldCode） */
export function conditionOf(group, fieldCode) {
  const hit = (group.conditions || []).find((c) => String(c.fieldCode || c.field) === fieldCode)
  if (!hit) {
    throw new Error(`策略「${group.policyName || ''}」的留痕里没有字段 ${fieldCode}，实际有：`
      + (group.conditions || []).map((c) => c.fieldCode || c.field).join('、'))
  }
  return hit
}

/** 所有组里 evaluated=false 的条件（AND 链首个不满足之后的那几条） */
export function unevaluatedConditions(groups) {
  const list = Array.isArray(groups) ? groups : [groups]
  return list.flatMap((g) => (g.conditions || []).filter((c) => c.evaluated === false))
}

/** 订单状态台账（按记账顺序） */
export function ledgerRowsOf(orderId) {
  return rowsOf(
    `SELECT IFNULL(from_status, ''), to_status, reason, IFNULL(reason_detail, ''), `
    + `IFNULL(ref_type, ''), IFNULL(ref_id, ''), idem_key `
    + `FROM order_status_ledger WHERE order_id = ${orderId} ORDER BY created_at ASC, id ASC`
  ).map(([from, to, reason, reasonDetail, refType, refId, idemKey]) => ({
    from: from === '' ? null : Number(from),
    to: Number(to),
    reason,
    reasonDetail,
    refType: refType || null,
    refId: refId === '' ? null : Number(refId),
    idemKey
  }))
}

/** active 的审核标（冻结 / 挂起类） */
export function activeTagsOf(orderId) {
  return rowsOf(
    `SELECT tag_code, IFNULL(reason, '') FROM order_audit_tag WHERE order_id = ${orderId} AND active = 1 ORDER BY id`
  ).map(([tagCode, reason]) => ({ tagCode, reason }))
}

/** 全部标（含已解除；用来断言「解冻 = 解除而不是删行」） */
export function allTagsOf(orderId) {
  return rowsOf(
    `SELECT tag_code, active, IFNULL(released_by, '') FROM order_audit_tag WHERE order_id = ${orderId} ORDER BY id`
  ).map(([tagCode, active, releasedBy]) => ({ tagCode, active: Number(active), releasedBy }))
}

/** 后台操作审计里这张单的动作序列（冻结/解冻/退货审核…）—— trail 的第三段就是它 */
export function orderAuditActionsOf(orderId) {
  return rowsOf(
    `SELECT action, IFNULL(summary, '') FROM admin_audit_log WHERE order_id = ${orderId} ORDER BY id`
  ).map(([action, summary]) => ({ action, summary }))
}

/** 策略行（优先级 / 版本号 / 启用），按类型+优先级排序 */
export function policyRowsOf() {
  return rowsOf(
    'SELECT id, type, priority, name, version_no, enabled FROM audit_policy ORDER BY FIELD(type, '
    + "'BLOCK','EXCEPTION','AUTO'), priority, id"
  ).map(([id, type, priority, name, versionNo, enabled]) => ({
    id: Number(id),
    type,
    priority: Number(priority),
    name,
    versionNo: Number(versionNo),
    enabled: Number(enabled)
  }))
}

/** 单条策略（按名字） */
export function policyOf(name) {
  const hit = policyRowsOf().find((p) => p.name === name)
  if (!hit) throw new Error(`库里没有策略「${name}」（先 ensureAuditDemoSeed）`)
  return hit
}

/** 某策略的版本快照条数（改条件会 +1；只挪优先级**不该** +1） */
export function policyVersionCountOf(policyId) {
  return countOf(`SELECT COUNT(*) FROM audit_policy_version WHERE policy_id = ${policyId}`)
}

/**
 * 复位「用例自己改过的策略优先级」。
 *
 * 只给 cleanup 用：改优先级本身走 UI（上移/下移），但如果那条 UI 路径中途失败，
 * 库里的顺序就停在被改过的位置 —— 那会污染后面所有用例的判定顺序。
 * 所以 finally 里再用**原值**写回一次（幂等），不依赖 UI 是否成功。
 * @param pairs [{id, priority}]
 */
export function restorePolicyPriorities(pairs) {
  const list = (pairs || []).filter((p) => p && p.id)
  if (!list.length) return
  const cases = list.map((p) => `WHEN ${Number(p.id)} THEN ${Number(p.priority)}`).join(' ')
  execSql(`UPDATE audit_policy SET priority = CASE id ${cases} END WHERE id IN (${list.map((p) => Number(p.id)).join(', ')})`)
}

/** 全部 run 条数（只用来做「多跑了一轮」这类相对断言，不断言绝对值） */
export function auditRunCount() {
  return countOf('SELECT COUNT(*) FROM audit_run')
}
