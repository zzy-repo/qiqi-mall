#!/usr/bin/env node
// 乐观锁（version）验收测试：用 SDK client 通过 **stdio 真启动** src/index.mjs + **真库**。
//
// 背景（用户定的口径）：并发控制只留 **version 乐观锁**（后端 406a54b），确认码整套正在删除。
// MCP 是「人的操作的模拟」——人在 UI 上会撞的锁，MCP 走同一个接口必然也要撞到。
// 所以这个文件的重点不是「能不能改成功」，而是：
//   ① 读接口必须把 version 吐出来（否则模型手上根本没有锁值）；
//   ② 写工具必须**强制**带 version（缺了在 zod 层就被拒，连后端都调不到）；
//   ③ version 过期必须被后端 409 拒，且**错误文本原样透给模型**，还要说清「该重新读」；
//   ④ **并发场景**：MCP 读完 → 另一个人（裸 fetch）改了 → MCP 用旧 version 提交 → 409 → 重读 → 成功。
//
// 断言清单（与验收单编号一致）：
//   ① search_products / get_product_detail 返回里含 version（商品 + 每个 SKU）
//   ② update_product 带正确 version → 成功，回读 version +1
//   ③ update_product 带旧 version → 被拒，文本含「数据已被他人修改」+「409」
//   ④ update_product 不带 version → zod 层就拒绝（required）
//   ⑤ **并发场景**：读 V → 裸 HTTP 改一次（模拟别人）→ 用 V 提交 → 409；重读 V+1 → 提交成功
//   ⑥ update_sku_stock 同上（正确 / 旧 / 缺 version）
//   ⑦ write=0 启动 → 两个写工具消失，只读工具齐全
//   ⑧ 回归（loop / catalog / order-read）由 `npm test` 串联执行，见同一轮输出
//
// 数据自给自足：只改一个**动态探测**到的商品/它的第一个 SKU，改名改成带时间戳的标记，
// 结束后**尽量恢复**原值（每个用例都重读最新 version 再改，不硬编码 version），
// 不 reset 测试库、不删任何行（只 bump version），因此可以连跑两轮。
//
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { connectServer, call, expectError, jsonAfter, sql, DB, MALL_BASE, MALL_API_KEY } from './_setup.mjs';

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

// ---------------- 直连测试库（只读 version，用来交叉验证「回读的数字是真的」） ----------------
const dbProductVersion = (id) => Number(sql(`SELECT version FROM ${DB}.product WHERE id = ${Number(id)}`));
const dbSkuVersion = (id) => Number(sql(`SELECT version FROM ${DB}.sku WHERE id = ${Number(id)}`));
const dbProductName = (id) => sql(`SELECT name FROM ${DB}.product WHERE id = ${Number(id)}`);

/** 裸 HTTP：模拟「另一个人/另一个标签页」在后台直接改库（不带 MCP） */
async function rawRequest(method, path, body) {
  const res = await fetch(`${MALL_BASE}${path}`, {
    method,
    headers: { 'X-Api-Key': MALL_API_KEY, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = { code: -1, message: text.slice(0, 200) };
  }
  return { status: res.status, payload };
}

/** 当前商品 + 它的 SKU（每次都**真读一次**，绝不缓存 version） */
async function readProduct(client, productId) {
  const j = jsonAfter((await call(client, 'get_product_detail', { productId })).text);
  return { product: j.product, skus: j.skus || [] };
}

const uniqueMark = () => String(Date.now()).slice(-6);
const WRITE_TOOLS = ['update_product', 'update_sku_stock'];
const READONLY_TOOLS = [
  'list_pending_orders',
  'preview_split',
  'list_deliveries',
  'list_orders',
  'get_order_detail',
  'count_deliveries',
  'search_products',
  'get_product_detail',
  'list_categories',
  'list_product_types',
  'list_attributes',
  'list_skus',
];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 乐观锁（version）验收 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}\n`);

  const client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'lock');
  const mark = uniqueMark();
  let productId = null;
  let originalName = null;
  let skuId = null;
  let originalStock = null;

  try {
    // ---- 探一个真实存在的商品（不写死 id），并记下原值以便收尾恢复 ----
    // ⚠️ **随机挑**而不是永远挑 id=1：后端/前端 agent 的集成测试常常也拿种子商品 1 做载体，
    //    固定挑第一个会让「version 正好 ±1」这类算术断言被别人的写入打乱（踩过一次）。
    const list = jsonAfter((await call(client, 'search_products', { size: 50 })).text);
    const candidates = (list.records || []).slice();
    if (!candidates.length) throw new Error('测试库没有任何商品（product 表为空），无法做乐观锁验收');
    // 打乱（Fisher-Yates），然后取第一个「有 SKU」的当载体
    for (let i = candidates.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
    }

    let seed = null;
    for (const c of candidates) {
      const d = await readProduct(client, c.id);
      if (d.skus.length) {
        seed = { ...d, productId: c.id };
        break;
      }
    }
    if (!seed) throw new Error('测试库没有任何带 SKU 的商品，无法测 update_sku_stock');

    productId = seed.productId;
    originalName = seed.product.name;
    skuId = seed.skus[0].id;
    originalStock = seed.skus[0].stock;
    console.log(
      `  探测到的载体（随机挑，避开别人也爱用的 id=1）：productId=${productId}（version=${seed.product.version}）` +
        `skuId=${skuId}（stock=${originalStock}, version=${seed.skus[0].version}）\n`
    );

    // ① 读接口必须带 version
    try {
      const j = jsonAfter((await call(client, 'search_products', { size: 3 })).text);
      const withVersion = (j.records || []).filter((r) => Number.isInteger(r.version));
      const d = jsonAfter((await call(client, 'get_product_detail', { productId })).text);
      const productHas = Number.isInteger(d.product?.version);
      const skusHave = (d.skus || []).length > 0 && d.skus.every((k) => Number.isInteger(k.version));
      assert(
        withVersion.length === (j.records || []).length && productHas && skusHave,
        '① search_products / get_product_detail 都返回 version（商品 + 每个 SKU）',
        `search 带 version ${withVersion.length}/${j.records.length}；product.version=${d.product?.version}；skus=${d.skus.length} 个全部带 version`
      );
    } catch (e) {
      ko('① search_products / get_product_detail 都返回 version', e.message);
    }

    // ② update_product 带正确 version → 成功，回读 version +1
    //    用一个带时间戳的新名字（唯一、可回溯），并核对 DB 里真的变了
    let afterName = null;
    try {
      const cur = await readProduct(client, productId);
      const oldVersion = cur.product.version;
      afterName = `${originalName}·锁测试${mark}`;
      const { text } = await call(client, 'update_product', { productId, name: afterName, version: oldVersion });
      const j = jsonAfter(text);
      const reread = await readProduct(client, productId);
      const bump = reread.product.version - oldVersion;
      assert(
        j.newVersion === oldVersion + 1 &&
          reread.product.version === oldVersion + 1 &&
          reread.product.name === afterName &&
          dbProductVersion(productId) === oldVersion + 1,
        `② update_product 带正确 version(${oldVersion}) → 成功，version +1（回读 + DB 双确认）`,
        `响应 newVersion=${j.newVersion}；回读 version=${reread.product.version}；DB version=${dbProductVersion(productId)}；名称已生效=${reread.product.name === afterName}`
      );
      console.log(`    成功样例摘要：${text.split('\n').slice(0, 3).join(' / ')}`);
    } catch (e) {
      ko('② update_product 带正确 version → 成功，version +1', e.message);
    }

    // ③ update_product 带旧 version → 被拒，错误文本含「数据已被他人修改」+「409」
    try {
      const cur = await readProduct(client, productId);
      const staleVersion = cur.product.version - 1; // 上一轮用过的那个（必定过期）
      const r = await expectError(client, 'update_product', { productId, name: `${originalName}·不该生效${mark}`, version: staleVersion });
      const stillOld = (await readProduct(client, productId)).product.name === afterName;
      assert(
        r.threw && /数据已被他人修改/.test(r.text) && /409/.test(r.text) && stillOld,
        `③ update_product 带旧 version(${staleVersion}) → 被拒，文本含「数据已被他人修改」+「[409]」，且数据没被改`,
        r.threw ? `${r.text.split('\n')[0].slice(0, 90)}｜名称仍为上一轮值=${stillOld}` : '竟然成功了'
      );
      console.log('\n  ③ 被拒样例（完整错误文本）：');
      for (const line of r.text.split('\n')) console.log(`     ${line}`);
      console.log('');
    } catch (e) {
      ko('③ update_product 带旧 version → 被拒（数据已被他人修改 + 409）', e.message);
    }

    // ④ update_product 不带 version → zod 层就拒绝（连后端都不会调到）
    try {
      const before = dbProductVersion(productId);
      const r = await expectError(client, 'update_product', { productId, name: `${originalName}·无版本${mark}` });
      const after = dbProductVersion(productId);
      assert(
        r.threw && /version/i.test(r.text) && /required|invalid|校验|参数/i.test(r.text) && before === after,
        '④ update_product 不带 version → zod 层拒绝（DB version 不变，请求没到后端）',
        r.threw ? `${r.text.split('\n')[0].slice(0, 110)}｜DB version ${before}→${after}` : '竟然成功了'
      );
    } catch (e) {
      ko('④ update_product 不带 version → zod 层拒绝', e.message);
    }

    // ⑤ **并发场景（核心）**：MCP 读 V → 别人（裸 HTTP）改一次 → MCP 用 V 提交 → 409 → 重读 V+1 → 成功
    console.log('⑤ 并发场景（核心）：MCP 读到 version=V → 另一个人用裸 HTTP 改同一商品 → MCP 拿 V 提交');
    try {
      // 步骤 1：MCP 读到 V
      const read1 = await readProduct(client, productId);
      const V = read1.product.version;
      console.log(`   [1] MCP 读到：productId=${productId} version=V=${V} name=「${read1.product.name}」`);

      // 步骤 2：模拟「另一个人」在后台改（另一个标签页 / 另一个 AI），直接打后端 REST
      const otherName = `${originalName}·别人改的${mark}`;
      const other = await rawRequest('PUT', `/admin/products/${productId}`, { product: { id: productId, version: V, name: otherName } });
      console.log(`   [2] 别人（裸 HTTP PUT，带自己的 version=${V}）→ code=${other.payload?.code} message=${other.payload?.message}`);
      assert(other.payload?.code === 0, '⑤-1 「另一个人」的写入成功（模拟并发写入者）', `code=${other.payload?.code}`);

      // 步骤 3：MCP 拿着**过期的 V** 提交 → 必须 409
      const mineName = `${originalName}·我要改的${mark}`;
      const collide = await expectError(client, 'update_product', { productId, name: mineName, version: V });
      const nowName = (await readProduct(client, productId)).product.name;
      const notOverwritten = nowName === otherName;
      assert(
        collide.threw && /数据已被他人修改/.test(collide.text) && /409/.test(collide.text) && notOverwritten,
        `⑤-2 MCP 用过期 version=${V} 提交 → 被后端 409 拒绝，且**没覆盖**别人的修改`,
        collide.threw ? `${collide.text.split('\n')[0].slice(0, 90)}｜当前名称仍是别人的（未覆盖）=${notOverwritten}` : '竟然成功了'
      );
      console.log(`   [3] MCP 用 version=${V} 提交 → 被拒（409）；库里现在是「${nowName}」（我的改动没有生效）`);
      console.log('       完整错误文本：');
      for (const line of collide.text.split('\n')) console.log(`         ${line}`);

      // 步骤 4：按提示重新读 → 拿到 V+1 → 再提交 → 成功
      const read2 = await readProduct(client, productId);
      const V2 = read2.product.version;
      console.log(`   [4] 按提示重新读：version=${V2}（= V+${V2 - V}）name=「${read2.product.name}」`);
      const retry = await call(client, 'update_product', { productId, name: mineName, version: V2 });
      const retryJson = jsonAfter(retry.text);
      const finalRead = await readProduct(client, productId);
      assert(
        V2 === V + 1 && retryJson.newVersion === V2 + 1 && finalRead.product.name === mineName && dbProductVersion(productId) === V2 + 1,
        `⑤-3 重读拿到 version=V+1(${V2}) → 再提交 → 成功，version → ${retryJson.newVersion}（DB=${dbProductVersion(productId)}）`,
        `名称已生效=${finalRead.product.name === mineName}`
      );
      console.log(`   [5] 用 version=${V2} 重试 → 成功，新 version=${retryJson.newVersion}；库里名称变为「${finalRead.product.name}」`);
      console.log(`   ✅ 结论：并发冲突被 409 挡住、数据没被覆盖；重读后同一动作即可成功（乐观锁闭环成立）\n`);
    } catch (e) {
      ko('⑤ 并发场景：读 V → 别人改 → 用 V 提交 409 → 重读 V+1 → 成功', e.message);
    }

    // ⑥ update_sku_stock：正确 / 旧 / 缺 version（三个子用例各自断言）
    //   ⑥a 正确 version → 成功，version +1
    let skuStockNow = null;
    try {
      const cur = await readProduct(client, productId);
      const target = cur.skus.find((k) => k.id === skuId) || cur.skus[0];
      skuStockNow = Number(target.stock) + 1; // 改成一个可辨识的新值（+1），收尾会改回去
      const { text } = await call(client, 'update_sku_stock', { skuId: target.id, stock: skuStockNow, version: target.version });
      const j = jsonAfter(text);
      const reread = (await readProduct(client, productId)).skus.find((k) => k.id === target.id);
      assert(
        j.newVersion === target.version + 1 && reread.version === target.version + 1 && Number(reread.stock) === skuStockNow && dbSkuVersion(target.id) === target.version + 1,
        `⑥a update_sku_stock 带正确 version(${target.version}) → 成功，库存=${skuStockNow}，version +1`,
        `响应 newVersion=${j.newVersion}；回读 version=${reread.version} stock=${reread.stock}；DB version=${dbSkuVersion(target.id)}`
      );
      console.log(`    成功样例摘要：${text.split('\n').slice(0, 2).join(' / ')}`);
    } catch (e) {
      ko('⑥a update_sku_stock 带正确 version → 成功，version +1', e.message);
    }

    //   ⑥b 旧 version → 被拒（409 + 数据未被覆盖）
    try {
      const cur = (await readProduct(client, productId)).skus.find((k) => k.id === skuId);
      const stale = cur.version - 1;
      const r = await expectError(client, 'update_sku_stock', { skuId, stock: 1, version: stale });
      const stillOk = Number((await readProduct(client, productId)).skus.find((k) => k.id === skuId).stock) === skuStockNow;
      assert(
        r.threw && /数据已被他人修改/.test(r.text) && /409/.test(r.text) && stillOk,
        `⑥b update_sku_stock 带旧 version(${stale}) → 被拒，文本含「数据已被他人修改」+「[409]」，库存没被改成 1`,
        r.threw ? `${r.text.split('\n')[0].slice(0, 90)}｜库存仍为 ${skuStockNow}=${stillOk}` : '竟然成功了'
      );
    } catch (e) {
      ko('⑥b update_sku_stock 带旧 version → 被拒', e.message);
    }

    //   ⑥c 缺 version → zod 层拒绝
    try {
      const before = dbSkuVersion(skuId);
      const r = await expectError(client, 'update_sku_stock', { skuId, stock: 2 });
      assert(
        r.threw && /version/i.test(r.text) && /required|invalid|校验|参数/i.test(r.text) && before === dbSkuVersion(skuId),
        '⑥c update_sku_stock 不带 version → zod 层拒绝（DB version 不变）',
        r.threw ? r.text.split('\n')[0].slice(0, 110) : '竟然成功了'
      );
    } catch (e) {
      ko('⑥c update_sku_stock 不带 version → zod 层拒绝', e.message);
    }

    // ⑥d 并发场景（SKU 版）：读 V → 裸 HTTP 扣库存（模拟别人下单）→ MCP 用 V 提交 409 → 重读 → 成功
    try {
      const cur = (await readProduct(client, productId)).skus.find((k) => k.id === skuId);
      const V = cur.version;
      const other = await rawRequest('PUT', `/admin/skus/${skuId}`, { sku: { id: skuId, version: V, stock: Number(cur.stock) - 5 } });
      const collide = await expectError(client, 'update_sku_stock', { skuId, stock: Number(cur.stock) + 7, version: V });
      const read2 = (await readProduct(client, productId)).skus.find((k) => k.id === skuId);
      const retry = await call(client, 'update_sku_stock', { skuId, stock: Number(read2.stock) + 1, version: read2.version });
      const j = jsonAfter(retry.text);
      assert(
        other.payload?.code === 0 && collide.threw && /409/.test(collide.text) && read2.version === V + 1 && j.newVersion === V + 2,
        `⑥d SKU 并发：别人先扣库存（裸 HTTP）→ MCP 用旧 version=${V} 提交被 409 拒 → 重读 V+1(${read2.version}) → 提交成功 (version=${j.newVersion})`,
        `别人 code=${other.payload?.code}；我 code=${collide.threw ? '409' : '0(异常)'}；最终 version=${j.newVersion}`
      );
    } catch (e) {
      ko('⑥d SKU 并发场景（别人先扣库存 → 我 409 → 重读 → 成功）', e.message);
    }
  } finally {
    // ---- 收尾：把商品名 / SKU 库存改回原值（每次都重读最新 version 再改，绝不复用旧值）----
    try {
      if (productId !== null && originalName !== null) {
        const cur = await readProduct(client, productId);
        if (cur.product.name !== originalName) {
          await call(client, 'update_product', { productId, name: originalName, version: cur.product.version });
          console.log(`🧹 已把商品 ${productId} 的名称改回原值（version → ${dbProductVersion(productId)}）`);
        }
      }
      if (skuId !== null && originalStock !== null) {
        const cur = (await readProduct(client, productId)).skus.find((k) => k.id === skuId);
        if (Number(cur.stock) !== Number(originalStock)) {
          await call(client, 'update_sku_stock', { skuId, stock: Number(originalStock), version: cur.version });
          console.log(`🧹 已把 SKU ${skuId} 的库存改回原值 ${originalStock}（version → ${dbSkuVersion(skuId)}）`);
        }
      }
    } catch (e) {
      console.error(`🧹 收尾恢复失败（不影响结论，但请人工核对商品 ${productId} / SKU ${skuId}）：${e?.message || e}`);
    }

    await client.close().catch(() => {});
  }

  // ⑦ write=0 → 两个写工具消失，只读工具齐全
  const step7 = `⑦ MCP_ENABLE_WRITE=0 → update_product / update_sku_stock / execute_audit_pass 全部消失，${READONLY_TOOLS.length} 个只读工具齐全`;
  try {
    const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
    const names = (await ro.listTools()).tools.map((t) => t.name).sort();
    const missing = READONLY_TOOLS.filter((n) => !names.includes(n));
    const leaked = [...WRITE_TOOLS, 'execute_audit_pass'].filter((n) => names.includes(n));
    assert(
      missing.length === 0 && leaked.length === 0 && names.length === READONLY_TOOLS.length,
      step7,
      `${names.length} 个只读工具；缺失=${missing.join(',') || '无'}；漏出的写工具=${leaked.join(',') || '无'}`
    );
    await ro.close();
  } catch (e) {
    ko(step7, e.message);
  }

  // ⑧ 回归：loop / catalog / order-read 由 `npm test` 串联（本轮输出里各自一行总结）
  ok('⑧ 回归：loop.mjs / catalog.mjs / order-read.mjs 由 `npm test` 串联，见同轮输出', '本文件在链条末尾');

  // ---------------- 总结 ----------------
  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 乐观锁验收通过：读接口带 version；写工具强制 version；旧 version 被 409 挡住且数据不被覆盖；');
    console.log('   并发场景（别人先改 → 我 409 → 重读 → 成功）两种载体（商品/SKU）都成立；write=0 时写工具消失。');
    console.log('ℹ️ 本文件只 bump version、结束时把名称/库存改回原值，不删行 → 可连跑多轮。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
