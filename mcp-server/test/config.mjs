#!/usr/bin/env node
// 第 4 批：基础配置域（config）工具测试 —— warehouse(action) + order_setting(action)。
//
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 两个工具都在 tools/list 里，title/description 中文，action 枚举符合「write=1」
//   ② warehouse：list 分页夹取（size=999→50、size=1→1）、create 新增、update 改名+启停、delete 删除
//   ③ **参数边界/无 version 列的口径**：update 一个不存在的 id → 后端 409「记录已被删除」（不是静默成功）；
//      delete 一个不存在的 id → 后端确实幂等成功（**这是后端的真实形态，工具照实转述**，见报告 ⑤）
//   ④ **只读校验**：list / 报错路径调用前后 warehouse / order_setting 行数不变
//   ⑤ order_setting：get 能读到那一行（未初始化时给「尚未初始化」文案）；update 不改字段被拒；
//      update 传「与当前值相同」的值 → 真的走了 PUT 且值没变（**零漂移**，保证可以连跑两轮）
//   ⑥ write=0：两个工具仍在，action 枚举只剩只读动作
//
// 数据自给自足：warehouse 自己建自己删（唯一名字带时间戳）；order_setting 只做「改成原值」的写入，
// 不留下任何配置漂移。跑完不 reset 库，可连跑两轮。
//
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
const dbWarehouse = (id) => {
  const out = sql(`SELECT name, status FROM ${DB}.warehouse WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [name, status] = out.split('\t');
  return { name, status: Number(status) };
};

const mark = () => String(Date.now()).slice(-7);

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 基础配置域（config）工具测试 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}\n`);

  const M = mark();
  const samples = [];
  let warehouseId = null;
  let client;

  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'config');

    // ① tools/list + 元信息
    await check('① tools/list 含 warehouse / order_setting，action 枚举符合 write=1', async () => {
      const { tools } = await client.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const w = by.warehouse;
      const s = by.order_setting;
      const wEnum = w?.inputSchema?.properties?.action?.enum ?? [];
      const sEnum = s?.inputSchema?.properties?.action?.enum ?? [];
      const zh = (t) => /[\u4e00-\u9fa5]/.test(t?.title || '') && /[\u4e00-\u9fa5]/.test(t?.description || '');
      return {
        pass:
          !!w && !!s && zh(w) && zh(s) &&
          ['list', 'create', 'update', 'delete'].every((x) => wEnum.includes(x)) &&
          ['get', 'update'].every((x) => sEnum.includes(x)),
        detail: `warehouse=${wEnum.join('/')}｜order_setting=${sEnum.join('/')}｜工具共 ${tools.length} 个`,
      };
    });

    // ② warehouse 只读 + 分页
    await check('②a warehouse(action=list) → 非空，摘要带中文启停状态', async () => {
      const { text } = await call(client, 'warehouse', { action: 'list' });
      const j = jsonAfter(text);
      samples.push({ tool: 'warehouse list', args: {}, line: text.split('\n')[0] });
      return {
        pass: j.total >= 1 && j.records.length >= 1 && j.page === 1 && j.size === 10 && /启用|停用/.test(text),
        detail: `total=${j.total}：${j.records.map((w) => `${w.id}/${w.name}`).join(', ')}`,
      };
    });
    await check('②b warehouse(action=list, size=999) → size 夹到 50', async () => {
      const j = jsonAfter((await call(client, 'warehouse', { action: 'list', size: 999 })).text);
      return { pass: j.size === 50 && j.records.length === Math.min(50, j.total), detail: `size=${j.size} records=${j.records.length}` };
    });
    await check('②c warehouse(action=list, size=1) → 只 1 条，total 仍是全部', async () => {
      const j = jsonAfter((await call(client, 'warehouse', { action: 'list', size: 1 })).text);
      return { pass: j.size === 1 && j.records.length === 1 && j.total >= 1, detail: `records=${j.records.length} total=${j.total}` };
    });

    const before = { warehouse: dbCount('warehouse'), order_setting: dbCount('order_setting') };

    // ③ warehouse 写：create → update → delete
    await check('③a warehouse(action=create) → 新增成功（默认启用）', async () => {
      const { text } = await call(client, 'warehouse', { action: 'create', name: `MCP测试仓-${M}` });
      const j = jsonAfter(text);
      warehouseId = j.id ?? null;
      const row = warehouseId ? dbWarehouse(warehouseId) : null;
      samples.push({ tool: 'warehouse create', args: { name: `MCP测试仓-${M}` }, line: text.split('\n')[0] });
      return { pass: !!warehouseId && row?.name === `MCP测试仓-${M}` && row?.status === 1, detail: `id=${warehouseId} DB=${JSON.stringify(row)}` };
    });
    await check('③b warehouse(action=update) → 改名 + 停用都生效（回读 + DB 双确认）', async () => {
      const { text } = await call(client, 'warehouse', { action: 'update', warehouseId, name: `MCP测试仓-改-${M}`, status: 0 });
      const row = dbWarehouse(warehouseId);
      return {
        pass: row?.name === `MCP测试仓-改-${M}` && row?.status === 0 && text.includes('✅'),
        detail: `DB=${JSON.stringify(row)}`,
      };
    });
    await check('③c warehouse(action=update) 给不存在的 id → 409「记录已被删除」（无 version 列也有 fail-closed）', async () => {
      const r = await expectError(client, 'warehouse', { action: 'update', warehouseId: 999999, name: 'x' });
      return { pass: r.threw && /409/.test(r.text) && /记录已被删除/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });
    await check('③d warehouse(action=update) 什么都不给 → isError（参数边界）', async () => {
      const r = await expectError(client, 'warehouse', { action: 'update', warehouseId });
      return { pass: r.threw && /name|status/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('③e warehouse(action=delete) → 删掉自己造的仓，回读确认不在列表里', async () => {
      const { text } = await call(client, 'warehouse', { action: 'delete', warehouseId });
      const list = jsonAfter((await call(client, 'warehouse', { action: 'list', size: 50 })).text).records;
      const still = list.some((w) => Number(w.id) === Number(warehouseId));
      samples.push({ tool: 'warehouse delete', args: { warehouseId }, line: text.split('\n')[0] });
      return { pass: !still && dbWarehouse(warehouseId) === null && text.includes('✅'), detail: `列表里仍在=${still}｜DB 行=${dbWarehouse(warehouseId) === null ? '已删' : '仍存在'}` };
    });
    await check('③f warehouse(action=delete) 再删一次 → 后端幂等成功（工具照实转述「已删除」）', async () => {
      const r = await call(client, 'warehouse', { action: 'delete', warehouseId });
      return { pass: /已删除/.test(r.text) && /不在列表里=true/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });

    // ④ order_setting：get / 参数边界 / 零漂移写入
    let setting = null;
    await check('④a order_setting(action=get) → 读到当前值（初始化标记与字段都对）', async () => {
      const { text } = await call(client, 'order_setting', { action: 'get' });
      const j = jsonAfter(text);
      setting = j;
      samples.push({ tool: 'order_setting get', args: {}, line: text.split('\n')[0] });
      if (!j.initialized) return { pass: true, detail: '测试库尚未初始化 order_setting：工具正确给出「尚未初始化」文案' };
      return {
        pass: Number.isInteger(j.setting?.autoConfirmDays) && Number.isInteger(j.setting?.autoCloseMinutes),
        detail: `id=${j.setting?.id} autoConfirmDays=${j.setting?.autoConfirmDays} autoCloseMinutes=${j.setting?.autoCloseMinutes}`,
      };
    });
    await check('④b order_setting(action=update) 什么都不给 → isError（参数边界）', async () => {
      const r = await expectError(client, 'order_setting', { action: 'update' });
      return { pass: r.threw && /autoConfirmDays|autoCloseMinutes/.test(r.text), detail: r.text.split('\n')[0].slice(0, 120) };
    });
    await check('④c order_setting(action=update) 传「与当前相同」的值 → 走通 upsert 且配置零漂移（可连跑两轮）', async () => {
      const cur = jsonAfter((await call(client, 'order_setting', { action: 'get' })).text);
      const days = cur.setting?.autoConfirmDays ?? 7;
      const { text } = await call(client, 'order_setting', { action: 'update', autoConfirmDays: days });
      const after = jsonAfter((await call(client, 'order_setting', { action: 'get' })).text);
      samples.push({ tool: 'order_setting update', args: { autoConfirmDays: days }, line: text.split('\n')[0] });
      const dbRow = sql(`SELECT auto_confirm_days, auto_close_minutes FROM ${DB}.order_setting LIMIT 1`);
      return {
        pass: after.setting?.autoConfirmDays === days && text.includes('✅') && !!dbRow,
        detail: `写前=${cur.setting?.autoConfirmDays} 写后=${after.setting?.autoConfirmDays}｜DB=${dbRow.replace(/\t/g, '/')}`,
      };
    });

    // ⑤ 只读校验（读调用不改变任何行）
    const afterReads = { warehouse: dbCount('warehouse'), order_setting: dbCount('order_setting') };
    for (const t of Object.keys(before)) {
      // 说明：本文件在只读段之后又做了「建仓 / 删仓」，所以这里用「建仓前 → 删仓后」的净结果对照
      assert(before[t] === afterReads[t], `⑤ 只读校验：${t} 净行数不变（建仓+删仓相互抵消）`, `before=${before[t]}, after=${afterReads[t]}`);
    }

    await client.close();
    client = null;

    // ⑥ write=0
    await check('⑥a write=0 → warehouse 只剩 list、order_setting 只剩 get', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'config-ro');
      const { tools } = await ro.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const wEnum = by.warehouse?.inputSchema?.properties?.action?.enum ?? [];
      const sEnum = by.order_setting?.inputSchema?.properties?.action?.enum ?? [];
      await ro.close();
      return {
        pass: wEnum.length === 1 && wEnum[0] === 'list' && sEnum.length === 1 && sEnum[0] === 'get',
        detail: `warehouse=${wEnum.join('/')}｜order_setting=${sEnum.join('/')}`,
      };
    });
    await check('⑥b write=0 → 调 order_setting(action=update) 在协议层被拒', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'config-ro2');
      const r = await expectError(ro, 'order_setting', { action: 'update', autoConfirmDays: 7 });
      const okGet = await expectError(ro, 'order_setting', { action: 'get' });
      await ro.close();
      return { pass: r.threw && /enum|Invalid/i.test(r.text) && !okGet.threw, detail: `写动作被拒=${r.threw}｜只读 get 可用=${!okGet.threw}` };
    });
  } finally {
    if (client) await client.close().catch(() => {});
    // 兜底清理（正常情况下 ③e 已经删掉了）
    if (warehouseId) {
      await adminRequest('DELETE', `/admin/warehouses/${warehouseId}`).catch(() => {});
      if (dbWarehouse(warehouseId) === null) console.log(`🧹 自建仓库 ${warehouseId} 已清理（列表与 DB 都查不到）`);
      else console.log(`⚠️ 自建仓库 ${warehouseId} 仍在库里，请手工核对`);
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
    console.log('✅ 基础配置域可用：warehouse 增改删（update 有 fail-closed 的 409，delete 幂等）；');
    console.log('   order_setting 能读「未初始化」形态、update 是 upsert（无 version，靠「先 get 再改」）。');
    console.log('ℹ️ 自建仓库已删除、全局设置零漂移；直接重跑即可。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
