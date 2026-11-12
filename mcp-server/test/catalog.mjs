#!/usr/bin/env node
// 商品域（catalog）第 1 批测试：6 个只读工具。
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 6 个工具都能在 tools/list 里列出来
//   ② 6 个工具都能调通且返回非空（用**动态探测到的**商品/SKU/类型 id，不写死种子 id）
//   ③ search_products 分页：size=2 → ≤2 条；size=999 → 夹到 50；翻页不重复（全部按 total 做相对断言）
//   ④ 空结果场景给明确文案而不是报错
//   ⑤ 只读校验：调用前后 product / sku / sku_spec 行数不变
//   ⑥ 写开关关闭时只读工具仍在（写工具消失）——条数动态取，不写死
//
// 数据自给自足：本文件只读、不造数据，因此**不依赖测试库的商品总数是 28**，
// 只要求在架商品 ≥1 且每个类型都带属性。断言强度靠「相对关系」保证（records === min(size,total)、
// 翻页不重复、详情必带 gallery/skus），而不是靠「库里恰好有 28 个商品」这种外部状态。

import { connectServer, call, jsonAfter, dbCount, MALL_BASE, MALL_API_KEY, DB } from './_setup.mjs';

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

// ---------------- 只读校验用：数行数 ----------------
const snapshot = () => ({
  product: dbCount('product'),
  sku: dbCount('sku'),
  sku_spec: dbCount('sku_spec'),
  category: dbCount('category'),
  attribute: dbCount('attribute'),
});

/** 每个工具的真实调用样例（用于报告里各贴一行摘要） */
const samples = [];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 商品域只读工具测试 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? MALL_API_KEY.slice(0, 8) + '…' : '(缺失)'} | 库：${DB}\n`);

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

  // ②0 先探一次：拿到**真实存在**的商品 id / 类型 id，后面的用例全部用它（不再写死 1）
  let sampleProductId = null;
  let sampleTypeId = null;
  let catalogTotal = 0;
  try {
    const p = jsonAfter((await call(client, 'search_products', { size: 1 })).text);
    catalogTotal = Number(p.total) || 0;
    sampleProductId = p.records?.[0]?.id ?? null;
    const t = jsonAfter((await call(client, 'list_product_types', {})).text);
    sampleTypeId = t.records?.[0]?.id ?? null;
    assert(
      catalogTotal >= 1 && sampleProductId !== null && sampleTypeId !== null,
      '②0 商品池非空：至少 1 个在架商品、1 个商品类型（不写死「28 个商品」）',
      `total=${catalogTotal}, sampleProductId=${sampleProductId}, sampleTypeId=${sampleTypeId}`
    );
    if (sampleProductId === null || sampleTypeId === null) {
      console.log('\n⚠️ 商品池为空，后续用例无意义，直接失败退出。');
      process.exit(1);
    }
  } catch (e) {
    ko('②0 商品池非空：至少 1 个在架商品、1 个商品类型', e.message);
  }

  // ② 逐个调通 + 返回非空（用动态探测到的 id，不依赖种子具体值）
  const detail = {};
  for (const [name, args] of [
    ['search_products', { size: 3 }],
    ['get_product_detail', { productId: sampleProductId }],
    ['list_categories', {}],
    ['list_product_types', {}],
    ['list_attributes', { typeId: sampleTypeId }],
    ['list_skus', { productId: sampleProductId }],
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

  // ②b 详情工具必须真含相册 + 规格列表（对我探到的那个商品断言）
  try {
    const j = jsonAfter(detail.get_product_detail || '');
    assert(
      j.product?.id === sampleProductId && Array.isArray(j.gallery) && Array.isArray(j.skus) && j.skus.length > 0,
      '②b get_product_detail 含 product（id === 我探到的商品）+ gallery + skus（规格列表非空）',
      `productId=${j.product?.id}, gallery=${j.gallery?.length} skus=${j.skus?.length}`
    );
  } catch (e) {
    ko('②b get_product_detail 含 product + gallery + skus（规格列表非空）', e.message);
  }

  // ②c 类型工具必须真带上属性（后端不聚合，工具层补的）
  try {
    const j = jsonAfter(detail.list_product_types || '');
    const records = j.records || [];
    const withAttrs = records.filter((t) => Array.isArray(t.attributes) && t.attributes.length > 0);
    assert(
      records.length > 0 && withAttrs.length === records.length,
      '②c list_product_types 每个类型都带 attributes',
      `类型 ${records.length} 个，带属性 ${withAttrs.length} 个`
    );
  } catch (e) {
    ko('②c list_product_types 每个类型都带 attributes', e.message);
  }

  // ③a 分页：size=2 → 条数 === min(2, total)，且带 total/size/page 契约字段
  try {
    const { text } = await call(client, 'search_products', { size: 2 });
    const j = jsonAfter(text);
    assert(
      j.size === 2 && j.records.length === Math.min(2, j.total) && j.records.length > 0,
      '③a search_products size=2 → 条数 === min(2, total)（相对断言，不假设库里有几个商品）',
      `records=${j.records.length}, total=${j.total}, size=${j.size}`
    );
  } catch (e) {
    ko('③a search_products size=2 → 条数 === min(2, total)', e.message);
  }

  // ③b 分页：size=999 → 夹到 50（不报错）
  try {
    const { text } = await call(client, 'search_products', { size: 999 });
    const j = jsonAfter(text);
    assert(
      j.size === 50 && j.records.length === Math.min(50, j.total),
      '③b search_products size=999 → 夹到 50 且条数 === min(50, total)',
      `size=${j.size}, records=${j.records.length}, total=${j.total}`
    );
  } catch (e) {
    ko('③b search_products size=999 → 夹到 50 且条数 === min(50, total)', e.message);
  }

  // ③c 翻页：total>2 时第 2 页必须与第 1 页不重复；total<=2 时第 2 页必须为空
  try {
    const p1 = jsonAfter((await call(client, 'search_products', { size: 2, page: 1 })).text);
    const p2 = jsonAfter((await call(client, 'search_products', { size: 2, page: 2 })).text);
    const ids1 = p1.records.map((r) => r.id);
    const ids2 = p2.records.map((r) => r.id);
    const disjoint = ids1.every((id) => !ids2.includes(id));
    const expectP2Empty = p1.total <= 2;
    assert(
      p2.page === 2 && (expectP2Empty ? ids2.length === 0 : ids2.length > 0 && disjoint),
      '③c 翻页正确：第 2 页与第 1 页不重复（total<=2 时第 2 页为空）',
      `total=${p1.total} p1=[${ids1.join(',')}] p2=[${ids2.join(',')}]`
    );
  } catch (e) {
    ko('③c 翻页正确：第 2 页与第 1 页不重复（total<=2 时第 2 页为空）', e.message);
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

  // ⑥ 写开关关闭时：商品域 6 个只读工具仍在，写工具消失（只读工具**总数动态取**，后面加域不用改这里）
  try {
    const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
    const names = (await ro.listTools()).tools.map((t) => t.name);
    const allThere = CATALOG_TOOLS.every((n) => names.includes(n));
    assert(
      allThere && !names.includes('execute_audit_pass'),
      `⑥ MCP_ENABLE_WRITE=0 → 商品域 6 个只读工具全部仍在（当前只读工具共 ${names.length} 个，其中含商品域 ${CATALOG_TOOLS.length} 个），写工具消失`,
      `${names.length} 个：${names.sort().join(', ')}`
    );
    await ro.close();
  } catch (e) {
    ko('⑥ MCP_ENABLE_WRITE=0 → 商品域 6 个只读工具全部仍在、写工具消失', e.message);
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
    console.log(`✅ 商品域 ${CATALOG_TOOLS.length} 个只读工具全部可用；分页夹取一致；空结果文案明确；无写库行为。`);
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
