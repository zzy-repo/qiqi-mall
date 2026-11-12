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
 * 拆单预览（dry-run，不落库）=> { willDo:"中文描述", plan:[{warehouseId,warehouseName,items:[...]}] }
 * ⚠️ 并发控制口径（用户定的）：**version 乐观锁 + 后端业务守卫** ——
 *    预演只回答「会做什么」（willDo）+「拆成几包」（plan），是纯读。
 *    预演也不吃锁：真正可能撞锁的是执行。
 */
export function splitPreview(orderId) {
  return request('POST', `/admin/orders/${encodeURIComponent(orderId)}/split-preview`);
}

/**
 * 审核通过：订单 7→8，并按方案真的生成发货单 => [{id,deliverySn,...}]
 * ⚠️ orderId 就是全部输入。
 *    并发保护来自后端的业务守卫（仅 status=7 可审核）+ 热表 version 乐观锁，
 *    所以「重复执行 / 期间被别人改过」会由后端带中文原因拒掉，MCP 原样透给模型。
 */
export function auditPass(orderId) {
  return request('POST', `/admin/orders/${encodeURIComponent(orderId)}/audit-pass`);
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

// ---------- 商品域写接口（乐观锁 version） ----------
// ⚠️ 并发控制口径（用户定的）：**只留 version 乐观锁**（后端 406a54b）。三条硬约定，别在工具层软化：
//   ① 缺 version → 后端 code=400「缺少版本号，请刷新页面后重试」（payload 里 code 必须原样透出）；
//   ② version 过期（读之后被别人改过）→ 后端 code=409「数据已被他人修改…，请刷新后重试」，
//      且**一个字段都不会被覆盖**（UPDATE ... WHERE version=? 影响 0 行，事务内直接返回，不落库）；
//   ③ 成功时后端只回 {code:0, data:null}，**不带新版本号** → 新 version 必须由调用方回读（工具层负责回读）。
// 之所以在 MCP 这里也坚持「必须带 version」：MCP 是「人的操作的模拟」，人在 UI 上会撞的锁，
// MCP 走同一个接口必然也要撞到 —— 这正是本轮验收要证明的东西。

/**
 * 更新商品（PUT /admin/products/{id}，body = { product }）。
 * ⚠️ 后端 ProductSaveRequest 只认 { product, gallery } 两个字段；这里只发 product（不碰相册）——
 *    传 gallery 是「整组替换相册」，误传会把相册删光，所以刻意不支持。
 * @param {number} id
 * @param {object} product 至少含 { id, version }；其余字段只传要改的（MyBatis-Plus updateById 跳过 null）
 */
export function updateProduct(id, product) {
  return request('PUT', `/admin/products/${encodeURIComponent(id)}`, { body: { product: { ...product, id } } });
}

/** 更新 SKU（PUT /admin/skus/{id}，body = { sku }）；同样只发 sku（不碰规格值 specs，避免整组替换） */
export function updateSku(id, sku) {
  return request('PUT', `/admin/skus/${encodeURIComponent(id)}`, { body: { sku: { ...sku, id } } });
}

/** SKU 详情 => { sku, specs }（写操作成功后回读新 version 用；列表接口也可以，但这个更准） */
export function skuDetail(id) {
  return request('GET', `/admin/skus/${encodeURIComponent(id)}`);
}

export const mallBase = BASE;
