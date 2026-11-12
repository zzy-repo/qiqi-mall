import request from './request'

const BASE = '/products'

export const listProducts = () => request.get(BASE)
/** 详情：{product, gallery}（product 里带 version，编辑时原样带回） */
export const getProduct = (id) => request.get(`${BASE}/${id}`)
/** 保存体：{product, gallery}（相册聚合于商品，整组替换）。编辑时 product 必须带 version */
export const createProduct = (data) => request.post(BASE, data)
export const updateProduct = (id, data) => request.put(`${BASE}/${id}`, data)

/**
 * 删除商品（L3 高危）：被订单明细 / 购物车引用时后端拒绝，并回中文原因（建议改为下架）。
 * 确认码已下线 —— 删除不再需要「码」，只保留「?dryRun=true 预演」与真正执行两条路径。
 */
export const deleteProduct = (id) => request.delete(`${BASE}/${id}`)
/** 删除商品预演（?dryRun=true）：返回 { willDo }，库里零变化 */
export const deleteProductDryRun = (id) => request.delete(`${BASE}/${id}`, { params: { dryRun: true } })
