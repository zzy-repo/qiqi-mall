import request from './request'

const BASE = '/deliveries'

/** 说明：预演只带 query，但必须显式给一个 JSON 空对象 body —— axios 对 data=null 的请求
 * 会带 application/x-www-form-urlencoded，后端 @RequestBody 会直接 415 拒掉（实测踩过）。 */

/**
 * 发货单（包裹）接口。
 *
 * 高危写操作（L3）统一两条通道，见 shared/security/ConfirmCodeService 的契约：
 *   ① 预演：同一路径 + ?dryRun=true → { code:0, data:{ willDo, confirmCode, expiresInSec } }，库里零变化
 *   ② 执行：同一路径 + body 带 { confirmCode } → 缺码/无效/已用/过期/目标不符/指纹变化 一律拒绝
 * 所以每个写操作都有成对的 `xxxDryRun(...)`（预演）与 `xxx(..., body)`（执行，body 至少含 confirmCode）。
 */

// ---------------------------------------------------------------------------
// 只读
// ---------------------------------------------------------------------------

/** 按父订单查子发货单（含仓库名/明细/运单号） */
export const listDeliveries = (orderId) => request.get(BASE, { params: { orderId } })
/** 批量查订单的包裹数（列表页「N 个包裹」标签）：orderIds 数组 */
export const deliveryCounts = (orderIds) => request.get(`${BASE}/counts`, { params: { orderIds: orderIds.join(',') } })

// ---------------------------------------------------------------------------
// 订单审核（1 通过 / 2 不通过）+ 拆单方案预览
// ---------------------------------------------------------------------------

/** 审核通过：待审核 → 待发货，并触发拆单。body 必须带 { confirmCode } */
export const auditPassOrder = (id, body) => request.post(`/orders/${id}/audit-pass`, body)
/**
 * 审核通过预演：走 split-preview（真预览，不落库）。
 * 返回结构已变：{ willDo, plan:[{warehouseId,warehouseName,items:[...]}], confirmCode, expiresInSec }
 * —— 比 audit-pass?dryRun=true 多一份 plan，弹窗里能顺带把「拆成几个包裹」列出来。
 */
export const auditPassDryRun = (id) =>
  request.post(`/orders/${id}/split-preview`, {}, { params: { dryRun: true } })

/** 审核不通过：待审核 → 已关闭，回补库存。body 必须带 { confirmCode } */
export const auditRejectOrder = (id, body) => request.post(`/orders/${id}/audit-reject`, body)
/** 审核不通过预演（?dryRun=true） */
export const auditRejectDryRun = (id) =>
  request.post(`/orders/${id}/audit-reject`, {}, { params: { dryRun: true } })

// ---------------------------------------------------------------------------
// 发货单状态流转
// ---------------------------------------------------------------------------

/** 下发 WMS（待下发 → 已下发）。body 必须带 { confirmCode } */
export const dispatchDelivery = (id, body) => request.post(`${BASE}/${id}/dispatch`, body)
/** 下发预演（?dryRun=true） */
export const dispatchDeliveryDryRun = (id) => request.post(`${BASE}/${id}/dispatch`, {}, { params: { dryRun: true } })

/** 取消（待下发 → 已取消，回补库存）。body 必须带 { confirmCode } */
export const cancelDelivery = (id, body) => request.post(`${BASE}/${id}/cancel`, body)
/** 取消预演（?dryRun=true） */
export const cancelDeliveryDryRun = (id) => request.post(`${BASE}/${id}/cancel`, {}, { params: { dryRun: true } })

/** 模拟 WMS 回传：pick | outbound | transit | signed。body 必须带 { confirmCode } */
export const wmsCallback = (id, event, body) =>
  request.post(`${BASE}/${id}/wms-callback`, body, { params: { event } })
/** WMS 回传预演（?dryRun=true&event=xxx） */
export const wmsCallbackDryRun = (id, event) =>
  request.post(`${BASE}/${id}/wms-callback`, {}, { params: { event, dryRun: true } })

// ---------------------------------------------------------------------------
// 待下发包裹的人工干预（编辑 / 拆分 / 合并 / 删除）
// ---------------------------------------------------------------------------

/** 编辑待下发包裹：改发货仓 / 改明细数量（守卫仅待下发）。body = {...原参数, confirmCode} */
export const updateDelivery = (id, data) => request.put(`${BASE}/${id}`, data)
/** 编辑预演：同一路径 + ?dryRun=true，body 仍是「原参数」 */
export const updateDeliveryDryRun = (id, data) =>
  request.put(`${BASE}/${id}`, data, { params: { dryRun: true } })

/** 拆分待下发包裹：拆出一个新的待下发包裹。body = {newWarehouseId, items, confirmCode} */
export const splitDelivery = (id, data) => request.post(`${BASE}/${id}/split`, data)
/** 拆分预演：同一路径 + ?dryRun=true */
export const splitDeliveryDryRun = (id, data) =>
  request.post(`${BASE}/${id}/split`, data, { params: { dryRun: true } })

/** 合并待下发包裹：把 id 并入 targetId。body = {targetId, confirmCode} */
export const mergeDelivery = (id, body) => request.post(`${BASE}/${id}/merge`, body)
/** 合并预演：同一路径 + ?dryRun=true，body 仍是 { targetId } */
export const mergeDeliveryDryRun = (id, targetId) =>
  request.post(`${BASE}/${id}/merge`, { targetId }, { params: { dryRun: true } })

/**
 * 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处）。
 * 目标在 query（后端 @RequestParam），确认码在 body（这个接口 body 只装码）。
 */
export const deleteDelivery = (id, targetId, body) =>
  request.delete(`${BASE}/${id}`, { params: targetId ? { targetId } : {}, data: body })
/** 删除预演：?dryRun=true&targetId= */
export const deleteDeliveryDryRun = (id, targetId) =>
  request.delete(`${BASE}/${id}`, { params: targetId ? { targetId, dryRun: true } : { dryRun: true }, data: {} })
