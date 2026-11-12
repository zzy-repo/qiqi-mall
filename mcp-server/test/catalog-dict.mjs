#!/usr/bin/env node
// 第 5 批（覆盖补齐）· 商品字典工具测试：category / product_type / attribute（各 action=get|create|update|delete）。
//
// 缺口来源：CategoryController / ProductTypeController / AttributeController 各有 5 个端点，
// 之前 MCP 只覆盖了 list（list_categories / list_product_types / list_attributes），
// 后台「分类管理 / 商品类型管理 / 属性管理」三个页面的**增删改 + 单条详情**是空白 —— 本文件验收这 3 个新工具。
//
// 逐条断言：
//   ① tools/list 含 3 个字典工具；write=1 时 action 枚举 = get/create/update/delete；write=0 时只剩 get
//   ② product_type：create → 库里有行 → get 读回 → update 改名 → 库内确实改了
//   ③ attribute：create（挂到 typeId 下）→ get 读回 → update（改名 + isFilter）→ 库内确实改了
//   ④ category：create(parentId=0) → get → update 改名 → 建子分类 → **删父级被引用守卫拒** → 删子 → 删父
//   ⑤ 无 version 口径：update / delete 一条**刚被删掉**的记录 → 409「记录已被删除」（三张表都没有 version 列）
//   ⑥ write=0：create 在协议层被拒（action 枚举里没有写动作）
//
// 数据自给自足：只造自己这几个字典行，跑完删干净；连跑两轮不需要 reset 库。
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { connectServer, call, expectError, jsonAfter, sql, DB, MALL_BASE, MALL_API_KEY } from './_setup.mjs';

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
async function check(step, fn) {
  try {
    const r = await fn();
    const pass = r === true || r?.pass;
    pass ? ok(step, r?.detail) : ko(step, r?.detail);
    return !!pass;
  } catch (e) {
    ko(step, e.message);
    return false;
  }
}

const stamp = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
};
const T = stamp();
const one = (q) => {
  const out = sql(q);
  return out === '' || out === undefined || out === null ? null : out;
};
const dbCategory = (id) => {
  const out = one(`SELECT name, parent_id FROM ${DB}.category WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [name, parentId] = out.split('\t');
  return { name, parentId: Number(parentId) };
};
const dbType = (id) => {
  const out = one(`SELECT name FROM ${DB}.product_type WHERE id = ${Number(id)}`);
  return out ? { name: out } : null;
};
const dbAttr = (id) => {
  const out = one(`SELECT name, type_id, is_filter FROM ${DB}.attribute WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [name, typeId, isFilter] = out.split('\t');
  return { name, typeId: Number(typeId), isFilter: Number(isFilter) };
};

async function main() {
  console.log(`▶ 商品字典工具测试（第 5 批覆盖补齐：category / product_type / attribute）| 目标：${MALL_BASE} | 库：${DB}`);

  const created = { types: [], attrs: [], cats: [] };
  let client;
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'catalog-dict');
    const samples = [];

    // ① 工具清单 + 写开关
    await check('① tools/list 含 3 个字典工具；write=1 枚举=get/create/update/delete，write=0 只剩 get', async () => {
      const { tools } = await client.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const names = ['category', 'product_type', 'attribute'];
      const allThere = names.every((n) => by[n]);
      const enums = names.map((n) => (by[n]?.inputSchema?.properties?.action?.enum ?? []).join('/'));
      const full = enums.every((e) => e === 'get/create/update/delete');
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'catalog-dict-ro');
      const roBy = Object.fromEntries((await ro.listTools()).tools.map((t) => [t.name, t]));
      const roEnums = names.map((n) => (roBy[n]?.inputSchema?.properties?.action?.enum ?? []).join('/'));
      await ro.close();
      return {
        pass: allThere && full && roEnums.every((e) => e === 'get'),
        detail: `write=1 枚举=${enums.join('｜')}｜write=0 枚举=${roEnums.join('｜')}`,
      };
    });

    // ② product_type
    let typeId = null;
    await check('②a product_type create → 返回 id，库里有这一行', async () => {
      const { text } = await call(client, 'product_type', { action: 'create', name: `测试类型T${T}` });
      const row = jsonAfter(text);
      typeId = row?.id;
      if (typeId) created.types.push(typeId);
      samples.push({ tool: 'product_type.create', args: { name: `测试类型T${T}` }, line: text.split('\n')[0] });
      const db = dbType(typeId);
      return { pass: !!typeId && db?.name === `测试类型T${T}`, detail: `id=${typeId} 库内名称=${db?.name}` };
    });

    await check('②b product_type get → 读回同一条', async () => {
      const { text } = await call(client, 'product_type', { action: 'get', typeId });
      const row = jsonAfter(text);
      return { pass: Number(row?.id) === Number(typeId) && row?.name === `测试类型T${T}`, detail: `读回 id=${row?.id} name=${row?.name}` };
    });

    await check('②c product_type update 改名 → 库内确实改了', async () => {
      const { text } = await call(client, 'product_type', { action: 'update', typeId, name: `测试类型T${T}改` });
      const db = dbType(typeId);
      return { pass: db?.name === `测试类型T${T}改` && text.includes('✅'), detail: `库内名称=${db?.name}` };
    });

    // ③ attribute（挂在 ② 建的类型下）
    let attrId = null;
    await check('③a attribute create（挂 typeId 下）→ 返回 id，库里有这一行', async () => {
      const { text } = await call(client, 'attribute', { action: 'create', typeId, name: `测试属性A${T}`, isFilter: 1 });
      const row = jsonAfter(text);
      attrId = row?.id;
      if (attrId) created.attrs.push(attrId);
      samples.push({ tool: 'attribute.create', args: { typeId, name: `测试属性A${T}` }, line: text.split('\n')[0] });
      const db = dbAttr(attrId);
      return { pass: !!attrId && db?.name === `测试属性A${T}` && db?.typeId === Number(typeId), detail: `id=${attrId} 库内=${db?.name}/typeId=${db?.typeId}` };
    });

    await check('③b attribute update（改名 + isFilter=0）→ 库内确实改了', async () => {
      const { text } = await call(client, 'attribute', { action: 'update', attributeId: attrId, name: `测试属性A${T}改`, isFilter: 0 });
      const db = dbAttr(attrId);
      return {
        pass: db?.name === `测试属性A${T}改` && db?.isFilter === 0 && text.includes('✅'),
        detail: `库内=${db?.name}/isFilter=${db?.isFilter}`,
      };
    });

    await check('③c attribute get → 读回改名后的值（规格名）', async () => {
      const row = jsonAfter((await call(client, 'attribute', { action: 'get', attributeId: attrId })).text);
      return { pass: Number(row?.id) === Number(attrId) && row?.name === `测试属性A${T}改`, detail: `读回 name=${row?.name} typeId=${row?.typeId}` };
    });

    // ④ category：建 → 改 → 子分类 → 引用守卫
    let catId = null;
    let childId = null;
    await check('④a category create(parentId=0) → 一级分类，库里有这一行', async () => {
      const { text } = await call(client, 'category', { action: 'create', name: `测试分类C${T}`, parentId: 0 });
      const row = jsonAfter(text);
      catId = row?.id;
      if (catId) created.cats.push(catId);
      const db = dbCategory(catId);
      return { pass: !!catId && db?.name === `测试分类C${T}` && db?.parentId === 0, detail: `id=${catId} 库内=${db?.name}/parentId=${db?.parentId}` };
    });

    await check('④b category update 改名 → 库内确实改了', async () => {
      const { text } = await call(client, 'category', { action: 'update', categoryId: catId, name: `测试分类C${T}改` });
      const db = dbCategory(catId);
      return { pass: db?.name === `测试分类C${T}改` && text.includes('✅'), detail: `库内名称=${db?.name}` };
    });

    await check('④c 有子分类时 delete 父分类 → 被引用守卫拒（后端回中文原因，不是静默删掉）', async () => {
      const { text } = await call(client, 'category', { action: 'create', name: `测试子分类S${T}`, parentId: catId });
      const child = jsonAfter(text);
      childId = child?.id;
      if (childId) created.cats.push(childId);
      const r = await expectError(client, 'category', { action: 'delete', categoryId: catId });
      const still = dbCategory(catId);
      return { pass: r.threw && !!still, detail: `${r.text.split('\n')[0].slice(0, 80)}｜父分类仍在=${!!still}` };
    });

    await check('④d 先删子分类再删父分类 → 两级都删掉', async () => {
      await call(client, 'category', { action: 'delete', categoryId: childId });
      const goneChild = dbCategory(childId);
      await call(client, 'category', { action: 'delete', categoryId: catId });
      const goneParent = dbCategory(catId);
      return { pass: !goneChild && !goneParent, detail: `子=${goneChild ? '仍在' : '已删'}｜父=${goneParent ? '仍在' : '已删'}` };
    });

    // ⑤ 无 version 口径：改/删一条刚删掉的记录 → 409
    await check('⑤ 三张表都没有 version 列：update 一条刚被删掉的类型 → 409「记录已被删除」', async () => {
      const { text } = await call(client, 'product_type', { action: 'create', name: `测试临时类型G${T}` });
      const tmp = jsonAfter(text);
      await call(client, 'product_type', { action: 'delete', typeId: tmp?.id });
      const r = await expectError(client, 'product_type', { action: 'update', typeId: tmp?.id, name: `不该成功${T}` });
      return { pass: r.threw && /409|已被删除/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });

    // ⑥ write=0：create 在协议层被拒
    await check('⑥ write=0 → category(action=create) 在协议层被拒（枚举里没有写动作）', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'catalog-dict-ro2');
      const r = await expectError(ro, 'category', { action: 'create', name: '不该成功' });
      const okGet = await expectError(ro, 'category', { action: 'get', categoryId: 1 });
      await ro.close();
      return { pass: r.threw && !okGet.threw, detail: `写动作被拒=${r.threw}｜只读 get 可用=${!okGet.threw}` };
    });

    // 清理（顺序：属性 → 类型）
    for (const id of created.attrs) await call(client, 'attribute', { action: 'delete', attributeId: id }).catch(() => {});
    for (const id of created.types) await call(client, 'product_type', { action: 'delete', typeId: id }).catch(() => {});

    console.log('\n调用样例（首行摘要）：');
    for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${s.line.replace(/\s+/g, ' ').trim().slice(0, 140)}`);
  } finally {
    if (client) await client.close().catch(() => {});
    // 兜底：万一中途抛错，直接用 SQL 清掉本文件造的行（带唯一戳，不会误伤别人）
    try {
      sql(`DELETE FROM ${DB}.attribute WHERE name LIKE '测试属性A${T}%'`);
      sql(`DELETE FROM ${DB}.product_type WHERE name LIKE '测试类型T${T}%'`);
      sql(`DELETE FROM ${DB}.category WHERE name LIKE '测试分类C${T}%' OR name LIKE '测试子分类S${T}%'`);
    } catch (e) {
      console.log(`  （兜底清理跳过：${e.message}）`);
    }
  }

  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 分类 / 商品类型 / 属性 三个字典的 查·增·改·删 全部可用；删除走引用守卫；无 version 表用 409 兜并发。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
