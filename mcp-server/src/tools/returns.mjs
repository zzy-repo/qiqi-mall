// 退货域（returns）工具：2 个
//   return_apply(action)   list / get / audit      —— 退货申请（audit 是 L3：通过=退款+回补库存）
//   return_reason(action)  list / create / update / delete —— 原因字典（配置类，L2）
//
// ── 后端实际形态（已读 ReturnApplyController / ReturnReasonController 确认，跟「标准 REST」不一样）──
//   ① GET /admin/return-applies?status=&orderId=  返回 List、**不分页**，每项后端已 join 好 orderSn
//   ② **没有** GET /admin/return-applies/{id} → action=get 只能「取列表再挑一条」（工具层做，含判空）
//   ③ 审核：PUT /admin/return-applies/{id}  body {status:1通过/2拒绝, description?}，支持 ?dryRun=true
//   ④ ⚠️ **return_apply 表没有 version 列**，所以这个接口**根本不收 version** —— 并发控制在后端用
//      两道影响行数闸门（同一事务内）：申请行 UPDATE ... WHERE id=? AND status=0 影响 0 行 → 409；
//      订单行 @Version 更新 0 行 → 409 **整笔回滚**（绝不会「申请已通过、货没退、库存回补两次」）。
//      工具层**不能**凭空造一个 version 参数：那会让每一次审核都被后端当成非法入参。
//      （这就是本域与 update_product / member(update) 的区别，别照抄那两处的「zod 必填 version」。）
//   ⑤ 原因字典：List 不分页；PUT 无 version（影响 0 行 → 409「记录已被删除」）；
//      DELETE 被退货申请引用时后端直接拒绝（历史申请要能追溯原因）。

import { z } from 'zod';
import {
  listReturnApplies,
  auditReturnApply,
  listReturnReasons,
  createReturnReason,
  updateReturnReason,
  deleteReturnReason,
} from '../mall.mjs';
import { text, fail, failWrite, clampPage, paginate, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

// ---------- 状态码中文化 ----------
// 映射来源：oms/entity/ReturnApply.java 注释（0待处理 1通过 2拒绝）+ admin-web 一致。
const RETURN_STATUS_TEXT = ['待处理', '已通过', '已拒绝'];
const returnStatusLabel = (v) => RETURN_STATUS_TEXT[v] ?? `未知状态(${v ?? '-'})`;

const APPLY_ACTIONS_ALL = ['list', 'get', 'audit'];
const APPLY_ACTIONS_READONLY = ['list', 'get'];

const pageSchema = {
  page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
  size: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
};

/** 退货申请一行摘要 */
function applyRowText(r, i) {
  return (
    `  ${i + 1}. id=${r.id} 订单=${r.orderSn || `#${r.orderId}`} 状态=${returnStatusLabel(r.status)}(${r.status}) ` +
    `原因=${r.returnReason ?? '-'}（reasonId=${r.reasonId ?? '-'}） 会员=${r.memberId ?? '-'} 说明=${r.description ?? '-'}`
  );
}

/**
 * 注册退货域工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerReturnsTools(server, { writeEnabled } = {}) {
  const applyActions = writeEnabled ? APPLY_ACTIONS_ALL : APPLY_ACTIONS_READONLY;
  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → return_apply 只开放 list / get（audit 不开放）；return_reason 只开放 list');
  }

  // ---------------- ① 退货申请 ----------------
  server.registerTool(
    'return_apply',
    {
      title: '退货申请（查 / 审核）',
      description:
        '按 action 处理退货申请。list=按状态/订单分页查（只读）；get=看单条申请详情（只读）；' +
        'audit=审核（**L3 高影响**）。' +
        '⚠️ audit 的后果很重：**通过(1) = 订单退款 + 记一条退款流水 + 把该订单全部明细的数量回补到 SKU 总池库存 + 订单转已退款(6)**；' +
        '**拒绝(2) = 订单退回已付款(1)，不退款、不回补库存**。两者都不可撤回（后端只允许「待处理」被审核一次）。' +
        '所以：先 list / get 看清申请与订单，把「退多少钱、回补多少件」念给用户确认，并建议先 dryRun=true 预演（后端会返回 willDo 的中文后果描述）再执行。' +
        '后端没有版本号可传（return_apply 表无 version 列），并发靠「仅待处理可审」的状态守卫 + 影响行数闸门兜住：' +
        '别人刚审过 / 订单被别人改过 → 409，此时请重新读一次再决定，不要盲目重试。',
      inputSchema: {
        action: z.enum(applyActions).describe('list=列表(只读) / get=详情(只读) / audit=审核(L3，通过即退款+回补库存)'),
        applyId: z.number().int().positive().optional().describe('退货申请 id：get / audit 必填（list 时忽略）'),
        status: z.number().int().min(0).max(2).optional().describe('list 过滤：0待处理 / 1已通过 / 2已拒绝；不传=全部'),
        orderId: z.number().int().positive().optional().describe('list 过滤：只看某笔订单的退货申请；不传=全部订单'),
        auditStatus: z
          .number()
          .int()
          .min(1)
          .max(2)
          .optional()
          .describe('audit 的审核结论（**必填**）：1=通过（退款+回补库存，订单转已退款） / 2=拒绝（订单退回已付款）'),
        description: z.string().optional().describe('audit 时顺带写回申请说明（可选；不传=不改）'),
        dryRun: z.boolean().optional().describe('true=只预演：返回 willDo（不落库、不退款、不回补库存），建议审核前先跑一次'),
        ...pageSchema,
      },
    },
    async ({ action, applyId, status, orderId, auditStatus, description, dryRun, page, size }) => {
      if (!writeEnabled && action === 'audit') {
        return fail(new Error('写操作未启用：return_apply(action=audit) 需要 MCP_ENABLE_WRITE=1'));
      }
      try {
        if (action === 'list') {
          const { page: p, size: s } = clampPage({ page, size });
          const rows = await listReturnApplies({ status, orderId });
          const all = Array.isArray(rows) ? rows : [];
          const paged = paginate(all, { page: p, size: s });
          const cond = [status !== undefined ? `状态=${returnStatusLabel(status)}(${status})` : null, orderId !== undefined ? `订单=${orderId}` : null].filter(Boolean);
          const head = `退货申请：${pageHeader('', paged)}｜条件：${cond.length ? cond.join('，') : '无（全部）'}｜待处理 ${all.filter((r) => r.status === 0).length} 条`;
          if (!all.length) {
            return text(
              `${head}\n没有符合条件的退货申请。可以去掉 status / orderId 再查一次。\n\nJSON:\n${JSON.stringify(paged, null, 2)}`
            );
          }
          return text(`${head}\n${paged.records.map(applyRowText).join('\n')}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }

        if (action === 'get') {
          if (!applyId) throw new Error('action=get 必须给 applyId');
          const rows = await listReturnApplies({});
          const found = (Array.isArray(rows) ? rows : []).find((r) => Number(r.id) === Number(applyId));
          // 后端没有单条详情接口，id 不存在时列表里就是没有 → 工具层必须自己判空给中文错误
          if (!found) throw new Error(`退货申请不存在（id=${applyId}）：后端没有单条详情接口，这里是从全部申请里查的，请用 return_apply(action=list) 确认 id`);
          return text(
            `退货申请详情 id=${found.id}（订单 ${found.orderSn || `#${found.orderId}`}）\n` +
              `  状态=${returnStatusLabel(found.status)}(${found.status})${found.status === 0 ? '（可审核）' : '（已处理，不能再审）'}\n` +
              `  原因=${found.returnReason ?? '-'}（reasonId=${found.reasonId ?? '-'}） 会员=${found.memberId ?? '-'}\n` +
              `  说明=${found.description ?? '-'}\n\n` +
              `JSON:\n${JSON.stringify(found, null, 2)}`
          );
        }

        // ---------- audit（L3）----------
        if (!applyId) throw new Error('action=audit 必须给 applyId');
        if (auditStatus === undefined) throw new Error('action=audit 必须给 auditStatus：1=通过（退款+回补库存） / 2=拒绝（订单退回已付款）');

        const resp = await auditReturnApply(applyId, { status: auditStatus, description, dryRun });
        if (dryRun === true) {
          return text(
            `预演（未落库、未退款、未回补库存）— 审核退货申请 ${applyId}（结论=${returnStatusLabel(auditStatus)}）：\n` +
              `  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\n` +
              `用户明确同意后再调一次 return_apply(action=audit, applyId=${applyId}, auditStatus=${auditStatus})（不带 dryRun）执行。\n\n` +
              `JSON:\n${JSON.stringify(resp, null, 2)}`
          );
        }

        // 执行成功：后端只回 {code:0, data:null} → 回读一次拿最新状态（申请 + 订单）
        let after = null;
        try {
          const rows = await listReturnApplies({});
          after = (Array.isArray(rows) ? rows : []).find((r) => Number(r.id) === Number(applyId)) ?? null;
        } catch {
          after = null;
        }
        return text(
          `✅ 退货申请 ${applyId} 已审核为「${returnStatusLabel(auditStatus)}」。\n` +
            (auditStatus === 1
              ? '  已发生：订单退款 + 一条退款流水 + 该订单全部明细数量回补到 SKU 总池库存 + 订单转已退款(6)。\n'
              : '  已发生：订单退回已付款(1)；不退款、不回补库存。\n') +
            (after ? `  回读：申请状态=${returnStatusLabel(after.status)}(${after.status})\n` : '  回读失败，请用 return_apply(action=list) 确认最新状态。\n') +
            `\nJSON:\n${JSON.stringify({ applyId, auditStatus, apply: after ?? resp }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ---------------- ② 退货原因字典 ----------------
  server.registerTool(
    'return_reason',
    {
      title: '退货原因字典（查 / 增 / 改 / 删）',
      description:
        'C 端「申请退货」下拉用的原因字典。list=查全部（只读，量小不分页）；' +
        'create=新增一条原因；update=改原因名；delete=删除（**L2**）。' +
        '⚠️ 写操作会真改配置数据：delete 时若该原因**已被退货申请引用，后端会直接拒绝**（历史申请要能追溯原因），' +
        '被拒时换「停用/改名」而不是硬删。原因表没有 version 列：update 时若这条刚被别人删掉，后端回 409「记录已被删除」，' +
        '此时重新 list 一次即可，不要重试旧的 id。',
      inputSchema: {
        action: z.enum(writeEnabled ? ['list', 'create', 'update', 'delete'] : ['list']).describe('list=列表(只读) / create=新增 / update=改名 / delete=删除'),
        reasonId: z.number().int().positive().optional().describe('原因 id：update / delete 必填（先用 list 查）'),
        name: z.string().min(1).optional().describe('原因名（如「质量问题」「不想要了」）：create 必填；update 时必填'),
        ...pageSchema,
      },
    },
    async ({ action, reasonId, name, page, size }) => {
      try {
        if (action === 'list') {
          const { page: p, size: s } = clampPage({ page, size });
          const rows = await listReturnReasons();
          const all = Array.isArray(rows) ? rows : [];
          const paged = paginate(all, { page: p, size: s });
          const head = `退货原因字典：${pageHeader('', paged)}`;
          if (!all.length) return text(`${head}\n（字典是空的，可用 return_reason(action=create, name="质量问题") 建一条）\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
          const body = paged.records.map((r, i) => `  ${i + 1}. id=${r.id} ${r.name}`).join('\n');
          return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }

        if (!writeEnabled) {
          return fail(new Error(`写操作未启用：return_reason(action=${action}) 需要 MCP_ENABLE_WRITE=1`));
        }

        if (action === 'create') {
          if (!name) throw new Error('action=create 必须给 name（原因名）');
          const created = await createReturnReason({ name });
          return text(
            `✅ 已新增退货原因：id=${created?.id ?? '(后端未返回)'}「${created?.name ?? name}」\n` +
              `  新原因会立刻出现在 C 端「申请退货」下拉里。\n\n` +
              `JSON:\n${JSON.stringify(created, null, 2)}`
          );
        }

        if (!reasonId) throw new Error(`action=${action} 必须给 reasonId`);
        if (action === 'update' && !name) throw new Error('action=update 必须给 name（新的原因名）');

        try {
          if (action === 'update') await updateReturnReason(reasonId, { name });
          else await deleteReturnReason(reasonId);
        } catch (e) {
          // 无 version 列 → 这里是 409「记录已被删除」或「已被退货申请引用，不能删」
          return failWrite(e, { action: action === 'update' ? '退货原因修改' : '退货原因删除', label: `reasonId=${reasonId}`, rereadTool: 'return_reason（action=list）' });
        }

        // 回读字典（无 version 概念，回读只为让模型看到改后的真实状态）
        let after = null;
        try {
          const rows = await listReturnReasons();
          after = (Array.isArray(rows) ? rows : []).find((r) => Number(r.id) === Number(reasonId)) ?? null;
        } catch {
          after = null;
        }
        return text(
          action === 'update'
            ? `✅ 退货原因 ${reasonId} 已改名为「${after?.name ?? name}」。\n\nJSON:\n${JSON.stringify({ reasonId, reason: after }, null, 2)}`
            : `✅ 退货原因 ${reasonId} 已删除（回读确认已不在字典里=${after === null}）。\n\nJSON:\n${JSON.stringify({ reasonId, deleted: after === null }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );
}
