import request from './request'

const BASE = '/deliveries'

/** 说明：POST 预演只带 query，但必须显式给一个 JSON 空对象 body —— axios 对 data=null 的请求
 * 会带 application/x-www-form-urlencoded，后端 @RequestBody 会直接 415 拒掉（实测踩过）。 */

/**
 * 发货单（包裹）接口。
 *
 * 高危写操作（L3）统一两条通道：
 *   ① 预演：同一路径 + ?dryRun=true → { code:0, data:{ willDo, ... } }，库里零变化；
 *   ② 执行：同一路径 + 业务 body（确认码已下线，不再需要 confirmCode）。
 * 所以每个写操作都有成对的 `xxxDryRun(...)`（预演）与 `xxx(...)`（执行）。
 *
 * 乐观锁：待下发包裹的四个人工干预入口（编辑 / 拆分 / 合并 / 删除）都要带行级 version，
 * 版本号一律从 GET /admin/deliveries?orderId= 的**该行**取；缺了后端 400、过期 409。
 * 其中编辑走 body，拆分 / 合并走 body，**删除走 query**（见 deleteDelivery 的注释）。
 * 预演（?dryRun=true）**都不需要 version**：后端在版本校验之前就返回预演结果。
 */

// ---------------------------------------------------------------------------
// 只读
// ---------------------------------------------------------------------------

/** 按父订单查子发货单（含仓库名/明细/运单号/version） */
export const listDeliveries = (orderId) => request.get(BASE, { params: { orderId } })
/** 批量查订单的包裹数（列表页「N 个包裹」标签）：orderIds 数组 */
export const deliveryCounts = (orderIds) => request.get(`${BASE}/counts`, { params: { orderIds: orderIds.join(',') } })

// ---------------------------------------------------------------------------
// 订单审核（1 通过 / 2 不通过）+ 拆单方案预览
// ---------------------------------------------------------------------------

/** 审核通过：待审核 → 待发货，并触发拆单 */
export const auditPassOrder = (id) => request.post(`/orders/${id}/audit-pass`, {})
/**
 * 审核通过预演：走 split-preview（真预览，不落库）。
 * 返回结构：{ willDo, plan:[{warehouseId,warehouseName,items:[...]}] }
 * —— 比 audit-pass?dryRun=true 多一份 plan，弹窗里能顺带把「拆成几个包裹」列出来。
 */
export const auditPassDryRun = (id) =>
  request.post(`/orders/${id}/split-preview`, {}, { params: { dryRun: true } })

/** 审核不通过：待审核 → 已关闭，回补库存 */
export const auditRejectOrder = (id) => request.post(`/orders/${id}/audit-reject`, {})
/** 审核不通过预演（?dryRun=true） */
export const auditRejectDryRun = (id) =>
  request.post(`/orders/${id}/audit-reject`, {}, { params: { dryRun: true } })

// ---------------------------------------------------------------------------
// 发货单状态流转
// ---------------------------------------------------------------------------

/** 下发 WMS（待下发 → 已下发） */
export const dispatchDelivery = (id) => request.post(`${BASE}/${id}/dispatch`, {})
/** 下发预演（?dryRun=true） */
export const dispatchDeliveryDryRun = (id) => request.post(`${BASE}/${id}/dispatch`, {}, { params: { dryRun: true } })

/** 取消（待下发 → 已取消，回补库存） */
export const cancelDelivery = (id) => request.post(`${BASE}/${id}/cancel`, {})
/** 取消预演（?dryRun=true） */
export const cancelDeliveryDryRun = (id) => request.post(`${BASE}/${id}/cancel`, {}, { params: { dryRun: true } })

/** 模拟 WMS 回传：pick | outbound | transit | signed */
export const wmsCallback = (id, event) =>
  request.post(`${BASE}/${id}/wms-callback`, {}, { params: { event } })
/** WMS 回传预演（?dryRun=true&event=xxx） */
export const wmsCallbackDryRun = (id, event) =>
  request.post(`${BASE}/${id}/wms-callback`, {}, { params: { event, dryRun: true } })

// ---------------------------------------------------------------------------
// 待下发包裹的人工干预（编辑 / 拆分 / 合并 / 删除）
// ---------------------------------------------------------------------------

/** 编辑待下发包裹：改发货仓 / 改明细数量（守卫仅待下发）。body = { warehouseId, items, version } */
export const updateDelivery = (id, data) => request.put(`${BASE}/${id}`, data)
/** 编辑预演：同一路径 + ?dryRun=true，body 是同一份（含 version） */
export const updateDeliveryDryRun = (id, data) =>
  request.put(`${BASE}/${id}`, data, { params: { dryRun: true } })

/**
 * 拆分待下发包裹：拆出一个新的待下发包裹。
 * body = { newWarehouseId, items, version }
 * version 来自 GET /admin/deliveries?orderId= 的对应行（乐观锁）；缺了后端 400、过期 409。
 */
export const splitDelivery = (id, data, version) =>
  request.post(`${BASE}/${id}/split`, { ...data, version })
/**
 * 拆分预演：**不带 version**。
 * 后端在版本校验**之前**就返回预演结果（预演不落库、也不占用版本），所以预演不需要版本号；
 * 真带上了反而会让「预演过了、执行 409」这种正常并发场景变得更难解释。
 */
export const splitDeliveryDryRun = (id, data) =>
  request.post(`${BASE}/${id}/split`, data, { params: { dryRun: true } })

/** 合并待下发包裹：把 id 并入 targetId。body = { targetId, version } */
export const mergeDelivery = (id, targetId, version) =>
  request.post(`${BASE}/${id}/merge`, { targetId, version })
/** 合并预演：不带 version（同上，预演在版本校验之前返回） */
export const mergeDeliveryDryRun = (id, targetId) =>
  request.post(`${BASE}/${id}/merge`, { targetId }, { params: { dryRun: true } })

/**
 * 删除待下发包裹：还有明细时必须带 targetId（等同于并入别处）。
 *
 * ⚠️ targetId 与 **version 都走 query**，不走 body：DELETE 带 body 过代理/CDN 不可靠
 * （nginx / 部分中间件会丢掉 DELETE 的 body，后端 `@RequestBody` 就拿不到 → 莫名 400）。
 */
export const deleteDelivery = (id, targetId, version) =>
  request.delete(`${BASE}/${id}`, { params: { ...(targetId ? { targetId } : {}), version } })
/** 删除预演：?dryRun=true&targetId=（不带 version，同拆/合） */
export const deleteDeliveryDryRun = (id, targetId) =>
  request.delete(`${BASE}/${id}`, { params: targetId ? { targetId, dryRun: true } : { dryRun: true } })
