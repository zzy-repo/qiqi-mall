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
  STOCK: '库存'
}
export const LEDGER_TYPE_TAG = {
  ORDER_STATUS: 'primary',
  DELIVERY_STATUS: 'primary',
  RETURN_STATUS: 'warning',
  AUDIT: 'info',
  WMS: 'info',
  STOCK: 'danger'
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
