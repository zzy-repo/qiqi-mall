import request from './request'

const BASE = '/deliveries'

/** 说明：POST 预演只带 query，但必须显式给一个 JSON 空对象 body —— axios 对 data=null 的请求
 * 会带 application/x-www-form-urlencoded，后端 @RequestBody 会直接 415 拒掉（实测踩过）。 */

/**
 * 发货单（包裹）接口。
 *
 * 高危写操作（L3）统一两条通道：
 *   ① 预演：同一路径 + ?dryRun=true → { code:0, data:{ willDo, ... } }，库里零变化；
 *   ② 执行：同一路径 + 业务 body（确认码已下线，不再需要 confirmCode）。
 * 所以每个写操作都有成对的 `xxxDryRun(...)`（预演）与 `xxx(...)`（执行）。
 *
 * 乐观锁：只有「编辑待下发包裹」（PUT /{id}）需要 body 带 version，
 * 预演与执行的 body 都要带（同一份 payload 分别发两次），缺了后端回 400、过期回 409。
 */

// ---------------------------------------------------------------------------
// 只读
// ---------------------------------------------------------------------------

/** 按父订单查子发货单（含仓库名/明细/运单号/version） */
export const listDeliveries = (orderId) => request.get(BASE, { params: { orderId } })
/** 批量查订单的包裹数（列表页「N 个包裹」标签）：orderIds 数组 */
export const deliveryCounts = (orderIds) => request.get(`${BASE}/counts`, { params: { orderIds: orderIds.join(',') } })

// ---------------------------------------------------------------------------
// 订单审核（1 通过 / 2 不通过）+ 拆单方案预览
// ---------------------------------------------------------------------------

/** 审核通过：待审核 → 待发货，并触发拆单 */
export const auditPassOrder = (id) => request.post(`/orders/${id}/audit-pass`, {})
/**
 * 审核通过预演：走 split-preview（真预览，不落库）。
 * 返回结构：{ willDo, plan:[{warehouseId,warehouseName,items:[...]}] }
 * —— 比 audit-pass?dryRun=true 多一份 plan，弹窗里能顺带把「拆成几个包裹」列出来。
 */
export const auditPassDryRun = (id) =>
  request.post(`/orders/${id}/split-preview`, {}, { params: { dryRun: true } })

/** 审核不通过：待审核 → 已关闭，回补库存 */
export const auditRejectOrder = (id) => request.post(`/orders/${id}/audit-reject`, {})
/** 审核不通过预演（?dryRun=true） */
export const auditRejectDryRun = (id) =>
  request.post(`/orders/${id}/audit-reject`, {}, { params: { dryRun: true } })

// ---------------------------------------------------------------------------
// 发货单状态流转
// ---------------------------------------------------------------------------

/** 下发 WMS（待下发 → 已下发） */
export const dispatchDelivery = (id) => request.post(`${BASE}/${id}/dispatch`, {})
/** 下发预演（?dryRun=true） */
export const dispatchDeliveryDryRun = (id) => request.post(`${BASE}/${id}/dispatch`, {}, { params: { dryRun: true } })

/** 取消（待下发 → 已取消，回补库存） */
export const cancelDelivery = (id) => request.post(`${BASE}/${id}/cancel`, {})
/** 取消预演（?dryRun=true） */
export const cancelDeliveryDryRun = (id) => request.post(`${BASE}/${id}/cancel`, {}, { params: { dryRun: true } })

/** 模拟 WMS 回传：pick | outbound | transit | signed */
export const wmsCallback = (id, event) =>
  request.post(`${BASE}/${id}/wms-callback`, {}, { params: { event } })
/** WMS 回传预演（?dryRun=true&event=xxx） */
export const wmsCallbackDryRun = (id, event) =>
  request.post(`${BASE}/${id}/wms-callback`, {}, { params: { event, dryRun: true } })

// ---------------------------------------------------------------------------
// 待下发包裹的人工干预（编辑 / 拆分 / 合并 / 删除）
// ---------------------------------------------------------------------------

/** 编辑待下发包裹：改发货仓 / 改明细数量（守卫仅待下发）。body = { warehouseId, items, version } */
export const updateDelivery = (id, data) => request.put(`${BASE}/${id}`, data)
/** 编辑预演：同一路径 + ?dryRun=true，body 是同一份（含 version） */
export const updateDeliveryDryRun = (id, data) =>
  request.put(`${BASE}/${id}`, data, { params: { dryRun: true } })

/** 拆分待下发包裹：拆出一个新的待下发包裹。body = {newWarehouseId, items} */
export const splitDelivery = (id, data) => request.post(`${BASE}/${id}/split`, data)
/** 拆分预演：同一路径 + ?dryRun=true */
export const splitDeliveryDryRun = (id, data) =>
  request.post(`${BASE}/${id}/split`, data, { params: { dryRun: true } })

/** 合并待下发包裹：把 id 并入 targetId。body = { targetId } */
export const mergeDelivery = (id, targetId) => request.post(`${BASE}/${id}/merge`, { targetId })
/** 合并预演：同一路径 + ?dryRun=true，body 仍是 { targetId } */
export const mergeDeliveryDryRun = (id, targetId) =>
  request.post(`${BASE}/${id}/merge`, { targetId }, { params: { dryRun: true } })

/**
 * 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处）。
 * 目标在 query（后端 @RequestParam）。
 */
export const deleteDelivery = (id, targetId) =>
  request.delete(`${BASE}/${id}`, { params: targetId ? { targetId } : {} })
/** 删除预演：?dryRun=true&targetId= */
export const deleteDeliveryDryRun = (id, targetId) =>
  request.delete(`${BASE}/${id}`, { params: targetId ? { targetId, dryRun: true } : { dryRun: true } })
