// qiqi-mall 后端 REST 客户端（stdio MCP 专用）
// 只做四件事：登录拿 Bearer、解 {code,message,data}、code!==0 抛中文原因、401 重登一次。
// 配置全部走环境变量（绝不读相对路径配置文件，否则 cwd 一变就崩）。

const BASE = (process.env.MALL_BASE || 'http://127.0.0.1:18080').replace(/\/+$/, '');

/** 后端业务失败：message 是后端原样的中文原因，直接透给模型 */
export class MallError extends Error {
  constructor(message, code) {
    super(message);
    this.name = 'MallError';
    this.code = code;
  }
}

let token = null;
let loginInFlight = null;

async function doLogin() {
  const username = process.env.MALL_USER;
  const password = process.env.MALL_PASS;
  if (!username || !password) {
    throw new MallError('缺少 MALL_USER / MALL_PASS 环境变量，无法登录后端', 'ENV');
  }
  const res = await fetch(`${BASE}/admin/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const body = await readBody(res);
  if (body.code !== 0 || !body.data?.accessToken) {
    throw new MallError(`登录失败：${body.message || `HTTP ${res.status}`}（账号 ${username}）`, body.code);
  }
  token = body.data.accessToken;
  return token;
}

async function ensureToken() {
  if (token) return token;
  if (!loginInFlight) {
    loginInFlight = doLogin().finally(() => { loginInFlight = null; });
  }
  return loginInFlight;
}

/** 强制下次请求重新登录（token 过期 / 被踢） */
export function resetToken() {
  token = null;
}

async function readBody(res) {
  const text = await res.text();
  if (!text) return { code: res.ok ? 0 : res.status, message: `HTTP ${res.status}`, data: null };
  try {
    return JSON.parse(text);
  } catch {
    return { code: res.status, message: `响应不是 JSON（HTTP ${res.status}）：${text.slice(0, 200)}`, data: null };
  }
}

/**
 * 统一请求：自动带 Bearer；外层 {code,message,data} 解包。
 * code!==0 抛 MallError(message)；HTTP 401 或 code===401 时重登一次再重试（仅一次）。
 */
export async function request(method, path, { body, retried = false } = {}) {
  const t = await ensureToken();
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${t}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const payload = await readBody(res);

  const unauthorized = res.status === 401 || payload.code === 401;
  if (unauthorized && !retried) {
    resetToken();
    await ensureToken();
    return request(method, path, { body, retried: true });
  }

  if (payload.code !== 0) {
    throw new MallError(payload.message || `请求失败（HTTP ${res.status}）`, payload.code);
  }
  return payload.data;
}

// ---------- 业务接口 ----------

/** 待审核订单（status=7）：MyBatis Page => {records,total,size,current} */
export function listPendingOrders({ size = 20 } = {}) {
  return request('GET', `/admin/orders?status=7&page=1&size=${encodeURIComponent(size)}`);
}

/** 拆单预览（dry-run，不落库）=> [{warehouseId,warehouseName,items:[...]}] */
export function splitPreview(orderId) {
  return request('POST', `/admin/orders/${encodeURIComponent(orderId)}/split-preview`);
}

/** 审核通过：订单 7→8，并按方案真的生成发货单 => [{id,deliverySn,...}] */
export function auditPass(orderId) {
  return request('POST', `/admin/orders/${encodeURIComponent(orderId)}/audit-pass`);
}

/** 某笔订单拆出来的所有发货单 */
export function listDeliveries(orderId) {
  return request('GET', `/admin/deliveries?orderId=${encodeURIComponent(orderId)}`);
}

/** 订单详情（用于回读 status） */
export function orderDetail(orderId) {
  return request('GET', `/admin/orders/${encodeURIComponent(orderId)}`);
}

// ---------- 商品域（catalog）只读接口 ----------
// ⚠️ 后端实际形态（已读 Controller 确认，别按"标准分页"想当然）：
//   GET /admin/products      只支持 publishStatus 过滤，**不分页**，直接返回 List<Product>
//   GET /admin/skus          支持 productId 过滤，**不分页**，返回带 specText 的 List
//   GET /admin/attributes    支持 typeId 过滤，**不分页**
//   GET /admin/categories、/admin/product-types 全量返回，**不分页**，也不含子级/属性
// 因此分页/关键字/分类树这些都在工具层做（见 src/tools/catalog.mjs）。

/** 商品列表（全量；publishStatus: 0 下架 / 1 上架，可选） */
export function listProducts({ publishStatus } = {}) {
  const qs = publishStatus === undefined || publishStatus === null ? '' : `?publishStatus=${encodeURIComponent(publishStatus)}`;
  return request('GET', `/admin/products${qs}`);
}

/** 商品详情 => {product, gallery}（后端聚合：商品 + 相册） */
export function productDetail(id) {
  return request('GET', `/admin/products/${encodeURIComponent(id)}`);
}

/** 分类全量（扁平；parentId=0 为顶级，后端不返回树） */
export function listCategories() {
  return request('GET', '/admin/categories');
}

/** 商品类型全量（扁平；**不含**属性，属性要另调 listAttributes） */
export function listProductTypes() {
  return request('GET', '/admin/product-types');
}

/** 属性列表（typeId 可选：不传=全部属性） */
export function listAttributes({ typeId } = {}) {
  const qs = typeId === undefined || typeId === null ? '' : `?typeId=${encodeURIComponent(typeId)}`;
  return request('GET', `/admin/attributes${qs}`);
}

/** SKU 列表（productId 可选：不传=全部 SKU），每项含 specText 规格文本 */
export function listSkus({ productId } = {}) {
  const qs = productId === undefined || productId === null ? '' : `?productId=${encodeURIComponent(productId)}`;
  return request('GET', `/admin/skus${qs}`);
}

export const mallBase = BASE;
