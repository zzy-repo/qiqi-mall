#!/usr/bin/env node
// 订单域（order）第 3 批测试：新增 3 个只读工具（list_orders / get_order_detail / count_deliveries）。
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 3 个新工具都在 tools/list 里（且 list_deliveries / split_preview 这两个老工具没被改动）
//   ② list_orders 分页：不带参 → ≤10 条；size=2 → ≤2 条；size=999 → 夹到 50
//   ③ list_orders {status:7} → 全部 status===7 且状态名显示「待审核」
//   ④ list_orders {orderSn:"QQ2026"} → 模糊匹配有结果；不存在的单号 → 空结果文案（不报错）
//   ⑤ get_order_detail {orderId:1} → 含明细 + 退货申请；不存在的 id → 中文错误
//   ⑥ count_deliveries {orderIds:[1,2,3]} → 每个单都有包裹数，且与库里（未取消）发货单数一致
//   ⑦ 只读校验：调用前后 order / order_item / delivery_order 行数不变
//   ⑧ MCP_ENABLE_WRITE=0 → 只读工具仍然齐全，写工具 execute_audit_pass 消失
//
// 前置：后端 18080 可达 + 测试库 qiqi_mall_test 有种子数据（14 笔订单 / 14 条明细）。
// 与 loop.mjs 的关系：本脚本可在 loop.mjs 之后跑（那时订单 3 已被审核成 8），断言全部不依赖「订单几是几」。

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const SERVER_ENTRY = fileURLToPath(new URL('../src/index.mjs', import.meta.url));

const MALL_BASE = process.env.MALL_BASE || 'http://127.0.0.1:18080';
const MALL_USER = process.env.MALL_USER || 'ai_operator';
const MALL_PASS = process.env.MALL_PASS || 'Ai@2026#ops';

const MYSQL_CONTAINER = process.env.MCP_TEST_MYSQL_CONTAINER || 'qiqi-mysql';
const DB = process.env.MCP_TEST_DB || 'qiqi_mall_test';
const MYSQL_PASS = process.env.MCP_TEST_MYSQL_PASSWORD || 'qiqi123456';

/** 本批新增的 3 个 */
const NEW_TOOLS = ['list_orders', 'get_order_detail', 'count_deliveries'];
/** 本批不动的订单域老工具（只验证仍在，不验证细节） */
const KEPT_TOOLS = ['list_pending_orders', 'preview_split', 'list_deliveries'];
/** write=0 时应当仍在的全部只读工具：订单域 6 + 商品域 6 */
const READONLY_TOOLS = [...KEPT_TOOLS, ...NEW_TOOLS, 'search_products', 'get_product_detail', 'list_categories', 'list_product_types', 'list_attributes', 'list_skus'];

// ---------------- 结果记录 ----------------
let passed = 0;
let failed = 0;
const results = [];

function ok(step, detail) {
  passed++;
  results.push({ step, ok: true, detail });
  console.log(`✅ ${step}${detail ? ` — ${detail}` : ''}`);
}
function ko(step, detail) {
  failed++;
  results.push({ step, ok: false, detail });
  console.log(`❌ ${step}${detail ? ` — ${detail}` : ''}`);
}
function assert(cond, step, detail) {
  cond ? ok(step, detail) : ko(step, detail);
  return !!cond;
}

// ---------------- 直连测试库（只为数行数，只读） ----------------
function sql(query) {
  return execFileSync(
    'docker',
    [
      'exec',
      '-e',
      `MYSQL_PWD=${MYSQL_PASS}`,
      MYSQL_CONTAINER,
      'mysql',
      '--default-character-set=utf8mb4',
      `-u${process.env.MCP_TEST_MYSQL_USER || 'root'}`,
      '-N',
      '-e',
      query,
    ],
    { encoding: 'utf8' }
  ).trim();
}
const count = (table) => Number(sql(`SELECT COUNT(*) FROM ${DB}.\`${table}\``));
const snapshot = () => ({ order: count('order'), order_item: count('order_item'), delivery_order: count('delivery_order') });
/** 库里某订单未取消的发货单数（与后端 counts 接口同口径：status != 9） */
const dbDeliveryCount = (orderId) =>
  Number(sql(`SELECT COUNT(*) FROM ${DB}.delivery_order WHERE order_id = ${Number(orderId)} AND status <> 9`));

// ---------------- MCP 客户端 ----------------
function cleanEnv(extra) {
  const env = {};
  for (const [k, v] of Object.entries({ ...process.env, ...extra })) {
    if (typeof v === 'string') env[k] = v;
  }
  return env;
}

async function connectServer(extraEnv = {}, label = 'server') {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [SERVER_ENTRY],
    env: cleanEnv({ MALL_BASE, MALL_USER, MALL_PASS, MCP_ENABLE_WRITE: '1', ...extraEnv }),
    stderr: 'ignore',
  });
  const client = new Client({ name: `order-read-test-${label}`, version: '0.1.0' });
  await client.connect(transport);
  return client;
}

async function call(client, name, args) {
  const res = await client.callTool({ name, arguments: args });
  const textOut = (res.content || [])
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('\n');
  if (res.isError) throw new Error(textOut);
  return { text: textOut, res };
}

/** 从工具文本里取 "xxx JSON:\n<json>" 段（统一格式：摘要在前、JSON 在后） */
function jsonAfter(text, marker = 'JSON:') {
  const idx = text.indexOf(marker);
  if (idx < 0) throw new Error(`响应里找不到「${marker}」段`);
  return JSON.parse(text.slice(idx + marker.length).trim());
}

const samples = [];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 订单域只读工具测试 | 目标：${MALL_BASE} | 账号：${MALL_USER} | 库：${DB}\n`);

  const client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'rw');

  // ① tools/list 含 3 个新工具（老工具也还在 = 没被本批改坏）
  try {
    const { tools } = await client.listTools();
    const listed = tools.map((t) => t.name);
    for (const name of NEW_TOOLS) {
      assert(listed.includes(name), `① tools/list 含新工具 ${name}`, listed.includes(name) ? `共 ${listed.length} 个工具` : `实际：${listed.join(', ')}`);
    }
    for (const name of KEPT_TOOLS) {
      assert(listed.includes(name), `① tools/list 仍含老工具 ${name}（本批未动）`, `共 ${listed.length} 个工具`);
    }
  } catch (e) {
    for (const name of NEW_TOOLS) ko(`① tools/list 含新工具 ${name}`, e.message);
  }

  // ①b 工具元信息：中文 title + 中文 description（模型靠这个选工具）
  try {
    const { tools } = await client.listTools();
    const byName = Object.fromEntries(tools.map((t) => [t.name, t]));
    for (const name of NEW_TOOLS) {
      const t = byName[name] || {};
      const zhTitle = /[\u4e00-\u9fa5]/.test(t.title || '');
      const zhDesc = /[\u4e00-\u9fa5]/.test(t.description || '') && (t.description || '').length > 20;
      assert(zhTitle && zhDesc, `①b ${name} 有中文 title 且 description 说明用法`, `${t.title || '(无 title)'}`);
    }
  } catch (e) {
    ko('①b 3 个新工具的 title/description 中文化', e.message);
  }

  const before = snapshot();

  // ② list_orders 默认分页：不带参 → ≤10 条（size 默认 10）
  try {
    const { text } = await call(client, 'list_orders', {});
    const j = jsonAfter(text);
    assert(
      Array.isArray(j.records) && j.records.length > 0 && j.records.length <= 10 && j.size === 10 && j.page === 1,
      '②a list_orders 不带参 → 默认每页 10 条（records ≤ 10）',
      `records=${j.records.length}, total=${j.total}, size=${j.size}, page=${j.page}, pages=${j.pages}`
    );
    assert(
      j.records.every((o) => typeof o.statusText === 'string' && o.statusText.length > 0 && !o.statusText.startsWith('未知')),
      '②a2 list_orders 每条都带中文状态名 statusText',
      j.records.map((o) => `${o.status}→${o.statusText}`).join(' ')
    );
    samples.push({ tool: 'list_orders', args: {}, line: text.split('\n').slice(0, 2).join(' ') });
  } catch (e) {
    ko('②a list_orders 不带参 → 默认每页 10 条（records ≤ 10）', e.message);
  }

  // ② list_orders size=2 → ≤2 条
  try {
    const j = jsonAfter((await call(client, 'list_orders', { size: 2 })).text);
    assert(j.records.length <= 2 && j.records.length > 0 && j.size === 2, '②b list_orders size=2 → 返回 ≤2 条', `records=${j.records.length}, size=${j.size}`);
  } catch (e) {
    ko('②b list_orders size=2 → 返回 ≤2 条', e.message);
  }

  // ② list_orders size=999 → 夹到 50（不报错）
  try {
    const j = jsonAfter((await call(client, 'list_orders', { size: 999 })).text);
    assert(j.size === 50 && j.records.length <= 50, '②c list_orders size=999 → 夹到 50 且不报错', `size=${j.size}, records=${j.records.length}, total=${j.total}`);
  } catch (e) {
    ko('②c list_orders size=999 → 夹到 50 且不报错', e.message);
  }

  // ② 翻页不重复
  try {
    const p1 = jsonAfter((await call(client, 'list_orders', { size: 2, page: 1 })).text);
    const p2 = jsonAfter((await call(client, 'list_orders', { size: 2, page: 2 })).text);
    const ids1 = p1.records.map((r) => r.id).join(',');
    const ids2 = p2.records.map((r) => r.id).join(',');
    assert(!!ids1 && !!ids2 && ids1 !== ids2 && p2.page === 2, '②d page=1 与 page=2 结果不重复（后端真分页，没被二次切片）', `p1=[${ids1}] p2=[${ids2}]`);
  } catch (e) {
    ko('②d page=1 与 page=2 结果不重复（后端真分页，没被二次切片）', e.message);
  }

  // ③ status=7 → 全部待审核
  try {
    const { text } = await call(client, 'list_orders', { status: 7, size: 50 });
    const j = jsonAfter(text);
    assert(
      j.total > 0 && j.records.length > 0 && j.records.every((o) => o.status === 7 && o.statusText === '待审核'),
      '③ list_orders {status:7} → 全部 status===7 且状态名显示「待审核」',
      `${j.records.length} 条：${j.records.map((o) => `id=${o.id}/${o.statusText}`).join(', ')}`
    );
    assert(text.includes('待审核'), '③b 摘要文本里状态写的是中文「待审核」而不是裸数字', text.split('\n')[0].slice(0, 90));
    samples.push({ tool: 'list_orders', args: { status: 7 }, line: text.split('\n').slice(0, 2).join(' ') });
  } catch (e) {
    ko('③ list_orders {status:7} → 全部 status===7 且状态名显示「待审核」', e.message);
  }

  // ④ orderSn 模糊匹配有结果
  try {
    const { text } = await call(client, 'list_orders', { orderSn: 'QQ2026', size: 5 });
    const j = jsonAfter(text);
    assert(
      j.total > 0 && j.records.length > 0 && j.records.every((o) => String(o.orderSn).includes('QQ2026')),
      '④a list_orders {orderSn:"QQ2026"} → 模糊匹配有结果',
      `total=${j.total}，样例单号=${j.records.map((o) => o.orderSn).join(',')}`
    );
    samples.push({ tool: 'list_orders', args: { orderSn: 'QQ2026' }, line: text.split('\n').slice(0, 2).join(' ') });
  } catch (e) {
    ko('④a list_orders {orderSn:"QQ2026"} → 模糊匹配有结果', e.message);
  }

  // ④ 不存在的单号 → 空结果文案（不报错）
  try {
    const { text } = await call(client, 'list_orders', { orderSn: 'QQ999999999999' });
    const j = jsonAfter(text);
    assert(
      text.includes('没有符合条件的订单') && j.total === 0 && j.records.length === 0,
      '④b 不存在的单号 → 返回「没有符合条件的订单」且不抛错',
      text.split('\n')[1]?.slice(0, 60)
    );
  } catch (e) {
    ko('④b 不存在的单号 → 返回「没有符合条件的订单」且不抛错', e.message);
  }

  // ⑤ 订单详情：含明细 + 退货申请
  try {
    const { text } = await call(client, 'get_order_detail', { orderId: 1 });
    const j = jsonAfter(text);
    assert(
      !!j.order?.id && Array.isArray(j.items) && Array.isArray(j.returnApplies),
      '⑤a get_order_detail {orderId:1} → 含 order + items + returnApplies',
      `items=${j.items?.length}, returnApplies=${j.returnApplies?.length}, statusText=${j.order?.statusText}`
    );
    assert(
      j.items.length > 0 && j.items.every((it) => it.productName && it.productQuantity > 0),
      '⑤b 明细非空且带商品名快照/数量',
      j.items.map((it) => `${it.productName?.slice(0, 12)}×${it.productQuantity}`).join(' | ')
    );
    assert(
      typeof j.order?.statusText === 'string' && !j.order.statusText.startsWith('未知') && text.includes('退货申请'),
      '⑤c 详情摘要带中文状态名与「退货申请」段',
      `${j.order?.statusText} / returnApplyCount=${j.returnApplyCount}`
    );
    samples.push({ tool: 'get_order_detail', args: { orderId: 1 }, line: text.split('\n').slice(0, 2).join(' ') });
  } catch (e) {
    ko('⑤a get_order_detail {orderId:1} → 含 order + items + returnApplies', e.message);
  }

  // ⑤ 不存在的 id → 中文错误（后端对这个不报错，工具层必须自己判空）
  try {
    const res = await client.callTool({ name: 'get_order_detail', arguments: { orderId: 99999 } });
    const t = (res.content || []).map((c) => c.text).join('\n');
    assert(res.isError === true && t.includes('订单不存在'), '⑤d 不存在的订单 id → isError 且给出中文「订单不存在」', t.split('\n')[0].slice(0, 80));
  } catch (e) {
    ko('⑤d 不存在的订单 id → isError 且给出中文「订单不存在」', e.message);
  }

  // ⑥ 批量包裹数
  try {
    const ids = [1, 2, 3];
    const { text } = await call(client, 'count_deliveries', { orderIds: ids });
    const j = jsonAfter(text);
    const got = Object.fromEntries((j.records || []).map((r) => [r.orderId, r.deliveryCount]));
    const want = Object.fromEntries(ids.map((id) => [id, dbDeliveryCount(id)]));
    const allPresent = ids.every((id) => Number.isInteger(got[id]));
    const matchDb = ids.every((id) => got[id] === want[id]);
    assert(
      allPresent && matchDb,
      '⑥ count_deliveries {orderIds:[1,2,3]} → 每个单都有包裹数，且与库里未取消发货单数一致',
      `工具=${JSON.stringify(got)} 库=${JSON.stringify(want)}`
    );
    samples.push({ tool: 'count_deliveries', args: { orderIds: ids }, line: text.split('\n').slice(0, 2).join(' ') });
  } catch (e) {
    ko('⑥ count_deliveries {orderIds:[1,2,3]} → 每个单都有包裹数，且与库里未取消发货单数一致', e.message);
  }

  // ⑥b 去重 + 不存在的订单 id → 记 0（而不是整单报错）
  try {
    const j = jsonAfter((await call(client, 'count_deliveries', { orderIds: [1, 1, 99999] })).text);
    const ids = (j.records || []).map((r) => r.orderId);
    const zero = (j.records || []).find((r) => r.orderId === 99999);
    assert(ids.length === 2 && ids.filter((i) => i === 1).length === 1 && zero?.deliveryCount === 0, '⑥b 重复 id 去重、不存在的订单 id 记 0（不报错）', `records=${JSON.stringify(j.records)}`);
  } catch (e) {
    ko('⑥b 重复 id 去重、不存在的订单 id 记 0（不报错）', e.message);
  }

  // ⑦ 只读校验：行数不变
  const after = snapshot();
  for (const table of Object.keys(before)) {
    assert(before[table] === after[table], `⑦ 只读校验：${table} 行数不变`, `before=${before[table]}, after=${after[table]}`);
  }

  await client.close();

  // ⑧ 写开关关闭：只读工具齐全、写工具消失
  try {
    const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
    const names = (await ro.listTools()).tools.map((t) => t.name);
    const missing = READONLY_TOOLS.filter((n) => !names.includes(n));
    assert(
      missing.length === 0 && !names.includes('execute_audit_pass'),
      '⑧ MCP_ENABLE_WRITE=0 → 只读工具仍然齐全、写工具 execute_audit_pass 消失',
      missing.length ? `缺：${missing.join(', ')}` : `${names.length} 个：${names.sort().join(', ')}`
    );
    await ro.close();
  } catch (e) {
    ko('⑧ MCP_ENABLE_WRITE=0 → 只读工具仍然齐全、写工具 execute_audit_pass 消失', e.message);
  }

  // ---------------- 总结 ----------------
  console.log('\n调用样例（各工具首行摘要）：');
  for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${s.line.replace(/\s+/g, ' ').trim().slice(0, 160)}`);

  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 订单域 3 个新只读工具可用；分页夹取一致；状态码中文化；空结果文案明确；无写库行为。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
