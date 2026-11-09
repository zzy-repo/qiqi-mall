import request from './request'

const BASE = '/orders'

export const listOrders = (params) => request.get(BASE, { params })
export const getOrder = (id) => request.get(`${BASE}/${id}`)
export const createOrder = (data) => request.post(BASE, data)
export const updateOrder = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteOrder = (id) => request.delete(`${BASE}/${id}`)
