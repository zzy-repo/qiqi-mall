import request from './request'

/**
 * 多平台接入（/admin/channel-*）。
 *
 * 请求层 baseURL 已经是 /admin（见 request.js），所以这里写 /channel-shops 实际打的
 * 是 /admin/channel-shops。
 *
 * 两条写接口都要人工 JWT：它们分别会「建订单」与「向平台外呼回传」，
 * 对 API Key 一律 403（scope channel:write 不在可授予目录里，见 ApiKeyAuthFilter）。
 */

/** 三个渠道店铺 + 今日成功/失败/跳过数 */
export const listChannelShops = () => request.get('/channel-shops')

/** 立即拉单（人工触发，本轮没有定时调度）：返回 total/created/skipped/failed/orderIds */
export const pullChannelShop = (id) => request.post(`/channel-shops/${id}/pull`, {})

/**
 * 回传发货：把该订单已出库的包裹逐个回传平台（失败自动重试一次）。
 * ⚠️ 参数走 query：路径故意放在 /admin/channel 下，避开 /admin/orders 的权限前缀。
 */
export const pushShipment = (orderId) => request.post('/channel/push-shipment', {}, { params: { orderId } })

/** 同步日志分页：{ page, size, shopId?, action?, result? } → MP Page */
export const listChannelSyncLogs = (params) => request.get('/channel-sync-logs', { params })
