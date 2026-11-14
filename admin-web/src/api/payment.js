import request from './request'

// 请求层 baseURL 已经是 /admin（见 request.js），所以这里只写 /payments，
// 实际打到 /admin/payments{...}。
const BASE = '/payments'

/**
 * 重放渠道回调（**沙箱端点**）：拿这条流水**原来的** trade_no 再走一遍渠道回调逻辑，
 * 等价于「第三方把同一笔通知又发了一次」。
 *
 * 为什么这个入口必须有：`payment_record` 上 (channel, trade_no) 有唯一键 uk_channel_trade，
 * 但「同一笔通知来两次」这件事在界面上此前**造不出来**（模拟支付的 trade_no 是本地按时间戳生成的），
 * 于是「重复通知被幂等键挡住」只能写在注释里，没法演示。
 *
 * 后端契约（`POST /admin/payments/{id}/replay-channel-callback`，2026-11-14 用真实 curl 核过）：
 * <pre>
 *   重复通知 → { code:0, message:'重复通知，已忽略',
 *                data:{ paymentId, orderSn, channel, tradeNo, type, outcome:'DUPLICATE', recorded:false } }
 *   首次入账 → { code:0, message:'渠道通知已入账', ... outcome:'RECORDED', recorded:true }
 *   真失败   → { code:1, message:'渠道回调重放只在非生产环境开放（…）：生产环境没有这个口子' | 其它中文 }
 * </pre>
 *
 * 两条口径（前端必须照此处理，否则会把「正常」显示成「报错」）：
 *   ① **code=0 只表示「这次通知处理完了」，不表示新增了一行** —— 到底记没记账看 data.outcome；
 *      重复通知写成 code=0 是刻意的：它是渠道的正常行为，报错会诱导调用方去重试，而重试只会继续被挡。
 *   ② code=1 才是真失败（沙箱开关 `qiqi.payment.sandbox-endpoints-enabled` 关着 / 流水不存在 /
 *      该流水没有 trade_no / 撞了 uk_channel_trade 之外唯一键要求人工对账）。
 *      request.js 已把后端中文原文挂到 `err.message`、业务码挂到 `err.bizCode` 并 reject。
 *
 * 权限：`payment:write` 不可授予 ⇒ API Key 一律 403；只有人工 JWT（后台登录）能调。
 */
export const replayChannelCallback = (id) => request.post(`${BASE}/${id}/replay-channel-callback`)
