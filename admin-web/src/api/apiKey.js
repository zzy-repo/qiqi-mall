import request from './request'

// 后端接口：/admin/api-keys（request.baseURL 已含 /admin）
const BASE = '/api-keys'

/** 列表：→ [{ id, name, keyPrefix, scopes, expiresAt, status, lastUsedAt, createdAt }] */
export const listApiKeys = () => request.get(BASE)

/** 新增：body { name, scopes, expiresAt } → { id, name, key, keyPrefix, scopes, expiresAt }（key 仅此一次返回明文） */
export const createApiKey = (data) => request.post(BASE, data)

/** 编辑：body { name?, scopes?, expiresAt?, status? }（不能改 key 本身） */
export const updateApiKey = (id, data) => request.put(`${BASE}/${id}`, data)

/** 删除：删除后该 Key 立即失效 */
export const deleteApiKey = (id) => request.delete(`${BASE}/${id}`)
