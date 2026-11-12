#!/usr/bin/env node
// 第 4 批：概览 + 上传（misc）工具测试。
//
// ① dashboard_overview 在 tools/list 里，title/description 中文；**write=1 时工具总数 = 38**（第 4 批 24 + 第 5 批补齐 14）
// ② dashboard_overview 能调通、返回非空，且数据自洽（口径断言，不是「数字等于某个常量」）：
//      · 商品总数 ≥ 在架数 ≥ 0
//      · 订单状态分布各档之和 === 订单总数
//      · 趋势整齐 7 天、日期递增、金额是两位小数字符串
//      · 库存告警最多 5 条且每条 stock ≤ 10；Top5 最多 5 条
// ③ **只读校验**：调用前后 order / product / sku 行数不变
// ④ `upload_image` **刻意没有这个工具**（本批决策，见 src/tools/misc.mjs 尾注）：
//      这里用裸 HTTP 证明「后端只吃 multipart/form-data 文件流」——
//      用 JSON 打同一个端点，后端回「Current request is not a multipart request」，
//      所以它无法用 JSON 入参表达（也就无法在给模型的参数里表达一张图）。
// ⑤ write=0：dashboard_overview 仍然在，且工具总数 = 26（38 - 12 个纯写工具）
//
// 只读文件，不造数据、不写库；跑完什么都不用清理，可连跑两轮。
//
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { connectServer, call, jsonAfter, dbCount, MALL_BASE, MALL_API_KEY, DB } from './_setup.mjs';

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
async function check(step, fn) {
  try {
    const r = await fn();
    assert(r === true || r?.pass, step, r?.detail);
  } catch (e) {
    ko(step, e.message);
  }
}

/** 第 5 批（覆盖补齐）后的工具总数：24（第 4 批）+ 字典 3 + 商品只读 2 + 商品写 4 + 订单写 5 = 38 */
const TOTAL_WRITE_ON = 38;
/** write=0 时消失的纯写工具共 12 个（订单 6 + 商品 6），13 = 25… 这里只关心总数：38 - 12 = 26 */
const WRITE_ONLY_COUNT = 12;
const TOTAL_WRITE_OFF = TOTAL_WRITE_ON - WRITE_ONLY_COUNT;

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 概览域（misc）工具测试 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}\n`);

  let client;
  const samples = [];
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'misc');

    // ① tools/list + 工具总数
    await check(`① tools/list 含 dashboard_overview，且 write=1 时工具总数 = ${TOTAL_WRITE_ON}（第 4 批 24 + 第 5 批补齐 14）`, async () => {
      const { tools } = await client.listTools();
      const names = tools.map((t) => t.name);
      const t = tools.find((x) => x.name === 'dashboard_overview');
      const zh = /[\u4e00-\u9fa5]/.test(t?.title || '') && /[\u4e00-\u9fa5]/.test(t?.description || '');
      return {
        pass: !!t && zh && names.length === TOTAL_WRITE_ON,
        detail: `共 ${names.length} 个：${names.sort().join(', ')}`,
      };
    });
    await check('①b 三个红线/不做的东西确实没有：upload_image、管理员账号、API Key 管理', async () => {
      const names = (await client.listTools()).tools.map((t) => t.name);
      const forbidden = ['upload_image', 'admin', 'admins', 'api_key', 'api_keys', 'list_admins', 'create_admin'];
      const leaked = forbidden.filter((n) => names.includes(n));
      return { pass: leaked.length === 0, detail: `存在的禁止项=${leaked.join(',') || '无'}` };
    });

    const before = { order: dbCount('order'), product: dbCount('product'), sku: dbCount('sku') };

    // ② dashboard_overview 数据自洽
    let data = null;
    await check('②a dashboard_overview 能调通，摘要带中文小标题、JSON 段可解析', async () => {
      const { text } = await call(client, 'dashboard_overview', {});
      data = jsonAfter(text);
      samples.push({ tool: 'dashboard_overview', args: {}, line: text.split('\n').slice(0, 3).join(' ') });
      return {
        pass: !!data && /商品：|订单：|今日：/.test(text) && text.includes('JSON:'),
        detail: text.split('\n').slice(0, 2).join(' / ').slice(0, 120),
      };
    });
    await check('②b 商品口径自洽：productTotal ≥ productOnline ≥ 0', async () => {
      const d = data ?? jsonAfter((await call(client, 'dashboard_overview', {})).text);
      return {
        pass: Number.isInteger(d.productTotal) && Number.isInteger(d.productOnline) && d.productTotal >= d.productOnline && d.productOnline >= 0,
        detail: `productTotal=${d.productTotal} productOnline=${d.productOnline}`,
      };
    });
    await check('②c 订单口径自洽：状态分布各档之和 === orderTotal', async () => {
      const d = data ?? jsonAfter((await call(client, 'dashboard_overview', {})).text);
      const dist = Array.isArray(d.statusDistribution) ? d.statusDistribution : [];
      const sum = dist.reduce((a, r) => a + Number(r.count || 0), 0);
      return {
        pass: sum === Number(d.orderTotal),
        detail: `orderTotal=${d.orderTotal}｜分布=${dist.map((r) => `${r.status}:${r.count}`).join(' ')}（和=${sum}）`,
      };
    });
    await check('②d 趋势 7 天：日期递增、金额是两位小数、订单数是整数', async () => {
      const d = data ?? jsonAfter((await call(client, 'dashboard_overview', {})).text);
      const trend = Array.isArray(d.trend) ? d.trend : [];
      const dates = trend.map((t) => t.date);
      const ascending = dates.every((v, i) => i === 0 || String(dates[i - 1]) < String(v));
      const shaped = trend.every((t) => /^\d{4}-\d{2}-\d{2}$/.test(String(t.date)) && /^\d+\.\d{2}$/.test(String(t.amount)) && Number.isInteger(t.orderCount));
      return { pass: trend.length === 7 && ascending && shaped, detail: `${trend.length} 天：${dates[0]} → ${dates[dates.length - 1]}` };
    });
    await check('②e 库存告警 ≤5 条且每条 stock ≤ 10；Top5 商品 ≤5 条', async () => {
      const d = data ?? jsonAfter((await call(client, 'dashboard_overview', {})).text);
      const alerts = Array.isArray(d.stockAlerts) ? d.stockAlerts : [];
      const tops = Array.isArray(d.topProducts) ? d.topProducts : [];
      return {
        pass: alerts.length <= 5 && alerts.every((s) => Number(s.stock) <= 10 && Number.isInteger(Number(s.skuId))) && tops.length <= 5 && tops.every((p) => typeof p.productName === 'string' && Number.isInteger(Number(p.qty))),
        detail: `库存告警 ${alerts.length} 条（最低 ${alerts[0]?.stock ?? '-'} 件）｜Top5 ${tops.length} 条（首条 ${tops[0]?.productName ?? '-'} ${tops[0]?.qty ?? '-'} 件）`,
      };
    });
    await check('②f 今日成交额是金额字符串、退货待处理是非负整数', async () => {
      const d = data ?? jsonAfter((await call(client, 'dashboard_overview', {})).text);
      return {
        pass: /^\d+\.\d{2}$/.test(String(d.todayAmount)) && Number.isInteger(d.returnPending) && d.returnPending >= 0 && Number.isInteger(d.orderPendingAudit),
        detail: `todayAmount=${d.todayAmount} todayOrderCount=${d.todayOrderCount} returnPending=${d.returnPending} 待审核=${d.orderPendingAudit}`,
      };
    });

    // ③ 只读校验
    const after = { order: dbCount('order'), product: dbCount('product'), sku: dbCount('sku') };
    for (const t of Object.keys(before)) {
      assert(before[t] === after[t], `③ 只读校验：${t} 行数不变`, `before=${before[t]}, after=${after[t]}`);
    }

    // ④ upload_image：本批刻意不做 —— 用裸 HTTP 证明后端只吃 multipart
    await check('④ upload_image 没有被做成工具，且后端确实只吃 multipart 文件流（JSON 打不动它）', async () => {
      const names = (await client.listTools()).tools.map((t) => t.name);
      const res = await fetch(`${MALL_BASE}/admin/upload/image`, {
        method: 'POST',
        headers: { 'X-Api-Key': MALL_API_KEY, 'Content-Type': 'application/json' },
        body: JSON.stringify({ file: 'aGVsbG8=' }),
      });
      const payload = await res.json().catch(() => null);
      const msg = String(payload?.message ?? '');
      return {
        pass: !names.includes('upload_image') && /multipart/i.test(msg),
        detail: `工具列表含 upload_image=${names.includes('upload_image')}｜后端对 JSON 的答复：「${msg.slice(0, 80)}」`,
      };
    });

    await client.close();
    client = null;

    // ⑤ write=0
    await check(`⑤ write=0：dashboard_overview 仍在，工具总数 = ${TOTAL_WRITE_OFF}`, async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'misc-ro');
      const names = (await ro.listTools()).tools.map((t) => t.name);
      const hasDash = names.includes('dashboard_overview');
      const leakedWrite = [
        'execute_audit_pass',
        'update_product',
        'update_sku_stock',
        'create_order',
        'update_order',
        'audit_reject_order',
        'ship_order',
        'delete_order',
        'create_product',
        'delete_product',
        'create_sku',
        'delete_sku',
      ].filter((n) => names.includes(n));
      await ro.close();
      return {
        pass: hasDash && names.length === TOTAL_WRITE_OFF && leakedWrite.length === 0,
        detail: `共 ${names.length} 个，含 dashboard_overview=${hasDash}，漏出的写工具=${leakedWrite.join(',') || '无'}`,
      };
    });
    await check('⑤b write=0 时 dashboard_overview 仍然能调通（只读工具不受写开关影响）', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'misc-ro2');
      const { text } = await call(ro, 'dashboard_overview', {});
      await ro.close();
      const j = jsonAfter(text);
      return { pass: Number.isInteger(j.productTotal) && Number.isInteger(j.orderTotal), detail: `productTotal=${j.productTotal} orderTotal=${j.orderTotal}` };
    });
  } finally {
    if (client) await client.close().catch(() => {});
  }

  console.log('\n调用样例（首行摘要）：');
  for (const s of samples) console.log(`  ${s.tool}\n    → ${String(s.line).replace(/\s+/g, ' ').trim().slice(0, 200)}`);

  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((x) => !x.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 概览域可用：dashboard_overview 只读、口径自洽；upload_image 未实现（后端只吃 multipart，无法用 JSON 表达）；');
    console.log(`   工具总数 ${TOTAL_WRITE_ON}（write=1）/ ${TOTAL_WRITE_OFF}（write=0）。`);
    console.log('ℹ️ 本文件只读，不造数据、不写库；直接重跑即可。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
