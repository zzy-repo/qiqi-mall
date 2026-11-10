import request from './request'

const BASE = '/products'

export const listProducts = () => request.get(BASE)
export const getProduct = (id) => request.get(`${BASE}/${id}`)
export const createProduct = (data) => request.post(BASE, data)
export const updateProduct = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteProduct = (id) => request.delete(`${BASE}/${id}`)
