// 自动审核域（第 6 批 · 只读巡检域）：4 个工具，**一个写工具都没有**
//
//   list_order_audit_queue → GET /admin/order-audit/queue?result=&page=&size=          （scope: audit:read）
//   get_order_audit_trail  → GET /admin/order-audit/orders/{id}/trail                  （scope: audit:read）
//   list_audit_policies    → GET /admin/audit-policies                                 （scope: audit:read）
//   list_audit_runs        → GET /admin/audit-runs?page=&size=                         （scope: audit:read）
//
// ── 什么时候该用我 ──────────────────────────────────────────────────────────
//   「这单为什么没过审 / 为什么被冻结」「现在有多少单卡在人工队列」「策略是怎么配的、版本几」
//   「上一轮 run 跑了多少单、拦了多少」。**读判决依据与巡检**，不是改判决。
//
// ── 拿不到的（结构上就 403，不是忘了做）─────────────────────────────────────
//   ✗ 拿不到：`POST /admin/order-audit/run` 一次批量改大量订单的命运；
//   ✗ 拿不到：`POST /admin/order-audit/orders/{id}/freeze` / `unfreeze` 动的是「人的刹车」；
//   ✗ 拿不到：`POST /admin/audit-policies` / `PUT` / `DELETE` / `move` 动的是判定标准本身。
//   它们的写 scope 是 audit:write，**不在 ALL_SCOPES 里** ⇒ 任何 API Key 都拿不到（这段是清单，不是工具）。
//   一句话口径：AI 能读证据、能巡检，不能改判定、不能放行。
//
// ── 后端真实形态（已读 audit/controller/*.java + AuditQueryService 确认）─────
//   ① /order-audit/queue 与 /audit-runs 返回的是**手写 Map**：`{total,page,size,rows}` ——
//      行数组叫 **rows 不是 records**，行内字段是 **snake_case**（order_sn / reason_text / matched_policy_name …）。
//      工具层**不改名**（改了模型反而对不上后端），只在摘要里给人话、JSON 里原样透出；
//   ② /orders/{id}/trail 返回**裸数组**，每项 {segment,at,title,detail,raw,aligned}：
//      segment = DECISION（判定）/ STATUS（状态台账）/ AUDIT（操作审计）三段按时间拼；
//      **id 不存在时是空数组且 code=0**（不报错）→ 工具层必须自己判空并说清楚；
//      aligned 是「这条判定 ↔ 这条状态台账行」的对齐信息（AUDIT 段给 null，它不改状态）；
//   ③ /audit-policies 返回 {policies[], globalExclusions[], fields[], operators[]}：
//      fields/operators 是**字段与操作符元数据**（前端条件编辑器据此渲染），照实带上便于解释策略；
//   ④ 五类判定（AuditResult）：AUTO_PASS 自动过 / MANUAL 转人工 / BLOCKED 前置拦截 / FROZEN 命中异常冻结 /
//      NO_MATCH 没有 AUTO 匹配；**SKIPPED 是历史值**，新跑的轮次不再产生（按它筛通常为空）。

import { z } from 'zod';
import { orderAuditQueue, orderAuditTrail, listAuditPolicies, listAuditRuns } from '../mall.mjs';
import { text, fail, clampPage, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

/** 五判定 → 中文（顺序即「严重度」：越靠后越需要人看） */
const RESULT_TEXT = {
  AUTO_PASS: '自动过审',
  MANUAL: '转人工',
  BLOCKED: '前置拦截',
  FROZEN: '异常冻结',
  NO_MATCH: '无策略命中',
  SKIPPED: '未参与（历史值）',
};

/** 策略类型 → 中文 */
const POLICY_TYPE_TEXT = { AUTO: '自动过审', BLOCK: '前置拦截', EXCEPTION: '异常冻结' };

/** 审核时间线三段 → 中文 */
const SEGMENT_TEXT = { DECISION: '判定', STATUS: '状态台账', AUDIT: '操作审计' };

const resultLabel = (v) => RESULT_TEXT[v] ?? `未知判定(${v ?? '-'})`;

/** 把 queue 的一行（snake_case）翻成一句人话；JSON 里仍是后端原字段 */
function queueRowText(r, i) {
  const parts = [
    `  ${i + 1}. 订单 ${r.order_sn ?? '-'}（orderId=${r.order_id ?? '-'}）判定=${resultLabel(r.result)}`,
    r.run_id !== undefined ? `run=${r.run_id}` : null,
    r.round_no !== undefined ? `第 ${r.round_no} 轮` : null,
    r.matched_policy_name ? `命中策略「${r.matched_policy_name}」v${r.matched_policy_version_no ?? '-'}` : null,
    r.would_be_auto_policy_name ? `本可自动过（策略「${r.would_be_auto_policy_name}」）` : null,
    r.total_amount !== undefined ? `金额=${r.total_amount}` : null,
    r.order_status !== undefined ? `订单状态码=${r.order_status}` : null,
    r.shop_name ? `店铺=${r.shop_name}` : null,
    r.created_at ? `时间=${r.created_at}` : null,
  ].filter(Boolean);
  const head = parts.join('｜');
  const why = r.reason_text ? `\n      判定理由：${r.reason_text}` : '';
  return `${head}${why}`;
}

/**
 * 注册自动审核域工具（纯只读，不受 MCP_ENABLE_WRITE 影响）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 */
export function registerAuditTools(server) {
  server.registerTool(
    'list_order_audit_queue',
    {
      title: '自动审核人工队列（五判定）',
      description:
        '分页看「每张单最新一轮审核的判定结果」，重点是把**需要人管的单**捞出来。' +
        '用户问"哪些单卡在人工""为什么这单没自动过""有单被拦截/被冻结吗""自动审核现在什么情况"时用。' +
        'result 五选一：AUTO_PASS 自动过 / MANUAL 转人工 / BLOCKED 前置拦截 / FROZEN 异常冻结 / NO_MATCH 无策略命中；' +
        '不传=全部（每单只留最新一轮）。行里带 matched_policy_name（命中哪条策略、哪个版本）、' +
        'reason_text（判定理由原文）、would_be_auto_policy_name（本该自动过但被降级时是哪条策略）、miss_reasons（没满足哪些条件）。' +
        '只读：这张表是判决输出，改判决要走人工后台（AI 没有 audit:write，结构上就是 403）。',
      inputSchema: {
        result: z
          .enum(['AUTO_PASS', 'MANUAL', 'BLOCKED', 'FROZEN', 'NO_MATCH'])
          .optional()
          .describe('判定结果过滤：AUTO_PASS 自动过 / MANUAL 转人工 / BLOCKED 前置拦截 / FROZEN 异常冻结 / NO_MATCH 无策略命中；不传=全部'),
        page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
        size: z
          .number()
          .int()
          .min(1)
          .max(MAX_PAGE_SIZE)
          .optional()
          .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
      },
    },
    async ({ result, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const data = await orderAuditQueue({ result, page: p, size: s });
        // ⚠️ 后端是手写 Map：行数组叫 rows，不是 MyBatis 的 records
        const rows = Array.isArray(data?.rows) ? data.rows : [];
        const envelope = {
          total: Number(data?.total ?? rows.length),
          page: Number(data?.page ?? p),
          size: Number(data?.size ?? s),
          pages: Number(data?.size) > 0 ? Math.ceil(Number(data?.total ?? rows.length) / Number(data.size)) : 0,
          rows,
        };
        const head = `审核队列：${pageHeader('', envelope)}｜判定=${result ? `${resultLabel(result)}(${result})` : '全部'}`;

        if (!rows.length) {
          return text(
            `${head}\n没有符合条件的审核判定。` +
              (result
                ? `该判定下当前没有单；去掉 result 看全部，或用 list_audit_runs 看最近几轮跑出了什么。`
                : '还没跑过自动审核（人工后台点「立即审核」才会产生判定行）。') +
              `\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`
          );
        }

        const body = rows.map(queueRowText).join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  server.registerTool(
    'get_order_audit_trail',
    {
      title: '单张订单的审核时间线',
      description:
        '查一张订单的**审核全过程**：判定（哪一轮、什么结果、为什么）+ 状态台账（状态真变了吗、序号对不对）+ ' +
        '操作审计（谁在什么时候动过它），三段按时间拼成一条时间线，并带「判定 ↔ 台账」的对齐信息。' +
        '用户问"这单为什么被冻结""是哪条策略拦的""自动审核跑了几轮""这单的状态是谁改的"时用 —— 比只看状态码有用得多。' +
        '只读。注意：订单 id 不存在时后端返回**空数组且不报错**，工具层会说「没有审核时间线」。',
      inputSchema: {
        orderId: z.number().int().positive().describe('订单 id（不是订单号；订单号先用 list_orders 换成 id）'),
      },
    },
    async ({ orderId }) => {
      try {
        const list = await orderAuditTrail(orderId);
        const entries = Array.isArray(list) ? list : [];
        const head = `订单 ${orderId} 的审核时间线共 ${entries.length} 段`;

        if (!entries.length) {
          return text(
            `${head}：**没有审核时间线**。` +
              `这通常说明该订单不存在（后端对不存在的 id 返回空数组、不报错），或者它从未进入自动审核（不是待审核单、也没有判定行）。` +
              `请先用 list_orders / get_order_detail 确认订单 id 存在。` +
              `\n\nJSON:\n${JSON.stringify([], null, 2)}`
          );
        }

        const body = entries
          .map((e, i) => {
            const seg = SEGMENT_TEXT[e.segment] ?? e.segment ?? '-';
            const aligned =
              e.aligned && typeof e.aligned === 'object'
                ? `\n      对齐：ledgerRowId=${e.aligned.ledgerRowId ?? '-'} statusSeq=${e.aligned.statusSeq ?? '-'} ` +
                  `aligned=${e.aligned.aligned} strong=${e.aligned.strong}` +
                  `${e.aligned.decisionIds ? ` decisionIds=${JSON.stringify(e.aligned.decisionIds)}` : ''}`
                : '';
            return `  ${i + 1}. [${seg}] ${e.at ?? '-'} ${e.title ?? ''}${e.detail ? `\n      说明：${e.detail}` : ''}${aligned}`;
          })
          .join('\n');

        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(entries, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  server.registerTool(
    'list_audit_policies',
    {
      title: '审核策略列表（含条件与版本）',
      description:
        '读自动审核的**全部判定标准**：策略（AUTO 自动过 / BLOCK 前置拦截 / EXCEPTION 异常冻结，含优先级、启用状态、' +
        '版本号、条件行）、全局排除（EXCLUDE）、以及字段与操作符元数据。' +
        '用户问"审核规则是怎么配的""为什么这单会命中/不命中某条策略""某条策略的条件是什么、第几版"时用 —— ' +
        '它是解释 list_order_audit_queue 里那些判定的钥匙。' +
        '只读：策略的增删改与上下移需要 audit:write，**不在 API Key 的可授予范围里**（改判定标准等于改结论，只能人工）。',
      inputSchema: {},
    },
    async () => {
      try {
        const d = await listAuditPolicies();
        const policies = Array.isArray(d?.policies) ? d.policies : [];
        const excludes = Array.isArray(d?.globalExclusions) ? d.globalExclusions : [];
        const fields = Array.isArray(d?.fields) ? d.fields : [];
        const operators = Array.isArray(d?.operators) ? d.operators : [];

        const condText = (c) =>
          `${c.fieldLabel ?? c.fieldCode ?? '?'} ${c.opCode ?? '?'} ` +
          `${c.valueText ?? c.valueNum ?? (c.valueJson ? JSON.stringify(c.valueJson) : '')}`.trim();

        const head =
          `审核策略：共 ${policies.length} 条（${['AUTO', 'BLOCK', 'EXCEPTION'].map((t) => `${POLICY_TYPE_TEXT[t]} ${policies.filter((p) => p.type === t).length}`).join('｜')}），` +
          `全局排除 ${excludes.length} 条｜可选字段 ${fields.length} 个｜可选操作符 ${operators.length} 个`;

        const list = policies.length
          ? policies
              .map((p, i) => {
                const conds = Array.isArray(p.conditions) ? p.conditions : [];
                const condStr = conds.length ? conds.map(condText).join(' 且 ') : '（无条件）';
                return (
                  `  ${i + 1}. [${p.type}] ${p.name ?? '-'}（id=${p.id}，优先级 ${p.priority ?? '-'}，` +
                  `v${p.versionNo ?? '-'}，${p.enabled ? '启用' : '**停用**'}）\n` +
                  `      条件：${condStr}` +
                  (p.actionTag || p.actionReason ? `\n      命中动作：标=${p.actionTag ?? '-'} 原因=${p.actionReason ?? '-'}` : '')
                );
              })
              .join('\n')
          : '  （还没有任何策略）';

        const excludeStr = excludes.length
          ? '\n全局排除（EXCLUDE，先于所有策略生效）：\n' +
            excludes.map((c, i) => `  ${i + 1}. ${condText(c)}`).join('\n')
          : '\n全局排除：无';

        return text(
          `${head}\n${list}${excludeStr}\n\n` +
            `（想看某张单为什么是这个结果：用 list_order_audit_queue 看 reason_text / miss_reasons，` +
            `或用 get_order_audit_trail 看判定时间线。）\n\nJSON:\n${JSON.stringify(d ?? null, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  server.registerTool(
    'list_audit_runs',
    {
      title: '审核批次列表（每轮跑了多少单）',
      description:
        '分页看自动审核的**批次（run）**：每轮一次 run 一行，含触发方式、范围内单数、五类计数（自动过/转人工/拦截/冻结/无匹配）。' +
        '用户问"自动审核跑过几次""上一轮处理了多少单""哪一轮拦了多少"时用；配合 list_order_audit_queue 用 run_id 对应到具体订单。' +
        '只读。⚠️ 触发一轮 run（POST /admin/order-audit/run）是批量改订单命运的动作，AI 拿不到（audit:write 不在可授予范围）：' +
        '要跑请让用户在后台点「立即审核」。',
      inputSchema: {
        page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
        size: z
          .number()
          .int()
          .min(1)
          .max(MAX_PAGE_SIZE)
          .optional()
          .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
      },
    },
    async ({ page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const data = await listAuditRuns({ page: p, size: s });
        const rows = Array.isArray(data?.rows) ? data.rows : [];
        const envelope = {
          total: Number(data?.total ?? rows.length),
          page: Number(data?.page ?? p),
          size: Number(data?.size ?? s),
          pages: Number(data?.size) > 0 ? Math.ceil(Number(data?.total ?? rows.length) / Number(data.size)) : 0,
          rows,
        };
        const head = `审核批次：${pageHeader('', envelope)}`;

        if (!rows.length) {
          return text(
            `${head}\n还没有跑过自动审核（后台点「立即审核」才会产生 run）。\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`
          );
        }

        const body = rows
          .map((r, i) => {
            const sum = ['autoPass', 'manual', 'blocked', 'frozen', 'noMatch'].reduce((n, k) => n + Number(r[k] ?? 0), 0);
            return (
              `  ${i + 1}. run=${r.id} ${r.triggerType ?? '-'} ${r.createdAt ?? '-'}｜范围内 ${r.total ?? 0} 单` +
              `（五类之和 ${sum}）｜自动过 ${r.autoPass ?? 0}｜转人工 ${r.manual ?? 0}｜拦截 ${r.blocked ?? 0}｜` +
              `冻结 ${r.frozen ?? 0}｜无匹配 ${r.noMatch ?? 0}`
            );
          })
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );
}

export default registerAuditTools;
