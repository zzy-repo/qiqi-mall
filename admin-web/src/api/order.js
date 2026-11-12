import request from './request'

const BASE = '/orders'

export const listOrders = (params) => request.get(BASE, { params })  // { page, size, status?, orderSn? }
export const getOrder = (id) => request.get(`${BASE}/${id}`)
export const createOrder = (data) => request.post(BASE, data)
/** 更新订单基础信息：body 里必须带 version（乐观锁，缺了后端回 400） */
export const updateOrder = (id, data) => request.put(`${BASE}/${id}`, data)

// ---------------------------------------------------------------------------
// 高危写操作（L3）：统一「?dryRun=true 预演 → 执行」两条通道。
// 预演函数一律 *DryRun 后缀、返回 { willDo }，库里零变化。
// 确认码已下线：执行时不再需要 body 里带 confirmCode（并发保护交给行级 version）。
// ---------------------------------------------------------------------------

/** 发货：已付款(1) → 已发货(2)（后端带守卫） */
export const shipOrder = (id) => request.post(`${BASE}/${id}/ship`)
/** 发货预演（?dryRun=true）：不落库 */
export const shipOrderDryRun = (id) => request.post(`${BASE}/${id}/ship`, {}, { params: { dryRun: true } })

/** 删除订单（级联删明细/发货单，不可恢复） */
export const deleteOrder = (id) => request.delete(`${BASE}/${id}`)
/** 删除订单预演（?dryRun=true）：不落库 */
export const deleteOrderDryRun = (id) => request.delete(`${BASE}/${id}`, { params: { dryRun: true } })
