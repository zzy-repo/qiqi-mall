import request from './request'

export const listOrders = () => request.get('/orders')
export const getOrder = (id) => request.get(`/orders/${id}`)
export const createOrder = (data) => request.post('/orders', data)
export const payOrder = (id, payType = 1) => request.post(`/orders/${id}/pay`, null, { params: { payType } })
/** 确认收货：已发货 → 已完成（后端带守卫） */
export const confirmOrder = (id) => request.post(`/orders/${id}/confirm`)
