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

// ---------- 履约：发货单（delivery 域，第 4 批）----------
// ⚠️ 后端实际形态（已读 oms/controller/DeliveryController.java + DeliveryEditService / DeliveryFlowService 确认，
//    别按「标准 REST」想当然）：
//   GET    /admin/deliveries?orderId=   **orderId 是必填参数**（@RequestParam Long orderId，不给→HTTP 400），
//                                       返回 List 且**不分页**；每项自带 warehouseName / version / items / company / trackNo
//   ——     **没有** GET /admin/deliveries/{id}（单条详情），也没有「不带 orderId 的全局发货单列表」；
//          所以工具层的 action=get 只能「按 orderId 取列表再挑出那一条」（见 tools/delivery.mjs）
//   PUT    /admin/deliveries/{id}        body { warehouseId?, items?:[{deliveryItemId,quantity}], version }
//   POST   /admin/deliveries/{id}/split  body { newWarehouseId?, items:[{deliveryItemId,quantity}], version }
//   POST   /admin/deliveries/{id}/merge  body { targetId, version }
//   DELETE /admin/deliveries/{id}        **version 在 query**（?targetId=&version=），不是 body ——
//                                        DELETE 带请求体在部分代理/网关会被丢掉，后端刻意改成 query
//   POST   /admin/deliveries/{id}/dispatch、/{id}/cancel   这两个**不收 version**：
//                                        由「仅待下发」状态守卫 + UPDATE 影响行数闸门兜住
//   以上写操作全部支持 ?dryRun=true：只返回 { willDo }、不落库、不需要 version（预演不产生冲突）
//   四个编辑动作（update/split/merge/delete）成功时返回 { orderId, splitStatus, deliveries:[...] } ——
//   里面已经带**改完之后的最新 version**，所以工具层回读新 version 只需读这个响应（不必再发一次 GET）。

/** ?dryRun=true 统一拼接（只认布尔 true，与后端 PreviewResult.isDryRun 同口径） */
function dryRunQuery(dryRun) {
  return dryRun === true ? 'dryRun=true' : '';
}

/**
 * 编辑待下发包裹（改仓 / 改量）。
 * @param {number} id 发货单 id
 * @param {{warehouseId?:number, items?:Array<{deliveryItemId:number,quantity:number}>, version?:number, dryRun?:boolean}} req
 */
export function updateDelivery(id, { warehouseId, items, version, dryRun } = {}) {
  const body = {};
  if (warehouseId !== undefined && warehouseId !== null) body.warehouseId = warehouseId;
  if (Array.isArray(items) && items.length) body.items = items;
  if (version !== undefined && version !== null) body.version = version;
  const qs = dryRunQuery(dryRun);
  return request('PUT', `/admin/deliveries/${encodeURIComponent(id)}${qs ? `?${qs}` : ''}`, { body });
}

/** 拆分待下发包裹（拆出一个新包裹） */
export function splitDelivery(id, { newWarehouseId, items, version, dryRun } = {}) {
  const body = {};
  if (newWarehouseId !== undefined && newWarehouseId !== null) body.newWarehouseId = newWarehouseId;
  if (Array.isArray(items) && items.length) body.items = items;
  if (version !== undefined && version !== null) body.version = version;
  const qs = dryRunQuery(dryRun);
  return request('POST', `/admin/deliveries/${encodeURIComponent(id)}/split${qs ? `?${qs}` : ''}`, { body });
}

/** 合并待下发包裹（源包 {id} 并入 targetId；version 校验的是**源包**那一行） */
export function mergeDelivery(id, { targetId, version, dryRun } = {}) {
  const body = {};
  if (targetId !== undefined && targetId !== null) body.targetId = targetId;
  if (version !== undefined && version !== null) body.version = version;
  const qs = dryRunQuery(dryRun);
  return request('POST', `/admin/deliveries/${encodeURIComponent(id)}/merge${qs ? `?${qs}` : ''}`, { body });
}

/** 删除待下发包裹（有明细时必须给 targetId，等同并入别处）；⚠️ version / targetId 都在 query */
export function deleteDelivery(id, { targetId, version, dryRun } = {}) {
  const qs = new URLSearchParams();
  if (targetId !== undefined && targetId !== null) qs.set('targetId', String(targetId));
  if (version !== undefined && version !== null) qs.set('version', String(version));
  if (dryRun === true) qs.set('dryRun', 'true');
  const s = qs.toString();
  return request('DELETE', `/admin/deliveries/${encodeURIComponent(id)}${s ? `?${s}` : ''}`);
}

/** 下发仓库（状态 待下发→已下发；**不可撤回**，只能等 WMS 回传）；返回 DeliveryOrder 实体 */
export function dispatchDelivery(id, { dryRun } = {}) {
  const qs = dryRunQuery(dryRun);
  return request('POST', `/admin/deliveries/${encodeURIComponent(id)}/dispatch${qs ? `?${qs}` : ''}`);
}

/** 取消发货单（状态 待下发→已取消，**回补总池库存**）；返回 DeliveryOrder 实体 */
export function cancelDelivery(id, { dryRun } = {}) {
  const qs = dryRunQuery(dryRun);
  return request('POST', `/admin/deliveries/${encodeURIComponent(id)}/cancel${qs ? `?${qs}` : ''}`);
}

// ---------- 退货（returns 域，第 4 批）----------
// ⚠️ 后端实际形态（已读 ReturnApplyController / ReturnReasonController 确认）：
//   GET /admin/return-applies?status=&orderId=   List，**不分页**；返回项自带 orderSn（后端已 join 好）
//   ——  **没有** GET /admin/return-applies/{id}（单条详情）→ 工具层 get 只能「取列表再挑一条」
//   POST /admin/return-applies                   创建（本批 MCP 不暴露：退货申请由买家在 C 端发起）
//   PUT  /admin/return-applies/{id}              body {status:1通过/2拒绝, description?}，支持 ?dryRun=true
//        ⚠️ **return_apply 表没有 version 列**：并发靠「UPDATE ... WHERE id=? AND status=0 影响 0 行 → 409」
//        加「order 行 @Version 0 行 → 409 整笔回滚」两道闸门，所以 MCP 这里**没有 version 可传**（不是我省略）
//   GET/POST/PUT/DELETE /admin/return-reasons    原因字典：List 不分页；DELETE 被引用时后端拒绝

/** 退货申请列表（status / orderId 都可选，都是精确等值） */
export function listReturnApplies({ status, orderId } = {}) {
  const qs = new URLSearchParams();
  if (status !== undefined && status !== null) qs.set('status', String(status));
  if (orderId !== undefined && orderId !== null) qs.set('orderId', String(orderId));
  const s = qs.toString();
  return request('GET', `/admin/return-applies${s ? `?${s}` : ''}`);
}

/**
 * 审核退货申请（status=1 通过 → 退款 + 回补库存；2 拒绝 → 订单回已付款）。
 * ⚠️ 没有 version 可传（表无 version 列），并发由后端影响行数闸门兜住 → 409。
 */
export function auditReturnApply(id, { status, description, dryRun } = {}) {
  const body = { status };
  if (description !== undefined && description !== null) body.description = description;
  const qs = dryRunQuery(dryRun);
  return request('PUT', `/admin/return-applies/${encodeURIComponent(id)}${qs ? `?${qs}` : ''}`, { body });
}

/** 退货原因字典（全量，不分页） */
export function listReturnReasons() {
  return request('GET', '/admin/return-reasons');
}

export function createReturnReason({ name }) {
  return request('POST', '/admin/return-reasons', { body: { name } });
}

/** 改原因名（表无 version 列；后端 updateById 影响 0 行 → 409「记录已被删除」） */
export function updateReturnReason(id, { name }) {
  return request('PUT', `/admin/return-reasons/${encodeURIComponent(id)}`, { body: { name } });
}

/** 删原因（被退货申请引用时后端直接拒绝，中文原因原样透出） */
export function deleteReturnReason(id) {
  return request('DELETE', `/admin/return-reasons/${encodeURIComponent(id)}`);
}

// ---------- 基础配置（config 域，第 4 批）----------
// ⚠️ 后端实际形态（已读 WarehouseController / OrderSettingController 确认）：
//   GET/POST/PUT/DELETE /admin/warehouses       List 不分页；无 PUT 版本号（表无 version 列，
//                                               updateById 影响 0 行 → 409「记录已被删除」）
//   GET/PUT /admin/order-setting                全局只有一行；GET 没有行时返回**空对象**（不是 null）
//                                               —— 工具层必须能读「未初始化」这种形态
//                                               PUT 是 upsert，**不校验影响行数、也没有 version**

export function listWarehouses() {
  return request('GET', '/admin/warehouses');
}

export function createWarehouse({ name, status }) {
  const body = { name };
  if (status !== undefined && status !== null) body.status = status;
  return request('POST', '/admin/warehouses', { body });
}

export function updateWarehouse(id, { name, status }) {
  const body = {};
  if (name !== undefined && name !== null) body.name = name;
  if (status !== undefined && status !== null) body.status = status;
  return request('PUT', `/admin/warehouses/${encodeURIComponent(id)}`, { body });
}

export function deleteWarehouse(id) {
  return request('DELETE', `/admin/warehouses/${encodeURIComponent(id)}`);
}

/** 订单全局设置（只有一行；未初始化时后端返回空对象 {}） */
export function getOrderSetting() {
  return request('GET', '/admin/order-setting');
}

export function updateOrderSetting({ autoConfirmDays, autoCloseMinutes }) {
  const body = {};
  if (autoConfirmDays !== undefined && autoConfirmDays !== null) body.autoConfirmDays = autoConfirmDays;
  if (autoCloseMinutes !== undefined && autoCloseMinutes !== null) body.autoCloseMinutes = autoCloseMinutes;
  return request('PUT', '/admin/order-setting', { body });
}

// ---------- 会员（member 域，第 4 批）----------
// ⚠️ 后端实际形态（已读 MemberController / MemberAddressController / CartController 确认）：
//   GET /admin/members?status=&username=   List **不分页**（username 是 like 模糊）；返回含 version
//   GET /admin/members/{id}                单条；id 不存在时 data=null（**code=0 不报错**）→ 工具层判空
//   GET /admin/members/{id}/detail         {member, addresses, carts} 聚合（本批工具改用两个细粒度接口，语义更清楚）
//   POST   /admin/members                  新建即 version=0；密码 BCrypt，**响应不回密码**
//   PUT    /admin/members/{id}             **收 body.version（必填）**：缺 → 400，冲突 → 409
//   DELETE /admin/members/{id}             无守卫；⚠️ order.member_id 外键无级联 → 有订单的会员删不掉
//   GET/POST/PUT/DELETE /admin/member-addresses   List 不分页；无 version 列（影响 0 行 → 409「记录已被删除」）
//   GET /admin/carts?memberId=             List 不分页；本批只做只读 list

export function listMembers({ status, username } = {}) {
  const qs = new URLSearchParams();
  if (status !== undefined && status !== null) qs.set('status', String(status));
  if (typeof username === 'string' && username.trim() !== '') qs.set('username', username.trim());
  const s = qs.toString();
  return request('GET', `/admin/members${s ? `?${s}` : ''}`);
}

/** 会员详情（id 不存在时后端 data=null，判空在工具层） */
export function memberDetail(id) {
  return request('GET', `/admin/members/${encodeURIComponent(id)}`);
}

export function createMember({ username, password, nickname, phone, status }) {
  const body = { username, password };
  if (nickname !== undefined && nickname !== null) body.nickname = nickname;
  if (phone !== undefined && phone !== null) body.phone = phone;
  if (status !== undefined && status !== null) body.status = status;
  return request('POST', '/admin/members', { body });
}

/** 改会员（PUT /admin/members/{id}，body 至少含 {version}；updateById 跳 null，只传要改的字段） */
export function updateMember(id, patch) {
  return request('PUT', `/admin/members/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}

export function deleteMember(id) {
  return request('DELETE', `/admin/members/${encodeURIComponent(id)}`);
}

export function listMemberAddresses({ memberId } = {}) {
  const qs = memberId === undefined || memberId === null ? '' : `?memberId=${encodeURIComponent(memberId)}`;
  return request('GET', `/admin/member-addresses${qs}`);
}

export function createMemberAddress(body) {
  return request('POST', '/admin/member-addresses', { body });
}

/** 改地址（表无 version 列；影响 0 行 → 409「记录已被删除」） */
export function updateMemberAddress(id, patch) {
  return request('PUT', `/admin/member-addresses/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}

export function deleteMemberAddress(id) {
  return request('DELETE', `/admin/member-addresses/${encodeURIComponent(id)}`);
}

export function listCarts({ memberId } = {}) {
  const qs = memberId === undefined || memberId === null ? '' : `?memberId=${encodeURIComponent(memberId)}`;
  return request('GET', `/admin/carts${qs}`);
}

// ---------- 概览（misc 域，第 4 批）----------
// GET /admin/dashboard/overview：一次返回看板全部数字（商品/订单/今日成交/退货待处理/库存告警/7 日趋势/Top5）。
// 口径写在后端注释里：今日成交按 payment_time 归日、只算「已付款且未退款」状态（1/2/3/7/8/9）。
// 注意 scope：dashboard 是**只读**（ApiKeyAuthFilter 里读/写 scope 都是 dashboard:read），所以这里没有写接口。

export function dashboardOverview() {
  return request('GET', '/admin/dashboard/overview');
}

// ---------- 第 5 批（覆盖补齐）：把 admin 后台「人能做」的其余写/读端点补齐 ----------
// 口径：见 mcp-server/README「工具表」。每个 wrapper 都对着 Controller 真实入参写，
// 收 version 的收 version、支持 ?dryRun=true 的透 dryRun（只回 willDo，不落库、不需要 version）。

/** 拼 query（值为 undefined / null 的键直接丢掉；dryRun=true 才拼，false 不拼） */
function qs(params = {}) {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    if (v === false) continue;
    sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

// ---- 订单写（OrderController 的 create / update / audit-reject / ship / delete）----
/** POST /admin/orders  body = { order, items }；总额由后端按明细快照价重算 */
export function createOrder({ order, items } = {}) {
  return request('POST', '/admin/orders', { body: { order, items } });
}

/** PUT /admin/orders/{id}  body 必须含 version；后端强制 status=null（状态只能走专用接口） */
export function updateOrder(id, patch = {}) {
  return request('PUT', `/admin/orders/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}

/** POST /admin/orders/{id}/audit-reject?dryRun=true  待审核(7) → 已关闭(4) + 回补总池库存 */
export function auditRejectOrder(id, { dryRun } = {}) {
  return request('POST', `/admin/orders/${encodeURIComponent(id)}/audit-reject${qs({ dryRun })}`);
}

/** POST /admin/orders/{id}/ship?dryRun=true  已付款(1) → 已发货(2)，直发路径不拆单 */
export function shipOrder(id, { dryRun } = {}) {
  return request('POST', `/admin/orders/${encodeURIComponent(id)}/ship${qs({ dryRun })}`);
}

/** DELETE /admin/orders/{id}?dryRun=true  级联删明细/发货单/物流，不可恢复 */
export function deleteOrder(id, { dryRun } = {}) {
  return request('DELETE', `/admin/orders/${encodeURIComponent(id)}${qs({ dryRun })}`);
}

// ---- 发货单 WMS 回传（DeliveryController 的第 9 个端点）----
// POST /admin/deliveries/{id}/wms-callback?event=pick|outbound|transit|signed[&dryRun=true]
// event 是 @RequestParam **必填**；不收 version；接口幂等（状态已到/已过则不改数据）。
export function wmsCallbackDelivery(id, { event, dryRun } = {}) {
  return request('POST', `/admin/deliveries/${encodeURIComponent(id)}/wms-callback${qs({ event, dryRun })}`);
}

// ---- 商品写（ProductController create / delete）----
/** POST /admin/products  body = { product, gallery }；version 由后端置 0 */
export function createProduct({ product, gallery } = {}) {
  return request('POST', '/admin/products', { body: { product, gallery } });
}

/** DELETE /admin/products/{id}?dryRun=true  被订单明细/购物车引用时拒绝（预演也会照实说） */
export function deleteProduct(id, { dryRun } = {}) {
  return request('DELETE', `/admin/products/${encodeURIComponent(id)}${qs({ dryRun })}`);
}

// ---- SKU 写（SkuController create / delete）----
/** POST /admin/skus  body = { sku, specs }；productId 必填（后端拦），version 由后端置 0 */
export function createSku({ sku, specs } = {}) {
  return request('POST', '/admin/skus', { body: { sku, specs } });
}

/** DELETE /admin/skus/{id}?dryRun=true  被订单明细/购物车引用时拒绝 */
export function deleteSku(id, { dryRun } = {}) {
  return request('DELETE', `/admin/skus/${encodeURIComponent(id)}${qs({ dryRun })}`);
}

/** GET /admin/skus/attributes?productId=  「该商品所属类型下的属性」（前端选规格名用） */
export function skuAttributes(productId) {
  return request('GET', `/admin/skus/attributes?productId=${encodeURIComponent(productId)}`);
}

// ---- 基础字典：分类 / 商品类型 / 属性（三张表都**没有 version 列**）----
// 并发口径：后端 updateById 影响 0 行 → 409「记录已被删除」；delete 有引用守卫会回可读中文原因。
export function getCategory(id) {
  return request('GET', `/admin/categories/${encodeURIComponent(id)}`);
}
export function createCategory(body) {
  return request('POST', '/admin/categories', { body });
}
export function updateCategory(id, patch = {}) {
  return request('PUT', `/admin/categories/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}
export function deleteCategory(id) {
  return request('DELETE', `/admin/categories/${encodeURIComponent(id)}`);
}

export function getProductType(id) {
  return request('GET', `/admin/product-types/${encodeURIComponent(id)}`);
}
export function createProductType(body) {
  return request('POST', '/admin/product-types', { body });
}
export function updateProductType(id, patch = {}) {
  return request('PUT', `/admin/product-types/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}
export function deleteProductType(id) {
  return request('DELETE', `/admin/product-types/${encodeURIComponent(id)}`);
}

export function getAttribute(id) {
  return request('GET', `/admin/attributes/${encodeURIComponent(id)}`);
}
export function createAttribute(body) {
  return request('POST', '/admin/attributes', { body });
}
export function updateAttribute(id, patch = {}) {
  return request('PUT', `/admin/attributes/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}
export function deleteAttribute(id) {
  return request('DELETE', `/admin/attributes/${encodeURIComponent(id)}`);
}

// ---- 后台购物车（CartController 的 create / update / delete；GET 已在 member_cart 里）----
export function createCart(body) {
  return request('POST', '/admin/carts', { body });
}
export function updateCart(id, patch = {}) {
  return request('PUT', `/admin/carts/${encodeURIComponent(id)}`, { body: { ...patch, id } });
}
export function deleteCart(id) {
  return request('DELETE', `/admin/carts/${encodeURIComponent(id)}`);
}

// ---- 退货申请（ReturnApplyController 的 create / delete；PUT 审核已在 return_apply.audit）----
export function createReturnApply(body) {
  return request('POST', '/admin/return-applies', { body });
}
export function deleteReturnApply(id) {
  return request('DELETE', `/admin/return-applies/${encodeURIComponent(id)}`);
}

export const mallBase = BASE;
