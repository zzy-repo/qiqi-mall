import request from './request'

const BASE = '/products'

export const listProducts = () => request.get(BASE)
/** 详情：{product, gallery} */
export const getProduct = (id) => request.get(`${BASE}/${id}`)
/** 保存体：{product, gallery}（相册聚合于商品，整组替换） */
export const createProduct = (data) => request.post(BASE, data)
export const updateProduct = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteProduct = (id) => request.delete(`${BASE}/${id}`)
