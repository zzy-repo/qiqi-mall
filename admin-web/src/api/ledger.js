import request from './request'

// 请求层 baseURL 已经是 /admin（见 request.js），所以这里只写 /ledgers，
// 实际打到 /admin/ledgers。
const BASE = '/ledgers'

/**
 * 台账分页查询。
 * 参数名是 page / size（不是 current / pageSize），size 上限 50 —— 后端按 MP 的 Page 收参。
 * 其余过滤项全部可选：type / bizId / operatorType / from / to。
 * 返回 MP Page 结构：{ records, total, size, current, pages }。
 */
export const listLedgers = (params) => request.get(BASE, { params })
