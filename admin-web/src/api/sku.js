import request from './request'

const BASE = '/skus'

/** 列表项含 specText（规格文本）、pic 与 version（编辑时原样带回） */
export const listSkus = (params) => request.get(BASE, { params })
/** 详情：{sku, specs}（sku 里带 version） */
export const getSku = (id) => request.get(`${BASE}/${id}`)
/** 保存体：{sku, specs}（规格值聚合于 SKU，整组替换）。编辑时 sku 必须带 version */
export const createSku = (data) => request.post(BASE, data)
export const updateSku = (id, data) => request.put(`${BASE}/${id}`, data)
/** 该商品所属类型的属性（选规格名用） */
export const listSkuAttributes = (productId) => request.get(`${BASE}/attributes`, { params: { productId } })

/**
 * 删除规格（L3 高危）：被订单明细 / 购物车引用时后端拒绝，并回中文原因。
 * 确认码已下线 —— 删除不再需要「码」，只保留「?dryRun=true 预演」与真正执行两条路径。
 */
export const deleteSku = (id) => request.delete(`${BASE}/${id}`)
/** 删除规格预演（?dryRun=true）：返回 { willDo }，库里零变化 */
export const deleteSkuDryRun = (id) => request.delete(`${BASE}/${id}`, { params: { dryRun: true } })
