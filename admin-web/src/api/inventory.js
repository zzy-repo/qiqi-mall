import request from './request'

// 请求层 baseURL 已经是 /admin，实际打到 /admin/inventory/**
const BASE = '/inventory'

/**
 * 单 SKU 库存对账。
 * 返回 { skuId, skuCode, skuName, bucketSum, skuStock, drift, lastRefreshAt,
 *        bucketCount, ledgerNet, ledgerRows[] }
 *
 * 口径提醒（前端取值时必须分清，别把三个数当同一层含义）：
 *   bucketSum  = SUM(sku_bucket.stock)  —— 权威值，桶是事实源
 *   skuStock   = sku.stock              —— 展示缓存，应当等于 bucketSum，drift 就是滞后量
 *   ledgerNet  = SUM(stock_ledger.delta) —— 全生命周期进出和，**不是余额**，不能与 bucketSum 对平
 */
export const getReconcile = (skuId) => request.get(`${BASE}/reconcile`, { params: { skuId } })

/**
 * 全库偏差 TOP：|drift| >= threshold 的 SKU 分页。
 * page / size 同列表口径，threshold 默认 100（后端默认也是 100）。
 */
export const listReconcileDrift = (params) => request.get(`${BASE}/reconcile/drift`, { params })
