import request from './request'

const BASE = '/carts'

export const listCarts = (params) => request.get(BASE, { params })
export const createCart = (data) => request.post(BASE, data)
export const updateCart = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteCart = (id) => request.delete(`${BASE}/${id}`)
