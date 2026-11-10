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
/** 审核通过（待审核 → 待发货，并触发拆单） */
export const auditPassOrder = (id) => request.post(`/orders/${id}/audit-pass`)
/** 审核不通过（待审核 → 已关闭，回补库存） */
export const auditRejectOrder = (id) => request.post(`/orders/${id}/audit-reject`)
