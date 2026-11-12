#!/usr/bin/env node
// 闭环测试：用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言 12 步。
//
// 数据自给自足（见 test/_setup.mjs）：每次运行自己造「待审核」订单（A：2 个 SKU → 多个包裹；
// B：1 个 SKU，专门用来验「A 单的码不能用到 B 单」），只对**自己造的这两笔**做绝对断言，
// 跑完把自己造的单删掉 → 不需要 scripts/reset-test-db.sh，也不会和并行任务打架。
//
// ── 本版改了什么（后端已把确认码收到服务端） ────────────────────────────
//   ① 认证：管理端一律走 API Key（MALL_API_KEY），不再登录拿 JWT；
//   ② preview_split 的返回从裸数组变成 { plan, confirmCode, expiresInSec }（后端签发，KV 存 5 分钟）；
//   ③ execute_audit_pass 从 { token } 改成 { orderId, confirmCode } ——
//      码→订单的映射在后端 KV 里，MCP 解不出来，所以 orderId 必须由调用方一起给（方案 A），
//      后端负责校验「码里的 orderId == 路径 id」（= 下面第 ⑩ 条）；
//   ④ MCP 自造的 src/confirm.mjs 已删除（一次性/过期/指纹比对全部由后端做）。
//
// 断言编号与验收单一致；其中 ⑩（跨单用码）会**消耗掉**手里的码，所以它排在 ⑥ 之前执行，
// 执行完再 preview_split 一次拿新码 —— 顺序是按「码只能用一次」这个真实语义排的，编号不变。
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

/** 后端签发的 confirmCode 是标准 UUID（v4）；用它做「不是空串/不是自家造的串」的强校验 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 从 preview_split 的文本里取摘要行上的 confirmCode（顺带验证「摘要里真的有码」这件事） */
const codeLineOf = (text) => {
  const m = String(text).match(/confirmCode:\s*([0-9a-zA-Z-]+)/);
  return m ? m[1] : null;
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
  console.log(`▶ 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}`);

  // 自建数据：A（2 个不同仓的 SKU、qty=2 → 预期拆成多个包裹）+ B（只用来看「跨单用码被拒」）
  let ctxA;
  let ctxB;
  try {
    ctxA = await bootstrap({ qty: 2, skuCount: 2, autoCleanup: true });
    ctxB = await bootstrap({ qty: 1, skuCount: 1, autoCleanup: true });
  } catch (e) {
    console.error(`❌ 造测试数据失败（_setup.bootstrap）：${e.message}`);
    process.exit(1);
  }
  const ORDER_ID = ctxA.orderId;
  const ORDER_B = ctxB.orderId;
  console.log(
    `  自建待审核订单 A：id=${ORDER_ID} 单号=${ctxA.orderSn} SKU=${ctxA.skuIds.join(',')} ` +
      `收货人=${ctxA.receiverName} 预期包裹=${ctxA.expectedParcels ?? '(未知)'} | 凭证=${ctxA.authMode}`
  );
  console.log(`  自建待审核订单 B：id=${ORDER_B} 单号=${ctxB.orderSn}（只用于「跨单用码被拒」）\n`);

  let client;
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'rw');
    let plan, confirmCode, before;

    // ① 待审核列表：**必须包含我造的两笔**，且返回的每笔都是 status=7
    try {
      const { text } = await call(client, 'list_pending_orders', {});
      const records = jsonAfter(text, 'JSON:');
      const mineA = records.find((o) => o.id === ORDER_ID);
      const mineB = records.find((o) => o.id === ORDER_B);
      assert(
        records.length > 0 &&
          records.every((o) => o.status === 7) &&
          !!mineA &&
          mineA.orderSn === ctxA.orderSn &&
          !!mineB &&
          mineB.orderSn === ctxB.orderSn,
        `① list_pending_orders 含我造的 A(id=${ORDER_ID})/B(id=${ORDER_B}) 两笔，且每笔都 status===7`,
        `共 ${records.length} 笔，id=${records.map((o) => o.id).join(',')}`
      );
    } catch (e) {
      ko('① list_pending_orders 含我造的 A/B 两笔，且每笔都 status===7', e.message);
    }

    // ② preview_split → { plan, confirmCode(UUID), expiresInSec:300 }
    try {
      const { text } = await call(client, 'preview_split', { orderId: ORDER_ID });
      const env = jsonAfter(text);
      plan = env.plan;
      confirmCode = env.confirmCode;
      const parcelsOk = ctxA.expectedParcels === null ? plan.length > 0 : plan.length === ctxA.expectedParcels;
      const summaryCode = codeLineOf(text);
      assert(
        parcelsOk &&
          Array.isArray(plan) &&
          typeof confirmCode === 'string' &&
          UUID_RE.test(confirmCode) &&
          env.expiresInSec === 300 &&
          summaryCode === confirmCode,
        `② preview_split → plan=${ctxA.expectedParcels ?? '>0'} 个包裹 + confirmCode（UUID）+ expiresInSec=300（摘要里的码与 JSON 一致）`,
        `plan=${plan.length} 个包裹, confirmCode=${String(confirmCode).slice(0, 8)}…, expiresInSec=${env.expiresInSec}, 摘要码一致=${summaryCode === confirmCode}`
      );
    } catch (e) {
      ko('② preview_split → plan + confirmCode（UUID）+ expiresInSec=300', e.message);
    }

    // ③ 预览不落库（confirmCode 只进后端 KV，不碰 delivery_order）
    try {
      before = deliveryCount();
      const mine = deliveryCountOf(ORDER_ID);
      assert(
        mine === 0,
        '③ 预览不落库：delivery_order 行数不变（我造的单仍是 0 个发货单）',
        `before=${before}（其中订单 ${ORDER_ID} = ${mine} 个）`
      );
    } catch (e) {
      ko('③ 预览不落库：delivery_order 行数不变', e.message);
    }

    // ④ 负例：不传 confirmCode → zod 拒绝（连后端都不会调到）
    try {
      const r = await expectError(client, 'execute_audit_pass', { orderId: ORDER_ID });
      assert(
        r.threw && /confirmCode|required|invalid|参数|校验/i.test(r.text),
        '④ 负例：execute_audit_pass 不带 confirmCode → 被 zod 校验拒绝',
        r.threw ? r.text.split('\n')[0].slice(0, 140) : '竟然成功了'
      );
    } catch (e) {
      ko('④ 负例：不带 confirmCode 被 zod 校验拒绝', e.message);
    }

    // ⑤ 负例：假 confirmCode → 后端「确认码无效、已使用或已过期」
    try {
      const r = await expectError(client, 'execute_audit_pass', {
        orderId: ORDER_ID,
        confirmCode: '00000000-0000-4000-8000-000000000000',
      });
      assert(
        r.threw && /确认码无效|已使用|已过期/.test(r.text),
        '⑤ 负例：假 confirmCode → 后端拒「确认码无效、已使用或已过期」',
        r.threw ? r.text.split('\n')[0].slice(0, 140) : '竟然成功了'
      );
    } catch (e) {
      ko('⑤ 负例：假 confirmCode 被后端拒绝', e.message);
    }

    // ⑩ 负例：A 单的码用到 B 单 → 后端「确认码与订单不匹配」
    //    ⚠️ 后端「取出即删」：这次失败会**消耗掉** ② 拿到的码，所以它排在 ⑥ 之前，
    //    之后必须重新 preview_split 才能执行（下面 ⑩b）。
    try {
      const r = await expectError(client, 'execute_audit_pass', { orderId: ORDER_B, confirmCode });
      const bStillPending = orderStatus(ORDER_B) === 7 && deliveryCountOf(ORDER_B) === 0;
      assert(
        r.threw && /与订单不匹配/.test(r.text) && bStillPending,
        '⑩ 负例：A 单的 confirmCode 用到 B 单 → 后端拒「确认码与订单不匹配」，且 B 单没被动过',
        r.threw ? `${r.text.split('\n')[0].slice(0, 120)}；B 单 status=${orderStatus(ORDER_B)} 发货单=${deliveryCountOf(ORDER_B)}` : '竟然成功了'
      );
    } catch (e) {
      ko('⑩ 负例：A 单的码用到 B 单被拒（码与订单不匹配）', e.message);
    }

    // ⑩b 重新预览 A：拿到新码（上一把已被 ⑩ 消耗），方案包裹数必须与第一次一致
    let confirmCode2 = null;
    try {
      const { text } = await call(client, 'preview_split', { orderId: ORDER_ID });
      const env = jsonAfter(text);
      confirmCode2 = env.confirmCode;
      assert(
        UUID_RE.test(String(confirmCode2)) && confirmCode2 !== confirmCode && env.plan.length === plan.length,
        '⑩b 重新 preview_split → 新码（与上一把不同）+ 方案包裹数不变',
        `新码=${String(confirmCode2).slice(0, 8)}…，包裹数 ${env.plan.length}=${plan?.length}`
      );
    } catch (e) {
      ko('⑩b 重新 preview_split 拿到新码', e.message);
    }

    // ⑥ 执行成功：execute_audit_pass { orderId, confirmCode }
    let delivered = 0;
    try {
      const { text } = await call(client, 'execute_audit_pass', { orderId: ORDER_ID, confirmCode: confirmCode2 });
      const deliveries = jsonAfter(text, '发货单 JSON:');
      delivered = deliveries.length;
      assert(
        plan && delivered === plan.length,
        '⑥ execute_audit_pass { orderId, confirmCode } → 成功，deliveries.length === plan.length',
        `deliveries=${delivered}, plan=${plan?.length}`
      );
    } catch (e) {
      ko('⑥ execute_audit_pass { orderId, confirmCode } → deliveries.length === plan.length', e.message);
    }

    // ⑦ 真落库
    try {
      const now = deliveryCount();
      assert(
        now === before + plan.length,
        '⑦ 真落库：delivery_order 行数 === before + plan.length',
        `before=${before}, now=${now}, 期望=${before + plan.length}`
      );
    } catch (e) {
      ko('⑦ 真落库：delivery_order 行数 === before + plan.length', e.message);
    }

    // ⑧ 订单状态 8
    try {
      const st = orderStatus(ORDER_ID);
      assert(st === 8, `⑧ 我造的订单 ${ORDER_ID} status 变 8（待发货）`, `status=${st}`);
    } catch (e) {
      ko(`⑧ 我造的订单 ${ORDER_ID} status 变 8`, e.message);
    }

    // ⑨ 负例：同码复用（同一个码第二次用）→ 被拒
    try {
      const r = await expectError(client, 'execute_audit_pass', { orderId: ORDER_ID, confirmCode: confirmCode2 });
      assert(
        r.threw && /确认码无效|已使用|已过期/.test(r.text),
        '⑨ 负例：同一个 confirmCode 复用 → 被拒（码一次性，后端取出即删）',
        r.threw ? r.text.split('\n')[0].slice(0, 140) : '竟然成功了'
      );
    } catch (e) {
      ko('⑨ 负例：同一个 confirmCode 复用被拒', e.message);
    }

    // ⑪ 回读一致（闭环）
    try {
      const { text } = await call(client, 'list_deliveries', { orderId: ORDER_ID });
      const rows = jsonAfter(text, 'JSON:');
      assert(
        rows.length === plan.length,
        '⑪ list_deliveries → 条数 === plan.length（闭环一致）',
        `deliveries=${rows.length}, plan=${plan.length}, sn=${rows.map((d) => d.deliverySn).join(',')}`
      );
    } catch (e) {
      ko('⑪ list_deliveries → 条数 === plan.length（闭环一致）', e.message);
    }

    await client.close();
    client = null;

    // ⑫ 写开关：MCP_ENABLE_WRITE=0 → 只剩 12 个只读工具（订单 6 + 商品 6），写工具消失
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
    for (const [label, c] of [
      ['A', ctxA],
      ['B', ctxB],
    ]) {
      if (!c?.cleanup) continue;
      const r = await c.cleanup();
      console.log(`🧹 清理自建数据 ${label}：${r.notes.join('；')}`);
    }
  }

  // ---------------- 总结 ----------------
  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 闭环通过：查询 → 预览（不落库，拿后端确认码）→ 确认执行（真落库）→ 回读一致；');
    console.log('   负例齐全（缺码 / 假码 / 跨单用码 / 同码复用）；写开关生效。');
    console.log('ℹ️ 本次数据自建自清（A/B 两笔订单已删除），无残留；直接重跑即可，不需要 reset-test-db.sh。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
