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
