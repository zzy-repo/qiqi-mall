#!/usr/bin/env node
// 订单域（order）第 3 批测试：新增 3 个只读工具（list_orders / get_order_detail / count_deliveries）。
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 3 个新工具都在 tools/list 里（且 list_deliveries / preview_split / list_pending_orders 没被改动）
//   ② list_orders 分页：不带参 → ≤10 条；size=2 → min(2,total) 条；size=999 → 夹到 50；翻页不重复
//   ③ list_orders {status:7} → 全部 status===7、状态名「待审核」，且**必须含我造的那笔**
//   ④ list_orders {orderSn} → 按完整单号恰好命中我造的这 1 笔；按前缀模糊也命中它
//   ⑤ get_order_detail {我造的 id} → 明细条数/数量与我造的一致；不存在的 id → 中文错误
//   ⑥ count_deliveries {我造的单 + 库里已有单} → 每个单都有包裹数，且与库里（未取消）发货单数一致
//   ⑦ 只读校验：调用前后 order / order_item / delivery_order 行数不变
//   ⑧ MCP_ENABLE_WRITE=0 → 只读工具仍然齐全，写工具 execute_audit_pass 消失
//
// 数据自给自足（见 test/_setup.mjs）：开头自己造一笔待审核订单（2 个 SKU → 2 个包裹），
// 所有「绝对值」断言都落在**我造的这笔**上（id / 单号 / 明细条数 / 包裹数 0）；
// 「库里有 14 笔订单」这类绝对数改成相对一致断言（list_orders 的 total 在 size=10 与 size=50 下必须相同）。
// 跑完删掉自己造的单，不需要 reset-test-db.sh。

import { bootstrap, connectServer, call, jsonAfter, sql, DB, MALL_BASE, MALL_USER } from './_setup.mjs';

/** 本批新增的 3 个 */
const NEW_TOOLS = ['list_orders', 'get_order_detail', 'count_deliveries'];
/** 本批不动的订单域老工具（只验证仍在，不验证细节） */
const KEPT_TOOLS = ['list_pending_orders', 'preview_split', 'list_deliveries'];
/** write=0 时应当仍在的全部只读工具：订单域 6 + 商品域 6 */
const READONLY_TOOLS = [...KEPT_TOOLS, ...NEW_TOOLS, 'search_products', 'get_product_detail', 'list_categories', 'list_product_types', 'list_attributes', 'list_skus'];

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
const count = (table) => Number(sql(`SELECT COUNT(*) FROM ${DB}.\`${table}\``));
const snapshot = () => ({ order: count('order'), order_item: count('order_item'), delivery_order: count('delivery_order') });
/** 库里某订单未取消的发货单数（与后端 counts 接口同口径：status != 9） */
const dbDeliveryCount = (orderId) =>
  Number(sql(`SELECT COUNT(*) FROM ${DB}.delivery_order WHERE order_id = ${Number(orderId)} AND status <> 9`));

const samples = [];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 订单域只读工具测试 | 目标：${MALL_BASE} | 账号：${MALL_USER} | 库：${DB}`);

  // 自建数据：一笔待审核订单（2 个 SKU、qty=2）
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
      `明细=${ctx.skuIds.length} 条 × 数量 ${ctx.qty} 收货人=${ctx.receiverName} | 凭证=${ctx.authMode}\n`
  );

  let client;
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'rw');

    // ① tools/list 含 3 个新工具（老工具也还在 = 没被本批改坏）
    try {
      const { tools } = await client.listTools();
      const listed = tools.map((t) => t.name);
      for (const name of NEW_TOOLS) {
        assert(listed.includes(name), `① tools/list 含新工具 ${name}`, listed.includes(name) ? `共 ${listed.length} 个工具` : `实际：${listed.join(', ')}`);
      }
      for (const name of KEPT_TOOLS) {
        assert(listed.includes(name), `① tools/list 仍含老工具 ${name}（本批未动）`, `共 ${listed.length} 个工具`);
      }
    } catch (e) {
      for (const name of NEW_TOOLS) ko(`① tools/list 含新工具 ${name}`, e.message);
    }

    // ①b 工具元信息：中文 title + 中文 description（模型靠这个选工具）
    try {
      const { tools } = await client.listTools();
      const byName = Object.fromEntries(tools.map((t) => [t.name, t]));
      for (const name of NEW_TOOLS) {
        const t = byName[name] || {};
        const zhTitle = /[\u4e00-\u9fa5]/.test(t.title || '');
        const zhDesc = /[\u4e00-\u9fa5]/.test(t.description || '') && (t.description || '').length > 20;
        assert(zhTitle && zhDesc, `①b ${name} 有中文 title 且 description 说明用法`, `${t.title || '(无 title)'}`);
      }
    } catch (e) {
      ko('①b 3 个新工具的 title/description 中文化', e.message);
    }

    const before = snapshot();

    // ②a list_orders 默认分页：不带参 → ≤10 条（size 默认 10），且包含我造的那笔
    try {
      const { text } = await call(client, 'list_orders', {});
      const j = jsonAfter(text);
      const mine = (j.records || []).find((o) => o.id === ORDER_ID);
      assert(
        Array.isArray(j.records) && j.records.length > 0 && j.records.length <= 10 && j.size === 10 && j.page === 1,
        '②a list_orders 不带参 → 默认每页 10 条（records ≤ 10）',
        `records=${j.records.length}, total=${j.total}, size=${j.size}, page=${j.page}, pages=${j.pages}`
      );
      assert(
        !!mine && mine.orderSn === ctx.orderSn && mine.status === 7,
        '②a1 list_orders 首页含我造的那笔（id/单号一致、status===7）',
        mine ? `id=${mine.id} 单号=${mine.orderSn} 状态=${mine.status}` : `首页 id=${(j.records || []).map((o) => o.id).join(',')} 里没有 ${ORDER_ID}`
      );
      // 数据库里到底有几笔订单不再写成常量：用「两次不同 size 的 total 必须一致」来锁数据集
      const j50 = jsonAfter((await call(client, 'list_orders', { size: 50 })).text);
      assert(
        j.total === j50.total && j.total >= 1,
        '②a2 list_orders 的 total 稳定（size=10 与 size=50 两次查询一致，且 ≥1）',
        `total(size=10)=${j.total}, total(size=50)=${j50.total}`
      );
      samples.push({ tool: 'list_orders', args: {}, line: text.split('\n').slice(0, 2).join(' ') });
    } catch (e) {
      ko('②a list_orders 不带参 → 默认每页 10 条（records ≤ 10）', e.message);
    }

    // ②a2 每条都带中文状态名
    try {
      const j = jsonAfter((await call(client, 'list_orders', {})).text);
      assert(
        j.records.every((o) => typeof o.statusText === 'string' && o.statusText.length > 0 && !o.statusText.startsWith('未知')),
        '②a3 list_orders 每条都带中文状态名 statusText',
        j.records.map((o) => `${o.status}→${o.statusText}`).join(' ')
      );
    } catch (e) {
      ko('②a3 list_orders 每条都带中文状态名 statusText', e.message);
    }

    // ②b list_orders size=2 → 条数 === min(2, total)
    try {
      const j = jsonAfter((await call(client, 'list_orders', { size: 2 })).text);
      assert(
        j.size === 2 && j.records.length === Math.min(2, j.total) && j.records.length > 0,
        '②b list_orders size=2 → 条数 === min(2, total)（相对断言，不假设库里有几笔订单）',
        `records=${j.records.length}, total=${j.total}, size=${j.size}`
      );
    } catch (e) {
      ko('②b list_orders size=2 → 条数 === min(2, total)', e.message);
    }

    // ②c list_orders size=999 → 夹到 50（不报错）
    try {
      const j = jsonAfter((await call(client, 'list_orders', { size: 999 })).text);
      assert(
        j.size === 50 && j.records.length === Math.min(50, j.total),
        '②c list_orders size=999 → 夹到 50 且条数 === min(50, total)',
        `size=${j.size}, records=${j.records.length}, total=${j.total}`
      );
    } catch (e) {
      ko('②c list_orders size=999 → 夹到 50 且条数 === min(50, total)', e.message);
    }

    // ②d 翻页：total>2 时第 2 页与第 1 页不重复；total<=2 时第 2 页为空
    try {
      const p1 = jsonAfter((await call(client, 'list_orders', { size: 2, page: 1 })).text);
      const p2 = jsonAfter((await call(client, 'list_orders', { size: 2, page: 2 })).text);
      const ids1 = p1.records.map((r) => r.id);
      const ids2 = p2.records.map((r) => r.id);
      const disjoint = ids1.every((id) => !ids2.includes(id));
      const expectP2Empty = p1.total <= 2;
      assert(
        p2.page === 2 && (expectP2Empty ? ids2.length === 0 : ids2.length > 0 && disjoint),
        '②d 翻页正确：第 2 页与第 1 页不重复（后端真分页，没被二次切片）',
        `total=${p1.total} p1=[${ids1.join(',')}] p2=[${ids2.join(',')}]`
      );
    } catch (e) {
      ko('②d 翻页正确：第 2 页与第 1 页不重复（后端真分页，没被二次切片）', e.message);
    }

    // ③ status=7 → 全部待审核，且必须含我造的那笔
    try {
      const { text } = await call(client, 'list_orders', { status: 7, size: 50 });
      const j = jsonAfter(text);
      const mine = (j.records || []).find((o) => o.id === ORDER_ID);
      assert(
        j.total > 0 && j.records.length > 0 && j.records.every((o) => o.status === 7 && o.statusText === '待审核') && !!mine,
        '③ list_orders {status:7} → 全部 status===7 且状态名「待审核」，并含我造的那笔',
        `${j.records.length} 条：${j.records.map((o) => `id=${o.id}/${o.statusText}`).join(', ')}`
      );
      assert(text.includes('待审核'), '③b 摘要文本里状态写的是中文「待审核」而不是裸数字', text.split('\n')[0].slice(0, 90));
      samples.push({ tool: 'list_orders', args: { status: 7 }, line: text.split('\n').slice(0, 2).join(' ') });
    } catch (e) {
      ko('③ list_orders {status:7} → 全部 status===7 且状态名「待审核」，并含我造的那笔', e.message);
    }

    // ④a 完整单号 → 精确命中我造的那 1 笔
    try {
      const { text } = await call(client, 'list_orders', { orderSn: ctx.orderSn, size: 5 });
      const j = jsonAfter(text);
      assert(
        j.total === 1 && j.records.length === 1 && j.records[0].id === ORDER_ID && j.records[0].orderSn === ctx.orderSn,
        '④a list_orders {orderSn:我造的单号} → 恰好 1 条且就是我造的那笔',
        `total=${j.total}, 返回=${j.records.map((o) => `${o.id}/${o.orderSn}`).join(',')}`
      );
      samples.push({ tool: 'list_orders', args: { orderSn: ctx.orderSn }, line: text.split('\n').slice(0, 2).join(' ') });
    } catch (e) {
      ko('④a list_orders {orderSn:我造的单号} → 恰好 1 条且就是我造的那笔', e.message);
    }

    // ④a2 单号前缀 → 模糊匹配命中我造的那笔，且每条都含该前缀
    try {
      const prefix = ctx.orderSn.slice(0, 10); // 例：QQT20261112
      const j = jsonAfter((await call(client, 'list_orders', { orderSn: prefix, size: 50 })).text);
      assert(
        j.total >= 1 && j.records.length > 0 && j.records.every((o) => String(o.orderSn).includes(prefix)) && j.records.some((o) => o.id === ORDER_ID),
        '④a2 list_orders {orderSn:前缀} → 模糊匹配命中我造的那笔，且每条都含该前缀',
        `prefix=${prefix}，${j.records.length} 条：${j.records.map((o) => o.orderSn).join(',')}`
      );
    } catch (e) {
      ko('④a2 list_orders {orderSn:前缀} → 模糊匹配命中我造的那笔', e.message);
    }

    // ④b 不存在的单号 → 空结果文案（不报错）
    try {
      const { text } = await call(client, 'list_orders', { orderSn: 'QQ999999999999' });
      const j = jsonAfter(text);
      assert(
        text.includes('没有符合条件的订单') && j.total === 0 && j.records.length === 0,
        '④b 不存在的单号 → 返回「没有符合条件的订单」且不抛错',
        text.split('\n')[1]?.slice(0, 60)
      );
    } catch (e) {
      ko('④b 不存在的单号 → 返回「没有符合条件的订单」且不抛错', e.message);
    }

    // ⑤ 订单详情：对我造的这笔做精确断言（明细条数/数量/状态都对得上）
    try {
      const { text } = await call(client, 'get_order_detail', { orderId: ORDER_ID });
      const j = jsonAfter(text);
      assert(
        j.order?.id === ORDER_ID && j.order?.status === 7 && j.order?.statusText === '待审核' && j.order?.orderSn === ctx.orderSn,
        '⑤a get_order_detail {我造的 id} → order.id/单号一致、状态=待审核(7)',
        `id=${j.order?.id}, sn=${j.order?.orderSn}, statusText=${j.order?.statusText}`
      );
      assert(
        Array.isArray(j.items) && j.items.length === ctx.skuIds.length && j.items.every((it) => it.productQuantity === ctx.qty && !!it.productName),
        '⑤b 明细条数与购买数量和我造的一致（每条都带商品名快照）',
        `${j.items?.length} 条：${j.items?.map((it) => `${it.productName?.slice(0, 12)}×${it.productQuantity}`).join(' | ')}`
      );
      assert(
        Array.isArray(j.returnApplies) && j.returnApplies.length === 0 && j.returnApplyCount === 0,
        '⑤c 新造的订单没有退货申请（returnApplies 是空数组，不是 undefined/null）',
        `returnApplies=${JSON.stringify(j.returnApplies)}`
      );
      assert(
        typeof j.order?.statusText === 'string' && !j.order.statusText.startsWith('未知') && text.includes('退货申请'),
        '⑤e 详情摘要带中文状态名与「退货申请」段',
        `${j.order?.statusText} / returnApplyCount=${j.returnApplyCount}`
      );
      samples.push({ tool: 'get_order_detail', args: { orderId: ORDER_ID }, line: text.split('\n').slice(0, 2).join(' ') });
    } catch (e) {
      ko('⑤a get_order_detail {我造的 id} → order.id/单号一致、状态=待审核(7)', e.message);
    }

    // ⑤d 不存在的 id → 中文错误（后端对这个不报错，工具层必须自己判空）
    try {
      const res = await client.callTool({ name: 'get_order_detail', arguments: { orderId: 99999 } });
      const t = (res.content || []).map((c) => c.text).join('\n');
      assert(res.isError === true && t.includes('订单不存在'), '⑤d 不存在的订单 id → isError 且给出中文「订单不存在」', t.split('\n')[0].slice(0, 80));
    } catch (e) {
      ko('⑤d 不存在的订单 id → isError 且给出中文「订单不存在」', e.message);
    }

    // ⑥ 批量包裹数：我造的单必须 0 个包裹；库里已有订单与 DB 未取消发货单数一致
    try {
      const preset = jsonAfter((await call(client, 'list_orders', { size: 2 })).text).records.map((r) => r.id);
      const ids = [...new Set([ORDER_ID, ...preset])].slice(0, 3);
      const { text } = await call(client, 'count_deliveries', { orderIds: ids });
      const j = jsonAfter(text);
      const got = Object.fromEntries((j.records || []).map((r) => [r.orderId, r.deliveryCount]));
      const want = Object.fromEntries(ids.map((id) => [id, dbDeliveryCount(id)]));
      const allPresent = ids.every((id) => Number.isInteger(got[id]));
      const matchDb = ids.every((id) => got[id] === want[id]);
      assert(
        allPresent && matchDb && got[ORDER_ID] === 0,
        `⑥ count_deliveries {${ids.join(',')}} → 每个单都有包裹数、与库里未取消发货单数一致，且我造的新单是 0 个包裹`,
        `工具=${JSON.stringify(got)} 库=${JSON.stringify(want)}`
      );
      samples.push({ tool: 'count_deliveries', args: { orderIds: ids }, line: text.split('\n').slice(0, 2).join(' ') });
    } catch (e) {
      ko('⑥ count_deliveries → 包裹数与库里一致，我造的新单是 0 个包裹', e.message);
    }

    // ⑥b 去重 + 不存在的订单 id → 记 0（而不是整单报错）
    try {
      const j = jsonAfter((await call(client, 'count_deliveries', { orderIds: [ORDER_ID, ORDER_ID, 99999] })).text);
      const ids = (j.records || []).map((r) => r.orderId);
      const mine = (j.records || []).find((r) => r.orderId === ORDER_ID);
      const ghost = (j.records || []).find((r) => r.orderId === 99999);
      assert(
        ids.length === 2 && ids.filter((i) => i === ORDER_ID).length === 1 && mine?.deliveryCount === 0 && ghost?.deliveryCount === 0,
        '⑥b 重复 id 去重、不存在的订单 id 记 0（不报错）',
        `records=${JSON.stringify(j.records)}`
      );
    } catch (e) {
      ko('⑥b 重复 id 去重、不存在的订单 id 记 0（不报错）', e.message);
    }

    // ⑦ 只读校验：行数不变
    const after = snapshot();
    for (const table of Object.keys(before)) {
      assert(before[table] === after[table], `⑦ 只读校验：${table} 行数不变`, `before=${before[table]}, after=${after[table]}`);
    }

    await client.close();
    client = null;

    // ⑧ 写开关关闭：只读工具齐全、写工具消失
    try {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'ro');
      const names = (await ro.listTools()).tools.map((t) => t.name);
      const missing = READONLY_TOOLS.filter((n) => !names.includes(n));
      assert(
        missing.length === 0 && !names.includes('execute_audit_pass'),
        `⑧ MCP_ENABLE_WRITE=0 → ${READONLY_TOOLS.length} 个只读工具仍然齐全、写工具 execute_audit_pass 消失`,
        missing.length ? `缺：${missing.join(', ')}` : `${names.length} 个：${names.sort().join(', ')}`
      );
      await ro.close();
    } catch (e) {
      ko('⑧ MCP_ENABLE_WRITE=0 → 只读工具仍然齐全、写工具 execute_audit_pass 消失', e.message);
    }
  } finally {
    if (client) await client.close().catch(() => {});
    if (ctx?.cleanup) {
      const r = await ctx.cleanup();
      console.log(`🧹 清理自建数据：${r.notes.join('；')}`);
    }
  }

  // ---------------- 总结 ----------------
  console.log('\n调用样例（各工具首行摘要）：');
  for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${s.line.replace(/\s+/g, ' ').trim().slice(0, 160)}`);

  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((r) => !r.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 订单域 3 个新只读工具可用；分页夹取一致；状态码中文化；空结果文案明确；无写库行为。');
    console.log('ℹ️ 本次数据自建自清（订单已删除），无残留；直接重跑即可。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
