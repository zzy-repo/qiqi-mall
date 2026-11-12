#!/usr/bin/env node
// 第 4 批：退货域（returns）工具测试 —— return_apply(action) + return_reason(action)。
//
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 两个工具都在 tools/list 里，title/description 中文，action 枚举符合「write=1」
//   ② return_reason：list 分页夹取、create 新增、update 改名、delete 删除；
//      **被退货申请引用的原因删不掉**（后端拒，工具把中文原因原样透出）—— 引用解除后再删就成功
//   ③ return_apply：list（含 orderSn）/ 按 orderId 与 status 过滤 / get 命中 / get 不存在 → 中文错误
//   ④ **只读校验**：list / get / 过滤 / 报错路径调用前后 return_apply / return_reason 行数不变
//   ⑤ audit 的 dryRun 预演：返回 willDo（写明退款+回补库存），**申请状态与订单状态都没变**
//   ⑥ **audit 没有 version 可传**（return_apply 表无 version 列）→ 这条断言要证明「不传 version 也能审核成功」，
//      以及并发保护换成了「仅待处理可审」的状态守卫：重复审核被拒
//   ⑦ 审核真执行（用「拒绝」这条不退款的分支，避免动真钱）：申请 0→2、订单回已付款(1)（DB 双确认）
//   ⑧ 参数边界：缺 auditStatus / auditStatus 超范围 → 都被拒（连后端都到不了）
//   ⑨ write=0：两个工具仍在，但 action 枚举里只剩只读动作；调 audit 在协议层被拒
//
// 数据自给自足（见 test/_setup.mjs）：自己造一笔待审核订单 + 一条退货申请（POST /admin/return-applies），
// 所有断言都落在自己造的数据上；跑完删申请、删原因、删单，可连跑两轮、不 reset 库。
//
// 前置：后端 18080 可达 + `export MALL_API_KEY=$(cat /tmp/test-env-ai-key.txt)`。

import { bootstrap, adminRequest, connectServer, call, expectError, jsonAfter, sql, DB, MALL_BASE, MALL_API_KEY } from './_setup.mjs';

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

// ---------------- 直连测试库（只读核对） ----------------
const dbCount = (table) => Number(sql(`SELECT COUNT(*) FROM ${DB}.\`${table}\``));
const dbApplyStatus = (id) => {
  const out = sql(`SELECT status FROM ${DB}.return_apply WHERE id = ${Number(id)}`);
  return out === '' ? null : Number(out);
};
const dbOrderStatus = (id) => Number(sql(`SELECT status FROM ${DB}.\`order\` WHERE id = ${Number(id)}`));
const dbReasonExists = (id) => Number(sql(`SELECT COUNT(*) FROM ${DB}.return_reason WHERE id = ${Number(id)}`)) > 0;

const mark = () => String(Date.now()).slice(-7);
const APPLY_ACTIONS_ALL = ['list', 'get', 'audit'];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 退货域（returns）工具测试 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}`);

  let ctx;
  try {
    ctx = await bootstrap({ qty: 1, skuCount: 1, autoCleanup: true });
  } catch (e) {
    console.error(`❌ 造测试数据失败（_setup.bootstrap）：${e.message}`);
    process.exit(1);
  }
  const ORDER_ID = ctx.orderId;
  console.log(`  自建待审核订单：id=${ORDER_ID} 单号=${ctx.orderSn} 会员=${ctx.memberId}\n`);

  const M = mark();
  const createdReasons = []; // 收尾清理
  const createdApplies = []; // 收尾清理
  const samples = [];
  let mainApplyId = null;
  let client;

  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'returns');

    // ① tools/list + 元信息
    await check('① tools/list 含 return_apply / return_reason，action 枚举符合 write=1', async () => {
      const { tools } = await client.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const a = by.return_apply;
      const r = by.return_reason;
      const aEnum = a?.inputSchema?.properties?.action?.enum ?? [];
      const rEnum = r?.inputSchema?.properties?.action?.enum ?? [];
      const zh = (t) => /[\u4e00-\u9fa5]/.test(t?.title || '') && /[\u4e00-\u9fa5]/.test(t?.description || '');
      return {
        pass: !!a && !!r && zh(a) && zh(r) && APPLY_ACTIONS_ALL.every((x) => aEnum.includes(x)) && ['list', 'create', 'update', 'delete'].every((x) => rEnum.includes(x)),
        detail: `return_apply=${aEnum.join('/')}｜return_reason=${rEnum.join('/')}｜工具共 ${tools.length} 个`,
      };
    });

    // ② return_reason 只读 + 分页
    await check('②a return_reason(action=list) → 非空，且在架字典可读（分页字段齐全）', async () => {
      const { text } = await call(client, 'return_reason', { action: 'list' });
      const j = jsonAfter(text);
      samples.push({ tool: 'return_reason list', args: {}, line: text.split('\n')[0] });
      return { pass: j.total >= 1 && j.records.length >= 1 && j.page === 1 && j.size === 10, detail: `total=${j.total}, 首条=id=${j.records[0]?.id} ${j.records[0]?.name}` };
    });
    await check('②b return_reason(action=list, size=999) → size 夹到 50', async () => {
      const j = jsonAfter((await call(client, 'return_reason', { action: 'list', size: 999 })).text);
      return { pass: j.size === 50 && j.records.length === Math.min(50, j.total), detail: `size=${j.size} records=${j.records.length} total=${j.total}` };
    });
    await check('②c return_reason(action=list, size=1) → 只 1 条，total 仍是全部', async () => {
      const j = jsonAfter((await call(client, 'return_reason', { action: 'list', size: 1 })).text);
      return { pass: j.size === 1 && j.records.length === 1 && j.total >= 1, detail: `records=${j.records.length} total=${j.total}` };
    });

    // ③ reason 写：create / update / delete
    let reasonId = null;
    await check('③a return_reason(action=create) → 新增成功（带 id）', async () => {
      const { text } = await call(client, 'return_reason', { action: 'create', name: `MCP测试原因-${M}` });
      const j = jsonAfter(text);
      reasonId = j.id ?? null;
      if (reasonId) createdReasons.push(reasonId);
      return { pass: !!reasonId && j.name === `MCP测试原因-${M}`, detail: `id=${reasonId} name=${j.name}` };
    });
    await check('③b return_reason(action=update) → 改名生效（回读确认）', async () => {
      const { text } = await call(client, 'return_reason', { action: 'update', reasonId, name: `MCP测试原因-改-${M}` });
      const list = jsonAfter((await call(client, 'return_reason', { action: 'list', size: 50 })).text).records;
      const mine = list.find((r) => Number(r.id) === Number(reasonId));
      return { pass: mine?.name === `MCP测试原因-改-${M}` && text.includes('✅'), detail: `回读 name=${mine?.name}` };
    });
    await check('③c return_reason(action=update) 给不存在的 id → 409「记录已被删除」（后端影响行数 0）', async () => {
      const r = await expectError(client, 'return_reason', { action: 'update', reasonId: 999999, name: 'x' });
      return { pass: r.threw && /409/.test(r.text) && /记录已被删除/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });
    await check('③d return_reason(action=create) 缺 name → isError（参数边界）', async () => {
      const r = await expectError(client, 'return_reason', { action: 'create' });
      return { pass: r.threw && /name/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });

    // ④ 造一条退货申请（用后台 admin 接口造，退货申请本身由买家在 C 端发起；MCP 不暴露 create）
    await check('④a 造一条「待处理」退货申请（POST /admin/return-applies，测试载体）', async () => {
      const apply = await adminRequest('POST', '/admin/return-applies', {
        orderId: ORDER_ID,
        memberId: ctx.memberId,
        returnReason: '质量问题',
        description: `MCP 退货域测试 ${M}`,
        status: 0,
        reasonId,
      });
      mainApplyId = apply?.id ?? null;
      if (mainApplyId) createdApplies.push(mainApplyId);
      return { pass: !!mainApplyId && dbApplyStatus(mainApplyId) === 0, detail: `applyId=${mainApplyId} DB status=${dbApplyStatus(mainApplyId)}` };
    });

    const before = { return_apply: dbCount('return_apply'), return_reason: dbCount('return_reason') };

    // ⑤ return_apply 只读
    await check('⑤a return_apply(action=list) → 含我造的这条，且带订单号 orderSn', async () => {
      const { text } = await call(client, 'return_apply', { action: 'list', size: 50 });
      const j = jsonAfter(text);
      const mine = (j.records || []).find((r) => Number(r.id) === Number(mainApplyId));
      samples.push({ tool: 'return_apply list', args: {}, line: text.split('\n')[0] });
      return {
        pass: !!mine && mine.orderId === ORDER_ID && String(mine.orderSn || '') === ctx.orderSn && j.page === 1,
        detail: mine ? `id=${mine.id} 订单=${mine.orderSn} 状态=${mine.status}（共 ${j.total} 条）` : `没找到 id=${mainApplyId}`,
      };
    });
    await check('⑤b return_apply(action=list, orderId=我造的) → 只返回这笔订单的申请', async () => {
      const j = jsonAfter((await call(client, 'return_apply', { action: 'list', orderId: ORDER_ID, size: 50 })).text);
      return {
        pass: j.records.length >= 1 && j.records.every((r) => r.orderId === ORDER_ID) && j.records.some((r) => r.id === mainApplyId),
        detail: `${j.records.length} 条，全部是本单=${j.records.every((r) => r.orderId === ORDER_ID)}`,
      };
    });
    await check('⑤c return_apply(action=list, status=0) → 全部待处理', async () => {
      const j = jsonAfter((await call(client, 'return_apply', { action: 'list', status: 0, size: 50 })).text);
      return {
        pass: j.records.length >= 1 && j.records.every((r) => r.status === 0) && j.records.some((r) => r.id === mainApplyId),
        detail: `${j.records.length} 条待处理`,
      };
    });
    await check('⑤d return_apply(action=get) → 命中我造的那条（含状态与原因）', async () => {
      const { text } = await call(client, 'return_apply', { action: 'get', applyId: mainApplyId });
      const j = jsonAfter(text);
      samples.push({ tool: 'return_apply get', args: { applyId: mainApplyId }, line: text.split('\n')[0] });
      return { pass: j.id === mainApplyId && j.status === 0 && text.includes('待处理') && text.includes('可审核'), detail: `id=${j.id} 状态=${j.status} 原因=${j.returnReason}` };
    });
    await check('⑤e return_apply(action=get) 不存在的 id → 中文错误（后端无单条详情接口，工具层判空）', async () => {
      const r = await expectError(client, 'return_apply', { action: 'get', applyId: 999999 });
      return { pass: r.threw && /不存在/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });
    await check('⑤f return_apply(action=get) 缺 applyId → isError（参数边界）', async () => {
      const r = await expectError(client, 'return_apply', { action: 'get' });
      return { pass: r.threw && /applyId/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });

    // ⑥ 只读校验
    const afterReads = { return_apply: dbCount('return_apply'), return_reason: dbCount('return_reason') };
    for (const t of Object.keys(before)) {
      assert(before[t] === afterReads[t], `⑥ 只读校验：${t} 行数不变`, `before=${before[t]}, after=${afterReads[t]}`);
    }

    // ⑦ 被引用的原因删不掉（引用解除后可删）
    await check('⑦a 被退货申请引用的原因 delete → 后端拒绝（中文原因透出）', async () => {
      const r = await expectError(client, 'return_reason', { action: 'delete', reasonId });
      return { pass: r.threw && /引用|不能删|无法删|使用/.test(r.text) && dbReasonExists(reasonId), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('⑦b 解除引用（删掉引用它的那条申请）后 delete → 成功，字典里查不到', async () => {
      // ⚠️ 必须用一条**只被本条申请引用**的原因：mainApply 也引用了 ③a 造的原因，
      //    所以拿它做「解除引用后可删」的例子会一直是「还有人引用」——那是断言写错了，不是后端错。
      const only = await call(client, 'return_reason', { action: 'create', name: `MCP仅引用一次-${M}` });
      const onlyId = jsonAfter(only.text).id;
      createdReasons.push(onlyId);
      const extra = await adminRequest('POST', '/admin/return-applies', {
        orderId: ORDER_ID,
        memberId: ctx.memberId,
        returnReason: '测试引用',
        description: `MCP 引用解除 ${M}`,
        status: 0,
        reasonId: onlyId,
      });
      createdApplies.push(extra.id);
      const blocked = await expectError(client, 'return_reason', { action: 'delete', reasonId: onlyId });
      await adminRequest('DELETE', `/admin/return-applies/${extra.id}`);
      const okDel = await call(client, 'return_reason', { action: 'delete', reasonId: onlyId });
      const gone = !dbReasonExists(onlyId);
      if (gone) {
        const i = createdReasons.indexOf(onlyId);
        if (i >= 0) createdReasons.splice(i, 1); // 已经删掉了，收尾不用再删
      }
      return {
        pass: blocked.threw && okDel.text.includes('✅') && gone,
        detail: `引用时被拒=${blocked.threw}；解除后删除成功=${okDel.text.includes('✅')}；库中已不存在=${gone}`,
      };
    });

    // ⑧ audit 预演 + 参数边界
    await check('⑧a audit dryRun=true（结论=通过）→ willDo 写明退款+回补库存，且申请/订单状态都没变', async () => {
      const orderBefore = dbOrderStatus(ORDER_ID);
      const { text } = await call(client, 'return_apply', { action: 'audit', applyId: mainApplyId, auditStatus: 1, dryRun: true });
      const j = jsonAfter(text);
      samples.push({ tool: 'return_apply audit(dryRun)', args: { applyId: mainApplyId, auditStatus: 1 }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof j.willDo === 'string' && /退款/.test(j.willDo) && /回补/.test(j.willDo) && dbApplyStatus(mainApplyId) === 0 && dbOrderStatus(ORDER_ID) === orderBefore,
        detail: `willDo=${(j.willDo || '').slice(0, 90)}…｜申请仍是 ${dbApplyStatus(mainApplyId)}、订单仍是 ${dbOrderStatus(ORDER_ID)}`,
      };
    });
    await check('⑧b audit 缺 auditStatus → isError（参数边界）', async () => {
      const r = await expectError(client, 'return_apply', { action: 'audit', applyId: mainApplyId });
      return { pass: r.threw && /auditStatus/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('⑧c audit 给非法结论（3）→ 被拒（zod 范围校验，请求到不了后端）', async () => {
      const r = await expectError(client, 'return_apply', { action: 'audit', applyId: mainApplyId, auditStatus: 3 });
      return { pass: r.threw && /Invalid|invalid|错误|校验/i.test(r.text) && dbApplyStatus(mainApplyId) === 0, detail: r.text.split('\n')[0].slice(0, 110) };
    });

    // ⑨ 真执行：用「拒绝」分支（不退款、不回补库存），DB 双确认
    await check('⑨a audit 真执行（结论=拒绝）→ 申请 0→2，订单回已付款(1)，无退款流水', async () => {
      const payBefore = Number(sql(`SELECT COUNT(*) FROM ${DB}.payment_record WHERE order_sn = '${String(ctx.orderSn).replace(/[^A-Za-z0-9_-]/g, '')}'`));
      const { text } = await call(client, 'return_apply', { action: 'audit', applyId: mainApplyId, auditStatus: 2 });
      const payAfter = Number(sql(`SELECT COUNT(*) FROM ${DB}.payment_record WHERE order_sn = '${String(ctx.orderSn).replace(/[^A-Za-z0-9_-]/g, '')}'`));
      samples.push({ tool: 'return_apply audit', args: { applyId: mainApplyId, auditStatus: 2 }, line: text.split('\n')[0] });
      return {
        pass: dbApplyStatus(mainApplyId) === 2 && dbOrderStatus(ORDER_ID) === 1 && payAfter === payBefore && text.includes('✅'),
        detail: `申请 status=${dbApplyStatus(mainApplyId)}（期望 2）｜订单 status=${dbOrderStatus(ORDER_ID)}（期望 1）｜退款流水 ${payBefore}→${payAfter}（不退款）`,
      };
    });
    await check('⑨b 重复 audit → 被「仅待处理可审」状态守卫拒（没有 version 也拦住了重复退款）', async () => {
      const r = await expectError(client, 'return_apply', { action: 'audit', applyId: mainApplyId, auditStatus: 1 });
      return { pass: r.threw && /已处理|不能重复/.test(r.text) && dbApplyStatus(mainApplyId) === 2, detail: r.text.split('\n')[0].slice(0, 110) };
    });

    await client.close();
    client = null;

    // ⑩ write=0：工具仍在、写动作消失
    await check('⑩a write=0 → return_apply 枚举只剩 list/get，return_reason 只剩 list', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'returns-ro');
      const { tools } = await ro.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const aEnum = by.return_apply?.inputSchema?.properties?.action?.enum ?? [];
      const rEnum = by.return_reason?.inputSchema?.properties?.action?.enum ?? [];
      await ro.close();
      return {
        pass: aEnum.length === 2 && aEnum.includes('list') && aEnum.includes('get') && rEnum.length === 1 && rEnum[0] === 'list',
        detail: `return_apply=${aEnum.join('/')}｜return_reason=${rEnum.join('/')}`,
      };
    });
    await check('⑩b write=0 → 调 return_apply(action=audit) 在协议层被拒', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'returns-ro2');
      const r = await expectError(ro, 'return_apply', { action: 'audit', applyId: 1, auditStatus: 1 });
      const okList = await expectError(ro, 'return_apply', { action: 'list' });
      await ro.close();
      return { pass: r.threw && /enum|Invalid/i.test(r.text) && !okList.threw, detail: `写动作被拒=${r.threw}｜只读 list 可用=${!okList.threw}` };
    });
  } finally {
    if (client) await client.close().catch(() => {});
    // 清理：先删申请（解除对原因的引用），再删本次造的原因，最后删订单
    for (const id of createdApplies) {
      await adminRequest('DELETE', `/admin/return-applies/${id}`).catch((e) => console.log(`  （清理申请 ${id} 跳过：${e.message}）`));
    }
    for (const id of createdReasons) {
      await adminRequest('DELETE', `/admin/return-reasons/${id}`).catch((e) => console.log(`  （清理原因 ${id} 跳过：${e.message}）`));
    }
    if (ctx?.cleanup) {
      const r = await ctx.cleanup();
      console.log(`🧹 清理自建数据：${r.notes.join('；')}｜退货申请 ${createdApplies.length} 条、原因 ${createdReasons.length} 条已删`);
    }
  }

  console.log('\n调用样例（各动作首行摘要）：');
  for (const s of samples) console.log(`  ${s.tool} ${JSON.stringify(s.args)}\n    → ${String(s.line).replace(/\s+/g, ' ').trim().slice(0, 150)}`);

  console.log('\n' + '─'.repeat(64));
  console.log(`总结：${passed} 通过 / ${failed} 失败（共 ${passed + failed} 条）`);
  if (failed > 0) {
    console.log('失败项：');
    for (const r of results.filter((x) => !x.ok)) console.log(`  ❌ ${r.step} — ${r.detail}`);
  } else {
    console.log('✅ 退货域可用：字典增改删 + 引用保护；申请 list/get 只读；audit 预演不落库；');
    console.log('   「仅待处理可审」的状态守卫挡住了重复审核（return_apply 表没有 version 列，这不是漏测）。');
    console.log('ℹ️ 本次数据自建自清，无残留；直接重跑即可。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
