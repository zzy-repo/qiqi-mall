import request from './request'

export const listOrders = (page = 1, size = 10) =>
  request.get('/orders', { params: { page, size } })
export const getOrder = (id) => request.get(`/orders/${id}`)
export const createOrder = (data) => request.post('/orders', data)
export const payOrder = (id, payType = 1) => request.post(`/orders/${id}/pay`, null, { params: { payType } })
/** 确认收货：已发货 → 已完成（后端带守卫） */
export const confirmOrder = (id) => request.post(`/orders/${id}/confirm`)

/** 进结算页领取幂等令牌，提交订单时携带，防重复提交 */
export const getIdempotentToken = () => request.get('/orders/idempotent-token')
