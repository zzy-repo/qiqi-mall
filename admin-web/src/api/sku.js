import request from './request'

const BASE = '/skus'

/** 列表项含 specText（规格文本）与 pic */
export const listSkus = (params) => request.get(BASE, { params })
/** 详情：{sku, specs} */
export const getSku = (id) => request.get(`${BASE}/${id}`)
/** 保存体：{sku, specs}（规格值聚合于 SKU，整组替换） */
export const createSku = (data) => request.post(BASE, data)
export const updateSku = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteSku = (id) => request.delete(`${BASE}/${id}`)
/** 该商品所属类型的属性（选规格名用） */
export const listSkuAttributes = (productId) => request.get(`${BASE}/attributes`, { params: { productId } })
