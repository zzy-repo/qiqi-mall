#!/usr/bin/env node
// 测试基座（test/_setup.mjs）—— 所有测试文件共用的「自建数据」层。
//
// ── 为什么有这个文件 ────────────────────────────────────────────────
// 以前 test/loop.mjs 依赖**测试库处于干净种子态**（恰好 2 笔待审核订单、0 张发货单）：
// 跑一次就消费掉一笔待审核单、生成 1 张发货单 → 第二次跑必红 6 条。
// 那不是「测试失败」，而是「测试不自治」：断言的是别人留下的状态。
// 后面还有 25 个工具要逐个加测试，这个坑会被踩几十次，所以把基座抽出来：
//
//   ① 每个测试文件自己造「待审核订单」，只对自己造的这笔做绝对断言；
//   ② 数据带唯一标记（orderSn 带时间戳、收货人带尾号），并发跑同一库互不干扰；
//   ③ 跑完自己清理，绝不调用 scripts/reset-test-db.sh（那是破坏性的，会和并行任务打架）。
//
// ── 造一笔「待审核(7)」订单用到的接口组合（4 步，都是现成接口） ──────────
//   1) POST /portal/auth/register                匿名注册一次性买家 → 拿 memberId + 会员 JWT
//      （为什么必须有买家：支付接口 /portal/orders/{id}/pay 校验「只能付自己的单」，
//        而管理端 JWT / API Key 都没有「会员主语」，所以支付必须用会员身份。）
//   2) GET  /admin/products?publishStatus=1 → GET /admin/skus?productId=X
//      挑在架商品下、且在 inventory 里有分仓库存记录的 SKU（拆单要按仓归属，没库存记录会拆不出）
//   3) POST /admin/orders { order: {..., status: 0}, items: [...] }
//      建单时把收货人/电话/地址快照写进去；金额由后端按明细快照价 × 数量重算
//   4) POST /portal/orders/{id}/pay?payType=1
//      支付。后端口径②：支付成功即自动进入「待审核」，状态 0 待付款 → 7 待审核（不是 1 已付款）。
//      最后再 GET /admin/orders/{id} 回读，确认真的落到 status=7，否则抛中文错误。
//
// ── 环境变量 ───────────────────────────────────────────────────────
//   MALL_BASE            后端地址，默认 http://127.0.0.1:18080
//   MALL_API_KEY         **必填**：管理端 API Key（请求头 X-Api-Key）。key 按库隔离 ——
//                        dev 库一把、test 库一把；测试环境（18080 / qiqi_mall_test）用：
//                          export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)
//                        造 key 见 scripts/init-ai-key.sh + mcp-server/README「认证：API Key」。
//                        （MCP_TEST_API_KEY 仍可作为同义别名。）
//                        注意：管理端一律走 API Key，**不再登录拿 JWT**；但支付接口
//                        /portal/orders/{id}/pay 校验会员归属，必须继续用会员 JWT（见下面的造单流程）。
//   MCP_TEST_DB / MCP_TEST_MYSQL_CONTAINER / MCP_TEST_MYSQL_USER / MCP_TEST_MYSQL_PASSWORD
//                        直连测试库的数行数/清理用（默认 qiqi_mall_test / qiqi-mysql / root / qiqi123456）
//
// ── 25 个工具怎么写以后加（复用方式） ─────────────────────────────────
//   import { bootstrap, connectServer, call, jsonAfter } from './_setup.mjs';
//   const ctx = await bootstrap({ autoCleanup: true });      // 现成的待审核单（含 cleanup）
//   ... 对自己的 ctx.orderId 做断言 ...                       // 断绝对状态，不再断言「库里共 N 笔」
//   finally { await ctx.cleanup?.(); }                       // 跑完删干净
//   需要多笔单就多次调 createPendingOrder()，每条都带自己的唯一单号。

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

export const SERVER_ENTRY = fileURLToPath(new URL('../src/index.mjs', import.meta.url));

// ---------------- 后端 / 库 配置 ----------------
export const MALL_BASE = (process.env.MALL_BASE || 'http://127.0.0.1:18080').replace(/\/+$/, '');
export const MALL_API_KEY = (process.env.MALL_API_KEY || process.env.MCP_TEST_API_KEY || '').trim();

export const MCP_DB_CONTAINER = process.env.MCP_TEST_MYSQL_CONTAINER || 'qiqi-mysql';
export const DB = process.env.MCP_TEST_DB || 'qiqi_mall_test';
const MYSQL_PASS = process.env.MCP_TEST_MYSQL_PASSWORD || 'qiqi123456';
const MYSQL_USER = process.env.MCP_TEST_MYSQL_USER || 'root';

// ---------------- 直连测试库（只数行数 / 查自己造的行的分仓，不写业务代码的库） ----------------
/**
 * 执行一条 SQL（-N 无表头，制表符分隔）。测试里只用来「数行数 / 核对自己造的那笔」。
 * ⚠️ 字符集必须显式 utf8mb4，否则中文会二次编码成乱码（踩过）。
 */
export function sql(query) {
  return execFileSync(
    'docker',
    [
      'exec',
      '-e',
      `MYSQL_PWD=${MYSQL_PASS}`,
      MCP_DB_CONTAINER,
      'mysql',
      '--default-character-set=utf8mb4',
      `-u${MYSQL_USER}`,
      '-N',
      '-e',
      query,
    ],
    { encoding: 'utf8' }
  ).trim();
}

/** 某张表的行数（表名交给调用方，测试里都是常量） */
export function dbCount(table) {
  return Number(sql(`SELECT COUNT(*) FROM ${DB}.\`${table}\``));
}

// ---------------- REST 小工具 ----------------
async function http(method, path, { headers = {}, body } = {}) {
  const res = await fetch(`${MALL_BASE}${path}`, {
    method,
    headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const raw = await res.text();
  let payload;
  try {
    payload = raw ? JSON.parse(raw) : null;
  } catch {
    throw new Error(`后端响应不是 JSON（HTTP ${res.status} ${method} ${path}）：${raw.slice(0, 200)}`);
  }
  return { status: res.status, payload };
}

// ---------------- ① 管理端凭证：API Key（唯一方式，不再登录拿 JWT） ----------------
let cachedAuth = null;

/**
 * 确保有一把可用的管理端 API Key，并探测一次（GET /admin/orders?size=1，只读、无副作用）。
 * 探测的作用：把「key 缺失 / key 属于别的库 / key 被禁用」在造数据之前就说清楚，
 * 而不是等到某条断言莫名其妙地红。
 * @returns {Promise<{headers: Record<string,string>, mode: 'apikey', label: string}>}
 */
export async function adminAuth() {
  if (cachedAuth) return cachedAuth;

  if (!MALL_API_KEY) {
    throw new Error(
      '缺少 MALL_API_KEY（管理端 API Key，按库隔离，dev / test 各一把）。' +
        '测试环境：export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)；' +
        '造 key：scripts/init-ai-key.sh（见 mcp-server/README「认证：API Key」）'
    );
  }

  const probe = await http('GET', '/admin/orders?page=1&size=1', { headers: { 'X-Api-Key': MALL_API_KEY } });
  if (probe.payload?.code !== 0) {
    throw new Error(
      `API Key 不可用（${probe.payload?.message || `HTTP ${probe.status}`}，key 前缀 ${MALL_API_KEY.slice(0, 8)}…）。` +
        '请确认这把 key 属于当前库（dev / test 各一把，按库隔离）；刚跑过 scripts/reset-test-db.sh 的话 ' +
        'ai_api_key 表已被 DROP，需要重新跑 scripts/init-ai-key.sh 建一把。'
    );
  }

  cachedAuth = { headers: { 'X-Api-Key': MALL_API_KEY }, mode: 'apikey', label: `API Key ${MALL_API_KEY.slice(0, 8)}…` };
  return cachedAuth;
}

/** 管理端请求：自动带 API Key，code!==0 抛中文错误（不静默） */
export async function adminRequest(method, path, body) {
  const auth = await adminAuth();
  const { status, payload } = await http(method, path, { headers: auth.headers, body });
  if (payload?.code !== 0) {
    throw new Error(`${method} ${path} 失败：${payload?.message || `HTTP ${status}`}（管理端凭证 ${auth.label}）`);
  }
  return payload?.data;
}

/**
 * 管理端 DELETE（高危写）：新后端要求 confirmCode，老后端不要求 → 两种都兼容。
 *   ① 先按直连方式 DELETE；成功就完事（老后端 / 尚未加码的接口）
 *   ② 返回「缺少确认码」→ `?dryRun=true` 预演拿码 → 带 body { confirmCode } 再删一次
 * 这样清理逻辑不会因为后端灰度升级而留垃圾数据。
 */
async function adminDeleteWithConfirm(path) {
  const auth = await adminAuth();
  const first = await http('DELETE', path, { headers: auth.headers });
  if (first.payload?.code === 0) return;

  const firstMsg = first.payload?.message || `HTTP ${first.status}`;
  if (!/确认码/.test(firstMsg)) throw new Error(`DELETE ${path} 失败：${firstMsg}`);

  const pre = await http('DELETE', `${path}${path.includes('?') ? '&' : '?'}dryRun=true`, { headers: auth.headers });
  const code = pre.payload?.data?.confirmCode;
  if (pre.payload?.code !== 0 || !code) {
    throw new Error(`DELETE ${path} 预演失败：${pre.payload?.message || `HTTP ${pre.status}`}`);
  }
  const done = await http('DELETE', path, { headers: auth.headers, body: { confirmCode: code } });
  if (done.payload?.code !== 0) {
    throw new Error(`DELETE ${path} 失败：${done.payload?.message || `HTTP ${done.status}`}`);
  }
}

/** 买家端请求（要会员 JWT，注册/支付用） */
async function portalRequest(method, path, { body, token } = {}) {
  const { status, payload } = await http(method, path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body,
  });
  if (payload?.code !== 0) {
    throw new Error(`买家端 ${method} ${path} 失败：${payload?.message || `HTTP ${status}`}`);
  }
  return payload?.data;
}

// ---------------- 唯一标记 / 自然名 ----------------
const pad = (n, w = 2) => String(n).padStart(w, '0');
const randN = (n) => String(Math.floor(Math.random() * 10 ** n)).padStart(n, '0');

/** 形如 20261112131514 的时间戳（秒级），用于单号/用户名，保证并发跑不撞 */
function stamp() {
  const d = new Date();
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

/** fixtures 风格的自然收货人名（+ 尾号），报告里一眼能认出是测试造的数据 */
const FIXTURE_NAMES = ['林小七', '周小满', '陈一诺', '许知远', '苏念安', '何清和', '沈知微', '顾南舟'];
const pickName = () => FIXTURE_NAMES[Math.floor(Math.random() * FIXTURE_NAMES.length)];

/** 只允许字母数字下划线（拼进 SQL 的单号先过一遍，避免手滑注入） */
const safeToken = (s) => String(s).replace(/[^A-Za-z0-9_-]/g, '');

// ---------------- 挑 SKU（要真的拆得出包裹） ----------------
/**
 * 查一批 SKU 的分仓库存 → Map<skuId, [{warehouseId, stock}]>。
 * 读不到（docker/SQL 不可用）返回 null，调用方据此退化为「不保证多仓」。
 */
function warehousesBySku(skuIds) {
  if (!skuIds.length) return new Map();
  try {
    const rows = sql(
      `SELECT sku_id, warehouse_id, stock FROM ${DB}.inventory WHERE sku_id IN (${skuIds.map(Number).join(',')})`
    );
    const map = new Map();
    for (const line of rows.split('\n').filter(Boolean)) {
      const [skuId, warehouseId, stock] = line.split('\t');
      if (!map.has(Number(skuId))) map.set(Number(skuId), []);
      map.get(Number(skuId)).push({ warehouseId: Number(warehouseId), stock: Number(stock) });
    }
    return map;
  } catch {
    return null; // 例如 docker 不可用：不影响建单，只是拿不到「预期包裹数」
  }
}

/**
 * 挑在架商品下的 N 个 SKU（尽量落在**不同仓**，这样拆单能拆出 N 个包裹，断言更有意义）。
 * @returns {Promise<{items: Array<{productId:number,skuId:number,productName:string,price:number,stock:number}>, expectedParcels: number|null}>}
 */
async function pickSkusForOrder(count) {
  const products = await adminRequest('GET', '/admin/products?publishStatus=1');
  if (!Array.isArray(products) || products.length === 0) {
    throw new Error('测试库没有在架商品（product.publish_status=1），无法造待审核订单');
  }

  // 候选：前若干个在架商品下的 SKU（不写死商品 id，也不假设只有 28 个商品）
  const candidates = [];
  for (const product of products.slice(0, 10)) {
    const skus = await adminRequest('GET', `/admin/skus?productId=${encodeURIComponent(product.id)}`);
    for (const sku of Array.isArray(skus) ? skus : []) {
      candidates.push({
        productId: product.id,
        skuId: sku.id,
        productName: `${product.name}${sku.specText ? ` ${sku.specText}` : ''}`,
        price: Number(sku.price) || 0,
        stock: Number(sku.stock) || 0,
      });
    }
    if (candidates.length >= count * 6) break;
  }
  if (!candidates.length) throw new Error('在架商品下没有任何 SKU，无法造待审核订单');

  const warehouses = warehousesBySku(candidates.map((c) => c.skuId));
  if (warehouses === null) {
    // SQL 不可用：退化为「取前 N 个 SKU」，expectedParcels 留 null（测试只断言「包裹数 === 方案数」）
    return { items: candidates.slice(0, count), expectedParcels: null };
  }

  // 后端拆单口径：SKU 的发货仓 = 库存最多的那个仓（SplitOrderService.resolveWarehouse）
  const primaryWarehouse = (skuId) => {
    const rows = warehouses.get(Number(skuId)) || [];
    if (!rows.length) return null;
    return rows.reduce((a, b) => (b.stock > a.stock ? b : a)).warehouseId;
  };

  const usable = candidates.filter((c) => primaryWarehouse(c.skuId) !== null);
  if (!usable.length) {
    throw new Error('所有在架 SKU 都没有分仓库存记录（inventory 表为空），拆单无法确定发货仓，造不出能审核的单');
  }

  const picked = [];
  const usedWarehouses = new Set();
  for (const c of usable) { // 第一轮：只挑「仓还没被占」的，保证拆出多个包裹
    const wh = primaryWarehouse(c.skuId);
    if (usedWarehouses.has(wh)) continue;
    usedWarehouses.add(wh);
    picked.push(c);
    if (picked.length >= count) break;
  }
  for (const c of usable) { // 第二轮：仓不够就凑数（可能合并成一个包裹）
    if (picked.length >= count) break;
    if (picked.some((p) => p.skuId === c.skuId)) continue;
    picked.push(c);
  }

  const expectedParcels = new Set(picked.map((c) => primaryWarehouse(c.skuId))).size;
  return { items: picked, expectedParcels };
}

// ---------------- ② 造一笔待审核订单 ----------------
/**
 * 造一笔「待审核(status=7)」订单（自给自足的核心）。
 *
 * 步骤（见文件头注释，4 步接口组合）：
 *   匿名注册一次性买家 → 挑在架商品+有分仓库存的 SKU → POST /admin/orders（status=0）→
 *   POST /portal/orders/{id}/pay（0→7）→ 回读确认 status=7
 *
 * @param {{qty?: number, skuCount?: number}} [opts]
 *   qty       每个明细的购买数量，默认 2
 *   skuCount  造几个明细（尽量落在不同仓 → 拆单成多个包裹），默认 1
 * @returns {Promise<{orderId:number, orderSn:string, productId:number, skuId:number, skuIds:number[],
 *                    qty:number, receiverName:string, memberId:number, memberToken:string,
 *                    expectedParcels:number|null, marker:string}>}
 */
export async function createPendingOrder({ qty = 2, skuCount = 1 } = {}) {
  if (!Number.isInteger(qty) || qty < 1) throw new Error(`qty 必须是正整数（收到 ${qty}）`);
  if (!Number.isInteger(skuCount) || skuCount < 1) throw new Error(`skuCount 必须是正整数（收到 ${skuCount}）`);

  const st = stamp();
  const tail = randN(2);
  const marker = `${st}${tail}`;                 // 本次造数据的唯一标记（并发跑同一库也不撞）
  const orderSn = `QQT${marker}`;                // 单号带时间戳：唯一、可回溯
  const receiverName = `${pickName()}-${tail}`;  // fixtures 风格自然名 + 尾号
  const receiverPhone = `13${st.slice(-8)}`;

  // ① 一次性买家（匿名注册即自动登录，不需要验证码/短信）
  const memberUsername = `mcp_probe_${marker}`;
  const memberPassword = `MCP@${st}a1`;
  const member = await portalRequest('POST', '/portal/auth/register', {
    body: { username: memberUsername, password: memberPassword, nickname: `MCP探针${tail}`, phone: `139${st.slice(-8)}` },
  });
  if (!member?.memberId || !member?.accessToken) {
    throw new Error(`注册一次性买家失败（/portal/auth/register 返回 ${JSON.stringify(member)}）`);
  }

  // ② 在架商品 + 有分仓库存的 SKU
  const { items: picks, expectedParcels } = await pickSkusForOrder(skuCount);

  // ③ 建单：status=0 待付款，收货三件套写成快照
  let created;
  try {
    created = await adminRequest('POST', '/admin/orders', {
      order: {
        memberId: member.memberId,
        orderSn,
        status: 0,
        payType: 0,
        receiverName,
        receiverPhone,
        receiverAddress: `MCP 自动化测试地址 ${orderSn}`,
        splitStatus: 0,
      },
      items: picks.map((p) => ({
        productId: p.productId,
        productSkuId: p.skuId,
        productName: p.productName,
        productPrice: p.price,
        productQuantity: qty,
      })),
    });
  } catch (e) {
    // 建单失败就把刚注册的买家收掉，别留垃圾
    await adminRequest('DELETE', `/admin/members/${member.memberId}`).catch(() => {});
    throw new Error(`建单失败（POST /admin/orders）：${e.message}`);
  }
  if (!created?.id) throw new Error(`建单响应里没有订单 id：${JSON.stringify(created)}`);

  // ④ 支付：0 待付款 → 7 待审核（后端口径②：支付成功即进入待审核）。
  //    这一步必须用**买家**身份，管理端 JWT/API Key 都没有会员主语。
  try {
    await portalRequest('POST', `/portal/orders/${created.id}/pay?payType=1`, { token: member.accessToken });
  } catch (e) {
    await adminDeleteWithConfirm(`/admin/orders/${created.id}`).catch(() => {});
    await adminRequest('DELETE', `/admin/members/${member.memberId}`).catch(() => {});
    throw new Error(`支付失败（POST /portal/orders/${created.id}/pay）：${e.message}`);
  }

  // ⑤ 回读确认真的落到 7，否则后面所有断言都是空中楼阁
  const detail = await adminRequest('GET', `/admin/orders/${created.id}`);
  if (detail?.order?.status !== 7) {
    await adminDeleteWithConfirm(`/admin/orders/${created.id}`).catch(() => {});
    await adminRequest('DELETE', `/admin/members/${member.memberId}`).catch(() => {});
    throw new Error(
      `造单后订单 ${created.id} 的状态是 ${detail?.order?.status}（期望 7 待审核）——` +
        `后端支付/状态流转口径可能变了，请先核对 /portal/orders/{id}/pay`
    );
  }

  return {
    orderId: created.id,
    orderSn: created.orderSn || orderSn,
    productId: picks[0].productId,
    skuId: picks[0].skuId,
    skuIds: picks.map((p) => p.skuId),
    qty,
    receiverName,
    memberId: member.memberId,
    memberToken: member.accessToken,
    expectedParcels, // 预期包裹数（= 不同发货仓数）；SQL 不可用时为 null
    marker,
  };
}

// ---------------- ③ 清理自己造的数据 ----------------
/**
 * 删掉自己造的订单（连带明细/发货单/支付流水），以及那个一次性买家。
 * 删不掉（例如已被别处引用）就记一条 note 跳过，不抛错 —— 测试结论不该被清理失败带偏。
 * @param {number} orderId
 * @param {{memberId?: number, orderSn?: string}} [refs]
 * @returns {Promise<{deleted: boolean, notes: string[]}>}
 */
export async function cleanupOrder(orderId, { memberId, orderSn } = {}) {
  const notes = [];
  let deleted = false;

  // ① 删订单：DB 侧 order_item / delivery_order / delivery_item 都是 ON DELETE CASCADE，
  //    所以一行 DELETE 就把这次造的全部业务数据带走
  try {
    await adminDeleteWithConfirm(`/admin/orders/${Number(orderId)}`);
    deleted = true;
    notes.push(`订单 ${orderId} 已删除（级联清掉明细/发货单）`);
  } catch (e) {
    notes.push(`订单 ${orderId} 删除失败，已跳过（可能被别处引用）：${e.message}`);
  }

  // ② 支付流水没有外键，按单号删（只删自己造的这一个单号）
  if (orderSn) {
    const sn = safeToken(orderSn);
    try {
      const existed = Number(sql(`SELECT COUNT(*) FROM ${DB}.payment_record WHERE order_sn = '${sn}'`));
      if (existed > 0) {
        sql(`DELETE FROM ${DB}.payment_record WHERE order_sn = '${sn}'`);
        notes.push(`支付流水 ${existed} 条已删除（order_sn=${orderSn}）`);
      }
    } catch (e) {
      notes.push(`支付流水清理跳过（SQL 不可用）：${String(e.message).split('\n')[0]}`);
    }
  }

  // ③ 一次性买家（订单已删，FK fk_order_member 不再拦着）
  if (memberId) {
    try {
      await adminRequest('DELETE', `/admin/members/${Number(memberId)}`);
      notes.push(`一次性买家 ${memberId} 已删除`);
    } catch (e) {
      notes.push(`一次性买家 ${memberId} 删除失败，已跳过：${e.message}`);
    }
  }

  return { deleted, notes };
}

// ---------------- 清理看门狗：被打断也要尽量收掉自己造的数据 ----------------
// 背景（实测踩过）：Ctrl-C、或者 `node test/x.mjs | head` 让 stdout 断掉（EPIPE）都会把进程掐死，
// 上面的 finally 来不及跑，数据就留在库里了。所以再兜一层：登记所有 autoCleanup 造的订单，
// 进程收到信号 / 未捕获异常时尽力删一遍再退出（删不掉就打印出来，便于手工核对）。
const pendingCleanups = new Set();
let safetyNetInstalled = false;

async function runPendingCleanups(reason) {
  if (pendingCleanups.size === 0) return;
  console.error(`[setup] ${reason}：尝试清理本次留下的 ${pendingCleanups.size} 组自建数据…`);
  for (const entry of [...pendingCleanups]) {
    try {
      const r = await entry.cleanup();
      pendingCleanups.delete(entry);
      console.error(`[setup] 已清理订单 ${entry.orderId}：${r.notes.join('；')}`);
    } catch (e) {
      console.error(`[setup] 订单 ${entry.orderId} 清理失败，请手工核对：${e?.message || e}`);
    }
  }
}

function installCleanupSafetyNet() {
  if (safetyNetInstalled) return;
  safetyNetInstalled = true;

  // stdout/stderr 被下游关掉（典型：`node test/x.mjs | head`）时，Node 会抛 EPIPE 直接把进程掐死，
  // 连 finally 都来不及跑。挂一个空 listener 把 write 失败降级为静默（输出丢了无所谓，数据必须收干净）。
  for (const stream of [process.stdout, process.stderr]) {
    if (stream.listenerCount('error') === 0) stream.on('error', () => {});
  }

  const bail = (reason, code) => () => {
    void runPendingCleanups(reason).finally(() => process.exit(code));
  };
  for (const [sig, code] of [
    ['SIGINT', 130],
    ['SIGTERM', 143],
    ['SIGHUP', 129],
  ]) {
    process.once(sig, bail(`收到 ${sig}`, code));
  }
  for (const evt of ['uncaughtException', 'unhandledRejection']) {
    const code = 1;
    process.once(evt, (err) => {
      console.error(`❌ ${evt}：`, err?.stack || err);
      void runPendingCleanups(evt).finally(() => process.exit(code));
    });
  }
}

// ---------------- ④ 一次性准备：本次测试要用的全部上下文 ----------------
/**
 * bootstrap = adminAuth + createPendingOrder（+ 可选自动清理）。
 * @param {{qty?: number, skuCount?: number, autoCleanup?: boolean}} [opts]
 * @returns {Promise<object>} createPendingOrder 的返回值，额外带：
 *   authMode  'apikey' | 'jwt'
 *   cleanup() 仅 autoCleanup=true 时存在，删掉本次造的数据并返回 {deleted, notes}
 */
export async function bootstrap({ qty = 2, skuCount = 1, autoCleanup = false } = {}) {
  const auth = await adminAuth();
  const created = await createPendingOrder({ qty, skuCount });
  const ctx = { ...created, authMode: auth.mode };
  if (autoCleanup) {
    installCleanupSafetyNet();
    let done = null;
    const entry = {
      orderId: ctx.orderId,
      cleanup: async () => {
        if (!done) done = await cleanupOrder(ctx.orderId, { memberId: ctx.memberId, orderSn: ctx.orderSn });
        return done;
      },
    };
    pendingCleanups.add(entry);
    ctx.cleanup = async () => {
      const r = await entry.cleanup();
      pendingCleanups.delete(entry); // 正常清理完就从看门狗名单里摘掉
      return r;
    };
  }
  return ctx;
}

// ---------------- MCP 客户端（stdio 真启动 src/index.mjs） ----------------
function cleanEnv(extra) {
  const env = {};
  for (const [k, v] of Object.entries({ ...process.env, ...extra })) {
    if (typeof v === 'string') env[k] = v;
  }
  return env;
}

/**
 * 连一个 MCP server 实例（每个实例 = 一个 stdio 子进程）。
 * @param {Record<string,string>} [extraEnv] 额外环境变量（如 MCP_ENABLE_WRITE）
 * @param {string} [label] 客户端名字后缀，日志里好区分
 */
export async function connectServer(extraEnv = {}, label = 'server') {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [SERVER_ENTRY],
    env: cleanEnv({ MALL_BASE, MALL_API_KEY, MCP_ENABLE_WRITE: '1', ...extraEnv }),
    stderr: 'ignore', // server 的 console.error 不进测试输出
  });
  const client = new Client({ name: `mcp-test-${label}`, version: '0.1.0' });
  await client.connect(transport);
  return client;
}

/** 调用工具；工具返回 isError 或协议层抛错都算「错误」，统一 throw 带文本 */
export async function call(client, name, args) {
  const res = await client.callTool({ name, arguments: args });
  const textOut = (res.content || [])
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('\n');
  if (res.isError) throw new Error(textOut);
  return { text: textOut, res };
}

/** 期望失败：返回 {threw, text}，不抛 */
export async function expectError(client, name, args) {
  try {
    const r = await call(client, name, args);
    return { threw: false, text: r.text };
  } catch (e) {
    return { threw: true, text: e?.message || String(e) };
  }
}

/** 从工具文本里取 "xxx JSON:\n<json>" 段（统一格式：摘要在前、JSON 在后） */
export function jsonAfter(text, marker = 'JSON:') {
  const idx = String(text ?? '').indexOf(marker);
  if (idx < 0) throw new Error(`响应里找不到「${marker}」段`);
  return JSON.parse(String(text).slice(idx + marker.length).trim());
}
