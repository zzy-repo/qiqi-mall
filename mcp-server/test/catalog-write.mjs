#!/usr/bin/env node
// 第 5 批（覆盖补齐）· 商品域「增删」工具测试：create_product / delete_product / create_sku / delete_sku。
//
// 缺口来源：ProductController 有 POST / DELETE、SkuController 有 POST / DELETE，
// 之前 MCP 只覆盖了「改」（update_product / update_sku_stock），后台商品页的
// 「新增商品 / 删除商品 / 新增规格 / 删除规格」四个按钮在 MCP 侧是空白 —— 本文件验收这 4 个新工具。
//
// 逐条断言：
//   ① tools/list：write=1 时商品域 6 个写工具都在；write=0 时 6 个整体消失
//   ② create_product：库里有行、version=0（后端置）、相册写入了 1 张
//   ③ create_sku：挂在该商品下、库里有行、version=0；list_skus 能查到
//   ④ delete_sku：dryRun 只回 willDo（SKU 仍在）→ 真删后行消失
//   ⑤ delete_product：dryRun 只回 willDo（商品仍在）→ 真删后商品与 SKU 一起消失（级联）
//   ⑥ 守卫/边界：删除不存在的商品 → 中文错误（不是静默成功）；create_sku 缺 productId → 协议层拒
//
// 数据自给自足：自己建商品 + SKU，跑完删干净；连跑两轮不需要 reset 库。
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
const dbProduct = (id) => {
  const out = one(`SELECT name, publish_status, version FROM ${DB}.product WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [name, publishStatus, version] = out.split('\t');
  return { name, publishStatus: Number(publishStatus), version: Number(version) };
};
const dbSku = (id) => {
  const out = one(`SELECT product_id, price, stock, version FROM ${DB}.sku WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [productId, price, stock, version] = out.split('\t').map(Number);
  return { productId, price, stock, version };
};
const dbGalleryCount = (productId) => Number(one(`SELECT COUNT(*) FROM ${DB}.product_gallery WHERE product_id = ${Number(productId)}`) ?? 0);

const WRITE_TOOLS = ['update_product', 'update_sku_stock', 'create_product', 'delete_product', 'create_sku', 'delete_sku'];

async function main() {
  console.log(`▶ 商品域增删工具测试（第 5 批覆盖补齐）| 目标：${MALL_BASE} | 库：${DB}`);

  let productId = null;
  let skuId = null;
  let client;
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'catalog-write');
    const samples = [];

    // 夹具：拿一个真实分类与类型（新建商品要挂上去，才能配规格属性）
    const cats = jsonAfter((await call(client, 'list_categories', {})).text);
    const flat = JSON.stringify(cats).includes('records') ? cats.records : cats;
    const categoryId = (Array.isArray(flat) ? flat : [])?.[0]?.id ?? 1;
    const types = jsonAfter((await call(client, 'list_product_types', {})).text);
    const typeList = JSON.stringify(types).includes('records') ? types.records : types;
    const typeId = (Array.isArray(typeList) ? typeList : [])?.[0]?.id ?? 1;

    // ① 工具清单
    await check('① write=1 时商品域 6 个写工具都在；write=0 时 6 个整体消失', async () => {
      const { tools } = await client.listTools();
      const names = tools.map((t) => t.name);
      const allThere = WRITE_TOOLS.every((n) => names.includes(n));
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'catalog-write-ro');
      const roNames = (await ro.listTools()).tools.map((t) => t.name);
      await ro.close();
      const leaked = WRITE_TOOLS.filter((n) => roNames.includes(n));
      return { pass: allThere && leaked.length === 0, detail: `write=1 工具共 ${names.length} 个；6 个齐=${allThere}；write=0 漏出=${leaked.join(',') || '无'}` };
    });

    // ② create_product
    await check('②a create_product → 库里有行、version=0（后端置）、相册 1 张', async () => {
      const { text } = await call(client, 'create_product', {
        name: `测试商品P${T}`,
        description: '第 5 批覆盖补齐测试用商品',
        categoryId: Number(categoryId),
        typeId: Number(typeId),
        publishStatus: 0,
        gallery: [{ url: '/qiqi-mall/products/test/fixture.webp', sort: 0 }],
      });
      const row = jsonAfter(text);
      productId = row?.id;
      samples.push({ tool: 'create_product', args: { name: `测试商品P${T}` }, line: text.split('\n')[0] });
      const db = dbProduct(productId);
      const gal = dbGalleryCount(productId);
      return {
        pass: !!productId && db?.name === `测试商品P${T}` && db?.version === 0 && db?.publishStatus === 0 && gal === 1,
        detail: `id=${productId} 库内=${db?.name}/version=${db?.version}/publish=${db?.publishStatus} 相册=${gal} 张`,
      };
    });

    await check('②b get_product_detail 能读回新商品（含相册）', async () => {
      const j = jsonAfter((await call(client, 'get_product_detail', { productId })).text);
      const p = j.product ?? j;
      const gallery = j.gallery ?? [];
      return { pass: Number(p.id) === Number(productId) && p.name === `测试商品P${T}` && gallery.length >= 1, detail: `读回 name=${p.name} 相册=${gallery.length} 张 version=${p.version}` };
    });

    // ③ create_sku
    await check('③a create_sku → 挂在该商品下、库里有行、version=0；list_skus 能查到', async () => {
      const { text } = await call(client, 'create_sku', { productId: Number(productId), price: 12.5, stock: 7 });
      const row = jsonAfter(text);
      skuId = row?.id;
      samples.push({ tool: 'create_sku', args: { productId, price: 12.5, stock: 7 }, line: text.split('\n')[0] });
      const db = dbSku(skuId);
      const listed = jsonAfter((await call(client, 'list_skus', { productId, size: 50 })).text);
      const found = (listed.records ?? []).some((s) => Number(s.id) === Number(skuId));
      return {
        pass: !!skuId && db?.productId === Number(productId) && db?.version === 0 && db?.stock === 7 && found,
        detail: `skuId=${skuId} 库内 productId=${db?.productId}/stock=${db?.stock}/version=${db?.version}；list_skus 命中=${found}`,
      };
    });

    await check('③b get_sku_detail 能读回这个 SKU（新读工具 + 新写工具串起来）', async () => {
      const j = jsonAfter((await call(client, 'get_sku_detail', { skuId })).text);
      const s = j.sku ?? j;
      return { pass: Number(s.id) === Number(skuId) && Number(s.price) === 12.5 && Number(s.stock) === 7, detail: `读回 price=${s.price} stock=${s.stock} version=${s.version}` };
    });

    await check('③c create_sku 缺 productId → 协议层拒（必填由 zod 保证）', async () => {
      const r = await expectError(client, 'create_sku', { price: 1, stock: 1 });
      return { pass: r.threw, detail: r.text.split('\n')[0].slice(0, 90) };
    });

    // ④ delete_sku
    await check('④a delete_sku dryRun=true → willDo 含「SKU/删除」，SKU 仍在库里', async () => {
      const { text } = await call(client, 'delete_sku', { skuId, dryRun: true });
      const preview = jsonAfter(text);
      const still = dbSku(skuId);
      samples.push({ tool: 'delete_sku(dryRun)', args: { skuId }, line: text.split('\n').slice(0, 2).join(' ') });
      return { pass: typeof preview?.willDo === 'string' && /删除/.test(preview.willDo) && !!still, detail: `willDo=${(preview?.willDo || '').slice(0, 80)}…｜SKU 仍在=${!!still}` };
    });

    await check('④b delete_sku 真执行 → 行消失（list_skus 里也没有了）', async () => {
      const { text } = await call(client, 'delete_sku', { skuId });
      const gone = dbSku(skuId);
      const listed = jsonAfter((await call(client, 'list_skus', { productId, size: 50 })).text);
      const found = (listed.records ?? []).some((s) => Number(s.id) === Number(skuId));
      return { pass: !gone && !found && text.includes('✅'), detail: `库内行=${gone ? '仍在' : '已删'}；list_skus 命中=${found}` };
    });

    // ⑤ delete_product
    await check('⑤a delete_product dryRun=true → willDo 写明「会连带删 SKU/相册」，商品仍在', async () => {
      // 先再建一个 SKU，验证「级联删 SKU」的说法不是空话
      await call(client, 'create_sku', { productId: Number(productId), price: 3.3, stock: 1 });
      const { text } = await call(client, 'delete_product', { productId, dryRun: true });
      const preview = jsonAfter(text);
      const still = dbProduct(productId);
      samples.push({ tool: 'delete_product(dryRun)', args: { productId }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof preview?.willDo === 'string' && /SKU/.test(preview.willDo) && !!still,
        detail: `willDo=${(preview?.willDo || '').slice(0, 90)}…｜商品仍在=${!!still}`,
      };
    });

    await check('⑤b delete_product 真执行 → 商品与其 SKU/相册一起消失（级联确认）', async () => {
      const skuBefore = Number(one(`SELECT COUNT(*) FROM ${DB}.sku WHERE product_id = ${Number(productId)}`) ?? 0);
      const { text } = await call(client, 'delete_product', { productId });
      const gone = dbProduct(productId);
      const skuAfter = Number(one(`SELECT COUNT(*) FROM ${DB}.sku WHERE product_id = ${Number(productId)}`) ?? 0);
      const galAfter = dbGalleryCount(productId);
      return {
        pass: !gone && skuBefore >= 1 && skuAfter === 0 && galAfter === 0 && text.includes('✅'),
        detail: `商品=${gone ? '仍在' : '已删'}｜SKU ${skuBefore}→${skuAfter}｜相册=${galAfter} 张`,
      };
    });

    // ⑥ 守卫/边界
    await check('⑥a delete_product 删一个不存在的商品 → 中文错误（不是静默成功）', async () => {
      const r = await expectError(client, 'delete_product', { productId: 99999999 });
      return { pass: r.threw && /不存在/.test(r.text), detail: r.text.split('\n')[0].slice(0, 90) };
    });

    await check('⑥b delete_product 预演对不存在的商品同样报错（预演不吞错）', async () => {
      const r = await expectError(client, 'delete_product', { productId: 99999999, dryRun: true });
      return { pass: r.threw, detail: r.text.split('\n')[0].slice(0, 90) };
    });

    console.log('\n调用样例（首行摘要）：');
    for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${s.line.replace(/\s+/g, ' ').trim().slice(0, 140)}`);
  } finally {
    if (client) await client.close().catch(() => {});
    // 兜底清理（正常路径已在 ⑤b 删掉；这里防止中途抛错留数据）
    try {
      sql(`DELETE FROM ${DB}.product WHERE name = '测试商品P${T}'`);
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
    console.log('✅ 商品/规格的「增」「删」补齐：新建 version=0、删除支持 dryRun 预演、级联删 SKU 与相册；写开关=0 时整体消失。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
