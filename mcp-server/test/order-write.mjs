#!/usr/bin/env node
// 第 5 批（覆盖补齐）· 订单域写工具测试：create_order / update_order / audit_reject_order / ship_order / delete_order。
//
// 缺口来源：后端 OrderController 有 9 个端点，之前 MCP 只覆盖了 4 个（list / get / audit-pass / split-preview），
// 后台订单页的「新增订单 / 编辑订单 / 审核不通过 / 发货 / 删除订单」五个按钮在 MCP 侧是空白 —— 本文件验收这 5 个新工具。
//
// 逐条断言：
//   ① tools/list 含 5 个新写工具（中文 title/description），且 write=0 时全部消失
//   ② create_order：协议层必填（缺 items / 缺收货人）→ 被 zod 拒；正常建单 → 金额=明细单价×数量（后端重算）、version=0
//   ③ update_order：假 version → 409 且**数据一个字段都没被覆盖**；正确 version → 成功、version +1（回读 + DB 双确认）
//   ④ ship_order：状态守卫（只有已付款能发）→ dryRun 不落库 → 真执行 1→2 → 重复被拒
//   ⑤ audit_reject_order：dryRun 不落库 → 真执行 7→4，并把总池库存回补（回补量原样减回去，不污染共用测试库）
//   ⑥ delete_order：dryRun 不落库（订单还在）→ 真执行后订单消失（级联删明细/发货单）
//
// 数据自给自足：本文件自己建单（自带唯一单号戳），跑完删干净；连跑两轮不需要 reset 库。
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { adminRequest, connectServer, call, expectError, jsonAfter, sql, DB, MALL_BASE, MALL_API_KEY } from './_setup.mjs';

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

const WRITE_TOOLS = ['create_order', 'update_order', 'audit_reject_order', 'ship_order', 'delete_order'];

// 唯一单号（秒级时间戳 + 3 位随机），并发跑同一库不撞单
const stamp = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}${String(
    Math.floor(Math.random() * 1000)
  ).padStart(3, '0')}`;
};
const dbOrder = (id) => {
  const out = sql(
    `SELECT order_sn, status, total_amount, receiver_name, version FROM ${DB}.\`order\` WHERE id = ${Number(id)}`
  );
  if (!out) return null;
  const [orderSn, status, totalAmount, receiverName, version] = out.split('\t');
  return { orderSn, status: Number(status), totalAmount: Number(totalAmount), receiverName, version: Number(version) };
};
const skuRow = (id) => {
  const out = sql(`SELECT stock, version FROM ${DB}.sku WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [stock, version] = out.split('\t').map(Number);
  return { stock, version };
};

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 订单域写工具测试（第 5 批覆盖补齐）| 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}`);

  // 造单需要一个真实会员（order.member_id 是 NOT NULL 外键）与一个真实 SKU
  const members = await adminRequest('GET', '/admin/members');
  const member = (Array.isArray(members) ? members : [])[0];
  if (!member) throw new Error('测试库里没有任何会员，无法建单（order.member_id 是 NOT NULL 外键）');
  const products = await adminRequest('GET', '/admin/products?publishStatus=1');
  const product = (Array.isArray(products) ? products : [])[0];
  if (!product) throw new Error('测试库里没有在架商品，无法挑 SKU 建单');
  const skus = await adminRequest('GET', `/admin/skus?productId=${product.id}`);
  const sku = (Array.isArray(skus) ? skus : []).find((s) => Number(s.stock) > 0) ?? (Array.isArray(skus) ? skus[0] : null);
  if (!sku) throw new Error(`商品 ${product.id} 下没有 SKU，无法建单`);

  const PRICE = 9.9;
  const QTY = 2;
  const item = { productSkuId: Number(sku.id), productId: Number(product.id), productName: product.name, productPrice: PRICE, productQuantity: QTY };
  console.log(`  夹具：会员 id=${member.id}｜商品 id=${product.id}「${product.name}」｜SKU id=${sku.id}｜单价 ${PRICE} × ${QTY}\n`);

  const createdOrders = [];
  let client;
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'order-write');
    const samples = [];

    /** 建一笔单（走 MCP 工具本身，不直连后端 —— 要验的就是这个工具） */
    const makeOrder = async (status, tag) => {
      const { text } = await call(client, 'create_order', {
        orderSn: `QQW${stamp()}${tag}`,
        memberId: Number(member.id),
        status,
        payType: status >= 1 ? 1 : 0,
        receiverName: `测试收件人${tag}`,
        receiverPhone: '13800000000',
        receiverAddress: '浙江省杭州市西湖区测试路 1 号',
        items: [item],
      });
      const j = jsonAfter(text);
      if (j?.id) createdOrders.push(j.id);
      return { j, text };
    };

    // ① 工具清单
    await check('① tools/list 含 5 个新写工具（中文 title/description），write=0 时全部消失', async () => {
      const { tools } = await client.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const allThere = WRITE_TOOLS.every((n) => by[n] && /[\u4e00-\u9fa5]/.test(by[n].title || '') && (by[n].description || '').length > 40);
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'order-write-ro');
      const roNames = (await ro.listTools()).tools.map((t) => t.name);
      await ro.close();
      const leaked = WRITE_TOOLS.filter((n) => roNames.includes(n));
      return {
        pass: allThere && leaked.length === 0,
        detail: `write=1 工具共 ${tools.length} 个；5 个新工具齐=${allThere}；write=0 漏出=${leaked.join(',') || '无'}`,
      };
    });

    // ② create_order 参数边界 + 建单
    await check('②a create_order 缺 items → 协议层拒；缺 memberId → 工具层中文错误（后端 member_id NOT NULL）', async () => {
      const r = await expectError(client, 'create_order', {
        receiverName: '缺明细',
        receiverPhone: '13800000000',
        receiverAddress: '浙江省杭州市测试路 2 号',
      });
      const r2 = await expectError(client, 'create_order', {
        receiverName: '缺会员',
        receiverPhone: '13800000000',
        receiverAddress: '浙江省杭州市测试路 2 号',
        items: [item],
      });
      return {
        pass: r.threw && r2.threw && /memberId/.test(r2.text),
        detail: `缺 items→${r.text.split('\n')[0].slice(0, 50)}｜缺 memberId→${r2.text.split('\n')[0].slice(0, 60)}`,
      };
    });

    let orderA = null;
    await check('②b create_order 正常建单（status=0 待付款）→ 金额按明细重算=19.8、version=0、库里有行', async () => {
      const { j, text } = await makeOrder(0, 'A');
      orderA = j;
      const row = dbOrder(j.id);
      samples.push({ tool: 'create_order', args: { status: 0, items: 1 }, line: text.split('\n')[0] });
      return {
        pass:
          !!row &&
          row.status === 0 &&
          Math.abs(row.totalAmount - PRICE * QTY) < 0.001 &&
          row.version === 0 &&
          String(j.orderSn || '').startsWith('QQW') &&
          text.includes('✅'),
        detail: `id=${j.id} 单号=${j.orderSn} 库内金额=${row?.totalAmount}（期望 ${PRICE * QTY}）version=${row?.version}`,
      };
    });

    await check('②c get_order_detail 能回读到这笔单（收货人/明细/状态对得上）', async () => {
      const j = jsonAfter((await call(client, 'get_order_detail', { orderId: orderA.id })).text);
      const o = j.order ?? j;
      const items = j.items ?? o.items ?? [];
      return {
        pass: Number(o.id) === Number(orderA.id) && o.receiverName === '测试收件人A' && Number(o.status) === 0 && items.length === 1,
        detail: `收货人=${o.receiverName} 状态=${o.status} 明细=${items.length} 条 version=${o.version}`,
      };
    });

    // ③ update_order：版本乐观锁
    await check('③a update_order 带**假 version** → 409「数据已被他人修改」，且收货人一个字段都没被覆盖', async () => {
      const r = await expectError(client, 'update_order', { orderId: orderA.id, version: 999, receiverName: '不该被写进去' });
      const row = dbOrder(orderA.id);
      return {
        pass: r.threw && /409|已被他人修改/.test(r.text) && row.receiverName === '测试收件人A',
        detail: `库里收货人=${row.receiverName}（未被覆盖）｜${r.text.split('\n')[0].slice(0, 70)}`,
      };
    });

    await check('③b update_order 带正确 version=0 → 成功、回读收货人已改、version 0→1', async () => {
      const { text } = await call(client, 'update_order', {
        orderId: orderA.id,
        version: 0,
        receiverName: '测试收件人A改',
        receiverAddress: '浙江省杭州市西湖区改后地址 9 号',
      });
      const row = dbOrder(orderA.id);
      samples.push({ tool: 'update_order', args: { orderId: orderA.id, version: 0 }, line: text.split('\n')[0] });
      return {
        pass: row.receiverName === '测试收件人A改' && row.version === 1 && text.includes('✅'),
        detail: `库内收货人=${row.receiverName} version=${row.version}（期望 1）`,
      };
    });

    // ④ ship_order
    await check('④a ship_order 对「待付款(0)」的单 → 被状态守卫拒（只有已付款能发货）', async () => {
      const r = await expectError(client, 'ship_order', { orderId: orderA.id });
      return { pass: r.threw && /已付款/.test(r.text), detail: r.text.split('\n')[0].slice(0, 90) };
    });

    let orderB = null;
    await check('④b ship_order dryRun=true（status=1 已付款）→ willDo 写明「已发货」，库里状态仍是 1', async () => {
      const { j } = await makeOrder(1, 'B');
      orderB = j;
      const { text } = await call(client, 'ship_order', { orderId: orderB.id, dryRun: true });
      const preview = jsonAfter(text);
      const row = dbOrder(orderB.id);
      samples.push({ tool: 'ship_order(dryRun)', args: { orderId: orderB.id }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof preview?.willDo === 'string' && /已发货/.test(preview.willDo) && row.status === 1,
        detail: `willDo=${(preview?.willDo || '').slice(0, 80)}…｜库里状态=${row.status}（未变）`,
      };
    });

    await check('④c ship_order 真执行 → 状态 1→2；重复执行被状态守卫拒', async () => {
      const { text } = await call(client, 'ship_order', { orderId: orderB.id });
      const row = dbOrder(orderB.id);
      const again = await expectError(client, 'ship_order', { orderId: orderB.id });
      return {
        pass: row.status === 2 && text.includes('✅') && again.threw && /已付款/.test(again.text),
        detail: `库内状态=${row.status}（期望 2）｜重复：${again.text.split('\n')[0].slice(0, 50)}`,
      };
    });

    // ⑤ audit_reject_order（7 → 4，回补库存）
    let orderC = null;
    await check('⑤a audit_reject_order dryRun=true（status=7 待审核）→ willDo 写明「回补库存」，库里状态仍是 7', async () => {
      const { j } = await makeOrder(7, 'C');
      orderC = j;
      const { text } = await call(client, 'audit_reject_order', { orderId: orderC.id, dryRun: true });
      const preview = jsonAfter(text);
      const row = dbOrder(orderC.id);
      samples.push({ tool: 'audit_reject_order(dryRun)', args: { orderId: orderC.id }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof preview?.willDo === 'string' && /库存/.test(preview.willDo) && row.status === 7,
        detail: `willDo=${(preview?.willDo || '').slice(0, 80)}…｜库里状态=${row.status}（未变）`,
      };
    });

    await check('⑤b audit_reject_order 真执行 → 状态 7→4（已关闭），并把总池库存回补（回补量已减回）', async () => {
      const before = skuRow(sku.id);
      const { text } = await call(client, 'audit_reject_order', { orderId: orderC.id });
      const row = dbOrder(orderC.id);
      const after = skuRow(sku.id);
      // 后端 restoreStock 加回了 QTY 件；本测试造单没扣过库存（后台建单不扣），所以原样减回去
      const restored = after.stock - before.stock;
      await adminRequest('PUT', `/admin/skus/${sku.id}`, { sku: { id: Number(sku.id), version: after.version, stock: after.stock - restored } });
      return {
        pass: row.status === 4 && restored === QTY && text.includes('✅'),
        detail: `库内状态=${row.status}（期望 4）｜库存回补=${restored} 件（已减回）`,
      };
    });

    // ⑥ delete_order
    await check('⑥a delete_order dryRun=true → willDo 写明「级联删除明细/发货单」，订单仍在', async () => {
      const { text } = await call(client, 'delete_order', { orderId: orderA.id, dryRun: true });
      const preview = jsonAfter(text);
      const row = dbOrder(orderA.id);
      samples.push({ tool: 'delete_order(dryRun)', args: { orderId: orderA.id }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof preview?.willDo === 'string' && /级联删除/.test(preview.willDo) && !!row,
        detail: `willDo=${(preview?.willDo || '').slice(0, 90)}…｜订单仍在=${!!row}`,
      };
    });

    await check('⑥b delete_order 真执行 → 订单从库里消失（get_order_detail 回读报「订单不存在」）', async () => {
      const { text } = await call(client, 'delete_order', { orderId: orderA.id });
      const row = dbOrder(orderA.id);
      const reread = await expectError(client, 'get_order_detail', { orderId: orderA.id });
      return {
        pass: !row && text.includes('✅') && reread.threw,
        detail: `库内行=${row ? '仍在' : '已删'}｜回读：${reread.text.split('\n')[0].slice(0, 60)}`,
      };
    });

    console.log('\n调用样例（首行摘要）：');
    for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${s.line.replace(/\s+/g, ' ').trim().slice(0, 140)}`);
  } finally {
    if (client) await client.close().catch(() => {});
    // 清理：删掉本次建的全部订单（B/C 还在；A 已由 ⑥b 删掉）
    for (const id of [...new Set(createdOrders)]) {
      try {
        await adminRequest('DELETE', `/admin/orders/${id}`);
        console.log(`🧹 已删除自建订单 ${id}`);
      } catch (e) {
        if (/不存在/.test(e.message)) continue;
        console.log(`  （清理订单 ${id} 跳过：${e.message}）`);
      }
    }
  }

  // ---------------- 总结 ----------------
  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 订单域 5 个新写工具全部可用：建单金额后端重算、改单 version 乐观锁、发货/审核不通过有状态守卫、');
    console.log('   三个 L3 动作 dryRun 预演不落库、删除级联；写开关=0 时 5 个工具整体消失。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
