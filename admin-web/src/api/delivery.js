import request from './request'

const BASE = '/deliveries'

/** 按父订单查子发货单（含仓库名/明细/运单号） */
export const listDeliveries = (orderId) => request.get(BASE, { params: { orderId } })
/** 下发 WMS（待下发 → 已下发） */
export const dispatchDelivery = (id) => request.post(`${BASE}/${id}/dispatch`)
/** 取消（待下发 → 已取消，回补库存） */
export const cancelDelivery = (id) => request.post(`${BASE}/${id}/cancel`)
/** 模拟 WMS 回传：pick | outbound | transit | signed */
export const wmsCallback = (id, event) => request.post(`${BASE}/${id}/wms-callback`, null, { params: { event } })
/** 批量查订单的包裹数（列表页「N 个包裹」标签）：orderIds 数组 */
export const deliveryCounts = (orderIds) => request.get(`${BASE}/counts`, { params: { orderIds: orderIds.join(',') } })
/** 审核弹窗的「查看拆单方案」：dry-run，不落库；守卫仅待审核 */
export const splitPreviewOrder = (id) => request.post(`/orders/${id}/split-preview`)
/** 编辑待下发包裹：改发货仓 / 改明细数量（守卫仅待下发） */
export const updateDelivery = (id, data) => request.put(`${BASE}/${id}`, data)
/** 拆分待下发包裹：拆出一个新的待下发包裹 */
export const splitDelivery = (id, data) => request.post(`${BASE}/${id}/split`, data)
/** 合并待下发包裹：把 id 并入 targetId */
export const mergeDelivery = (id, targetId) => request.post(`${BASE}/${id}/merge`, { targetId })
/** 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处） */
export const deleteDelivery = (id, targetId) =>
  request.delete(`${BASE}/${id}`, { params: targetId ? { targetId } : {} })

/** 审核通过（待审核 → 待发货，并触发拆单） */
export const auditPassOrder = (id) => request.post(`/orders/${id}/audit-pass`)
/** 审核不通过（待审核 → 已关闭，回补库存） */
export const auditRejectOrder = (id) => request.post(`/orders/${id}/audit-reject`)
