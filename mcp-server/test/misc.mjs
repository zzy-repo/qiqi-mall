#!/usr/bin/env node
// 第 4 批：概览 + 上传（misc）工具测试。
//
// ① dashboard_overview 在 tools/list 里，title/description 中文；**write=1 时工具总数 = 39**（第 4 批 24 + 第 5 批补齐 14 + upload_image_from_url 1）
// ② dashboard_overview 能调通、返回非空，且数据自洽（口径断言，不是「数字等于某个常量」）：
//      · 商品总数 ≥ 在架数 ≥ 0
//      · 订单状态分布各档之和 === 订单总数
//      · 趋势整齐 7 天、日期递增、金额是两位小数字符串
//      · 库存告警最多 5 条且每条 stock ≤ 10；Top5 最多 5 条
// ③ **只读校验**：调用前后 order / product / sku 行数不变
// ④ `upload_image`（multipart 那一条）**仍然没有工具**：后端 /admin/upload/image 只吃 multipart/form-data 文件流，
//      用 JSON 打它会回「Current request is not a multipart request」——所以模型既塞不进 base64，也不该传本地路径。
// ⑥ upload_image_from_url（第 5 批新增，写在 misc 域）：工具定义 + **正常用例**
//      · 真实 https 图片（OSS 上已有的商品图）→ 返回 /qiqi-mall/products/2026/11/*.webp
//      · 立刻用 CDN 取一遍，必须 HTTP 200 且 content-type 是图片（证明对象真的落进了同一个 bucket/前缀）
// ⑦ **负例全测**（每条都断言「中文原因 + 不是 500」）：
//      http:// / file:// / http://127.0.0.1 / http://169.254.169.254（云元数据）/ http://100.64.0.1（CGNAT）/
//      https://127.0.0.1（内网段真拦）/ 不存在的域名 / 6MB 超大文件 / 返回 HTML 的网页 / 3xx 重定向不跟随
// ⑧ 补的两处已知绕过（第 6 批）：
//      · https://[64:ff9b::7f00:1]  NAT64 well-known prefix 映射到 127.0.0.1 → 必须 403
//      · https://[2002:7f00:1::]    6to4 内嵌 127.0.0.1 → 必须 403
//      · https://example.com:8443   非 443 端口 → 必须 400（端口拒绝）
// ⑨ 反向断言：https://example.com/x.png（**不带端口**，getPort()=-1）**不能**被端口校验误拦，
//      真实拒绝原因必须是网络层的（415 不是图片 / 404 / 超时），不能出现「端口」二字。
// ⑤ write=0：dashboard_overview 仍然在，**upload_image_from_url 不注册**（只读模式不留上传通道），工具总数 = 26
//
// ⚠️ 本文件会真的往对象存储写 1 张图（正常用例），为的是能断言「CDN 立刻 200」。
//    造出来的 objectName 会追加写到 /tmp/mcp-upload-created.txt（供收尾清理），并打印到输出里。
//    除这一张图外不写库、不动业务数据，可连跑两轮。
//
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { appendFileSync } from 'node:fs';
import { connectServer, call, jsonAfter, dbCount, MALL_BASE, MALL_API_KEY, DB } from './_setup.mjs';

/** 正常用例的源图：OSS 上已有的商品图（与仓库里历史数据同域同前缀，长期有效） */
const SRC_IMAGE = 'https://cdn.rose7.cn/qiqi-mall/products/2026/11/067f91d1-8ffa-45f6-b7f5-95c07b5d94e1.webp';
/** 造出来的对象记在这里，收尾脚本按它删干净 */
const CREATED_LOG = process.env.MCP_UPLOAD_CLEANUP_LOG || '/tmp/mcp-upload-created.txt';

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

/** 工具总数：24（第 4 批）+ 字典 3 + 商品只读 2 + 商品写 4 + 订单写 5 = 38，再加 upload_image_from_url 1 = 39 */
const TOTAL_WRITE_ON = 39;
/** write=0 时消失的纯写工具共 13 个（订单 6 + 商品 6 + upload_image_from_url 1）：39 - 13 = 26 */
const WRITE_ONLY_COUNT = 13;
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
    await check('①b 红线/不该有的东西确实没有：multipart 版 upload_image、管理员账号、API Key 管理', async () => {
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
    await check('④ multipart 版 upload_image 仍没有工具（后端只吃文件流，JSON 打不动它）——所以上传走 URL 版', async () => {
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

    // ---------------- ⑥ 新工具定义 ----------------
    await check('⑥a upload_image_from_url 在工具表里，title/description 是中文，且写明「只支持 https、单图 ≤5MB」', async () => {
      const { tools } = await client.listTools();
      const t = tools.find((x) => x.name === 'upload_image_from_url');
      const zh = /[\u4e00-\u9fa5]/.test(t?.title || '') && /[\u4e00-\u9fa5]/.test(t?.description || '');
      const saysHttps = /https/i.test(t?.description || '');
      const saysLimit = /5MB|5 MB/i.test(t?.description || '');
      return { pass: !!t && zh && saysHttps && saysLimit, detail: `title=${t?.title || '(缺失)'}｜description 提到 https=${saysHttps}、5MB=${saysLimit}` };
    });
    await check('⑥b annotations 标了「写 + 会访问外部网络」（readOnly=false / openWorld=true / destructive=false）', async () => {
      const { tools } = await client.listTools();
      const a = tools.find((x) => x.name === 'upload_image_from_url')?.annotations || {};
      return {
        pass: a.readOnlyHint === false && a.openWorldHint === true && a.destructiveHint === false,
        detail: `readOnly=${a.readOnlyHint} openWorld=${a.openWorldHint} destructive=${a.destructiveHint} idempotent=${a.idempotentHint}`,
      };
    });

    // ---------------- ⑦ 正常用例：真实 https 图片 → 落进同一 bucket/前缀 → CDN 立刻 200 ----------------
    let uploadedUrl = null;
    await check('⑦a 正常上传：给 OSS 上已有的 https 图片 → 返回 /qiqi-mall/products/年/月/uuid.webp', async () => {
      const { text } = await call(client, 'upload_image_from_url', { url: SRC_IMAGE });
      const j = jsonAfter(text);
      uploadedUrl = j?.url ?? null;
      const shaped = /^\/qiqi-mall\/products\/\d{4}\/\d{2}\/[0-9a-f-]{36}\.(webp|png|jpg)$/.test(String(uploadedUrl));
      const objectOk = String(j?.objectName || '') === String(uploadedUrl).replace(/^\/qiqi-mall\//, '');
      if (shaped) {
        try {
          appendFileSync(CREATED_LOG, `${j.objectName}\n`);
        } catch {
          /* 记不下不影响断言，输出里还有一份 */
        }
      }
      samples.push({ tool: 'upload_image_from_url', args: { url: SRC_IMAGE }, line: text.split('\n').slice(0, 3).join(' ') });
      return { pass: shaped && objectOk, detail: `url=${uploadedUrl}｜objectName=${j?.objectName}｜与返回路径一致=${objectOk}` };
    });
    await check('⑦b 落地的图能通过 CDN 200 取到（content-type 是图片，字节数 > 0）', async () => {
      if (!uploadedUrl) return { pass: false, detail: '上一步没拿到 url，无法验证' };
      const res = await fetch(`https://cdn.rose7.cn${uploadedUrl}`);
      const buf = Buffer.from(await res.arrayBuffer());
      const ct = res.headers.get('content-type') || '';
      return { pass: res.status === 200 && /^image\//.test(ct) && buf.length > 0, detail: `GET https://cdn.rose7.cn${uploadedUrl} → ${res.status} ${ct} ${buf.length}B` };
    });

    // ---------------- ⑧ 负例全测：每条都必须是「中文原因 + 不是 500」 ----------------
    const NEGATIVES = [
      ['http:// 协议拒绝', 'http://cdn.rose7.cn/qiqi-mall/products/2026/11/067f91d1-8ffa-45f6-b7f5-95c07b5d94e1.webp', /只支持 https/],
      ['file:// 协议拒绝', 'file:///etc/passwd', /只支持 https/],
      ['http://127.0.0.1 拒绝（回环）', 'http://127.0.0.1/x.png', /只支持 https|内网/],
      ['http://169.254.169.254 拒绝（云元数据）', 'http://169.254.169.254/latest/meta-data/', /只支持 https|内网/],
      ['http://100.64.0.1 拒绝（CGNAT/Tailscale）', 'http://100.64.0.1/x.png', /只支持 https|内网/],
      ['https://127.0.0.1 拒绝（内网段真的拦）', 'https://127.0.0.1/x.png', /内网/],
      ['https://169.254.169.254 拒绝（元数据地址）', 'https://169.254.169.254/latest/meta-data/', /内网/],
      ['https://100.64.0.1 拒绝（Tailscale 段）', 'https://100.64.0.1/x.png', /内网/],
      ['不存在的域名拒绝', 'https://no-such-host-qiqi-2026.example/x.png', /域名解析失败/],
      ['超大文件拒绝（6MB > 5MB）', 'https://speed.cloudflare.com/__down?bytes=6000000', /5MB/],
      ['非图片内容拒绝（返回 HTML 的网页）', 'https://example.com/', /不是图片/],
      ['3xx 重定向拒绝（不跟随）', 'https://httpbin.org/redirect/1', /重定向/],
      // ↓ 补的两处已知绕过（①NAT64 / ②端口）—— 这两段地址能把 127.0.0.1 嵌进 IPv6，必须整段拦
      ['https://[64:ff9b::7f00:1] 拒绝（NAT64 well-known prefix → 127.0.0.1）', 'https://[64:ff9b::7f00:1]/x.png', /内网|NAT64/],
      ['https://[::127.0.0.1] 拒绝（IPv4-compatible ::/96 → 127.0.0.1，RFC 4291 废弃格式）', 'https://[::127.0.0.1]/x.png', /内网|转换前缀/],
      ['https://[2002:7f00:1::] 拒绝（6to4 内嵌 127.0.0.1）', 'https://[2002:7f00:1::]/x.png', /内网|6to4/],
      ['非 443 端口拒绝（:8443 不能被当端口探测器）', 'https://example.com:8443/x.png', /只允许 https 默认端口 443/],
    ];
    for (const [label, url, pattern] of NEGATIVES) {
      await check(`⑧ ${label}`, async () => {
        try {
          const r = await call(client, 'upload_image_from_url', { url });
          return { pass: false, detail: `竟然成功了，返回：${r.text.replace(/\s+/g, ' ').slice(0, 120)}` };
        } catch (e) {
          const msg = String(e.message || e).replace(/\s+/g, ' ');
          const chinese = /[\u4e00-\u9fa5]/.test(msg);
          const notServerError = !/HTTP 500|Internal Server Error|服务器内部错误|NullPointerException/.test(msg);
          return { pass: chinese && notServerError && pattern.test(msg), detail: msg.slice(0, 150) };
        }
      });
    }

    // ---------------- ⑨ 不带端口的 URL 不能被端口校验误拦（getPort() == -1 要按 443 放行）----------------
    await check('⑨ 不带端口的 https 地址不触发端口校验（会走到网络层，但**不能**是「端口拒绝」）', async () => {
      const url = 'https://example.com/x.png';
      try {
        const r = await call(client, 'upload_image_from_url', { url });
        return { pass: false, detail: `竟然成功了：${r.text.replace(/\s+/g, ' ').slice(0, 100)}` };
      } catch (e) {
        const msg = String(e.message || e).replace(/\s+/g, ' ');
        const chinese = /[\u4e00-\u9fa5]/.test(msg);
        const notServerError = !/HTTP 500|Internal Server Error|服务器内部错误|NullPointerException/.test(msg);
        // 端口拒绝的文案里一定有「端口」/「收到 :」，这里必须**没有**；真实原因应是 415 不是图片 / 404 / 超时等
        const portRejected = /端口/.test(msg) || /收到 :\d+/.test(msg);
        const reachedNetwork = /不是图片|返回 HTTP|超时|网络|解析失败|空内容|重定向/.test(msg);
        return {
          pass: chinese && notServerError && !portRejected && reachedNetwork,
          detail: `未被误判为端口拒绝=${!portRejected}｜走到了网络层的真实原因：${msg.slice(0, 140)}`,
        };
      }
    });

    await client.close();
    client = null;

    // ⑤ write=0
    await check(`⑤ write=0：dashboard_overview 仍在、upload_image_from_url 消失，工具总数 = ${TOTAL_WRITE_OFF}`, async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'misc-ro');
      const names = (await ro.listTools()).tools.map((t) => t.name);
      const hasDash = names.includes('dashboard_overview');
      const hasUpload = names.includes('upload_image_from_url');
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
        'upload_image_from_url',
      ].filter((n) => names.includes(n));
      await ro.close();
      return {
        pass: hasDash && !hasUpload && names.length === TOTAL_WRITE_OFF && leakedWrite.length === 0,
        detail: `共 ${names.length} 个，含 dashboard_overview=${hasDash}，含 upload_image_from_url=${hasUpload}，漏出的写工具=${leakedWrite.join(',') || '无'}`,
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
    console.log('✅ 概览域可用：dashboard_overview 只读、口径自洽；upload_image_from_url 正常落对象存储 + 15 条负例全部被中文原因拒绝（含 NAT64 / 6to4 / 非 443 端口）+ 不带端口不误拦；');
    console.log(`   工具总数 ${TOTAL_WRITE_ON}（write=1）/ ${TOTAL_WRITE_OFF}（write=0）。`);
    console.log(`ℹ️ 本文件只往对象存储写 1 张验证图（objectName 记在 ${CREATED_LOG}），不写库；直接重跑即可。`);
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
