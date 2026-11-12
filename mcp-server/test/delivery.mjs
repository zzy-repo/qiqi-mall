#!/usr/bin/env node
// 第 4 批：履约域（delivery）工具测试 —— 1 个合并工具 delivery(action)。
//
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① delivery 在 tools/list 里，title/description 中文，write=1 时 action 枚举含 8 个动作
//   ② action=list 在没有发货单时给明确文案（不是空串）、缺 orderId 时给中文错误（参数边界）
//   ③ 审核通过后 list / get 能调通：分页（size=999→50、size=1→1）、get 能对上 list 里的那一行
//   ④ **只读校验**：list / get / 分页 / 报错路径调用前后 delivery_order / delivery_item / order 行数不变
//   ⑤ 写动作的 version 口径（本域最容易被写错的地方）：
//        · update 不带 version → **后端**回 400「缺少版本号」（不是工具层造的，是 fail-closed 真的拒）
//        · update 带假 version → 409「数据已被他人修改」，且**数据没被改**
//        · update 带正确 version → 成功、version +1（回读 + DB 双确认）
//   ⑥ split / merge / delete 三个编辑动作各自改完包数符合预期（+1 / -1 / -1），且返回里带**新 version**
//   ⑦ dispatch / cancel：dryRun 预演不落库（状态不变）；真执行后状态变了，且**已下发的包裹不能再改**
//      （这是「下发不可撤回」在接口层面的证据）；重复 dispatch / 重复 cancel 都被状态守卫拒
//   ⑧ write=0：delivery 仍在，但 action 枚举里只剩 list / get；调 dispatch 被拒并说明需要 MCP_ENABLE_WRITE
//
// 数据自给自足（见 test/_setup.mjs）：自己造一笔待审核订单（qty=3、2 个 SKU）→ 用 execute_audit_pass
// 生成发货单 → 所有断言都落在**自己造的这笔**上；跑完删单（级联清掉 shipment），可连跑两轮、不 reset 库。
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
/** 每个步骤独立 try/catch：某一条炸了不影响后面的断言继续跑 */
async function check(step, fn) {
  try {
    const r = await fn();
    const pass = r === true || r?.pass;
    assert(pass, step, r?.detail);
  } catch (e) {
    ko(step, e.message);
  }
}

// ---------------- 直连测试库（只读/核对） ----------------
const dbCount = (table) => Number(sql(`SELECT COUNT(*) FROM ${DB}.\`${table}\``));
const dbDeliveryCountOf = (orderId) => Number(sql(`SELECT COUNT(*) FROM ${DB}.delivery_order WHERE order_id = ${Number(orderId)}`));
/** 某张发货单在库里的真实状态（不存在 → null） */
const dbDelivery = (id) => {
  const out = sql(`SELECT warehouse_id, status, version, source FROM ${DB}.delivery_order WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [warehouseId, status, version, source] = out.split('\t').map((v) => Number(v));
  return { warehouseId, status, version, source };
};
const snapshot = () => ({ delivery_order: dbCount('delivery_order'), delivery_item: dbCount('delivery_item'), order: dbCount('order') });

const ACTIONS_ALL = ['list', 'get', 'update', 'split', 'merge', 'delete', 'dispatch', 'cancel'];
const ACTIONS_READONLY = ['list', 'get'];

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 履约域（delivery）工具测试 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}`);

  // 自建数据：一笔待审核订单（qty=3 → 每个明细 3 件，方便反复拆 1 件出来；2 个 SKU → 可能多个仓）
  let ctx;
  try {
    ctx = await bootstrap({ qty: 3, skuCount: 2, autoCleanup: true });
  } catch (e) {
    console.error(`❌ 造测试数据失败（_setup.bootstrap）：${e.message}`);
    process.exit(1);
  }
  const ORDER_ID = ctx.orderId;
  console.log(`  自建待审核订单：id=${ORDER_ID} 单号=${ctx.orderSn} SKU=${ctx.skuIds.join(',')} 每明细 ${ctx.qty} 件 | 预期包裹数=${ctx.expectedParcels ?? '未知'}\n`);

  // 可用仓库（改仓测试用）：至少两个启用中的仓
  const warehouses = (await adminRequest('GET', '/admin/warehouses')).filter((w) => w.status === 1);
  if (warehouses.length < 2) throw new Error('测试库启用中的仓库少于 2 个，无法测「改仓」');

  let client;
  const samples = [];
  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'delivery');

    /** 读当前订单的全部发货单（每次真读，绝不缓存 version） */
    const listPkgs = async () => jsonAfter((await call(client, 'delivery', { action: 'list', orderId: ORDER_ID, size: 50 })).text).records;
    const pendingOf = async () => (await listPkgs()).filter((d) => d.status === 0);

    // ① tools/list + 元信息
    await check('① tools/list 含 delivery，且 write=1 时 action 枚举含 8 个动作', async () => {
      const { tools } = await client.listTools();
      const t = tools.find((x) => x.name === 'delivery');
      const actionEnum = t?.inputSchema?.properties?.action?.enum ?? [];
      const zhTitle = /[\u4e00-\u9fa5]/.test(t?.title || '');
      const zhDesc = /[\u4e00-\u9fa5]/.test(t?.description || '') && (t?.description || '').length > 50;
      return {
        pass: !!t && zhTitle && zhDesc && ACTIONS_ALL.every((a) => actionEnum.includes(a)),
        detail: t ? `title=${t.title}｜action=${actionEnum.join('/')}｜工具共 ${tools.length} 个` : `实际：${tools.map((x) => x.name).join(', ')}`,
      };
    });

    // ② 空结果 + 参数边界（还没审核 → 没有发货单）
    await check('②a 审核前 delivery(action=list) → 0 条发货单，且给明确中文文案（不是空串）', async () => {
      const { text } = await call(client, 'delivery', { action: 'list', orderId: ORDER_ID });
      const j = jsonAfter(text);
      return {
        pass: j.total === 0 && Array.isArray(j.records) && j.records.length === 0 && text.includes('没有任何发货单'),
        detail: text.split('\n')[0].slice(0, 100),
      };
    });
    await check('②b delivery(action=list) 缺 orderId → isError 且中文说明「必须给 orderId」', async () => {
      const r = await expectError(client, 'delivery', { action: 'list' });
      return { pass: r.threw && /必须给 orderId/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('②c delivery(action=get) 缺 deliveryId → isError（参数边界）', async () => {
      const r = await expectError(client, 'delivery', { action: 'get', orderId: ORDER_ID });
      return { pass: r.threw && /必须给 deliveryId/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('②d delivery(action=split) 只给 deliveryId 不给 items → isError（后端拒绝拆空包，工具层先说清）', async () => {
      const r = await expectError(client, 'delivery', { action: 'split', deliveryId: 1, version: 0 });
      return { pass: r.threw && /items/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });

    // ③ 造发货单（走已有的写工具 execute_audit_pass：本测试要真发货单）
    await check('③ execute_audit_pass 生成发货单（后续断言的载体）', async () => {
      const { text } = await call(client, 'execute_audit_pass', { orderId: ORDER_ID });
      const pkgs = await listPkgs();
      return {
        pass: pkgs.length >= 1 && pkgs.every((d) => d.status === 0),
        detail: `${pkgs.length} 个待下发包裹：${pkgs.map((d) => `id=${d.id}/${d.warehouseName}/v${d.version}`).join(' ') }｜${text.split('\n')[0].slice(0, 60)}`,
      };
    });

    const before = snapshot();

    // ④ 只读：分页 + get（全部只读，之后校验行数不变）
    await check('④a delivery(action=list) 默认每页 10 条、返回 page/size/total/pages', async () => {
      const { text } = await call(client, 'delivery', { action: 'list', orderId: ORDER_ID });
      const j = jsonAfter(text);
      samples.push({ tool: 'delivery list', args: { orderId: ORDER_ID }, line: text.split('\n').slice(0, 2).join(' ') });
      return { pass: j.page === 1 && j.size === 10 && j.total === dbDeliveryCountOf(ORDER_ID), detail: `records=${j.records.length} total=${j.total}（库里 ${dbDeliveryCountOf(ORDER_ID)} 行）` };
    });
    await check('④b delivery(action=list, size=999) → size 夹到 50（不报错）', async () => {
      const j = jsonAfter((await call(client, 'delivery', { action: 'list', orderId: ORDER_ID, size: 999 })).text);
      return { pass: j.size === 50 && j.records.length === Math.min(50, j.total), detail: `size=${j.size} records=${j.records.length}` };
    });
    await check('④c delivery(action=list, size=1) → 只返回 1 条，total 仍是全部条数', async () => {
      const j = jsonAfter((await call(client, 'delivery', { action: 'list', orderId: ORDER_ID, size: 1 })).text);
      return { pass: j.size === 1 && j.records.length === 1 && j.total === dbDeliveryCountOf(ORDER_ID), detail: `records=${j.records.length} total=${j.total} pages=${j.pages}` };
    });

    let A = null;
    await check('④d delivery(action=get) → 与 list 里同一行一致，带 version 与 items', async () => {
      const pkgs = await listPkgs();
      A = pkgs[0];
      const { text } = await call(client, 'delivery', { action: 'get', orderId: ORDER_ID, deliveryId: A.id });
      const j = jsonAfter(text);
      samples.push({ tool: 'delivery get', args: { orderId: ORDER_ID, deliveryId: A.id }, line: text.split('\n')[0] });
      return {
        pass: j.id === A.id && Number.isInteger(j.version) && Array.isArray(j.items) && j.items.length > 0 && text.includes('version='),
        detail: `id=${j.id} 单号=${j.deliverySn} 状态=${j.status} version=${j.version} 明细=${j.items?.length} 条`,
      };
    });
    await check('④e delivery(action=get) 缺 orderId → isError 并解释后端没有单条详情接口', async () => {
      const r = await expectError(client, 'delivery', { action: 'get', deliveryId: A.id });
      return { pass: r.threw && /orderId/.test(r.text) && /单条|接口/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('④f delivery(action=get) 给一个不属于该订单的 deliveryId → isError 且点名 id', async () => {
      const r = await expectError(client, 'delivery', { action: 'get', orderId: ORDER_ID, deliveryId: 999999 });
      return { pass: r.threw && /999999/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });

    // ④g 只读校验：以上全部只读调用跑完，行数必须一模一样
    const afterReads = snapshot();
    for (const t of Object.keys(before)) {
      assert(before[t] === afterReads[t], `④g 只读校验：${t} 行数不变`, `before=${before[t]}, after=${afterReads[t]}`);
    }

    // ⑤ 保证有 2 个待下发包裹（有的订单拆成 1 个包 → 先拆一个出来，供 dispatch/cancel 各用一个）
    await check('⑤ 保证 ≥2 个待下发包裹（不足则先 split 一个出来）', async () => {
      let pending = await pendingOf();
      if (pending.length >= 2) return { pass: true, detail: `已有 ${pending.length} 个待下发包裹` };
      const src = pending[0];
      const item = (src.items || [])[0];
      const r = jsonAfter((await call(client, 'delivery', { action: 'split', deliveryId: src.id, items: [{ deliveryItemId: item.id, quantity: 1 }], version: src.version })).text);
      pending = await pendingOf();
      return { pass: pending.length >= 2, detail: `拆出 1 个后待下发=${pending.length} 个（split 返回 ${r.summary.deliveries.length} 个包裹）` };
    });

    // ⑥ update：dry-run 不落库 → 缺 version 被后端 400 → 假 version 409 → 正确 version 成功
    // 先定下「要改成哪个仓」：取一个与当前包不同的启用仓（不写死 id）
    const pendingForTarget = await pendingOf();
    const targetWarehouse = warehouses.find((w) => w.id !== pendingForTarget[0].warehouseId);
    if (!targetWarehouse) throw new Error('找不到「与当前包不同」的启用仓库，无法测改仓');
    await check('⑥a update dryRun=true → 返回 willDo 预演文案，且**仓库没被改**（预演不落库）', async () => {
      const pkgs = await pendingOf();
      const A0 = pkgs[0];
      const beforeRow = dbDelivery(A0.id);
      const { text } = await call(client, 'delivery', { action: 'update', deliveryId: A0.id, warehouseId: targetWarehouse.id, dryRun: true });
      const j = jsonAfter(text);
      const afterRow = dbDelivery(A0.id);
      samples.push({ tool: 'delivery update(dryRun)', args: { deliveryId: A0.id }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof j.willDo === 'string' && j.willDo.length > 10 && beforeRow.warehouseId === afterRow.warehouseId,
        detail: `willDo=${(j.willDo || '').slice(0, 70)}…｜仓库 ${beforeRow.warehouseId}→${afterRow.warehouseId}（未变）`,
      };
    });
    await check('⑥b update **不带 version** → 后端 400「缺少版本号」（fail-closed，不是工具层自己造的）', async () => {
      const A0 = (await pendingOf())[0];
      const beforeRow = dbDelivery(A0.id);
      const r = await expectError(client, 'delivery', { action: 'update', deliveryId: A0.id, warehouseId: targetWarehouse.id });
      const afterRow = dbDelivery(A0.id);
      return {
        pass: r.threw && /400/.test(r.text) && /版本号/.test(r.text) && beforeRow.warehouseId === afterRow.warehouseId,
        detail: `${r.text.split('\n')[0].slice(0, 90)}｜仓库未变=${beforeRow.warehouseId === afterRow.warehouseId}`,
      };
    });
    await check('⑥c update 带**假 version** → 409「数据已被他人修改」，且数据没被改', async () => {
      const A0 = (await pendingOf())[0];
      const beforeRow = dbDelivery(A0.id);
      const r = await expectError(client, 'delivery', { action: 'update', deliveryId: A0.id, warehouseId: targetWarehouse.id, version: A0.version + 17 });
      const afterRow = dbDelivery(A0.id);
      console.log('    ⑥c 被拒样例（完整文本）：');
      for (const line of r.text.split('\n')) console.log(`      ${line}`);
      return {
        pass: r.threw && /409/.test(r.text) && /数据已被他人修改/.test(r.text) && beforeRow.warehouseId === afterRow.warehouseId && afterRow.version === beforeRow.version,
        detail: `version ${beforeRow.version}→${afterRow.version}（未变）｜仓库 ${beforeRow.warehouseId}→${afterRow.warehouseId}（未变）`,
      };
    });
    let versionAfterUpdate = null;
    await check('⑥d update 带正确 version → 成功，仓库真的改了，version +1（响应 + DB 双确认）', async () => {
      const A0 = (await pendingOf())[0];
      const oldVersion = A0.version;
      const { text } = await call(client, 'delivery', { action: 'update', deliveryId: A0.id, warehouseId: targetWarehouse.id, version: oldVersion });
      const j = jsonAfter(text);
      const row = dbDelivery(A0.id);
      const mine = (j.summary?.deliveries || []).find((d) => Number(d.id) === Number(A0.id));
      versionAfterUpdate = mine?.version ?? null;
      samples.push({ tool: 'delivery update', args: { deliveryId: A0.id }, line: text.split('\n')[0] });
      return {
        pass: row.warehouseId === targetWarehouse.id && row.version === oldVersion + 1 && mine?.version === oldVersion + 1 && text.includes('✅'),
        detail: `仓库=${row.warehouseId}（目标 ${targetWarehouse.id}）version ${oldVersion}→${row.version}（响应里的新 version=${mine?.version}）`,
      };
    });

    // ⑦ split：包数 +1，返回里带新包的新 version
    let splitOutId = null;
    await check('⑦ split 带正确 version → 包数 +1，新包是待下发，返回里带最新 version', async () => {
      const pending = await pendingOf();
      const src = pending.find((d) => (d.items || []).some((it) => it.quantity >= 2)) ?? pending[0];
      const item = (src.items || []).find((it) => it.quantity >= 2);
      if (!item) throw new Error('没有数量 ≥2 的明细，无法拆件（造单 qty 应 ≥2）');
      const beforeIds = new Set((await listPkgs()).map((d) => d.id));
      const j = jsonAfter((await call(client, 'delivery', { action: 'split', deliveryId: src.id, items: [{ deliveryItemId: item.id, quantity: 1 }], version: src.version })).text);
      const after = await listPkgs();
      const fresh = after.filter((d) => !beforeIds.has(d.id));
      splitOutId = fresh[0]?.id ?? null;
      return {
        pass: after.length === beforeIds.size + 1 && !!splitOutId && fresh.every((d) => d.status === 0 && Number.isInteger(d.version)),
        detail: `包数 ${beforeIds.size}→${after.length}，新包 id=${splitOutId}（version=${fresh[0]?.version}）`,
      };
    });
    await check('⑧ merge 带正确 version → 源包并入目标，包数 -1（源包那一行从库里消失）', async () => {
      const pkgs = await pendingOf();
      const src = pkgs.find((d) => d.id === splitOutId) ?? pkgs[pkgs.length - 1];
      const target = pkgs.find((d) => d.id !== src.id);
      const before = (await listPkgs()).length;
      await call(client, 'delivery', { action: 'merge', deliveryId: src.id, targetId: target.id, version: src.version });
      const after = (await listPkgs()).length;
      return { pass: after === before - 1 && dbDelivery(src.id) === null, detail: `包数 ${before}→${after}，源包 ${src.id} 行已删=${dbDelivery(src.id) === null}` };
    });
    await check('⑨ delete 带 targetId + version → 有明细的包裹被并入别处后删除（包数 -1）', async () => {
      const pending = await pendingOf();
      const src = pending.find((d) => (d.items || []).some((it) => it.quantity >= 2)) ?? pending[0];
      const item = (src.items || []).find((it) => it.quantity >= 2);
      if (!item) throw new Error('没有数量 ≥2 的明细，无法再拆一件出来（造单 qty 应 ≥2）');
      const beforeIds = new Set((await listPkgs()).map((d) => d.id)); // ⚠️ 必须在 split **之前**取快照
      const j = jsonAfter((await call(client, 'delivery', { action: 'split', deliveryId: src.id, items: [{ deliveryItemId: item.id, quantity: 1 }], version: src.version })).text);
      const created = (j.summary?.deliveries || []).find((d) => !beforeIds.has(d.id));
      if (!created) throw new Error('split 之后没找到新包裹，无法测 delete');
      const before = (await listPkgs()).length;
      await call(client, 'delivery', { action: 'delete', deliveryId: created.id, targetId: src.id, version: created.version });
      const after = (await listPkgs()).length;
      return { pass: after === before - 1 && dbDelivery(created.id) === null, detail: `包数 ${before}→${after}，被删包裹 ${created.id} 行已消失` };
    });

    // ⑩ dispatch：预演不落库 → 真执行状态变 → 已下发后任何编辑/取消都被状态守卫拒
    await check('⑩a dispatch dryRun=true → willDo 写清「下发后不可撤回」，状态仍是待下发(0)', async () => {
      const pending = await pendingOf();
      const A0 = pending[0];
      const { text } = await call(client, 'delivery', { action: 'dispatch', deliveryId: A0.id, dryRun: true });
      const j = jsonAfter(text);
      const row = dbDelivery(A0.id);
      samples.push({ tool: 'delivery dispatch(dryRun)', args: { deliveryId: A0.id }, line: text.split('\n').slice(0, 2).join(' ') });
      return {
        pass: typeof j.willDo === 'string' && /下发/.test(j.willDo) && /不可撤回/.test(j.willDo) && row.status === 0,
        detail: `willDo=${(j.willDo || '').slice(0, 80)}…｜库里状态=${row.status}（未变）`,
      };
    });
    let dispatchedId = null;
    await check('⑩b dispatch 真执行 → 状态 待下发(0)→已下发(1)（回读 + DB 双确认）', async () => {
      const pending = await pendingOf();
      const A0 = pending[0];
      dispatchedId = A0.id;
      const { text } = await call(client, 'delivery', { action: 'dispatch', deliveryId: A0.id });
      const row = dbDelivery(A0.id);
      const reread = (await listPkgs()).find((d) => Number(d.id) === Number(A0.id));
      samples.push({ tool: 'delivery dispatch', args: { deliveryId: A0.id }, line: text.split('\n')[0] });
      return {
        pass: row.status === 1 && reread?.status === 1 && text.includes('✅') && text.includes('不可撤回'),
        detail: `DB status=${row.status}｜回读 status=${reread?.status}｜version=${row.version}`,
      };
    });
    await check('⑩c **已下发**的包裹不能再改：update / split 都被「只有待下发」状态守卫拒', async () => {
      const d = (await listPkgs()).find((x) => Number(x.id) === Number(dispatchedId));
      const r1 = await expectError(client, 'delivery', { action: 'update', deliveryId: d.id, warehouseId: targetWarehouse.id, version: d.version });
      const r2 = await expectError(client, 'delivery', { action: 'split', deliveryId: d.id, items: [{ deliveryItemId: (d.items || [])[0]?.id ?? 1, quantity: 1 }], version: d.version });
      const row = dbDelivery(d.id);
      return {
        pass: r1.threw && /待下发/.test(r1.text) && r2.threw && /待下发/.test(r2.text) && row.status === 1,
        detail: `update：${r1.text.split('\n')[0].slice(0, 60)}｜split：${r2.text.split('\n')[0].slice(0, 60)}｜DB status=${row.status}`,
      };
    });
    await check('⑩d 重复 dispatch → 被状态守卫拒（「只有待下发发货单可以下发」）', async () => {
      const r = await expectError(client, 'delivery', { action: 'dispatch', deliveryId: dispatchedId });
      return { pass: r.threw && /待下发/.test(r.text), detail: r.text.split('\n')[0].slice(0, 90) };
    });

    // ⑪ cancel：预演不落库 → 真执行 → 重复取消被拒
    let cancelId = null;
    await check('⑪a cancel dryRun=true → willDo 写明「回补库存」，状态仍是待下发(0)', async () => {
      const pending = await pendingOf();
      const B = pending[0];
      cancelId = B.id;
      const { text } = await call(client, 'delivery', { action: 'cancel', deliveryId: B.id, dryRun: true });
      const j = jsonAfter(text);
      const row = dbDelivery(B.id);
      return {
        pass: typeof j.willDo === 'string' && /取消/.test(j.willDo) && /库存/.test(j.willDo) && row.status === 0,
        detail: `willDo=${(j.willDo || '').slice(0, 80)}…｜库里状态=${row.status}（未变）`,
      };
    });
    await check('⑪b cancel 真执行 → 状态 待下发(0)→已取消(9)，行仍在（不是物理删除）', async () => {
      // ⚠️ cancel 会把明细数量**回补到 SKU 总池库存**（后端 restoreStock）。本测试造的单没扣过库存
      //    （POST /admin/orders 不扣，只有 C 端下单才扣），所以这里回补出来的量属于测试副作用，
      //    跑完必须原样减回去 —— 否则连跑多轮会让 seed SKU 的库存一路涨（污染别人也在用的测试库）。
      const itemsBefore = ((await listPkgs()).find((d) => Number(d.id) === Number(cancelId))?.items || []).map((it) => ({
        skuId: Number(it.productSkuId),
        qty: Number(it.quantity),
      }));
      const { text } = await call(client, 'delivery', { action: 'cancel', deliveryId: cancelId });
      const row = dbDelivery(cancelId);
      const restored = [];
      for (const it of itemsBefore) {
        const { sku } = await adminRequest('GET', `/admin/skus/${it.skuId}`);
        await adminRequest('PUT', `/admin/skus/${it.skuId}`, { sku: { id: it.skuId, version: sku.version, stock: Number(sku.stock) - it.qty } });
        restored.push(`sku ${it.skuId} -${it.qty}`);
      }
      samples.push({ tool: 'delivery cancel', args: { deliveryId: cancelId }, line: text.split('\n')[0] });
      console.log(`    ⑪b cancel 回补的库存已原样减回：${restored.join('，') || '(无明细)'}`);
      return {
        pass: row?.status === 9 && text.includes('✅') && restored.length === itemsBefore.length,
        detail: `DB status=${row?.status}｜version=${row?.version}｜库存还原=${restored.join(',') || '无明细'}`,
      };
    });
    await check('⑪c 重复 cancel → 被状态守卫拒（「只有待下发发货单可以取消」）', async () => {
      const r = await expectError(client, 'delivery', { action: 'cancel', deliveryId: cancelId });
      return { pass: r.threw && /待下发/.test(r.text), detail: r.text.split('\n')[0].slice(0, 90) };
    });

    await client.close();
    client = null;

    // ⑫ write=0：工具仍在，但写动作从 action 枚举里消失；调用写动作被拒
    await check('⑫a write=0 → delivery 仍在，action 枚举只剩 list/get（写动作消失）', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'delivery-ro');
      const { tools } = await ro.listTools();
      const t = tools.find((x) => x.name === 'delivery');
      const actionEnum = t?.inputSchema?.properties?.action?.enum ?? [];
      const leaked = ACTIONS_ALL.filter((a) => !ACTIONS_READONLY.includes(a) && actionEnum.includes(a));
      await ro.close();
      return {
        pass: !!t && leaked.length === 0 && ACTIONS_READONLY.every((a) => actionEnum.includes(a)),
        detail: `枚举=${actionEnum.join('/')}｜漏出的写动作=${leaked.join(',') || '无'}`,
      };
    });
    await check('⑫b write=0 → 调 delivery(action=dispatch) 在**协议层**就被拒（action 枚举里没有写动作）', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'delivery-ro2');
      const r = await expectError(ro, 'delivery', { action: 'dispatch', deliveryId: 1 });
      // 只读动作仍然能正常调通（同一实例）
      const okList = await expectError(ro, 'delivery', { action: 'list', orderId: ORDER_ID });
      await ro.close();
      return {
        pass: r.threw && /enum|Invalid/i.test(r.text) && !okList.threw,
        detail: `写动作被拒：${r.text.split('\n')[0].slice(0, 80)}｜只读 list 仍可用=${!okList.threw}`,
      };
    });
  } finally {
    if (client) await client.close().catch(() => {});
    if (ctx?.cleanup) {
      const r = await ctx.cleanup();
      console.log(`🧹 清理自建数据：${r.notes.join('；')}`);
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
    console.log('✅ 履约域 delivery 可用：list/get 只读无副作用；update/split/merge/delete 强制 version（缺 400 / 假 409）；');
    console.log('   dispatch 下发后不可撤回（已下发的包裹改不动）；cancel 回补库存；write=0 时写动作整体消失。');
    console.log('ℹ️ 本次数据自建自清（订单已删除，发货单级联清掉），无残留；直接重跑即可。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
