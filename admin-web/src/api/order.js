import request from './request'

const BASE = '/orders'

export const listOrders = (params) => request.get(BASE, { params })
export const getOrder = (id) => request.get(`${BASE}/${id}`)
export const createOrder = (data) => request.post(BASE, data)
export const updateOrder = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteOrder = (id) => request.delete(`${BASE}/${id}`)

/** 发货：已付款 → 已发货（后端带守卫） */
export const shipOrder = (id) => request.post(`${BASE}/${id}/ship`)
