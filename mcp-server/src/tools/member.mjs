// 会员域（member）工具：3 个
//   member(action)          list / get / create / update / delete   —— 会员账号（update 吃 version；delete 属 L3）
//   member_address(action)  list / save / delete                    —— 收货地址（后台视角，L2）
//   member_cart(action)     list / create / update / delete        —— 购物车（第 5 批补齐后台的增删改；
//                                                                      注意这与 /portal/cart 的买家自助购物车是两份数据口径：
//                                                                      这里改的是**库里那条记录**，后台运维手段）
//
// ── 后端实际形态（已读 MemberController / MemberAddressController / CartController 确认）──
//   ① GET /admin/members?status=&username=  返回 List、**不分页**（username 是 like 模糊）；每项带 version
//   ② GET /admin/members/{id}               单条；**id 不存在时 data=null 且 code=0（不报错）**→ 工具层判空
//      （另有 GET /admin/members/{id}/detail = {member,addresses,carts} 聚合；本批改用细粒度接口，
//        因为「看地址」「看购物车」本来就是两个独立工具，聚合反而让模型多扒一层）
//   ③ PUT /admin/members/{id}               **收 body.version（必填）**：缺 → 400，被抢改 → 409。
//      → 所以 member(action=update) 的 version 是 **zod 必填**（与 update_product 同口径；这一张表真的有 version 列）
//   ④ DELETE /admin/members/{id}            无状态守卫，无 version —— 属于 L3：
//      ⚠️ order.member_id 外键**没有 ON DELETE CASCADE** → 有订单的会员删不掉（后端报外键错误）
//      ⚠️ member_address / cart 是 CASCADE → 删会员会**连带删掉他的地址和购物车**
//   ⑤ /admin/member-addresses、/admin/carts：List 不分页、**无 version 列**（PUT 影响 0 行 → 409「记录已被删除」）

import { z } from 'zod';
import {
  listMembers,
  memberDetail,
  createMember,
  updateMember,
  deleteMember,
  listMemberAddresses,
  createMemberAddress,
  updateMemberAddress,
  deleteMemberAddress,
  listCarts,
  createCart,
  updateCart,
  deleteCart,
} from '../mall.mjs';
import { text, fail, failWrite, clampPage, paginate, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

const memberStatusLabel = (v) => (v === 1 ? '启用' : v === 0 ? '禁用' : `未知(${v ?? '-'})`);

const pageSchema = {
  page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
  size: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
};

/** 会员一行摘要（**绝不打印 password**：后端本身也不回密码，这里再保险一次） */
function memberRowText(m, i) {
  return `  ${i + 1}. id=${m.id} 账号=${m.username} 昵称=${m.nickname ?? '-'} 手机=${m.phone ?? '-'} 状态=${memberStatusLabel(m.status)} version=${m.version ?? '-'}`;
}

function addressRowText(a, i) {
  const region = [a.province, a.city, a.region].filter(Boolean).join(' ');
  return `  ${i + 1}. id=${a.id} ${a.name ?? '-'} ${a.phone ?? '-'} ${region} ${a.detail ?? '-'}${a.defaultStatus === 1 ? '（默认）' : ''}`;
}

/**
 * 注册会员域工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerMemberTools(server, { writeEnabled } = {}) {
  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → member 只开放 list / get（create/update/delete 不开放）；member_address 只开放 list');
  }

  // ---------------- ① 会员 ----------------
  server.registerTool(
    'member',
    {
      title: '会员账号（查 / 增 / 改 / 删）',
      description:
        '会员账号维护。list=按状态/账号名分页查（只读）；get=看单个会员（只读）；create=新建；' +
        'update=改昵称/手机/状态/密码（**必须带 version**）；delete=删除会员（**L3**）。' +
        '⚠️ delete 的后果要说清：会员的**收货地址、购物车会被级联删掉**；' +
        '而只要他**还有订单**（order.member_id 外键无级联），后端会直接报外键错误、删不掉 —— 被拒时不要反复重试，' +
        '正确做法是把会员状态改成 0（禁用）而不是删除。' +
        'update 必须带 version（来自 list / get 的返回）：缺了后端回 400，期间被别人改过回 409，' +
        '此时请重新读最新数据、把新值念给用户确认后再提交，绝不要拿旧 version 重试。' +
        '密码只写不读：创建/改密时传明文，后端 BCrypt 后入库，任何读取接口都不会回密码。',
      inputSchema: {
        action: z
          .enum(writeEnabled ? ['list', 'get', 'create', 'update', 'delete'] : ['list', 'get'])
          .describe('list=列表(只读) / get=详情(只读) / create=新建 / update=修改(需version) / delete=删除(L3，级联删地址与购物车)'),
        memberId: z.number().int().positive().optional().describe('会员 id：get / update / delete 必填（list 时忽略）'),
        status: z.number().int().min(0).max(1).optional().describe('list 过滤 或 update 新状态：1 启用 / 0 禁用；create 不传默认 1'),
        username: z.string().min(1).optional().describe('list 过滤：账号名**模糊**匹配；不传=不按账号名过滤（create 必填）'),
        nickname: z.string().optional().describe('昵称：create / update 时可用；update 不传=不改'),
        phone: z.string().optional().describe('手机号：create / update 时可用；update 不传=不改'),
        password: z
          .string()
          .min(6)
          .optional()
          .describe('明文密码（至少 6 位）：create 必填；update 时传=改密（不传=不改）。⚠️ 只在参数里出现，不会被回显'),
        version: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe(
            '会员版本号：**action=update 时必填**（来自 member(action=list/get) 的返回）；delete / create / list / get 不需要。' +
              '（这里是 zod 可选、由工具层对 update 强校验，因为同一个工具还承载了不需要 version 的动作；后端仍然是 fail-closed：缺了回 400）'
          ),
        ...pageSchema,
      },
    },
    async ({ action, memberId, status, username, nickname, phone, password, version, page, size }) => {
      if (!writeEnabled && !['list', 'get'].includes(action)) {
        return fail(new Error(`写操作未启用：member(action=${action}) 需要 MCP_ENABLE_WRITE=1`));
      }
      try {
        if (action === 'list') {
          const { page: p, size: s } = clampPage({ page, size });
          const rows = await listMembers({ status, username });
          const all = Array.isArray(rows) ? rows : [];
          const paged = paginate(all, { page: p, size: s });
          const cond = [status !== undefined ? `状态=${memberStatusLabel(status)}` : null, username ? `账号含「${username}」` : null].filter(Boolean);
          const head = `会员列表：${pageHeader('', paged)}｜条件：${cond.length ? cond.join('，') : '无（全部）'}`;
          if (!all.length) {
            return text(`${head}\n没有符合条件的会员。可以去掉 status / username 再查一次。\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
          }
          return text(`${head}\n${paged.records.map(memberRowText).join('\n')}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }

        if (action === 'get') {
          if (!memberId) throw new Error('action=get 必须给 memberId');
          const m = await memberDetail(memberId);
          // 后端 id 不存在时 data=null 且 code=0（不报错）→ 工具层必须自己判空
          if (!m) throw new Error(`会员不存在（id=${memberId}），请用 member(action=list) 确认 id`);
          const safe = { ...m };
          delete safe.password; // 后端不回密码，这里再保险一次
          return text(
            `会员详情 id=${safe.id}\n` +
              `  账号=${safe.username} 昵称=${safe.nickname ?? '-'} 手机=${safe.phone ?? '-'}\n` +
              `  状态=${memberStatusLabel(safe.status)}(${safe.status}) version=${safe.version ?? '-'}（改它必须带这个号）\n` +
              `  注册时间=${safe.createTime ?? '-'}\n` +
              `  想看他的地址/购物车：member_address(action=list, memberId=${safe.id}) / member_cart(action=list, memberId=${safe.id})\n\n` +
              `JSON:\n${JSON.stringify(safe, null, 2)}`
          );
        }

        if (action === 'create') {
          if (!username) throw new Error('action=create 必须给 username');
          if (!password) throw new Error('action=create 必须给 password（明文，后端 BCrypt 后入库）');
          const created = await createMember({ username, password, nickname, phone, status });
          const safe = created ? { ...created } : null;
          if (safe) delete safe.password;
          return text(
            `✅ 已新建会员：id=${safe?.id ?? '(后端未返回)'} 账号=${safe?.username ?? username} 状态=${memberStatusLabel(safe?.status)}\n` +
              `  新会员 version=${safe?.version ?? 0}（后端新建即 0 版，接着改就用这个号）。\n\n` +
              `JSON:\n${JSON.stringify(safe, null, 2)}`
          );
        }

        if (!memberId) throw new Error(`action=${action} 必须给 memberId`);

        if (action === 'delete') {
          try {
            await deleteMember(memberId);
          } catch (e) {
            // 无 version、无状态守卫；失败原因多是「还有订单」（外键）
            return failWrite(e, { action: '会员删除', label: `memberId=${memberId}`, rereadTool: 'member（action=list）' });
          }
          let gone = null;
          try {
            gone = await memberDetail(memberId);
          } catch {
            gone = null;
          }
          return text(
            `✅ 会员 ${memberId} 已删除（回读 member=null=${gone === null}）。\n` +
              `  ⚠️ 该会员的收货地址与购物车已被级联删除；历史订单不受影响（订单里存的是收货快照）。\n\n` +
              `JSON:\n${JSON.stringify({ memberId, deleted: gone === null }, null, 2)}`
          );
        }

        // ---------- update（version 必填，工具层先强校验）----------
        if (version === undefined || version === null) {
          throw new Error(
            'action=update 必须给 version（来自 member(action=list/get) 的返回）。' +
              '后端 fail-closed：缺 version 一律拒绝（400），不会静默降级成「后写覆盖」。'
          );
        }
        const patch = { version };
        const changed = [];
        const setField = (key, val) => {
          if (val === undefined) return;
          patch[key] = val;
          changed.push(key);
        };
        setField('nickname', nickname);
        setField('phone', phone);
        setField('status', status);
        setField('password', password);
        // 账号名（username）刻意不在 update 开放：改名会让用户登不进去，属后台人工操作
        if (!changed.length) {
          throw new Error('action=update 没有要改的字段：nickname / phone / status / password 至少给一个（只传 memberId + version 不会产生任何修改）');
        }

        try {
          await updateMember(memberId, patch);
        } catch (e) {
          return failWrite(e, {
            action: '会员修改',
            label: `memberId=${memberId}（改 ${changed.join('、')}）`,
            oldVersion: version,
            rereadTool: `member(action=get, memberId=${memberId})`,
          });
        }

        // 成功：后端只回 {code:0,data:null} → 回读拿新 version
        let after = null;
        try {
          after = await memberDetail(memberId);
        } catch {
          after = null;
        }
        if (after) delete after.password;
        const newVersion = after?.version ?? null;
        const bump = typeof newVersion === 'number' ? newVersion - version : null;
        return text(
          `✅ 会员已更新：id=${memberId} 账号=${after?.username ?? '-'}\n` +
            `  改动字段：${changed.join('、')}（密码只写不读，不回显）\n` +
            `  version：${version} → ${newVersion ?? '(回读失败)'}${bump === 1 ? '（+1，乐观锁生效）' : ''}\n` +
            `  当前值：昵称=${after?.nickname ?? '-'} 手机=${after?.phone ?? '-'} 状态=${memberStatusLabel(after?.status)}\n` +
            `  下次再改这个会员请用 version=${newVersion}（旧 version 会被后端 409 拒掉）。\n\n` +
            `JSON:\n${JSON.stringify({ memberId, changed, oldVersion: version, newVersion, member: after }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ---------------- ② 收货地址（后台视角） ----------------
  server.registerTool(
    'member_address',
    {
      title: '会员收货地址（查 / 存 / 删）',
      description:
        '后台视角维护会员的收货地址。list=查地址（可按会员过滤，分页，只读）；' +
        'save=新增或修改（**给 addressId 就是改、不给就是新增**）；delete=删除（L2）。' +
        '⚠️ 写操作会真改会员的地址簿：save 覆盖的是「这个会员下次下单能选到的地址」，delete 会让该地址消失。' +
        '地址表没有 version 列：改一条刚被别人删掉的地址 → 409「记录已被删除」，重新 list 一次即可。' +
        '注意：**已下单的订单不受影响**（订单里存的是下单时的收货快照）。',
      inputSchema: {
        action: z.enum(writeEnabled ? ['list', 'save', 'delete'] : ['list']).describe('list=列表(只读) / save=新增或修改 / delete=删除'),
        memberId: z.number().int().positive().optional().describe('会员 id：save 必填；list 不传=全部会员的地址'),
        addressId: z.number().int().positive().optional().describe('地址 id：save 时传=修改这条；delete 必填'),
        name: z.string().min(1).optional().describe('收件人姓名（save 时必填）'),
        phone: z.string().min(1).optional().describe('收件人电话（save 时必填）'),
        province: z.string().optional().describe('省'),
        city: z.string().optional().describe('市'),
        region: z.string().optional().describe('区/县'),
        detail: z.string().optional().describe('详细地址'),
        defaultStatus: z.number().int().min(0).max(1).optional().describe('是否默认地址：1 是 / 0 否'),
        ...pageSchema,
      },
    },
    async ({ action, memberId, addressId, name, phone, province, city, region, detail, defaultStatus, page, size }) => {
      if (!writeEnabled && action !== 'list') {
        return fail(new Error(`写操作未启用：member_address(action=${action}) 需要 MCP_ENABLE_WRITE=1`));
      }
      try {
        if (action === 'list') {
          const { page: p, size: s } = clampPage({ page, size });
          const rows = await listMemberAddresses({ memberId });
          const all = Array.isArray(rows) ? rows : [];
          const paged = paginate(all, { page: p, size: s });
          const scope = memberId !== undefined ? `会员 ${memberId} 的` : '全部会员的';
          const head = `收货地址（${scope}）：${pageHeader('', paged)}`;
          if (!all.length) {
            return text(`${head}\n${scope}地址簿是空的（会员可能还没填过地址）。\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
          }
          return text(`${head}\n${paged.records.map(addressRowText).join('\n')}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }

        if (action === 'delete') {
          if (!addressId) throw new Error('action=delete 必须给 addressId');
          try {
            await deleteMemberAddress(addressId);
          } catch (e) {
            return failWrite(e, { action: '地址删除', label: `addressId=${addressId}`, rereadTool: 'member_address（action=list）' });
          }
          return text(`✅ 收货地址 ${addressId} 已删除。\n\nJSON:\n${JSON.stringify({ addressId, deleted: true }, null, 2)}`);
        }

        // ---------- save ----------
        if (!memberId) throw new Error('action=save 必须给 memberId（地址挂在会员下）');
        const patch = {};
        const changed = [];
        const setField = (key, val) => {
          if (val === undefined) return;
          patch[key] = val;
          changed.push(key);
        };
        setField('name', name);
        setField('phone', phone);
        setField('province', province);
        setField('city', city);
        setField('region', region);
        setField('detail', detail);
        setField('defaultStatus', defaultStatus);

        if (addressId) {
          if (!changed.length) throw new Error('action=save 带 addressId（修改）时至少要给一个要改的字段');
          try {
            await updateMemberAddress(addressId, patch);
          } catch (e) {
            return failWrite(e, { action: '地址修改', label: `addressId=${addressId}`, rereadTool: `member_address(action=list, memberId=${memberId})` });
          }
          let after = null;
          try {
            const rows = await listMemberAddresses({ memberId });
            after = (Array.isArray(rows) ? rows : []).find((a) => Number(a.id) === Number(addressId)) ?? null;
          } catch {
            after = null;
          }
          return text(
            `✅ 收货地址 ${addressId} 已更新（会员 ${memberId}）：改动字段=${changed.join('、')}\n` +
              `  当前：${after ? addressRowText(after, 0).trim() : '(回读失败，请再 list 一次)'}\n\n` +
              `JSON:\n${JSON.stringify({ addressId, memberId, changed, address: after }, null, 2)}`
          );
        }

        if (!name || !phone) throw new Error('action=save 新增地址时必须给 name（收件人）和 phone（电话）');
        const created = await createMemberAddress({ memberId, ...patch });
        return text(
          `✅ 已为会员 ${memberId} 新增收货地址：id=${created?.id ?? '(后端未返回)'}\n` +
            `  ${created ? addressRowText(created, 0).trim() : ''}\n\n` +
            `JSON:\n${JSON.stringify(created, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ---------------- ③ 购物车（查 / 增 / 改 / 删） ----------------
  const cartActions = writeEnabled ? ['list', 'create', 'update', 'delete'] : ['list'];
  server.registerTool(
    'member_cart',
    {
      title: '会员购物车（查 / 增 / 改 / 删）',
      description:
        '后台视角查看和维护会员购物车（有哪些商品/SKU、各几件）。用户问"这个会员购物车里有什么""加购了多少"时用 list。' +
        'create=给会员手工加一条；update=改数量/改 SKU；delete=删掉这一条（后台运维手段，用于纠正脏数据）。' +
        '⚠️ 这里改的是**库里那条记录**，不是买家在用的小程序购物车（买家自助加购走 C 端 /portal/cart，需要会员身份，API Key 无权调用）。' +
        '列表后端返回 List 不分页，工具层按同一套规则（默认 10、最大 50）夹取。' +
        '注意：cart 表**没有版本号** —— 改一条刚被别人删掉的记录 → 后端 409「记录已被删除」，重新 list 一次即可。',
      inputSchema: {
        action: z.enum(cartActions).describe('list=列表(只读) / create=新增 / update=改数量或改 SKU / delete=删除'),
        memberId: z.number().int().positive().optional().describe('会员 id：create 必填；list 不传=全部会员的购物车'),
        cartId: z.number().int().positive().optional().describe('购物车行 id：update / delete 必填（先用 list 查）'),
        productId: z.number().int().positive().optional().describe('商品 id：create 必填（后端不校验，但缺了这条数据没有意义）'),
        productSkuId: z.number().int().positive().optional().describe('SKU id：create 必填；update 传了才改'),
        quantity: z.number().int().min(1).optional().describe('数量：create 必填；update 传了才改（必须 >= 1，清空请用 delete）'),
        ...pageSchema,
      },
    },
    async ({ action, memberId, cartId, productId, productSkuId, quantity, page, size }) => {
      if (!writeEnabled && action !== 'list') {
        return fail(new Error(`写操作未启用：member_cart(action=${action}) 需要 MCP_ENABLE_WRITE=1（当前只开放 action=list）`));
      }
      try {
        if (action === 'create') {
          if (!memberId) throw new Error('action=create 必须给 memberId');
          if (!productId || !productSkuId) throw new Error('action=create 必须给 productId 与 productSkuId');
          if (!quantity) throw new Error('action=create 必须给 quantity（>=1）');
          const created = await createCart({ memberId, productId, productSkuId, quantity });
          return text(
            `✅ 已为会员 ${memberId} 新增购物车行：id=${created?.id} 商品=${created?.productId} SKU=${created?.productSkuId} 数量=${created?.quantity}\n\n` +
              `JSON:\n${JSON.stringify(created, null, 2)}`
          );
        }
        if (action === 'update') {
          if (!cartId) throw new Error('action=update 必须给 cartId');
          const patch = {};
          if (quantity !== undefined) patch.quantity = quantity;
          if (productSkuId !== undefined) patch.productSkuId = productSkuId;
          if (productId !== undefined) patch.productId = productId;
          if (!Object.keys(patch).length) throw new Error('action=update 至少要给 quantity / productSkuId / productId 其中一个');
          try {
            await updateCart(cartId, patch);
          } catch (e) {
            return failWrite(e, { action: '购物车修改', label: `cartId=${cartId}`, rereadTool: 'member_cart(action=list)' });
          }
          let after = null;
          try {
            const rows = await listCarts({ memberId });
            after = (Array.isArray(rows) ? rows : []).find((c) => Number(c.id) === Number(cartId)) ?? null;
          } catch {
            after = null;
          }
          return text(
            `✅ 购物车行 ${cartId} 已更新：${Object.keys(patch).join('、')}\n` +
              `  当前：${after ? `会员=${after.memberId} 商品=${after.productId} SKU=${after.productSkuId} 数量=${after.quantity}` : '(回读失败，请再 list 一次)'}\n\n` +
              `JSON:\n${JSON.stringify({ cartId, changed: Object.keys(patch), cart: after }, null, 2)}`
          );
        }
        if (action === 'delete') {
          if (!cartId) throw new Error('action=delete 必须给 cartId');
          try {
            await deleteCart(cartId);
          } catch (e) {
            return failWrite(e, { action: '购物车删除', label: `cartId=${cartId}`, rereadTool: 'member_cart(action=list)' });
          }
          let gone = true;
          try {
            const rows = await listCarts({ memberId });
            gone = !(Array.isArray(rows) ? rows : []).some((c) => Number(c.id) === Number(cartId));
          } catch {
            gone = true;
          }
          return text(
            `✅ 购物车行 ${cartId} 已删除。回读核对：${gone ? '已不存在（符合预期）' : '⚠️ 仍能读到，请人工核查'}\n\n` +
              `JSON:\n${JSON.stringify({ cartId, deleted: true, verifiedGone: gone }, null, 2)}`
          );
        }

        const { page: p, size: s } = clampPage({ page, size });
        const rows = await listCarts({ memberId });
        const all = Array.isArray(rows) ? rows : [];
        const paged = paginate(all, { page: p, size: s });
        const scope = memberId !== undefined ? `会员 ${memberId} 的` : '全部会员的';
        const head = `购物车（${scope}）：${pageHeader('', paged)}`;
        if (!all.length) {
          return text(`${head}\n${scope}购物车是空的。\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }
        const body = paged.records
          .map((c, i) => `  ${i + 1}. id=${c.id} 会员=${c.memberId} 商品=${c.productId} SKU=${c.productSkuId} 数量=${c.quantity} 加购时间=${c.createTime ?? '-'}`)
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );
}
