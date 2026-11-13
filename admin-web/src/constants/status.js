/**
 * 状态字典（中文文案 + el-tag type 映射）
 * ---------------------------------------------------------------------------
 * ⚠️ 关于现状（务必先读，别又抄出第 5 份副本）
 *
 * 本项目目前**没有**统一的状态映射，同一个状态码在 4 处各写了一份副本：
 *   1. src/views/order/index.vue        statusText / statusTag / deliveryText / deliveryTag
 *   2. src/views/order/index.vue        详情抽屉里又内联一套（同一个文件里两份，见 grep statusText）
 *   3. src/views/dashboard/index.vue     ORDER_STATUS_TEXT
 *   4. e2e/tests/support/assert.js       ORDER_STATUS_TEXT（断言侧，给日志和聚合推导用）
 *
 * 本文件是**新页面（台账查询 / 库存对账）的唯一来源**。
 * 上面那 4 处现有副本这次**故意不动**：改它们会连带影响订单列表、详情抽屉、看板、e2e 断言，
 * 属于「统一状态字典」的独立重构任务，不塞进写页面的这一手。后续统一时**以本文件为准**，
 * 把 4 处副本逐处替换成 import 即可（口径已对齐，文案一个字都没改）。
 *
 * 取值兼容：后端可能把状态序列化成数字（0）或字符串（"0" / "ADMIN"），
 * 所有 lookup 都同时兼容这两种形态，未知值原样回显而不是显示空白。
 */

/* ---------------------------------------------------------------------------
 * 订单十态（与后端 OrderStatus 对齐；数组下标即状态码，保持与现有副本同形便于逐处替换）
 * 0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货
 * ------------------------------------------------------------------------- */
export const ORDER_STATUS_TEXT = [
  '待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货'
]
export const ORDER_STATUS_TAG = [
  'warning', 'success', 'primary', 'success', 'info', 'warning', 'info', 'warning', 'warning', 'primary'
]

/* ---------------------------------------------------------------------------
 * 包裹（发货单子单）状态：0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消
 * 注意 6/7/8 不存在，用对象而不是数组，免得有人按下标顺序读错
 * ------------------------------------------------------------------------- */
export const DELIVERY_STATUS_TEXT = {
  0: '待下发', 1: '已下发', 2: '已拣货', 3: '已出库', 4: '运输中', 5: '已签收', 9: '已取消'
}
export const DELIVERY_STATUS_TAG = {
  0: 'warning', 1: 'primary', 2: 'primary', 3: 'success', 4: 'success', 5: 'success', 9: 'info'
}

/* ---------------------------------------------------------------------------
 * 售后（退货申请）状态：0待处理 1已通过 2已拒绝
 * ------------------------------------------------------------------------- */
export const RETURN_STATUS_TEXT = { 0: '待处理', 1: '已通过', 2: '已拒绝' }
export const RETURN_STATUS_TAG = { 0: 'warning', 1: 'success', 2: 'danger' }

/* ---------------------------------------------------------------------------
 * 操作人类型（台账 operatorType）
 * ------------------------------------------------------------------------- */
export const OPERATOR_TYPE_TEXT = {
  ADMIN: '管理员', MEMBER: '会员', APIKEY: '开放接口', SYSTEM: '系统'
}
export const OPERATOR_TYPE_TAG = {
  ADMIN: 'primary', MEMBER: 'success', APIKEY: 'warning', SYSTEM: 'info'
}

/* ---------------------------------------------------------------------------
 * 台账类型（台账 type / 查询页的类型下拉）
 * ------------------------------------------------------------------------- */
export const LEDGER_TYPE_TEXT = {
  ORDER_STATUS: '订单状态',
  DELIVERY_STATUS: '包裹状态',
  RETURN_STATUS: '售后状态',
  AUDIT: '审计',
  WMS: 'WMS 回调',
  STOCK: '库存',
  // 支付流水（钱进/钱出）：来源是 payment_record，不是状态台账 ——
  // 所以它的「前态→后态」永远是空（后端给 null，页面显示 — ），语义在「原因」列：
  // 支付入账 / 退款入账，渠道流水号在原因下面那行小字里。
  PAYMENT: '支付流水'
}
export const LEDGER_TYPE_TAG = {
  ORDER_STATUS: 'primary',
  DELIVERY_STATUS: 'primary',
  RETURN_STATUS: 'warning',
  AUDIT: 'info',
  WMS: 'info',
  STOCK: 'danger',
  PAYMENT: 'success'
}

/* ---------------------------------------------------------------------------
 * 下拉选项（顺序＝后端约定的顺序，别再手写一遍）
 * ------------------------------------------------------------------------- */
export const LEDGER_TYPE_OPTIONS = Object.keys(LEDGER_TYPE_TEXT).map((value) => ({
  value,
  label: LEDGER_TYPE_TEXT[value]
}))
export const OPERATOR_TYPE_OPTIONS = Object.keys(OPERATOR_TYPE_TEXT).map((value) => ({
  value,
  label: OPERATOR_TYPE_TEXT[value]
}))
export const ORDER_STATUS_OPTIONS = ORDER_STATUS_TEXT.map((label, value) => ({ value, label }))
export const DELIVERY_STATUS_OPTIONS = Object.keys(DELIVERY_STATUS_TEXT).map((k) => ({
  value: Number(k),
  label: DELIVERY_STATUS_TEXT[k]
}))
export const RETURN_STATUS_OPTIONS = Object.keys(RETURN_STATUS_TEXT).map((k) => ({
  value: Number(k),
  label: RETURN_STATUS_TEXT[k]
}))

/* ---------------------------------------------------------------------------
 * lookup：数组按下标、对象按 key，数字/字符串都认
 * ------------------------------------------------------------------------- */

/** 取原值对应的原始映射项（可能是中文，也可能是 undefined） */
const lookup = (dict, raw) => {
  if (raw === null || raw === undefined || raw === '') return undefined
  if (Array.isArray(dict)) {
    const i = Number(raw)
    return Number.isInteger(i) ? dict[i] : undefined
  }
  return dict[raw] ?? dict[String(raw)]
}

/** 状态码 → 中文；未知值原样回显（后端加了新状态也不至于显示空白），空值统一 '—' */
export const statusText = (dict, raw) => {
  const hit = lookup(dict, raw)
  if (hit !== undefined && hit !== null) return hit
  if (raw === null || raw === undefined || raw === '') return '—'
  return String(raw)
}

/** 状态码 → el-tag type；未知值保守用 info（不猜 success/danger，避免误导） */
export const statusTag = (dict, raw, fallback = 'info') => lookup(dict, raw) || fallback

/** 键值型字典（operatorType / ledger type）→ 中文，未知值原样回显 */
export const labelOf = (dict, raw) => statusText(dict, raw)

/* ---------------------------------------------------------------------------
 * 语义化封装：页面直接用这些，不必自己拼 dict
 * ------------------------------------------------------------------------- */
export const orderStatusText = (raw) => statusText(ORDER_STATUS_TEXT, raw)
export const orderStatusTag = (raw) => statusTag(ORDER_STATUS_TAG, raw)
export const deliveryStatusText = (raw) => statusText(DELIVERY_STATUS_TEXT, raw)
export const deliveryStatusTag = (raw) => statusTag(DELIVERY_STATUS_TAG, raw)
export const returnStatusText = (raw) => statusText(RETURN_STATUS_TEXT, raw)
export const returnStatusTag = (raw) => statusTag(RETURN_STATUS_TAG, raw)
export const operatorTypeText = (raw) => statusText(OPERATOR_TYPE_TEXT, raw)
export const operatorTypeTag = (raw) => statusTag(OPERATOR_TYPE_TAG, raw)
export const ledgerTypeText = (raw) => statusText(LEDGER_TYPE_TEXT, raw)
export const ledgerTypeTag = (raw) => statusTag(LEDGER_TYPE_TAG, raw)

/**
 * 台账行里 fromStatus / toStatus 的文案：语义由**行上的 type** 决定。
 * 订单台账里的 2 是「已发货」，包裹台账里的 2 是「已拣货」，售后台账里的 2 是「已拒绝」——
 * 同一个数字三种含义，所以必须带上 type 才能翻译，不能只看数字。
 * AUDIT / WMS / STOCK 这几类台账存的是自由文本或空值，原样回显。
 */
export const ledgerStatusText = (type, raw) => {
  switch (type) {
    case 'ORDER_STATUS': return orderStatusText(raw)
    case 'DELIVERY_STATUS': return deliveryStatusText(raw)
    case 'RETURN_STATUS': return returnStatusText(raw)
    default: return raw === null || raw === undefined || raw === '' ? '—' : String(raw)
  }
}

/** 同上，取 tag type（未知/空值给 info） */
export const ledgerStatusTag = (type, raw) => {
  switch (type) {
    case 'ORDER_STATUS': return orderStatusTag(raw)
    case 'DELIVERY_STATUS': return deliveryStatusTag(raw)
    case 'RETURN_STATUS': return returnStatusTag(raw)
    default: return 'info'
  }
}

/* ---------------------------------------------------------------------------
 * 订单自动审核（audit 模块）
 * ---------------------------------------------------------------------------
 * 判定的六种结局**不是**「通过/不通过」二分：人工队列要按处理人拆开分别派活，
 * 所以 BLOCKED（阻止 = 拦截下单，运营要先谈单）与 FROZEN（冻结 = 已打标等处理，客服跟进）
 * 必须分开统计、分开筛选、分开上色 —— 合并成一个「被拦」会让人派错工单。
 *
 * 颜色口径（一句话记：绿=系统自己过了 / 黄=等人 / 红=拦死 / 紫=冻住 / 灰=没命中）：
 *   AUTO_PASS 绿 success · MANUAL 黄 warning · BLOCKED 红 danger
 *   FROZEN    紫（el-tag 没有紫档，用 AUDIT_RESULT_CLASS 里带的自定义 class 覆盖成紫色）
 *   NO_MATCH  灰 info      · SKIPPED 灰 info（未参与本轮，不属于五类计数）
 * 三重冗余：颜色 + 文案 + data-result（e2e 与色盲用户都不靠颜色识别）。
 * ------------------------------------------------------------------------- */
export const AUDIT_RESULT_TEXT = {
  AUTO_PASS: '自动过审',
  MANUAL: '留人工',
  BLOCKED: '阻止',
  FROZEN: '冻结',
  NO_MATCH: '不命中',
  SKIPPED: '未参与'
}
/** el-tag 的 type 档位（紫色没有档位，见 AUDIT_RESULT_CLASS） */
export const AUDIT_RESULT_TAG = {
  AUTO_PASS: 'success',
  MANUAL: 'warning',
  BLOCKED: 'danger',
  FROZEN: 'primary',
  NO_MATCH: 'info',
  SKIPPED: 'info'
}
/** 需要额外 class 才能出效果的两档：FROZEN 紫色、SKIPPED 更淡的灰 */
export const AUDIT_RESULT_CLASS = {
  AUTO_PASS: 'badge-auto-pass',
  MANUAL: 'badge-manual',
  BLOCKED: 'badge-blocked',
  FROZEN: 'badge-frozen',
  NO_MATCH: 'badge-no-match',
  SKIPPED: 'badge-skipped'
}
/** 「被拦」= 阻止 + 冻结：只用于分组标题与合计，**不能**当成 queue 的筛选值（后端只收单值） */
export const AUDIT_BLOCKED_GROUP = ['BLOCKED', 'FROZEN']

/** 徽标色一句话（抽屉/跑马灯里的补充说明用） */
export const AUDIT_RESULT_HINT = {
  AUTO_PASS: '策略全部条件满足，已自动推进到待发货',
  MANUAL: '条件满足但被排除 / 库存或成本预检降级，需要人看一眼',
  BLOCKED: '被阻止策略前置拦截，不推进状态，需人工联系客户确认',
  FROZEN: '被异常策略命中并打上冻结标，客服处理前不会再参与自动审核',
  NO_MATCH: '没有任何自动策略命中，需要人工判断或补策略',
  SKIPPED: '状态不是待审核或已冻结，本轮没有参与判定'
}

/* 策略类型：AUTO 自动过审 / BLOCK 阻止 / EXCEPTION 异常冻结（判定链顺序固定，与 priority 无关） */
export const AUDIT_POLICY_TYPE_TEXT = { AUTO: '自动', BLOCK: '阻止', EXCEPTION: '异常' }
export const AUDIT_POLICY_TYPE_TAG = { AUTO: 'success', BLOCK: 'danger', EXCEPTION: 'warning' }
export const AUDIT_POLICY_TYPE_TIP = {
  AUTO: '条件全部满足 → 自动过审（7 → 8）',
  BLOCK: '命中即前置拦截，不推进任何状态',
  EXCEPTION: '命中即打冻结标，等客服处理'
}
export const AUDIT_POLICY_TYPE_OPTIONS = Object.keys(AUDIT_POLICY_TYPE_TEXT).map((value) => ({
  value,
  label: AUDIT_POLICY_TYPE_TEXT[value]
}))

/* 条件行：kind 匹配/排除 · scope 策略级/全局 */
export const AUDIT_CONDITION_KIND_TEXT = { MATCH: '匹配', EXCLUDE: '排除' }
export const AUDIT_CONDITION_SCOPE_TEXT = { POLICY: '策略级', ALL_AUTO: '全局（所有自动策略）' }

/* 触发方式 */
export const AUDIT_TRIGGER_TEXT = { MANUAL: '人工触发', SCHEDULE: '定时调度' }

/* 时间线三段（trail） */
export const AUDIT_SEGMENT_TEXT = { DECISION: '决策', STATUS: '状态台账', AUDIT: '操作审计' }
export const AUDIT_SEGMENT_TAG = { DECISION: 'primary', STATUS: 'success', AUDIT: 'warning' }

/* 操作符：与后端 OpCode 的 symbol 一致（10 个；EXISTS/NOT_EXISTS 不带值） */
export const AUDIT_OP_TEXT = {
  EQ: '=', NE: '≠', LT: '<', LTE: '≤', GT: '>', GTE: '≥',
  CONTAINS: '含', NOT_CONTAINS: '不含', EXISTS: '有值', NOT_EXISTS: '无值'
}
/** 不带配置值的操作符（值控件要跟着隐藏） */
export const AUDIT_OP_NO_VALUE = ['EXISTS', 'NOT_EXISTS']

export const auditResultText = (raw) => statusText(AUDIT_RESULT_TEXT, raw)
export const auditResultTag = (raw) => statusTag(AUDIT_RESULT_TAG, raw)
export const auditResultClass = (raw) => AUDIT_RESULT_CLASS[String(raw)] || 'badge-no-match'
export const auditResultHint = (raw) => AUDIT_RESULT_HINT[String(raw)] || ''
export const auditPolicyTypeText = (raw) => statusText(AUDIT_POLICY_TYPE_TEXT, raw)
export const auditPolicyTypeTag = (raw) => statusTag(AUDIT_POLICY_TYPE_TAG, raw)
export const auditConditionKindText = (raw) => statusText(AUDIT_CONDITION_KIND_TEXT, raw)
export const auditTriggerText = (raw) => statusText(AUDIT_TRIGGER_TEXT, raw)
export const auditSegmentText = (raw) => statusText(AUDIT_SEGMENT_TEXT, raw)
export const auditSegmentTag = (raw) => statusTag(AUDIT_SEGMENT_TAG, raw)
export const auditOpText = (raw) => statusText(AUDIT_OP_TEXT, raw)
export const auditOpNeedsValue = (raw) => !AUDIT_OP_NO_VALUE.includes(String(raw))
