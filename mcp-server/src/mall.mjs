// qiqi-mall 后端 REST 客户端（stdio MCP 专用）
// 三件事：带 API Key、解 {code,message,data}、code!==0 抛中文原因。
// 认证走 **API Key**（请求头 X-Api-Key），不再「登录拿 JWT / 401 重登」——
// key 是按库隔离的（dev 库一把、test 库一把），换库就换 key，代码不用动。
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

/** 当前 API Key（每次请求现读，便于同进程换库调试）；缺了直接给中文原因，别发一个没凭证的请求 */
export function apiKey() {
  const key = (process.env.MALL_API_KEY || '').trim();
  if (!key) {
    throw new MallError(
      '缺少 MALL_API_KEY 环境变量，无法调用后端。key 按库隔离（dev / test 各一把），' +
        '测试环境可 `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`；造 key 见 scripts/init-ai-key.sh（mcp-server/README「认证」一节）',
      'ENV'
    );
  }
  return key;
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
 * 统一请求：自动带 X-Api-Key；外层 {code,message,data} 解包。
 * code!==0 抛 MallError(message)；HTTP 401（key 无效/被禁用）也给一句能自诊断的中文原因。
 */
export async function request(method, path, { body } = {}) {
  const key = apiKey();
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'X-Api-Key': key,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const payload = await readBody(res);

  if (res.status === 401 || payload.code === 401) {
    throw new MallError(
      `API Key 无效或已被禁用（HTTP 401，key 前缀 ${key.slice(0, 8)}…）：` +
        '请确认这把 key 属于当前库（dev / test 各一把，按库隔离），必要时用 scripts/init-ai-key.sh 重建',
      401
    );
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

/**
 * 拆单预览（dry-run，不落库）=> { plan:[{warehouseId,warehouseName,items:[...]}], confirmCode, expiresInSec }
 * ⚠️ 后端已改成**返回对象**（不再是裸数组）：确认码 confirmCode 由后端签发（KV，5 分钟、一次性），
 *    执行 audit-pass 时必须原样带回；MCP 自己不再造票据。
 */
export function splitPreview(orderId) {
  return request('POST', `/admin/orders/${encodeURIComponent(orderId)}/split-preview`);
}

/**
 * 审核通过：订单 7→8，并按方案真的生成发货单 => [{id,deliverySn,...}]
 * ⚠️ 必须带 confirmCode（split-preview 签发）。后端会校验：存在/未用/未过期/用途/订单匹配/方案指纹。
 *    orderId 也要一起给——码存在后端 KV 里，MCP 解不出来，所以由调用方原样带上。
 */
export function auditPass(orderId, confirmCode) {
  return request('POST', `/admin/orders/${encodeURIComponent(orderId)}/audit-pass`, { body: { confirmCode } });
}

/** 某笔订单拆出来的所有发货单 */
export function listDeliveries(orderId) {
  return request('GET', `/admin/deliveries?orderId=${encodeURIComponent(orderId)}`);
}

// ---------- 订单域只读接口（第 3 批）----------
// ⚠️ 后端实际形态（已读 oms/controller/OrderController.java、DeliveryController.java 确认）：
//   GET /admin/orders            **真分页**（MyBatis Page），支持 page/size/status/orderSn；
//                                orderSn 是 `like`（模糊），status 是精确等值；size 后端自己夹 1~50；
//                                返回 {records,total,size,current,pages}——注意是 current 不是 page
//   GET /admin/orders/{id}       返回 OrderDetailDTO {order, items, returnApplies}；
//                                ⚠️ id 不存在时 **code=0 不报错**，只是 order=null、两个数组为空
//                                （工具层必须自己判空并给中文错误）
//   GET /admin/deliveries/counts 批量「包裹数」，返回 Map<orderId(string),count>；
//                                **只统计未取消(status!=9)的发货单**；逗号分隔、最多 50 个/次（后端不强制）；
//                                非法片段被静默跳过（工具层用 zod 保证都是正整数，不依赖这个兜底）

/**
 * 订单详情（聚合：订单 + 明细 + 退货申请）。
 * ⚠️ 后端对该 id 不存在时返回 {order:null, items:[], returnApplies:[]} 且 code=0，判空在工具层。
 */
export function orderDetail(orderId) {
  return request('GET', `/admin/orders/${encodeURIComponent(orderId)}`);
}

/**
 * 订单列表（后端真分页）=> {records,total,size,current,pages}
 * @param {{page?:number,size?:number,status?:number,orderSn?:string}} q
 */
export function listOrders({ page, size, status, orderSn } = {}) {
  const qs = new URLSearchParams();
  if (page !== undefined && page !== null) qs.set('page', String(page));
  if (size !== undefined && size !== null) qs.set('size', String(size));
  if (status !== undefined && status !== null) qs.set('status', String(status));
  if (orderSn !== undefined && orderSn !== null && String(orderSn).trim() !== '') qs.set('orderSn', String(orderSn).trim());
  const s = qs.toString();
  return request('GET', `/admin/orders${s ? `?${s}` : ''}`);
}

/** 后端 counts 接口单次上限（OrderController 注释口径：一次最多 50 个够一页用；后端不强制，工具层自己切） */
export const DELIVERY_COUNTS_CHUNK = 50;

/**
 * 批量统计多个订单的发货单数量 => Map<orderId, count>（已合并分片结果）。
 * 语义注意：**不含已取消（status=9）的发货单**，与后台订单列表页「N 个包裹」标签一致。
 */
export async function deliveryCounts(orderIds, { chunk = DELIVERY_COUNTS_CHUNK } = {}) {
  const ids = Array.isArray(orderIds) ? orderIds : [];
  const merged = new Map();
  for (let i = 0; i < ids.length; i += chunk) {
    const slice = ids.slice(i, i + chunk);
    const part = await request('GET', `/admin/deliveries/counts?orderIds=${encodeURIComponent(slice.join(','))}`);
    for (const [k, v] of Object.entries(part || {})) merged.set(String(k), Number(v));
  }
  return merged;
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
