import request from './request'

// 后端接口：/admin/api-keys（request.baseURL 已含 /admin）
const BASE = '/api-keys'

/** 列表：→ [{ id, name, keyPrefix, scopes, expiresAt, status, lastUsedAt, createdAt }] */
export const listApiKeys = () => request.get(BASE)

/**
 * 权限目录（令牌页勾选框的唯一来源）：→ { read: [{value,label}], write: [...], unGrantable: [...], grantable: [...] }
 *
 * 数据来自后端 AiApiKeyService.ALL_SCOPES（13 个可授予 = 9 读 + 4 写）与
 * ApiKeyAuthFilter.unGrantableWriteScopes()（5 个不可授予）。前端**不留抄本**，见 views/api-key/scopeCatalog.js。
 */
export const getScopeCatalog = () => request.get(`${BASE}/scopes`)

/** 新增：body { name, scopes, expiresAt } → { id, name, key, keyPrefix, scopes, expiresAt }（key 仅此一次返回明文） */
export const createApiKey = (data) => request.post(BASE, data)

/** 编辑：body { name?, scopes?, expiresAt?, status? }（不能改 key 本身） */
export const updateApiKey = (id, data) => request.put(`${BASE}/${id}`, data)

/** 删除：删除后该 Key 立即失效 */
export const deleteApiKey = (id) => request.delete(`${BASE}/${id}`)
