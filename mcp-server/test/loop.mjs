#!/usr/bin/env node
// 闭环测试：用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言 11 步。
//
// 数据自给自足（见 test/_setup.mjs）：每次运行自己造一笔「待审核」订单（A：2 个不同仓的 SKU
// → 多个包裹），只对**自己造的这笔**做绝对断言，跑完把自己造的单删掉
// → 不需要 scripts/reset-test-db.sh，也不会和并行任务打架。
//
// ── 本版改了什么（确认码整套已删除，并发控制只留 version 乐观锁）────────────
//   ① preview_split 不再返回 confirmCode / expiresInSec，只返回 willDo（中文「将做什么」）+ plan；
//   ② execute_audit_pass 参数回到 `{ orderId }` —— 不再需要任何凭证（schema 里就一个必填项）；
//   ③ 原来测「缺码被拒 / 假码被拒 / 同码复用 / 跨单用码」的四条断言全部删掉
//      （码已经不存在了，测它没有意义），换成：
//        · 预演返回 willDo + 方案（②/②b）
//        · 执行成功（⑤）
//        · **重复执行被业务守卫拦**（⑧：订单已不是待审核 → 「只有待审核订单可以审核通过」）
//   ④ 并发保护的责任边界变了：不再靠「一次性票据」，而是靠后端业务守卫（仅 status=7）+ 热表 version
//      乐观锁 —— 所以 loop 只验「状态守卫」，version 那套在 test/optimistic-lock.mjs 里专门验。
//
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { bootstrap, connectServer, call, expectError, jsonAfter, sql, DB, MALL_BASE, MALL_API_KEY } from './_setup.mjs';

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
const deliveryCountOf = (id) => Number(sql(`SELECT COUNT(*) FROM ${DB}.delivery_order WHERE order_id = ${Number(id)}`));
const orderStatus = (id) => Number(sql(`SELECT status FROM ${DB}.\`order\` WHERE id = ${Number(id)}`));

// ---------------- 写开关应保留的只读工具清单（订单域 6 + 商品域 6 + 第 4 批 9） ----------------
// 第 4 批是「一个资源一个工具、action 区分动作」的形态：write=0 时工具仍在，但写动作从 action 枚举里摘掉
// （各域自己的测试会断言「枚举里没有写动作」「调写动作报 MCP_ENABLE_WRITE」）。
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
  'delivery',
  'return_apply',
  'return_reason',
  'warehouse',
  'order_setting',
  'member',
  'member_address',
  'member_cart',
  'dashboard_overview',
];
/** 写工具（write=1 才注册）：订单域 1 + 商品域 2 —— 都不该出现在 write=0 的清单里 */
const WRITE_TOOLS = ['execute_audit_pass', 'update_product', 'update_sku_stock'];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}`);

  // 自建数据：一笔待审核订单（2 个不同仓的 SKU、qty=2 → 预期拆成多个包裹）
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
    let plan = null;
    let before = 0;

    // ① 待审核列表：**必须包含我造的这笔**，且返回的每笔都是 status=7
    try {
      const { text } = await call(client, 'list_pending_orders', {});
      const records = jsonAfter(text, 'JSON:');
      const mine = records.find((o) => o.id === ORDER_ID);
      assert(
        records.length > 0 && records.every((o) => o.status === 7) && !!mine && mine.orderSn === ctx.orderSn,
        `① list_pending_orders 含我造的订单 id=${ORDER_ID}，且每笔都 status===7`,
        `共 ${records.length} 笔，id=${records.map((o) => o.id).join(',')}`
      );
    } catch (e) {
      ko('① list_pending_orders 含我造的订单，且每笔都 status===7', e.message);
    }

    // ② preview_split → { willDo(中文) , plan }，且**不再有** confirmCode / expiresInSec
    try {
      const { text } = await call(client, 'preview_split', { orderId: ORDER_ID });
      const env = jsonAfter(text);
      plan = env.plan;
      const parcelsOk = ctx.expectedParcels === null ? plan.length > 0 : plan.length === ctx.expectedParcels;
      const hasWillDo = typeof env.willDo === 'string' && env.willDo.length > 10 && /审核通过|待发货|发货单/.test(env.willDo);
      const noCode = !('confirmCode' in env) && !('expiresInSec' in env) && !/confirmCode/i.test(text);
      assert(
        Array.isArray(plan) && parcelsOk && hasWillDo && noCode,
        `② preview_split → willDo（中文预演描述）+ plan=${ctx.expectedParcels ?? '>0'} 个包裹，且**不含** confirmCode/expiresInSec`,
        `plan=${plan.length} 个包裹，willDo=「${String(env.willDo).slice(0, 40)}…」，含 confirmCode=${/confirmCode/i.test(text)}`
      );
    } catch (e) {
      ko('② preview_split → willDo + plan，且不含 confirmCode', e.message);
    }

    // ②b execute_audit_pass 的参数只剩 orderId（确认码从 schema 里彻底移除）
    try {
      const { tools } = await client.listTools();
      const t = tools.find((x) => x.name === 'execute_audit_pass');
      const required = t?.inputSchema?.required ?? [];
      const props = Object.keys(t?.inputSchema?.properties ?? {});
      assert(
        !!t && required.length === 1 && required[0] === 'orderId' && !props.includes('confirmCode'),
        '②b execute_audit_pass 入参只剩必填 orderId（confirmCode 已从 schema 移除）',
        `required=[${required.join(',')}] props=[${props.join(',')}]`
      );
    } catch (e) {
      ko('②b execute_audit_pass 入参只剩必填 orderId', e.message);
    }

    // ③ 预演幂等：再预览一次，包裹数不变（预演不消费任何东西 —— 这正是不再需要「一次性码」的原因）
    try {
      const again = jsonAfter((await call(client, 'preview_split', { orderId: ORDER_ID })).text);
      assert(
        again.plan.length === plan?.length && typeof again.willDo === 'string',
        '③ 重复 preview_split → 方案包裹数不变（预演无副作用、可反复调用）',
        `${again.plan.length} = ${plan?.length}`
      );
    } catch (e) {
      ko('③ 重复 preview_split → 方案包裹数不变', e.message);
    }

    // ④ 预览不落库（预演只读，delivery_order 没变）
    try {
      before = deliveryCount();
      const mine = deliveryCountOf(ORDER_ID);
      assert(mine === 0, '④ 预览不落库：我造的单仍是 0 个发货单', `delivery_order 全库 ${before} 行，其中本单 ${mine}`);
    } catch (e) {
      ko('④ 预览不落库：我造的单仍是 0 个发货单', e.message);
    }

    // ⑤ 执行成功：execute_audit_pass { orderId } —— 不带任何凭证
    let delivered = 0;
    try {
      const { text } = await call(client, 'execute_audit_pass', { orderId: ORDER_ID });
      const deliveries = jsonAfter(text, '发货单 JSON:');
      delivered = deliveries.length;
      assert(
        plan && delivered === plan.length,
        '⑤ execute_audit_pass { orderId }（无凭证）→ 成功，deliveries.length === plan.length',
        `deliveries=${delivered}, plan=${plan?.length}`
      );
    } catch (e) {
      ko('⑤ execute_audit_pass { orderId } → deliveries.length === plan.length', e.message);
    }

    // ⑥ 真落库
    try {
      const now = deliveryCount();
      assert(
        now === before + (plan?.length ?? 0),
        '⑥ 真落库：delivery_order 行数 === before + plan.length',
        `before=${before}, now=${now}, 期望=${before + plan.length}`
      );
    } catch (e) {
      ko('⑥ 真落库：delivery_order 行数 === before + plan.length', e.message);
    }

    // ⑦ 订单状态 8
    try {
      const st = orderStatus(ORDER_ID);
      assert(st === 8, `⑦ 我造的订单 ${ORDER_ID} status 变 8（待发货）`, `status=${st}`);
    } catch (e) {
      ko(`⑦ 我造的订单 ${ORDER_ID} status 变 8`, e.message);
    }

    // ⑧ **重复执行被业务守卫拦**（替代原来的「同码复用被拒」）
    //    确认码没了，重复执行能拦住靠的是后端状态守卫：订单已是 8，不再是待审核
    try {
      const before2 = deliveryCountOf(ORDER_ID);
      const r = await expectError(client, 'execute_audit_pass', { orderId: ORDER_ID });
      const after2 = deliveryCountOf(ORDER_ID);
      assert(
        r.threw && /只有待审核订单可以审核通过/.test(r.text) && after2 === before2,
        '⑧ 重复执行 execute_audit_pass → 被业务守卫拦「只有待审核订单可以审核通过」，且没多生成发货单',
        r.threw ? `${r.text.split('\n')[0].slice(0, 90)}；发货单 ${before2}→${after2}` : '竟然成功了'
      );
    } catch (e) {
      ko('⑧ 重复执行被业务守卫拦（只有待审核订单可以审核通过）', e.message);
    }

    // ⑨ 回读一致（闭环）
    try {
      const { text } = await call(client, 'list_deliveries', { orderId: ORDER_ID });
      const rows = jsonAfter(text, 'JSON:');
      assert(
        rows.length === plan?.length,
        '⑨ list_deliveries → 条数 === plan.length（闭环一致）',
        `deliveries=${rows.length}, plan=${plan?.length}, sn=${rows.map((d) => d.deliverySn).join(',')}`
      );
    } catch (e) {
      ko('⑨ list_deliveries → 条数 === plan.length（闭环一致）', e.message);
    }

    await client.close();
    client = null;

    // ⑩ 写开关：MCP_ENABLE_WRITE=0 → 只剩只读工具（订单 6 + 商品 6 + 第 4 批 9），3 个写工具全部消失
    const step10 =
      `⑩ MCP_ENABLE_WRITE=0 → tools/list 只剩 ${READONLY_TOOLS.length} 个只读工具（订单 6 + 商品 6 + 第 4 批 9），` +
      `写工具（${WRITE_TOOLS.join(' / ')}）全部消失`;
    try {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
      const { tools } = await ro.listTools();
      const names = tools.map((t) => t.name).sort();
      const leaked = WRITE_TOOLS.filter((n) => names.includes(n));
      const pass = tools.length === READONLY_TOOLS.length && READONLY_TOOLS.every((n) => names.includes(n)) && leaked.length === 0;
      assert(pass, step10, `${tools.length} 个：${names.join(', ')}｜漏出的写工具=${leaked.join(',') || '无'}`);
      await ro.close();
    } catch (e) {
      ko(step10, e.message);
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
    console.log('✅ 闭环通过：查询 → 预演（willDo + 方案，不落库）→ 执行（只要 orderId，真落库）→ 回读一致；');
    console.log('   重复执行被业务守卫拦住（确认码已删，靠 status 守卫 + version 乐观锁）；写开关生效。');
    console.log('ℹ️ 本次数据自建自清（订单已删除），无残留；直接重跑即可，不需要 reset-test-db.sh。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
