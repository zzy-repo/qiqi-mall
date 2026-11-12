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

// ---------------- ④ 库存快照：npm test 不许让测试库库存净漂 ----------------
// ── 它修的是什么 ──────────────────────────────────────────────────────
// 跑一轮 `npm test`，qiqi_mall_test 的 sku.stock 会净漂（实测 1000 → 998 / 997）。
// 根因不是后端：后端删单会按 `stock_ledger` 净额回补（OrderPurgeService#settleStockLedger，正确），
// 而**个别测试为了抵掉「取消包裹 / 审核不通过」的回补，直接用 `PUT /admin/skus` 把库存改回去**——
// 这一步只改 sku.stock、不写台账。台账于是记成「+3 已补」（净额 0），实际库存却是「少 3」，
// 随后夹具删单时 purge 按净额算 = 0 件，一件都不回补 → 这 3 件就永久蒸发了。
//
// ── 为什么选「快照 + 还原 + 断言」而不是「收尾脚本」或「夹具改走 API 删单」 ──────
//   · 夹具本来就走 API 删单（cleanupOrder → DELETE /admin/orders → purge），选 C 等于现状，
//     修不到「裸改库存、台账不同步」这个真正的源头；
//   · 选 B（npm test 之后再跑一个还原脚本）只能保证「下一轮干净」，本轮结束那一刻仍是不一致的，
//     而且谁单独跑 `node test/xxx.mjs` 就绕过了它；
//   · 选 A 覆盖面最广：基座是本文件，**每个**测试进程（含单独跑某一条）都自动带上，
//     不依赖调用方记得调用，也不改任何一条既有断言。
//
// ── 口径（刻意的取舍，别改成「先还原再比对」） ──────────────────────────
//   1. 进程结束（含 process.exit / 未捕获异常 / 信号）时读一次真实库存，与本轮开始时的快照逐 SKU 比对；
//   2. 有差异 → **先如实报出差异**（哪个 SKU、快照多少、现在多少），再按快照还原，
//      这样「漂了多少」永远是可见的，而不是被还原悄悄抹掉；
//   3. 还原后再读一次：仍然不一致 → 打 ❌ 并把退出码置 1（真红）；
//      MCP_STOCK_STRICT=1 时，只要本轮出现过差异就红（给 CI 卡「谁又裸改库存」用）。
const firstLine = (e) => String(e?.message || e).split('\n')[0];

/** 读全表 sku.stock → Map<skuId, stock> */
function readSkuStock() {
  const map = new Map();
  for (const line of sql(`SELECT id, stock FROM ${DB}.sku ORDER BY id`).split('\n').filter(Boolean)) {
    const [id, stock] = line.split('\t');
    map.set(Number(id), Number(stock));
  }
  return map;
}

let stockSnapshot = null;
let stockSnapshotTried = false;

/**
 * 本轮开始前 `delivery_order` 的最大 id（水位线）。
 *
 * <p>用途：夹具清理「撞上已出库守卫」的订单时，只许删**本次测试自己造出来的**包裹行
 * （`id > 水位线` 且 `order_id = 本单`），不许按「这个订单名下所有包裹」盲删 ——
 * 将来若有真实包裹挂在这张单上，盲删就是把对账证据删掉了。
 * SQL 不可用时为 null，此时夹具放弃兜底（只报 note，不猜）。
 */
function maxDeliveryIdNow() {
  try {
    const v = sql(`SELECT IFNULL(MAX(id),0) FROM ${DB}.delivery_order`);
    return v === '' ? null : Number(v);
  } catch {
    return null;
  }
}
let deliveryWatermark = null;
let deliveryWatermarkTried = false;
function deliveryIdWatermark() {
  if (!deliveryWatermarkTried) {
    deliveryWatermarkTried = true;
    deliveryWatermark = maxDeliveryIdNow();
  }
  return deliveryWatermark;
}

/**
 * 本轮开始时的 sku.stock 快照（懒抓 + 只抓一次）。
 * SQL 不可用（没 docker / 没起 MySQL）时返回 null —— 库存一致性检查自动跳过，不让测试因为基座基建挂掉。
 */
export function skuStockSnapshot() {
  if (stockSnapshotTried) return stockSnapshot;
  stockSnapshotTried = true;
  try {
    stockSnapshot = readSkuStock();
  } catch (e) {
    console.error(`[setup] ⚠ 抓不到库存快照（直连测试库不可用），本轮跳过库存一致性检查：${firstLine(e)}`);
    stockSnapshot = null;
  }
  return stockSnapshot;
}

/** 与快照逐 SKU 比对 → [{skuId, before, after}]（只列有差异的；消失的 SKU 不算差异） */
function stockDiff(snapshot, current) {
  const diff = [];
  for (const [skuId, before] of snapshot) {
    if (!current.has(skuId)) continue; // 测试自己建的 SKU 已经删掉了，不是漂移
    const after = current.get(skuId);
    if (after !== before) diff.push({ skuId, before, after });
  }
  return diff;
}

/**
 * 收尾：比对 → 报差异 → 按快照还原 → 再比对（还不行就红）。
 * 同步实现（execFileSync），所以能挂在 process 'exit' 上 —— 那里只能跑同步代码。
 * @returns {{drift: Array<{skuId:number,before:number,after:number}>, restored: boolean, ok: boolean}}
 */
export function reconcileSkuStock() {
  const snapshot = skuStockSnapshot();
  if (!snapshot) return { drift: [], restored: false, ok: true };

  let current;
  try {
    current = readSkuStock();
  } catch (e) {
    console.error(`[setup] ⚠ 收尾时读不到库存，无法核对本轮是否漂移：${firstLine(e)}`);
    return { drift: [], restored: false, ok: true };
  }

  const drift = stockDiff(snapshot, current);
  if (drift.length === 0) {
    console.log(`[setup] ✅ 库存快照断言 PASS：${snapshot.size} 个 SKU 的 stock 与本轮开始时一致（无漂移）`);
    return { drift, restored: false, ok: true };
  }

  const lines = drift.map((d) => `sku ${d.skuId}: ${d.before} → ${d.after}（${d.after - d.before > 0 ? '+' : ''}${d.after - d.before}）`);
  console.log(`[setup] ⚠ 本轮库存有漂移（共 ${drift.length} 个 SKU），差异：${lines.join('；')}`);

  // 按快照还原（只改有差异的那几个 SKU，一次 SQL 发完）。
  // 写法是「增量」而不是「写绝对值」：stock = stock + (快照值 - 刚才读到的值) ——
  // 测试库是共用的（别的作业可能同时在造单扣库存），增量写法只补回**本轮观察到的**那部分差异，
  // 不会把并发方在我们读完之后扣掉的货一起「还原」回去。
  let restored = false;
  try {
    sql(drift
      .map((d) => `UPDATE ${DB}.sku SET stock = stock + (${Number(d.before)} - ${Number(d.after)}) WHERE id = ${Number(d.skuId)};`)
      .join(' '));
    restored = true;
  } catch (e) {
    console.error(`[setup] ❌ 库存还原失败：${firstLine(e)}`);
  }

  // 还原后再核对一遍：这才是那条断言（口径是「本轮结束时必须等于快照」）
  let after;
  try {
    after = readSkuStock();
  } catch (e) {
    console.error(`[setup] ❌ 还原后读不到库存，无法验证：${firstLine(e)}`);
    return { drift, restored, ok: false };
  }
  const left = stockDiff(snapshot, after);
  const strict = process.env.MCP_STOCK_STRICT === '1';
  const ok = left.length === 0 && !strict;
  if (left.length > 0) {
    console.error(`[setup] ❌ 库存快照断言 FAIL：还原后仍有 ${left.length} 个 SKU 与快照不一致 —— `
      + left.map((d) => `sku ${d.skuId} 应为 ${d.before}，实际 ${d.after}`).join('；'));
  } else if (strict) {
    console.error('[setup] ❌ MCP_STOCK_STRICT=1：本轮库存出现过漂移（已还原），按严格口径判红');
  } else {
    console.log(`[setup] ✅ 库存快照断言 PASS：${drift.length} 个漂移 SKU 已按快照还原，本轮结束库存 = 快照`);
  }
  return { drift, restored, ok };
}

// 立刻抓快照：此刻还没跑任何用例，这就是「本轮开始」的基准。
skuStockSnapshot();
deliveryIdWatermark();

// 挂到进程退出：每个测试进程（含单独 `node test/x.mjs`）都自动生效，不需要调用方记得做什么。
// 说明：'exit' 里只能跑同步代码，所以还原走同步 SQL；退出码可以在这里改写（实测生效）。
process.once('exit', () => {
  try {
    const r = reconcileSkuStock();
    if (!r.ok && !process.exitCode) process.exitCode = 1;
  } catch (e) {
    console.error(`[setup] ❌ 库存收尾异常：${e?.stack || e}`);
    if (!process.exitCode) process.exitCode = 1;
  }
});

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
 * 管理端 DELETE（清理自建数据用）。
 * 确认码机制已整套下线（后端不再签发、也不再校验），所以这里就是一次直连 DELETE ——
 * 并发保护由行级 version 乐观锁承担，删除整行本身幂等，不需要预演换码那一跳。
 */
async function adminDelete(path) {
  const auth = await adminAuth();
  const res = await http('DELETE', path, { headers: auth.headers });
  if (res.payload?.code !== 0) {
    throw new Error(`清理失败 DELETE ${path}: ${res.payload?.message || `HTTP ${res.status}`}`);
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
    await adminDelete(`/admin/orders/${created.id}`).catch(() => {});
    await adminRequest('DELETE', `/admin/members/${member.memberId}`).catch(() => {});
    throw new Error(`支付失败（POST /portal/orders/${created.id}/pay）：${e.message}`);
  }

  // ⑤ 回读确认真的落到 7，否则后面所有断言都是空中楼阁
  const detail = await adminRequest('GET', `/admin/orders/${created.id}`);
  if (detail?.order?.status !== 7) {
    await adminDelete(`/admin/orders/${created.id}`).catch(() => {});
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

  // ① 删订单：走管理端 DELETE（内部是 OrderPurgeService：显式逐层删 + 按台账净额回补库存）。
  try {
    await adminDelete(`/admin/orders/${Number(orderId)}`);
    deleted = true;
    notes.push(`订单 ${orderId} 已删除（purge 按台账净额回补库存）`);
  } catch (e) {
    const msg = String(e?.message || e);
    // 「已出库包裹拒删」这条守卫（OrderPurgeService#blockReason 的第 3 条）在**夹具**里要绕开一半：
    // 本文件造的包裹是**脚手架**（wms_callback 把状态推到 3/4/5 只是为了验证状态机），
    // 不是「货真的离开商家」的审计事实。所以先把这些脚手架包裹行删掉（物流明细一并删），
    // 再重试 DELETE —— 重试仍然走 purge，库存照旧按台账净额回补，不是裸 SQL 删单。
    if (/已出库|个包裹/.test(msg)) {
      const sn = Number(orderId);
      const mark = deliveryIdWatermark();
      // ⚠️ 圈定口径（别放宽）：只碰「本单 + 本轮水位线之后新建」的包裹行。
      //    绝不按「这个订单名下所有包裹」盲删 —— 真有真实包裹挂在这张单上时，那是对账证据。
      const floor = mark == null ? null : Number(mark);
      const scope = floor == null ? null : `order_id = ${sn} AND id > ${floor}`;
      if (scope == null) {
        notes.push(`订单 ${orderId} 删除被「已出库包裹」守卫拦住，且拿不到包裹水位线（SQL 不可用），未做兜底，请手工核对`);
      } else {
        try {
          // 注意：这里用「IN (子查询)」而不是多表 DELETE —— 多表 DELETE 在没选默认库的
          // `mysql -e` 会话里会回 1046「No database selected」（踩过，夹具会静默漏清一张单）。
          sql(`DELETE FROM ${DB}.delivery_logistics WHERE delivery_id IN
                (SELECT id FROM ${DB}.delivery_order WHERE ${scope})`);
          sql(`DELETE FROM ${DB}.delivery_item WHERE delivery_id IN
                (SELECT id FROM ${DB}.delivery_order WHERE ${scope})`);
          sql(`DELETE FROM ${DB}.delivery_order WHERE ${scope}`);
          await adminDelete(`/admin/orders/${Number(orderId)}`);
          deleted = true;
          notes.push(`订单 ${orderId} 已删除（先清掉本轮水位线之后、本单名下的脚手架包裹，再走 purge 回补库存）`);
        } catch (e2) {
          notes.push(`订单 ${orderId} 删除失败，已跳过（可能被别处引用）：${msg}；重试：${e2?.message || e2}`);
        }
      }
    } else {
      notes.push(`订单 ${orderId} 删除失败，已跳过（可能被别处引用）：${msg}`);
    }
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
