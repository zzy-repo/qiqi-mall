#!/usr/bin/env node
// 第 4 批：会员域（member）工具测试 —— member(action) + member_address(action) + member_cart(action)。
//
// 用 SDK client 通过 **stdio 真启动** src/index.mjs，逐条断言：
//   ① 三个工具都在 tools/list 里，title/description 中文，action 枚举符合「write=1」
//   ② member：list 分页夹取 + status/username 过滤；get 命中；get 不存在的 id → 中文错误
//   ③ 写口径（**这张表真的有 version 列**，后端 fail-closed）：
//        · update 不带 version → isError（工具层先说清「必须给 version」）
//        · update 带假 version → 409「数据已被他人修改」（后端拒）
//        · update 带正确 version → 成功、version +1（回读 + DB 双确认），且**密码不回显**
//   ④ member_address：save（新增）→ list 命中 → save（带 addressId 修改）→ delete 删除
//   ⑤ member_cart：只读 list（新会员 → 空车，给明确文案而不是空串）
//   ⑥ **只读校验**：全部只读调用前后 member / member_address / cart 行数不变
//   ⑦ delete 的 L3 后果：**有订单的会员删不掉**（order.member_id 外键无级联，后端报错）——
//      这正是描述里写「被拒就改成禁用」的原因，必须验到
//   ⑧ write=0：三个工具仍在，action 枚举只剩只读动作
//
// 数据自给自足：自己注册两个会员（一个没有任何订单 → 可删；一个来自 _setup 造单流程 → 有订单，删不掉），
// 跑完把自己造的会员/地址删干净，可连跑两轮、不 reset 库。
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
const dbMember = (id) => {
  const out = sql(`SELECT username, nickname, status, version FROM ${DB}.member WHERE id = ${Number(id)}`);
  if (!out) return null;
  const [username, nickname, status, version] = out.split('\t');
  return { username, nickname, status: Number(status), version: Number(version) };
};
const dbAddressExists = (id) => Number(sql(`SELECT COUNT(*) FROM ${DB}.member_address WHERE id = ${Number(id)}`)) > 0;

const mark = () => String(Date.now()).slice(-7);

// ---------------- 主流程 ----------------
async function main() {
  console.log(`▶ 会员域（member）工具测试 | 目标：${MALL_BASE} | 凭证：API Key ${MALL_API_KEY ? `${MALL_API_KEY.slice(0, 8)}…` : '(缺失)'} | 库：${DB}`);

  // 造单一笔：只为拿到「有订单的会员」（用来验证 delete 的外键拦截）
  let ctx;
  try {
    ctx = await bootstrap({ qty: 1, skuCount: 1, autoCleanup: true });
  } catch (e) {
    console.error(`❌ 造测试数据失败（_setup.bootstrap）：${e.message}`);
    process.exit(1);
  }
  console.log(`  自建订单：id=${ctx.orderId} 会员=${ctx.memberId}（会员有订单 → 删不掉，用于 ⑦）\n`);

  const M = mark();
  const username = `mcp_member_${M}`;
  const password = `MCP@${M}a1`;
  const samples = [];
  let memberId = null;
  let addressId = null;
  let client;

  try {
    client = await connectServer({ MCP_ENABLE_WRITE: '1' }, 'member');

    // ① tools/list + 元信息
    await check('① tools/list 含 member / member_address / member_cart，action 枚举符合 write=1', async () => {
      const { tools } = await client.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const m = by.member;
      const a = by.member_address;
      const c = by.member_cart;
      const mEnum = m?.inputSchema?.properties?.action?.enum ?? [];
      const aEnum = a?.inputSchema?.properties?.action?.enum ?? [];
      const zh = (t) => /[\u4e00-\u9fa5]/.test(t?.title || '') && /[\u4e00-\u9fa5]/.test(t?.description || '');
      return {
        pass:
          !!m && !!a && !!c && zh(m) && zh(a) && zh(c) &&
          ['list', 'get', 'create', 'update', 'delete'].every((x) => mEnum.includes(x)) &&
          ['list', 'save', 'delete'].every((x) => aEnum.includes(x)),
        detail: `member=${mEnum.join('/')}｜member_address=${aEnum.join('/')}｜member_cart 存在=${!!c}｜工具共 ${tools.length} 个`,
      };
    });

    const before = { member: dbCount('member'), member_address: dbCount('member_address'), cart: dbCount('cart') };

    // ② member 只读
    await check('②a member(action=list) → 非空，分页字段齐全（默认每页 10）', async () => {
      const { text } = await call(client, 'member', { action: 'list', size: 50 });
      const j = jsonAfter(text);
      samples.push({ tool: 'member list', args: {}, line: text.split('\n')[0] });
      return {
        pass: j.total >= 1 && j.records.length >= 1 && j.page === 1 && Array.isArray(j.records) && !('password' in (j.records[0] || {})),
        detail: `total=${j.total}，首条=id=${j.records[0]?.id}/${j.records[0]?.username}`,
      };
    });
    await check('②b member(action=list, size=999) → size 夹到 50；size=1 → 1 条', async () => {
      const big = jsonAfter((await call(client, 'member', { action: 'list', size: 999 })).text);
      const one = jsonAfter((await call(client, 'member', { action: 'list', size: 1 })).text);
      return {
        pass: big.size === 50 && big.records.length === Math.min(50, big.total) && one.records.length === 1 && one.size === 1 && one.total === big.total,
        detail: `size999→${big.size} records=${big.records.length}；size1→records=${one.records.length} total=${one.total}`,
      };
    });
    await check('②c member(action=list, status=1) → 全部启用账号', async () => {
      const j = jsonAfter((await call(client, 'member', { action: 'list', status: 1, size: 50 })).text);
      return { pass: j.records.length >= 1 && j.records.every((r) => r.status === 1), detail: `${j.records.length} 个启用账号` };
    });
    await check('②d member(action=get) 不存在的 id → isError（后端返回 data=null 不报错，工具层判空）', async () => {
      const r = await expectError(client, 'member', { action: 'get', memberId: 999999 });
      return { pass: r.threw && /会员不存在/.test(r.text), detail: r.text.split('\n')[0].slice(0, 100) };
    });

    // ③ member 写：create → update（version 三态）
    await check('③a member(action=create) → 新建成功，version=0，回读不带密码', async () => {
      const { text } = await call(client, 'member', { action: 'create', username, password, nickname: `MCP会员${M}`, phone: '13700000000' });
      const j = jsonAfter(text);
      memberId = j.id ?? null;
      const row = memberId ? dbMember(memberId) : null;
      samples.push({ tool: 'member create', args: { username }, line: text.split('\n')[0] });
      return {
        pass: !!memberId && row?.username === username && row?.version === 0 && row?.status === 1 && !text.includes(password) && !('password' in j),
        detail: `id=${memberId} DB version=${row?.version} status=${row?.status}｜文本里出现明文密码=${text.includes(password)}`,
      };
    });
    await check('③b member(action=get) → 命中自己造的会员，返回里没有密码字段', async () => {
      const { text } = await call(client, 'member', { action: 'get', memberId });
      const j = jsonAfter(text);
      return {
        pass: j.id === memberId && j.username === username && !('password' in j) && !text.includes(password) && Number.isInteger(j.version),
        detail: `id=${j.id} username=${j.username} version=${j.version}`,
      };
    });
    await check('③c member(action=update) **不带 version** → isError 并说明必须给 version（后端 fail-closed 的前置闸）', async () => {
      const r = await expectError(client, 'member', { action: 'update', memberId, nickname: '不该生效' });
      const row = dbMember(memberId);
      return {
        pass: r.threw && /version/.test(r.text) && row?.version === 0,
        detail: `${r.text.split('\n')[0].slice(0, 100)}｜DB version=${row?.version}（未变）`,
      };
    });
    await check('③d member(action=update) 带**假 version** → 409「数据已被他人修改」，且数据没被改', async () => {
      const before = dbMember(memberId);
      const r = await expectError(client, 'member', { action: 'update', memberId, nickname: '不该生效', version: 42 });
      const after = dbMember(memberId);
      console.log('    ③d 被拒样例（完整文本）：');
      for (const line of r.text.split('\n')) console.log(`      ${line}`);
      return {
        pass: r.threw && /409/.test(r.text) && /数据已被他人修改/.test(r.text) && after?.nickname === before?.nickname && after?.version === before?.version,
        detail: `nickname 未变=${after?.nickname === before?.nickname}｜version ${before?.version}→${after?.version}`,
      };
    });
    await check('③e member(action=update) 带正确 version → 成功、version +1、改密不回显明文', async () => {
      const old = dbMember(memberId);
      const newPass = `MCP@new${M}`;
      const { text } = await call(client, 'member', { action: 'update', memberId, nickname: `MCP会员改${M}`, status: 0, password: newPass, version: old.version });
      const after = dbMember(memberId);
      const j2 = jsonAfter((await call(client, 'member', { action: 'get', memberId })).text);
      samples.push({ tool: 'member update', args: { memberId }, line: text.split('\n')[0] });
      return {
        pass:
          after?.nickname === `MCP会员改${M}` && after?.status === 0 && after?.version === old.version + 1 &&
          j2.version === old.version + 1 && !text.includes(newPass) && !text.includes(password),
        detail: `nickname=${after?.nickname} status=${after?.status} version ${old.version}→${after?.version}（回读 ${j2.version}）｜明文密码出现=${text.includes(newPass)}`,
      };
    });

    // ④ member_address：save（新增）→ save（修改）→ list → delete
    await check('④a member_address(action=save) 不给 addressId → 新增地址，list 能查到', async () => {
      const { text } = await call(client, 'member_address', {
        action: 'save',
        memberId,
        name: `收件人${M}`,
        phone: '13600000000',
        province: '广东省',
        city: '深圳市',
        region: '南山区',
        detail: `MCP 测试路 ${M} 号`,
        defaultStatus: 1,
      });
      const j = jsonAfter(text);
      addressId = j.id ?? null;
      const list = jsonAfter((await call(client, 'member_address', { action: 'list', memberId, size: 50 })).text).records;
      const mine = list.find((a) => Number(a.id) === Number(addressId));
      samples.push({ tool: 'member_address save(create)', args: { memberId }, line: text.split('\n')[0] });
      return { pass: !!addressId && !!mine && mine.name === `收件人${M}`, detail: `addressId=${addressId} 列表命中=${!!mine}` };
    });
    await check('④b member_address(action=save) 带 addressId → 修改生效（回读确认）', async () => {
      const { text } = await call(client, 'member_address', { action: 'save', memberId, addressId, detail: `MCP 改后地址 ${M}` });
      const list = jsonAfter((await call(client, 'member_address', { action: 'list', memberId, size: 50 })).text).records;
      const mine = list.find((a) => Number(a.id) === Number(addressId));
      return { pass: mine?.detail === `MCP 改后地址 ${M}` && text.includes('✅'), detail: `回读 detail=${mine?.detail}` };
    });
    await check('④c member_address(action=save) 新增时缺 name/phone → isError（参数边界）', async () => {
      const r = await expectError(client, 'member_address', { action: 'save', memberId, detail: 'x' });
      return { pass: r.threw && /name/.test(r.text), detail: r.text.split('\n')[0].slice(0, 110) };
    });
    await check('④d member_address(action=delete) → 删掉刚建的地址（DB 里确认没有）', async () => {
      const { text } = await call(client, 'member_address', { action: 'delete', addressId });
      return { pass: text.includes('✅') && !dbAddressExists(addressId), detail: `DB 里仍存在=${dbAddressExists(addressId)}` };
    });

    // ⑤ member_cart：只读，新会员是空车
    await check('⑤a member_cart(action=list, memberId=新会员) → 空车并给明确文案（不是空串）', async () => {
      const { text } = await call(client, 'member_cart', { action: 'list', memberId });
      const j = jsonAfter(text);
      return { pass: j.total === 0 && j.records.length === 0 && /购物车是空的/.test(text), detail: text.split('\n')[0].slice(0, 90) };
    });
    await check('⑤b member_cart(action=list) 全量 → 分页字段齐全（size 夹取生效）', async () => {
      const j = jsonAfter((await call(client, 'member_cart', { action: 'list', size: 999 })).text);
      return { pass: j.size === 50 && j.records.length === Math.min(50, j.total), detail: `total=${j.total} size=${j.size}` };
    });

    // ⑥ 只读校验（只读段内没有写操作：地址的新增/删除已在 ④ 完成，这里用净结果对照）
    const afterReads = { member: dbCount('member'), member_address: dbCount('member_address'), cart: dbCount('cart') };
    assert(afterReads.cart === before.cart, '⑥ 只读校验：cart 行数不变（只读工具没写库）', `before=${before.cart}, after=${afterReads.cart}`);
    assert(
      afterReads.member === before.member + 1,
      '⑥ 只读校验：member 行数 = 原有 +1（只多了我建的那个会员，没有别的写入）',
      `before=${before.member}, after=${afterReads.member}`
    );
    assert(
      afterReads.member_address === before.member_address,
      '⑥ 只读校验：member_address 行数回到原值（新增 1 条 + 删除 1 条抵消）',
      `before=${before.member_address}, after=${afterReads.member_address}`
    );

    // ⑦ delete 的 L3 后果
    await check('⑦a member(action=delete) 删除**有订单**的会员 → 后端拒绝（外键无级联），会员还在', async () => {
      const r = await expectError(client, 'member', { action: 'delete', memberId: ctx.memberId });
      const still = dbMember(ctx.memberId) !== null;
      return {
        pass: r.threw && /失败|数据处理/.test(r.text) && still,
        detail: `${r.text.split('\n')[0].slice(0, 100)}｜会员仍在库中=${still}`,
      };
    });
    await check('⑦b member(action=delete) 删除**没有订单**的会员 → 成功，get 之后是「会员不存在」', async () => {
      const { text } = await call(client, 'member', { action: 'delete', memberId });
      const gone = dbMember(memberId) === null;
      const g = await expectError(client, 'member', { action: 'get', memberId });
      samples.push({ tool: 'member delete', args: { memberId }, line: text.split('\n')[0] });
      return { pass: gone && g.threw && /会员不存在/.test(g.text), detail: `DB 已删=${gone}｜get 报「会员不存在」=${g.threw}` };
    });

    await client.close();
    client = null;

    // ⑧ write=0
    await check('⑧a write=0 → member 只剩 list/get、member_address 只剩 list、member_cart 仍然只有只读 list', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'member-ro');
      const { tools } = await ro.listTools();
      const by = Object.fromEntries(tools.map((t) => [t.name, t]));
      const mEnum = by.member?.inputSchema?.properties?.action?.enum ?? [];
      const aEnum = by.member_address?.inputSchema?.properties?.action?.enum ?? [];
      // member_cart 本来就是纯只读工具：**没有 action 参数**（不是「枚举里只剩 list」），
      // 所以这里断言的是「write=0 时它还在、且 schema 里没有 action/没有写入口」。
      const cartProps = Object.keys(by.member_cart?.inputSchema?.properties ?? {});
      await ro.close();
      return {
        pass:
          mEnum.length === 2 && mEnum.includes('list') && mEnum.includes('get') &&
          aEnum.length === 1 && aEnum[0] === 'list' &&
          !!by.member_cart && !cartProps.includes('action'),
        detail: `member=${mEnum.join('/')}｜member_address=${aEnum.join('/')}｜member_cart 参数=[${cartProps.join(',')}]（无 action）`,
      };
    });
    await check('⑧b write=0 → 调 member(action=delete) 在协议层被拒', async () => {
      const ro = await connectServer({ MCP_ENABLE_WRITE: '0' }, 'member-ro2');
      const r = await expectError(ro, 'member', { action: 'delete', memberId: 1 });
      const okGet = await expectError(ro, 'member', { action: 'list' });
      await ro.close();
      return { pass: r.threw && /enum|Invalid/i.test(r.text) && !okGet.threw, detail: `写动作被拒=${r.threw}｜只读 list 可用=${!okGet.threw}` };
    });
  } finally {
    if (client) await client.close().catch(() => {});
    // 兜底清理：地址 → 会员（有订单的那个由 ctx.cleanup 处理）
    if (addressId) await adminRequest('DELETE', `/admin/member-addresses/${addressId}`).catch(() => {});
    if (memberId && dbMember(memberId)) {
      await adminRequest('DELETE', `/admin/members/${memberId}`).catch((e) => console.log(`  （清理会员 ${memberId} 跳过：${e.message}）`));
    }
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
    console.log('✅ 会员域可用：member 增改删（update 强制 version：缺→工具层拒、假→后端 409）、密码永不回显；');
    console.log('   member_address 增改删、member_cart 只读；有订单的会员删不掉（外键）——被拒时应改为禁用。');
    console.log('ℹ️ 自建会员/地址已清理，订单已删除；直接重跑即可。');
  }
  console.log('─'.repeat(64));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('❌ 测试脚本异常：', e?.stack || e);
  process.exit(1);
});
