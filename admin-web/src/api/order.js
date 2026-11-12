import request from './request'

const BASE = '/orders'

export const listOrders = (params) => request.get(BASE, { params })  // { page, size, status?, orderSn? }
export const getOrder = (id) => request.get(`${BASE}/${id}`)
export const createOrder = (data) => request.post(BASE, data)
export const updateOrder = (id, data) => request.put(`${BASE}/${id}`, data)

// ---------------------------------------------------------------------------
// 高危写操作（L3）：统一「预演 ?dryRun=true → 执行 body 带 confirmCode」两条通道。
// 预演函数一律 *DryRun 后缀、返回 { willDo, confirmCode, expiresInSec }，库里零变化；
// 执行函数都接受 body（至少 { confirmCode }），缺码会被后端拒绝。
// ---------------------------------------------------------------------------

/** 发货：已付款(1) → 已发货(2)（后端带守卫） */
export const shipOrder = (id, body) => request.post(`${BASE}/${id}/ship`, body)
/** 发货预演（?dryRun=true）：不落库 */
export const shipOrderDryRun = (id) => request.post(`${BASE}/${id}/ship`, {}, { params: { dryRun: true } })

/** 删除订单（级联删明细/发货单，不可恢复） */
export const deleteOrder = (id, body) => request.delete(`${BASE}/${id}`, { data: body })
/** 删除订单预演（?dryRun=true）：不落库 */
export const deleteOrderDryRun = (id) => request.delete(`${BASE}/${id}`, { params: { dryRun: true }, data: {} })
