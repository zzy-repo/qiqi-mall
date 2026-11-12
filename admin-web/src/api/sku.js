import request from './request'

const BASE = '/skus'

/** 列表项含 specText（规格文本）与 pic */
export const listSkus = (params) => request.get(BASE, { params })
/** 详情：{sku, specs} */
export const getSku = (id) => request.get(`${BASE}/${id}`)
/** 保存体：{sku, specs}（规格值聚合于 SKU，整组替换） */
export const createSku = (data) => request.post(BASE, data)
export const updateSku = (id, data) => request.put(`${BASE}/${id}`, data)
/** 该商品所属类型的属性（选规格名用） */
export const listSkuAttributes = (productId) => request.get(`${BASE}/attributes`, { params: { productId } })

/**
 * 删除规格（L3 高危）：body 必须带 { confirmCode }。
 * 被订单明细 / 购物车引用时后端在码校验之后拒绝，并回中文原因。
 */
export const deleteSku = (id, body) => request.delete(`${BASE}/${id}`, body ? { data: body } : undefined)
/** 删除规格预演（?dryRun=true）：返回 { willDo, confirmCode, expiresInSec }，库里零变化 */
export const deleteSkuDryRun = (id) => request.delete(`${BASE}/${id}`, { params: { dryRun: true }, data: {} })
