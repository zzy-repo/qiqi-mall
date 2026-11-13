// 台账域（第 6 批 · 只读证据域）：list_ledgers —— 七类台账的统一检索
//
//   list_ledgers → GET /admin/ledgers?page=&size=&type=&bizId=&operatorType=&from=&to=   （scope: ledger:read）
//
// ── 什么时候该用我（这是 description 的正文，别在这里改口径）──────────────────
//   「这张单到底怎么走到今天的」—— 排查状态异常、追一笔钱、核对包裹走没走、看谁动过它。
//   全站唯一的**证据入口**：七类台账（订单/包裹/售后状态 + 后台审计 + WMS 收发 + 库存流水 + 支付流水）
//   一次 UNION 查出来，按发生时间倒序。
//
// ── 后端真实形态（已读 shared/ledger/controller/AdminLedgerController.java 确认）──
//   ① **真分页**（MyBatis-Plus Page）→ {records,total,size,current,pages}；size 后端夹 1..50，工具层再夹一次；
//   ② type 是**白名单七选一**，拼错后端**直接报错并列出可选值**（不返回空数组 ——
//      「选错类型」和「这段时间真没记录」在页面上长得一样，后端刻意不许混淆）；
//   ③ bizId 的口径随 type 变：
//      · 不带 type = 「一根主键的全链路」：订单分支按订单 id、售后分支按冗余 order_id、
//        包裹与 WMS 分支经 delivery_order.order_id 搭桥反查 ⇒ 「订单 29 的台账」一条请求查全（含包裹与售后）；
//      · 带 type   = 该类型自己的业务主键（订单 id / 包裹 id / 售后 id / target_id / delivery_id）；
//      · PAYMENT 恒为订单 id（对账键，删单后仍在）；
//   ④ operatorType 只放行 ADMIN / MEMBER / APIKEY / SYSTEM（大小写不敏感）；
//      注意 **STOCK 那支没有 operator 列**，按操作人筛时 STOCK 不参与；
//   ⑤ from/to 按 'YYYY-MM-DD HH:mm:ss' 解析，另外容忍纯日期（from 补 00:00:00、to 补 23:59:59）与 ISO；
//      from > to 后端直接报错（不静默返回 0 条）。
//
// ── 权限（别在这里加写工具）────────────────────────────────────────────────
//   scope 只有 ledger:read；写 scope 是 ledger:write，**不在 ALL_SCOPES 里**，
//   任何 API Key 都拿不到 ⇒ 台账对 AI 天然只读（它是只追加的证据链，改了就等于把证据改成结论）。

import { z } from 'zod';
import { listLedgers } from '../mall.mjs';
import { text, fail, clampPage, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

/** 七类台账（与 LedgerQueryMapper.ALL_TYPES 逐字对齐，拼错后端会报错） */
const LEDGER_TYPES = ['ORDER_STATUS', 'DELIVERY_STATUS', 'RETURN_STATUS', 'AUDIT', 'WMS', 'STOCK', 'PAYMENT'];

/** 类型 → 中文（摘要里给人看；JSON 里仍是原始英文码） */
const TYPE_TEXT = {
  ORDER_STATUS: '订单状态台账',
  DELIVERY_STATUS: '包裹状态台账',
  RETURN_STATUS: '售后状态台账',
  AUDIT: '后台操作审计',
  WMS: 'WMS 收发台账',
  STOCK: '库存流水',
  PAYMENT: '支付流水',
};

/** 订单状态码（与 order.mjs / Order.java 同一张表；只用于把 fromStatus/toStatus 翻成中文） */
const ORDER_STATUS_TEXT = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货'];
/** 包裹状态码（DeliveryOrder 注释逐字：0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消） */
const DELIVERY_STATUS_TEXT = { 0: '待下发', 1: '已下发', 2: '已拣货', 3: '已出库', 4: '运输中', 5: '已签收', 9: '已取消' };
/** 售后状态码（ReturnApply 注释：0待处理 1已通过 2已拒绝） */
const RETURN_STATUS_TEXT = ['待处理', '已通过', '已拒绝'];

/** fromStatus/toStatus → 中文（只有三个状态类台账有值；其余类型后端给 null，这里显示 —） */
function statusPairText(row) {
  if (row.fromStatus === null || row.fromStatus === undefined) {
    return row.toStatus === null || row.toStatus === undefined ? '—' : `→${row.toStatus}`;
  }
  const map = (v) => {
    if (row.type === 'ORDER_STATUS') return ORDER_STATUS_TEXT[v] ?? `未知(${v})`;
    if (row.type === 'DELIVERY_STATUS') return DELIVERY_STATUS_TEXT[v] ?? `#${v}`;
    if (row.type === 'RETURN_STATUS') return RETURN_STATUS_TEXT[v] ?? `#${v}`;
    return String(v);
  };
  return `${map(row.fromStatus)}→${map(row.toStatus)}`;
}

/** 一行台账的摘要（字段按 LedgerRow 契约：summary / reason / reasonDetail / operatorName / createdAt） */
function rowToText(row, i) {
  const parts = [
    `  ${i + 1}. [${row.type}] ${row.createdAt ?? '-'}`,
    row.bizSn ? `单号=${row.bizSn}` : null,
    row.bizId !== null && row.bizId !== undefined ? `bizId=${row.bizId}` : null,
    row.fromStatus !== null || row.toStatus !== null ? `状态=${statusPairText(row)}` : null,
    row.reason ? `原因=${row.reason}` : null,
    row.reasonDetail ? `补充=${row.reasonDetail}` : null,
    row.operatorName ? `操作人=${row.operatorName}(${row.operatorType ?? '-'})` : `操作人=${row.operatorType ?? '-'}`,
  ].filter(Boolean);
  const head = parts.join('｜');
  return row.summary ? `${head}\n      说明：${row.summary}` : head;
}

/**
 * 注册台账域工具（纯只读，**不受 MCP_ENABLE_WRITE 影响**：它不是写工具，写开关关掉也照常可用）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 */
export function registerLedgerTools(server) {
  server.registerTool(
    'list_ledgers',
    {
      title: '查台账（七类证据链）',
      description:
        '查「一张单/一个包裹/一笔售后到底怎么走到今天」的**证据链**：七类台账统一检索，按发生时间倒序分页返回。' +
        '用户问"这单为什么变成这样""谁改的/谁审的""包裹到底发没发、WMS 回过什么""库存什么时候扣的、补回来没有"' +
        '"这笔钱记了没有""把这单的全过程按时间列出来"时用 —— 排查问题的第一入口，比猜状态强。' +
        'type 七选一：ORDER_STATUS 订单状态 / DELIVERY_STATUS 包裹状态 / RETURN_STATUS 售后状态 / AUDIT 后台操作审计 / ' +
        'WMS 收发 / STOCK 库存流水 / PAYMENT 支付流水；**留空 = 一次查全部七类**（这时 bizId 传订单 id 会把它名下的包裹与售后' +
        '一起带出来，是「查全链路」最快的用法）。' +
        '注意：只读；STOCK 那支没有操作人列，按 operatorType 筛时 STOCK 不参与；时间区间给 from>to 后端会直接报错。',
      inputSchema: {
        type: z
          .enum(LEDGER_TYPES)
          .optional()
          .describe('台账类型（七选一）：ORDER_STATUS / DELIVERY_STATUS / RETURN_STATUS / AUDIT / WMS / STOCK / PAYMENT；不传=查全部七类'),
        bizId: z
          .number()
          .int()
          .positive()
          .optional()
          .describe(
            '业务对象 id。不带 type 时=「全链路主键」（传订单 id 即可连它的包裹/售后一起查）；带 type 时=该类型自己的主键（订单 id / 包裹 id / 售后 id）；PAYMENT 恒为订单 id'
          ),
        operatorType: z
          .enum(['ADMIN', 'MEMBER', 'APIKEY', 'SYSTEM'])
          .optional()
          .describe('操作人类型：ADMIN 人工 / MEMBER 买家 / APIKEY AI / SYSTEM 系统；不传=全部（STOCK 类台账没有这一列，会被排除）'),
        from: z
          .string()
          .min(1)
          .optional()
          .describe("起始时间（含），形如 '2026-11-14 00:00:00'；也可只给日期 '2026-11-14'（自动补 00:00:00）"),
        to: z
          .string()
          .min(1)
          .optional()
          .describe("截止时间（含），形如 '2026-11-14 23:59:59'；也可只给日期 '2026-11-14'（自动补 23:59:59）"),
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
    async ({ type, bizId, operatorType, from, to, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const data = await listLedgers({ page: p, size: s, type, bizId, operatorType, from, to });
        const records = Array.isArray(data?.records) ? data.records : [];
        const envelope = {
          records,
          total: Number(data?.total ?? records.length),
          page: Number(data?.current ?? p),
          size: Number(data?.size ?? s),
          pages: Number(data?.pages ?? 0),
        };

        const cond = [
          type ? `类型=${TYPE_TEXT[type] ?? type}(${type})` : '类型=全部七类',
          bizId !== undefined ? `bizId=${bizId}` : null,
          operatorType ? `操作人=${operatorType}` : null,
          from ? `从 ${from}` : null,
          to ? `到 ${to}` : null,
        ].filter(Boolean);
        const head = `台账：${pageHeader('', envelope)}｜条件：${cond.join('，')}`;

        if (!envelope.records.length) {
          const hint =
            type || bizId || operatorType || from || to
              ? '这段时间/这个条件下确实没有台账行。若在找某张单的全过程，建议去掉 type 与 operatorType、只留 bizId=订单 id 再查一次（一次带出包裹与售后）。'
              : '库里还没有任何台账行。';
          return text(`${head}\n${hint}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
        }

        const body = envelope.records.map(rowToText).join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );
}

export default registerLedgerTools;
