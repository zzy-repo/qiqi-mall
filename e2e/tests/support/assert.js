/**
 * 只读断言查询 —— 只发 GET / 只读 localStorage，用来核对「服务端事实」。
 *
 * 规则：本文件不允许出现任何 POST/PUT/DELETE。业务状态只能靠 ui.js 的真实点击推进，
 * 这里只负责把点完之后的事实读回来做断言（分页 total、状态码、快照、运单号……）。
 */
import { API } from './env.js'

// ---------------------------------------------------------------------------
// 令牌：不调用登录接口，直接从已登录页面的 localStorage 读
// ---------------------------------------------------------------------------

/** 管理端令牌（admin 已通过真实登录页登录后从 localStorage 读取） */
export const adminTokenOf = (page) => page.evaluate(() => localStorage.getItem('qiqi_admin_token'))

/** 买家令牌（portal 已通过真实登录页登录后从 localStorage 读取） */
export const memberTokenOf = (page) => page.evaluate(() => localStorage.getItem('qiqi_member_token'))

const auth = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

// ---------------------------------------------------------------------------
// 商品 / SKU
// ---------------------------------------------------------------------------

/** 商品详情 + SKU 列表（库存、价格） */
export async function getProductApi(request, productId = 1) {
  const res = await request.get(`${API}/portal/products/${productId}`)
  const body = await res.json()
  return body.data            // { product, skus, gallery }
}

// ---------------------------------------------------------------------------
// 管理端商品 / SKU（只读）
// ---------------------------------------------------------------------------

/** 后台商品列表（可按名字过滤） */
export async function getAdminProductsApi(request, token, name) {
  const res = await request.get(`${API}/admin/products`, auth(token))
  const list = (await res.json()).data || []
  return name ? list.filter((p) => p.name === name) : list
}

/** 后台商品类型列表 */
export async function getAdminProductTypesApi(request, token) {
  const res = await request.get(`${API}/admin/product-types`, auth(token))
  return (await res.json()).data || []
}

/** 后台属性列表（可选按类型过滤） */
export async function getAdminAttributesApi(request, token, typeId) {
  const url = typeId ? `${API}/admin/attributes?typeId=${typeId}` : `${API}/admin/attributes`
  const res = await request.get(url, auth(token))
  return (await res.json()).data || []
}

/** 后台分类树（扁平列表） */
export async function getAdminCategoriesApi(request, token) {
  const res = await request.get(`${API}/admin/categories`, auth(token))
  return (await res.json()).data || []
}

/** 后台某商品的 SKU 列表 */
export async function getAdminSkusApi(request, token, productId) {
  const res = await request.get(`${API}/admin/skus?productId=${productId}`, auth(token))
  return (await res.json()).data || []
}

// ---------------------------------------------------------------------------
// 管理端订单 / 发货单
// ---------------------------------------------------------------------------

/** 按订单号查订单（管理端列表接口，返回单条记录） */
export async function getAdminOrderBySnApi(request, token, orderSn) {
  const res = await request.get(`${API}/admin/orders?page=1&size=10&orderSn=${orderSn}`, auth(token))
  const records = (await res.json()).data.records
  return records.find((o) => o.orderSn === orderSn)
}

/** 查父订单的子发货单（含 warehouseId / status / trackNo） */
export async function listDeliveriesApi(request, token, orderId) {
  const res = await request.get(`${API}/admin/deliveries?orderId=${orderId}`, auth(token))
  return (await res.json()).data
}

// ---------------------------------------------------------------------------
// 门户端订单 / 售后
// ---------------------------------------------------------------------------

/** 买家的订单分页（含 total / records） */
export async function getMemberOrdersApi(request, token, page = 1, size = 10) {
  const res = await request.get(`${API}/portal/orders?page=${page}&size=${size}`, auth(token))
  return (await res.json()).data
}

/** 买家单个订单详情（含 items 明细快照） */
export async function getMemberOrderApi(request, token, orderId) {
  const res = await request.get(`${API}/portal/orders/${orderId}`, auth(token))
  return (await res.json()).data
}

/** 商品分类列表（C 端） */
export async function getCategoriesApi(request) {
  const res = await request.get(`${API}/portal/categories`)
  return (await res.json()).data || []
}

/** 商品列表（C 端，可按分类过滤） */
export async function getPublishedProductsApi(request, categoryId) {
  const url = categoryId ? `${API}/portal/products?categoryId=${categoryId}` : `${API}/portal/products`
  const res = await request.get(url)
  return (await res.json()).data || []
}

/** 买家个人资料 */
export async function getMemberProfileApi(request, token) {
  const res = await request.get(`${API}/portal/member/profile`, auth(token))
  return (await res.json()).data
}

/** 买家的购物车（含商品名/规格/价格） */
export async function getMemberCartApi(request, token) {
  const res = await request.get(`${API}/portal/cart`, auth(token))
  return (await res.json()).data || []
}

/** 买家收货地址簿 */
export async function getMemberAddressesApi(request, token) {
  const res = await request.get(`${API}/portal/member/addresses`, auth(token))
  return (await res.json()).data || []
}

/** 买家的退货申请列表 */
export async function getMemberReturnsApi(request, token) {
  const res = await request.get(`${API}/portal/returns`, auth(token))
  return (await res.json()).data
}

// ---------------------------------------------------------------------------
// 发货单（包裹）状态 与「父订单聚合」口径 —— 纯断言侧常量/纯函数，不发任何请求
//
// 见 DeliveryFlowService：子单 0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消；
//     父单聚合：全部子单 ≥已签收 → 3 已完成；全部 ≥已出库 → 2 已发货；部分出库 → 9 部分发货；
//              一张都没出库（仅退货被拒这条路径）→ 8 待发货。
//
// 为什么放在这里：退货被拒后的订单状态由「未取消包裹」聚合推导，凡是断言这件事的 spec
// 都得用同一份实现（买家侧 6-售后退货、商家侧 4-退货处理…），否则口径会各写一份、逐步过期。
// ---------------------------------------------------------------------------

export const DELIVERY_OUTBOUND = 3
export const DELIVERY_SIGNED = 5
export const DELIVERY_CANCELLED = 9

/** 父订单状态码 → 文案（与后端 OrderStatus 对齐） */
export const ORDER_STATUS_TEXT = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货']

/** 子发货单状态码 → 文案（只用于日志可读性） */
export const deliveryStatusText = (status) => `${{ 0: '待下发', 1: '已下发', 2: '已拣货', 3: '已出库', 4: '运输中', 5: '已签收', 9: '已取消' }[status] ?? status}`

/**
 * 按「未取消的包裹」推导父订单状态 —— 断言不写死数字，而是照着后端的聚合规则算一遍再对撞。
 * 这样后端口径（子单是事实源）变、或包裹张数变，断言都会自己跟上，而不是变成一条过期常量。
 * @param active 未取消的包裹（已剔除 status=9）
 * @returns 聚合出的父单状态码；0 张包裹（直发单）时返回 null，调用方应自行决定怎么断
 */
export function aggregateOrderStatusOf(active) {
  if (active.length === 0) return null                                        // 直发单：没有子单事实可依
  if (active.every((d) => d.status >= DELIVERY_SIGNED)) return 3              // 已完成
  if (active.every((d) => d.status >= DELIVERY_OUTBOUND)) return 2            // 已发货（全部出库）
  if (active.some((d) => d.status >= DELIVERY_OUTBOUND)) return 9             // 部分发货
  return 8                                                                    // 待发货（都还没出库）
}
