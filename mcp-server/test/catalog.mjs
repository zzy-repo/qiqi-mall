#!/usr/bin/env node
// 商品域（catalog）第 1 批测试：6 个只读工具。
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 6 个工具都能在 tools/list 里列出来
//   ② 6 个工具都能调通且返回非空
//   ③ search_products 分页：size=2 → ≤2 条；size=999 → 夹到 50
//   ④ 空结果场景给明确文案而不是报错
//   ⑤ 只读校验：调用前后 product / sku / sku_spec 行数不变
//   ⑥ 写开关关闭时这 6 个只读工具仍在（写工具消失）
//
// 前置：后端 18080 可达 + 测试库 qiqi_mall_test 有种子数据（28 个商品 / 112 个 SKU）。

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

const CATALOG_TOOLS = [
  'search_products',
  'get_product_detail',
  'list_categories',
  'list_product_types',
  'list_attributes',
  'list_skus',
];

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
const count = (table) => Number(sql(`SELECT COUNT(*) FROM ${DB}.${table}`));
const snapshot = () => ({ product: count('product'), sku: count('sku'), sku_spec: count('sku_spec'), category: count('category'), attribute: count('attribute') });

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
  const client = new Client({ name: `catalog-test-${label}`, version: '0.1.0' });
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

/** 从工具文本里取 "xxx JSON:\n<json>" 段（统一格式，摘要在前、JSON 在后） */
function jsonAfter(text, marker = 'JSON:') {
  const idx = text.indexOf(marker);
  if (idx < 0) throw new Error(`响应里找不到「${marker}」段`);
  return JSON.parse(text.slice(idx + marker.length).trim());
}

/** 每个工具的真实调用样例（用于报告里各贴一行摘要） */
const samples = [];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 商品域只读工具测试 | 目标：${MALL_BASE} | 账号：${MALL_USER} | 库：${DB}\n`);

  const client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'rw');

  // ① tools/list 包含这 6 个名字
  let listed = [];
  try {
    const { tools } = await client.listTools();
    listed = tools.map((t) => t.name);
    for (const name of CATALOG_TOOLS) {
      assert(listed.includes(name), `① tools/list 含 ${name}`, listed.includes(name) ? `共 ${listed.length} 个工具` : `实际：${listed.join(', ')}`);
    }
  } catch (e) {
    for (const name of CATALOG_TOOLS) ko(`① tools/list 含 ${name}`, e.message);
  }

  const before = snapshot();

  // ② 逐个调通 + 返回非空（用测试库现有种子数据）
  const detail = {};
  for (const [name, args] of [
    ['search_products', { size: 3 }],
    ['get_product_detail', { productId: 1 }],
    ['list_categories', {}],
    ['list_product_types', {}],
    ['list_attributes', { typeId: 1 }],
    ['list_skus', { productId: 1 }],
  ]) {
    try {
      const { text } = await call(client, name, args);
      const nonEmpty = typeof text === 'string' && text.trim().length > 0;
      const hasJson = text.includes('JSON:');
      jsonAfter(text); // 反序列化一次，保证 JSON 段可解析
      detail[name] = text;
      assert(
        nonEmpty && hasJson,
        `② ${name} 调通且返回「摘要 + JSON」非空`,
        text.split('\n')[0].slice(0, 100)
      );
      samples.push({ tool: name, args, line: text.split('\n').slice(0, 2).join(' ') });
    } catch (e) {
      ko(`② ${name} 调通且返回「摘要 + JSON」非空`, e.message);
    }
  }

  // ②b 详情工具必须真含相册 + 规格列表
  try {
    const j = jsonAfter(detail.get_product_detail || '');
    assert(
      !!j.product?.id && Array.isArray(j.gallery) && Array.isArray(j.skus) && j.skus.length > 0,
      '②b get_product_detail 含 product + gallery + skus（规格列表非空）',
      `gallery=${j.gallery?.length} skus=${j.skus?.length}`
    );
  } catch (e) {
    ko('②b get_product_detail 含 product + gallery + skus（规格列表非空）', e.message);
  }

  // ②c 类型工具必须真带上属性（后端不聚合，工具层补的）
  try {
    const j = jsonAfter(detail.list_product_types || '');
    const withAttrs = (j.records || []).filter((t) => Array.isArray(t.attributes) && t.attributes.length > 0);
    assert(
      (j.records || []).length > 0 && withAttrs.length === j.records.length,
      '②c list_product_types 每个类型都带 attributes',
      `类型 ${j.records?.length} 个，带属性 ${withAttrs.length} 个`
    );
  } catch (e) {
    ko('②c list_product_types 每个类型都带 attributes', e.message);
  }

  // ③ 分页：size=2 → ≤2 条
  try {
    const { text } = await call(client, 'search_products', { size: 2 });
    const j = jsonAfter(text);
    assert(
      j.records.length <= 2 && j.records.length > 0 && j.size === 2,
      '③a search_products size=2 → 返回 ≤2 条',
      `records=${j.records.length}, total=${j.total}, size=${j.size}`
    );
  } catch (e) {
    ko('③a search_products size=2 → 返回 ≤2 条', e.message);
  }

  // ③ 分页：size=999 → 夹到 50（不报错）
  try {
    const { text } = await call(client, 'search_products', { size: 999 });
    const j = jsonAfter(text);
    assert(
      j.size === 50 && j.records.length <= 50,
      '③b search_products size=999 → 夹到 50 且不报错',
      `size=${j.size}, records=${j.records.length}, total=${j.total}`
    );
  } catch (e) {
    ko('③b search_products size=999 → 夹到 50 且不报错', e.message);
  }

  // ③ 分页：翻到第 2 页不重复首页
  try {
    const p1 = jsonAfter((await call(client, 'search_products', { size: 2, page: 1 })).text);
    const p2 = jsonAfter((await call(client, 'search_products', { size: 2, page: 2 })).text);
    const ids1 = p1.records.map((r) => r.id).join(',');
    const ids2 = p2.records.map((r) => r.id).join(',');
    assert(!!ids1 && !!ids2 && ids1 !== ids2 && p2.page === 2, '③c page=1 与 page=2 结果不重复', `p1=[${ids1}] p2=[${ids2}]`);
  } catch (e) {
    ko('③c page=1 与 page=2 结果不重复', e.message);
  }

  // ④ 空结果：明确文案，不报错
  try {
    const { text } = await call(client, 'search_products', { keyword: '这个关键词一定不存在zzz' });
    const j = jsonAfter(text);
    assert(
      text.includes('没有找到匹配的商品') && j.total === 0 && j.records.length === 0,
      '④ 搜不存在的关键词 → 返回「没有找到匹配的商品」且不抛错',
      text.split('\n')[1]?.slice(0, 60)
    );
  } catch (e) {
    ko('④ 搜不存在的关键词 → 返回「没有找到匹配的商品」且不抛错', e.message);
  }

  // ④b 分类搜不到：分类 id 不存在 → 同样空结果文案
  try {
    const { text } = await call(client, 'search_products', { categoryId: 99999 });
    const j = jsonAfter(text);
    assert(text.includes('没有找到匹配的商品') && j.total === 0, '④b 不存在的分类 id → 空结果文案（不报错）', `total=${j.total}`);
  } catch (e) {
    ko('④b 不存在的分类 id → 空结果文案（不报错）', e.message);
  }

  // ④c 错误透出：不存在的商品详情 → 后端中文 message 透出
  try {
    const res = await client.callTool({ name: 'get_product_detail', arguments: { productId: 99999 } });
    const t = (res.content || []).map((c) => c.text).join('\n');
    assert(res.isError === true && t.includes('商品不存在'), '④c 不存在的商品 → isError 且透出后端「商品不存在」', t.split('\n')[0].slice(0, 80));
  } catch (e) {
    ko('④c 不存在的商品 → isError 且透出后端「商品不存在」', e.message);
  }

  // ⑤ 只读校验：行数不变
  const after = snapshot();
  for (const table of Object.keys(before)) {
    assert(
      before[table] === after[table],
      `⑤ 只读校验：${table} 行数不变`,
      `before=${before[table]}, after=${after[table]}`
    );
  }

  await client.close();

  // ⑥ 写开关关闭时：这 6 个只读工具仍在，写工具消失
  try {
    const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
    const names = (await ro.listTools()).tools.map((t) => t.name);
    const allThere = CATALOG_TOOLS.every((n) => names.includes(n));
    assert(
      allThere && !names.includes('execute_audit_pass'),
      '⑥ MCP_ENABLE_WRITE=0 → 6 个只读工具仍在、写工具消失',
      `${names.length} 个：${names.join(', ')}`
    );
    await ro.close();
  } catch (e) {
    ko('⑥ MCP_ENABLE_WRITE=0 → 6 个只读工具仍在、写工具消失', e.message);
  }

  // ---------------- 总结 ----------------
  console.log('\n调用样例（各工具首行摘要）：');
  for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${s.line.replace(/\s+/g, ' ').trim().slice(0, 150)}`);

  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 商品域 6 个只读工具全部可用；分页夹取一致；空结果文案明确；无写库行为。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
