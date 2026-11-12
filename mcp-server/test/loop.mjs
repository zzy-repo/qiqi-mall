#!/usr/bin/env node
// 闭环测试：用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言 12 步。
//
// 数据自给自足（见 test/_setup.mjs）：每次运行自己造一笔「待审核」订单（2 个 SKU → 2 个包裹），
// 只对**自己造的这笔**做绝对断言（id / 单号 / status 7→8 / 包裹数），
// 不再断言「库里有 2 笔待审核」「发货单数从 0 开始」——那种断言依赖外部状态，跑第二遍必红。
// 跑完把自己造的单删掉，所以**不需要** scripts/reset-test-db.sh，也不会和并行任务打架。
//
// 前置：后端 18080 可达 + 管理端账号可用（MALL_USER/MALL_PASS，默认 ai_operator）。

import { bootstrap, connectServer, call, expectError, jsonAfter, sql, DB, MALL_BASE, MALL_USER } from './_setup.mjs';

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
const deliveryCount = () => Number(sql(`SELECT COUNT(*) FROM ${DB}.delivery_order`));
const orderStatus = (id) => Number(sql(`SELECT status FROM ${DB}.\`order\` WHERE id = ${Number(id)}`));

/** 从工具文本里取 confirm_token */
const tokenOf = (text) => {
  const m = text.match(/confirm_token:\s*([0-9a-fA-F-]{36})/);
  if (!m) throw new Error('preview_split 响应里没有 confirm_token');
  return m[1];
};

// ---------------- 写开关应保留的只读工具清单（订单域 6 + 商品域 6） ----------------
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
  console.log(`▶ 目标：${MALL_BASE} | 账号：${MALL_USER} | 库：${DB}`);

  // 自建数据：一笔待审核订单（2 个不同仓的 SKU、每个 qty=2 → 预期拆成 2 个包裹）
  let ctx;
  try {
    ctx = await bootstrap({ qty: 2, skuCount: 2, autoCleanup: true });
  } catch (e) {
    console.error(`❌ 造测试数据失败（_setup.bootstrap）：${e.message}`);
    process.exit(1);
  }
  const ORDER_ID = ctx.orderId;
  console.log(
    `  自建待审核订单：id=${ORDER_ID} 单号=${ctx.orderSn} SKU=${ctx.skuIds.join(',')} ` +
      `收货人=${ctx.receiverName} 预期包裹=${ctx.expectedParcels ?? '(未知)'} | 凭证=${ctx.authMode}\n`
  );

  let client;
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'rw');
    let plan, token, before;

    // ① 待审核列表：**必须包含我造的这笔**，且返回的每笔都是 status=7
    try {
      const { text } = await call(client, 'list_pending_orders', {});
      const records = jsonAfter(text, 'JSON:');
      const mine = records.find((o) => o.id === ORDER_ID);
      assert(
        records.length > 0 && records.every((o) => o.status === 7) && !!mine && mine.orderSn === ctx.orderSn,
        `① list_pending_orders 含我造的这笔（id=${ORDER_ID} 且单号一致），且每笔都 status===7`,
        `共 ${records.length} 笔，id=${records.map((o) => o.id).join(',')}`
      );
    } catch (e) {
      ko('① list_pending_orders 含我造的这笔（id 精确匹配），且每笔都 status===7', e.message);
    }

    // ② 记录 before（并确认我造的这笔确实是待审核）
    try {
      before = deliveryCount();
      const st = orderStatus(ORDER_ID);
      if (st !== 7) throw new Error(`我造的订单 ${ORDER_ID} 当前 status=${st}，不是 7（造单逻辑或并发任务有问题）`);
      ok('② 我造的订单状态=7（待审核），并记录 delivery_order 行数 before', `before=${before}, order ${ORDER_ID} status=7`);
    } catch (e) {
      ko('② 我造的订单状态=7（待审核），并记录 delivery_order 行数 before', e.message);
    }

    // ③ preview_split
    try {
      const { text } = await call(client, 'preview_split', { orderId: ORDER_ID });
      plan = jsonAfter(text, '方案 JSON:');
      token = tokenOf(text);
      const parcelsOk = ctx.expectedParcels === null ? plan.length > 0 : plan.length === ctx.expectedParcels;
      assert(
        parcelsOk && !!token,
        `③ preview_split → 方案 ${ctx.expectedParcels ?? '>0'} 个包裹（按我造的 SKU 分仓数算）且拿到 confirmToken`,
        `plan=${plan.length} 个包裹，token=${token.slice(0, 8)}…`
      );
    } catch (e) {
      ko('③ preview_split → 方案包裹数符合预期且拿到 confirmToken', e.message);
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
      assert(st === 8, `⑨ 我造的订单 ${ORDER_ID} status 变 8`, `status=${st}`);
    } catch (e) {
      ko(`⑨ 我造的订单 ${ORDER_ID} status 变 8`, e.message);
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
    client = null;

    // ⑫ 写开关：MCP_ENABLE_WRITE=0 → 只剩只读工具（订单 6 个 + 商品域 6 个），写工具消失
    // ⚠️ 第 3 批改动：订单域只读工具由 2 → 6（新增 list_orders / get_order_detail / count_deliveries），
    //    同时把只读的 list_deliveries 从「写开关门后面」挪到门前面（此前 write=0 时它会连带消失，是第 1 批的疏漏）。
    //    所以这里的只读清单从 8 个变成 12 个，闭环本身（①~⑪）一字未改。
    const step12 = `⑫ MCP_ENABLE_WRITE=0 → tools/list 只剩 ${READONLY_TOOLS.length} 个只读工具（订单 6 + 商品 6），写工具消失`;
    try {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
      const { tools } = await ro.listTools();
      const names = tools.map((t) => t.name).sort();
      const pass =
        tools.length === READONLY_TOOLS.length &&
        READONLY_TOOLS.every((n) => names.includes(n)) &&
        !names.includes('execute_audit_pass');
      assert(pass, step12, `${tools.length} 个：${names.join(', ')}`);
      await ro.close();
    } catch (e) {
      ko(step12, e.message);
    }
  } finally {
    if (client) await client.close().catch(() => {});
    // 清理自己造的数据（订单 + 明细 + 发货单 + 支付流水 + 一次性买家）
    if (ctx?.cleanup) {
      const r = await ctx.cleanup();
      console.log(`🧹 清理自建数据：${r.notes.join('；')}`);
    }
  }

  // ---------------- 总结 ----------------
  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 闭环通过：查询 → 预览（不落库）→ 确认执行（真落库）→ 回读一致；写开关生效。');
    console.log('ℹ️ 本次数据自建自清（订单已删除），无残留；直接重跑即可，不需要 reset-test-db.sh。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
