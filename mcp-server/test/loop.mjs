#!/usr/bin/env node
// 闭环测试：用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言 12 步。
// 前置：后端 18080 可达 + 测试库有 2 笔待审核订单（id=1、id=3）。
// 约定：用 id=3 做测试，id=1 留给录屏。

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const SERVER_ENTRY = fileURLToPath(new URL('../src/index.mjs', import.meta.url));

const MALL_BASE = process.env.MALL_BASE || 'http://127.0.0.1:18080';
const MALL_USER = process.env.MALL_USER || 'ai_operator';
const MALL_PASS = process.env.MALL_PASS || 'Ai@2026#ops';
const ORDER_ID = Number(process.env.MCP_TEST_ORDER_ID || 3);

const MYSQL_CONTAINER = process.env.MCP_TEST_MYSQL_CONTAINER || 'qiqi-mysql';
const DB = process.env.MCP_TEST_DB || 'qiqi_mall_test';
const MYSQL_PASS = process.env.MCP_TEST_MYSQL_PASSWORD || 'qiqi123456';

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

// ---------------- 直连测试库（只为数行数，不碰后端代码） ----------------
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
const deliveryCount = () => Number(sql(`SELECT COUNT(*) FROM ${DB}.delivery_order`));
const orderStatus = (id) => Number(sql(`SELECT status FROM ${DB}.\`order\` WHERE id = ${Number(id)}`));

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
    env: cleanEnv({
      MALL_BASE,
      MALL_USER,
      MALL_PASS,
      MCP_ENABLE_WRITE: '1',
      ...extraEnv,
    }),
    stderr: 'ignore', // server 的 console.error 不进测试输出
  });
  const client = new Client({ name: `loop-test-${label}`, version: '0.1.0' });
  await client.connect(transport);
  return client;
}

/** 调用工具；工具返回 isError 或协议层抛错都算「错误」，统一 throw 带文本 */
async function call(client, name, args) {
  const res = await client.callTool({ name, arguments: args });
  const textOut = (res.content || [])
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('\n');
  if (res.isError) throw new Error(textOut);
  return { text: textOut, res };
}

async function expectError(client, name, args) {
  try {
    const r = await call(client, name, args);
    return { threw: false, text: r.text };
  } catch (e) {
    return { threw: true, text: e?.message || String(e) };
  }
}

/** 从工具文本里取 "xxx JSON:\n<json>" 段 */
function jsonAfter(text, marker) {
  const idx = text.indexOf(marker);
  if (idx < 0) throw new Error(`响应里找不到「${marker}」段`);
  return JSON.parse(text.slice(idx + marker.length).trim());
}
const tokenOf = (text) => {
  const m = text.match(/confirm_token:\s*([0-9a-fA-F-]{36})/);
  if (!m) throw new Error('preview_split 响应里没有 confirm_token');
  return m[1];
};

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 目标：${MALL_BASE} | 账号：${MALL_USER} | 测试订单：id=${ORDER_ID} | 库：${DB}\n`);

  const client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'rw');
  let plan, token, before;

  // ① 待审核列表
  try {
    const { text } = await call(client, 'list_pending_orders', {});
    const records = jsonAfter(text, 'JSON:');
    assert(
      records.length > 0 && records.every((o) => o.status === 7),
      '① list_pending_orders 返回 >0 条且每条 status===7',
      `${records.length} 条，id=${records.map((o) => o.id).join(',')}`
    );
  } catch (e) {
    ko('① list_pending_orders 返回 >0 条且每条 status===7', e.message);
  }

  // ② 记录 before
  try {
    before = deliveryCount();
    const st = orderStatus(ORDER_ID);
    if (st !== 7) throw new Error(`订单 ${ORDER_ID} 当前 status=${st}，不是 7（测试库可能已被跑脏，先 reset）`);
    ok('② 记录 delivery_order 行数 before（并确认订单 7）', `before=${before}, order ${ORDER_ID} status=7`);
  } catch (e) {
    ko('② 记录 delivery_order 行数 before（并确认订单 7）', e.message);
  }

  // ③ preview_split
  try {
    const { text } = await call(client, 'preview_split', { orderId: ORDER_ID });
    plan = jsonAfter(text, '方案 JSON:');
    token = tokenOf(text);
    assert(Array.isArray(plan) && plan.length > 0 && !!token, '③ preview_split → plan.length>0 且拿到 confirmToken', `plan=${plan.length} 个包裹，token=${token.slice(0, 8)}…`);
  } catch (e) {
    ko('③ preview_split → plan.length>0 且拿到 confirmToken', e.message);
  }

  // ④ 预览不落库
  try {
    const now = deliveryCount();
    assert(now === before, '④ 预览不落库：delivery_order 行数不变', `before=${before}, after=${now}`);
  } catch (e) {
    ko('④ 预览不落库：delivery_order 行数不变', e.message);
  }

  // ⑤ 负例：假 token
  try {
    const r = await expectError(client, 'execute_audit_pass', { token: 'fake-uuid' });
    assert(
      r.threw && /无效|已使用/.test(r.text),
      '⑤ 负例：假 token 被拒且提示含「无效/已使用」',
      r.threw ? r.text.split('\n')[0] : '竟然成功了'
    );
  } catch (e) {
    ko('⑤ 负例：假 token 被拒且提示含「无效/已使用」', e.message);
  }

  // ⑥ 负例：不传 token（zod 拒绝）
  try {
    const r = await expectError(client, 'execute_audit_pass', {});
    assert(
      r.threw && /token|required|Invalid|invalid|参数|校验/i.test(r.text),
      '⑥ 负例：不传 token 被 zod 校验拒绝',
      r.threw ? r.text.split('\n')[0].slice(0, 120) : '竟然成功了'
    );
  } catch (e) {
    ko('⑥ 负例：不传 token 被 zod 校验拒绝', e.message);
  }

  // ⑦ 成功执行
  let delivered = 0;
  try {
    const { text } = await call(client, 'execute_audit_pass', { token });
    const deliveries = jsonAfter(text, '发货单 JSON:');
    delivered = deliveries.length;
    assert(
      plan && delivered === plan.length,
      '⑦ execute_audit_pass → deliveries.length === plan.length',
      `deliveries=${delivered}, plan=${plan?.length}`
    );
  } catch (e) {
    ko('⑦ execute_audit_pass → deliveries.length === plan.length', e.message);
  }

  // ⑧ 真落库
  try {
    const now = deliveryCount();
    assert(now === before + plan.length, '⑧ 真落库：delivery_order 行数 === before + plan.length', `before=${before}, now=${now}, 期望=${before + plan.length}`);
  } catch (e) {
    ko('⑧ 真落库：delivery_order 行数 === before + plan.length', e.message);
  }

  // ⑨ 订单状态 8
  try {
    const st = orderStatus(ORDER_ID);
    assert(st === 8, `⑨ 订单 ${ORDER_ID} status 变 8`, `status=${st}`);
  } catch (e) {
    ko(`⑨ 订单 ${ORDER_ID} status 变 8`, e.message);
  }

  // ⑩ 一次性
  try {
    const r = await expectError(client, 'execute_audit_pass', { token });
    assert(r.threw && /无效|已使用/.test(r.text), '⑩ 负例：同一 token 再用一次被拒（一次性）', r.threw ? r.text.split('\n')[0] : '竟然成功了');
  } catch (e) {
    ko('⑩ 负例：同一 token 再用一次被拒（一次性）', e.message);
  }

  // ⑪ 回读一致
  try {
    const { text } = await call(client, 'list_deliveries', { orderId: ORDER_ID });
    const rows = jsonAfter(text, 'JSON:');
    assert(rows.length === plan.length, '⑪ list_deliveries → 条数 === plan.length（闭环一致）', `deliveries=${rows.length}, plan=${plan.length}, sn=${rows.map((d) => d.deliverySn).join(',')}`);
  } catch (e) {
    ko('⑪ list_deliveries → 条数 === plan.length（闭环一致）', e.message);
  }

  await client.close();

  // ⑫ 写开关：MCP_ENABLE_WRITE=0 → 只剩只读工具（订单 2 个 + 商品域 6 个），写工具消失
  try {
    const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
    const { tools } = await ro.listTools();
    const names = tools.map((t) => t.name).sort();
    const want = [
      'list_pending_orders',
      'preview_split',
      'search_products',
      'get_product_detail',
      'list_categories',
      'list_product_types',
      'list_attributes',
      'list_skus',
    ];
    const pass = tools.length === want.length && want.every((n) => names.includes(n)) && !names.includes('execute_audit_pass');
    assert(pass, '⑫ MCP_ENABLE_WRITE=0 → tools/list 只剩 8 个只读工具（订单 2 + 商品 6），写工具消失', `${tools.length} 个：${names.join(', ')}`);
    await ro.close();
  } catch (e) {
    ko('⑫ MCP_ENABLE_WRITE=0 → tools/list 只剩 8 个只读工具（订单 2 + 商品 6），写工具消失', e.message);
  }

  // ---------------- 总结 ----------------
  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 闭环通过：查询 → 预览（不落库）→ 确认执行（真落库）→ 回读一致；写开关生效。');
    console.log('ℹ️ 测试库已脏（订单 3 已审核）。复位：bash scripts/reset-test-db.sh');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
