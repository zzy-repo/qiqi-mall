// 多平台接入域（第 6 批 · 只读巡检域）：2 个工具，**拉单与回传发货一个都不做**
//
//   list_channel_shops     → GET /admin/channel-shops                                  （scope: channel:read）
//   list_channel_sync_logs → GET /admin/channel-sync-logs?shopId=&action=&result=&page=&size=   （scope: channel:read）
//
// ── 什么时候该用我 ──────────────────────────────────────────────────────────
//   「今天从平台拉回来几单、失败几笔」「哪个店铺的水位卡住了」「这单为什么没进来」
//   「回传发货成功了吗、平台回了什么」。**只读巡检**，不动平台侧任何状态。
//
// ── 拿不到的（结构上就 403，不是忘了做）─────────────────────────────────────
//   ✗ 拿不到：`POST /admin/channel-shops/{id}/pull` 立即拉单 —— 会**建订单**（写业务数据）；
//   ✗ 拿不到：`POST /admin/channel/push-shipment` 回传发货 —— 一次**外呼**，改动在平台侧、回不来。
//   两者要 channel:write，**不在 ALL_SCOPES 里** ⇒ 任何 API Key 都拿不到，只能人工在后台点按钮。
//
// ── 后端真实形态（已读 channel/controller/ChannelAdminController.java + ChannelSyncLog 确认）──
//   ① /channel-shops 返回**裸数组**（不是分页）：每店 {id,platformType,shopCode,shopName,enabled,
//      watermarkAt,lastPullAt,todayOk,todayFail,todaySkip}；计数是「今天」的，按本地日界算；
//   ② /channel-sync-logs 是**真分页**（MP Page: records/total/size/current/pages），按 id 倒序=时间倒序；
//   ③ action 只有两种：PULL（拉单）/ SHIP_PUSH（回传发货）；result 只有三种：OK / FAIL / SKIP
//      （SKIP 是幂等命中或 SKU 对不上而整单跳过 —— 不是错误，别念成失败）；
//   ④ 日志里带 requestPayload / responsePayload **原文 JSON**（排查平台交互的原始证据），
//      摘要只截前 200 字，完整原文在 JSON 段里。

import { z } from 'zod';
import { listChannelShops, channelSyncLogs } from '../mall.mjs';
import { text, fail, clampPage, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

/** 动作 → 中文 */
const ACTION_TEXT = { PULL: '拉单', SHIP_PUSH: '回传发货' };
/** 结果 → 中文（SKIP 不是失败！幂等命中/SKU 对不上而跳过） */
const RESULT_TEXT = { OK: '成功', FAIL: '失败', SKIP: '跳过（幂等命中或整单跳过，不是错误）' };

/** 长文本截断（摘要里只给前 n 字，完整原文仍在 JSON 段） */
function clip(s, n = 200) {
  if (s === null || s === undefined) return '-';
  const str = String(s);
  return str.length > n ? `${str.slice(0, n)}…（共 ${str.length} 字，完整见 JSON）` : str;
}

/**
 * 注册多平台接入域工具（纯只读，不受 MCP_ENABLE_WRITE 影响）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 */
export function registerChannelTools(server) {
  server.registerTool(
    'list_channel_shops',
    {
      title: '渠道店铺列表（含今日拉单计数）',
      description:
        '列出接入的渠道店铺：平台类型、店铺编码/名称、是否启用、拉单水位（watermarkAt）、上次拉单时间、' +
        '以及**今天**的成功/失败/跳过计数。用户问"接了哪些平台""今天从淘宝/抖音拉了几单""有店铺拉单失败吗"' +
        '"上次拉单是什么时候、水位停在哪"时用。' +
        '只读（不触发拉单：立即拉单会建订单，AI 拿不到 channel:write）。' +
        '拿不准某次拉单为什么失败时，接着用 list_channel_sync_logs 按 shopId + result=FAIL 查原因。',
      inputSchema: {},
    },
    async () => {
      try {
        const list = await listChannelShops();
        const shops = Array.isArray(list) ? list : [];
        if (!shops.length) {
          return text(`渠道店铺：0 个（还没配置任何平台店铺）\n\nJSON:\n${JSON.stringify([], null, 2)}`);
        }
        const head = `渠道店铺：共 ${shops.length} 个（启用 ${shops.filter((s) => Number(s.enabled) === 1).length} 个）`;
        const body = shops
          .map(
            (s, i) =>
              `  ${i + 1}. ${s.shopName ?? '-'}（id=${s.id}，${s.platformType ?? '-'}/${s.shopCode ?? '-'}）` +
              `${Number(s.enabled) === 1 ? '启用' : '**停用**'}｜水位=${s.watermarkAt ?? '从未'}｜上次拉单=${s.lastPullAt ?? '从未'}\n` +
              `      今日：成功 ${s.todayOk ?? 0}｜失败 ${s.todayFail ?? 0}｜跳过 ${s.todaySkip ?? 0}`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(shops, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  server.registerTool(
    'list_channel_sync_logs',
    {
      title: '渠道同步日志（拉单 / 回传发货）',
      description:
        '分页查渠道同步日志：每次拉单或回传发货一行，含动作、结果、原因、重试次数、耗时，以及**请求/响应原文**。' +
        '用户问"这单为什么没从平台拉进来""回传发货成功了吗、平台回了什么""今天有哪些失败""SKU 对不上跳过的是哪些"时用。' +
        '可按 shopId（哪家店）、action（PULL 拉单 / SHIP_PUSH 回传发货）、result（OK 成功 / FAIL 失败 / SKIP 跳过）过滤。' +
        '⚠️ SKIP 不是错误：它是幂等命中（重复单）或平台商品编码对不上而整单跳过，看 reason 字段区分。' +
        '只读（不触发拉单/回传：那两件事会建订单或外呼平台，AI 拿不到 channel:write）。',
      inputSchema: {
        shopId: z.number().int().positive().optional().describe('店铺 id（先用 list_channel_shops 查）；不传=全部店铺'),
        action: z.enum(['PULL', 'SHIP_PUSH']).optional().describe('动作：PULL 拉单 / SHIP_PUSH 回传发货；不传=全部'),
        result: z.enum(['OK', 'FAIL', 'SKIP']).optional().describe('结果：OK 成功 / FAIL 失败 / SKIP 跳过（幂等命中或整单跳过）；不传=全部'),
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
    async ({ shopId, action, result, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const data = await channelSyncLogs({ shopId, action, result, page: p, size: s });
        const records = Array.isArray(data?.records) ? data.records : [];
        const envelope = {
          records,
          total: Number(data?.total ?? records.length),
          page: Number(data?.current ?? p),
          size: Number(data?.size ?? s),
          pages: Number(data?.pages ?? 0),
        };
        const cond = [
          shopId !== undefined ? `店铺=${shopId}` : '店铺=全部',
          action ? `动作=${ACTION_TEXT[action] ?? action}` : null,
          result ? `结果=${RESULT_TEXT[result] ?? result}` : null,
        ].filter(Boolean);
        const head = `渠道同步日志：${pageHeader('', envelope)}｜条件：${cond.join('，')}`;

        if (!envelope.records.length) {
          return text(
            `${head}\n没有符合条件的同步日志。可以放宽条件再查（去掉 action/result），或用 list_channel_shops 看今天各店的计数。` +
              `\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`
          );
        }

        const body = envelope.records
          .map(
            (r, i) =>
              `  ${i + 1}. ${r.createdAt ?? '-'} 店铺=${r.shopId ?? '-'} 动作=${ACTION_TEXT[r.action] ?? r.action ?? '-'} ` +
              `结果=${RESULT_TEXT[r.result] ?? r.result ?? '-'} 关联=${r.refType ?? '-'}#${r.refId ?? '-'}` +
              ` 尝试=${r.attempt ?? '-'} 耗时=${r.latencyMs ?? '-'}ms` +
              `${r.reason ? `\n      原因：${r.reason}` : ''}` +
              `\n      请求：${clip(r.requestPayload)}` +
              `\n      响应：${clip(r.responsePayload)}`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );
}

export default registerChannelTools;
