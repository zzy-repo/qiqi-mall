import request from './request'

const BASE = '/product-types'

export const listProductTypes = () => request.get(BASE)
export const getProductType = (id) => request.get(`${BASE}/${id}`)
export const createProductType = (data) => request.post(BASE, data)
export const updateProductType = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteProductType = (id) => request.delete(`${BASE}/${id}`)
