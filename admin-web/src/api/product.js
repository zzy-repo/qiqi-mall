import request from './request'

const BASE = '/products'

export const listProducts = () => request.get(BASE)
/** 详情：{product, gallery} */
export const getProduct = (id) => request.get(`${BASE}/${id}`)
/** 保存体：{product, gallery}（相册聚合于商品，整组替换） */
export const createProduct = (data) => request.post(BASE, data)
export const updateProduct = (id, data) => request.put(`${BASE}/${id}`, data)

/**
 * 删除商品（L3 高危）：body 必须带 { confirmCode }。
 * 被订单明细 / 购物车引用时后端在码校验之后拒绝，并回中文原因（建议改为下架）。
 */
export const deleteProduct = (id, body) => request.delete(`${BASE}/${id}`, body ? { data: body } : undefined)
/** 删除商品预演（?dryRun=true）：返回 { willDo, confirmCode, expiresInSec }，库里零变化 */
export const deleteProductDryRun = (id) => request.delete(`${BASE}/${id}`, { params: { dryRun: true }, data: {} })
