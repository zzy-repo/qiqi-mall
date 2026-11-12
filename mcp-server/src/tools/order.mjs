// 订单域工具：现有 4 个（2 只读 + 2 写；写工具受 MCP_ENABLE_WRITE 控制）
// ⚠️ 这批是从 src/tools.mjs 原样搬过来的，**行为一字未改**（输出文本、错误文案、注册顺序都保持原样），
//    只把 text/money/fail 抽到 ./_shared.mjs。

import { z } from 'zod';
import { listPendingOrders, splitPreview, auditPass, listDeliveries } from '../mall.mjs';
import { issue, consume, hashPlan } from '../confirm.mjs';
import { text, money, fail } from './_shared.mjs';

// ---------- 展示辅助 ----------

function planToText(plan) {
  return plan
    .map((pkg, i) => {
      const lines = (pkg.items || [])
        .map((it) => `      - ${it.productName} ×${it.quantity}（skuId=${it.productSkuId}, orderItemId=${it.orderItemId}）`)
        .join('\n');
      return `  ${i + 1}. ${pkg.warehouseName}（warehouseId=${pkg.warehouseId}）\n${lines}`;
    })
    .join('\n');
}

function deliveriesToText(list) {
  if (!list.length) return '  （暂无发货单）';
  return list
    .map((d, i) => {
      // audit-pass 直接返回 DeliveryOrder 实体（无 warehouseName / items），list_deliveries 才有
      const wh = d.warehouseName ? `${d.warehouseName}（id=${d.warehouseId}）` : `id=${d.warehouseId}`;
      const items = Array.isArray(d.items) ? ` 明细=${d.items.length} 条` : '';
      return `  ${i + 1}. deliverySn=${d.deliverySn} 仓库=${wh} 状态=${d.status} 来源=${d.source}${items}`;
    })
    .join('\n');
}

// ---------- 注册 ----------

/**
 * 注册订单域工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerOrderTools(server, { writeEnabled } = {}) {
  // ① 只读：待审核列表
  server.registerTool(
    'list_pending_orders',
    {
      title: '列出待审核订单',
      description: '列出当前所有待审核（status=7）的订单。用户说"待审核订单""要审的单子"时用。',
      inputSchema: {
        size: z.number().int().min(1).max(50).optional().describe('最多返回多少条，默认 20'),
      },
    },
    async ({ size }) => {
      try {
        const page = await listPendingOrders({ size: size ?? 20 });
        const records = page?.records ?? [];
        const head = `待审核订单 ${records.length} 笔（库中共 ${page?.total ?? records.length} 笔，status=7）：`;
        if (!records.length) return text(`${head}\n  （没有待审核订单）`);
        const body = records
          .map(
            (o, i) =>
              `  ${i + 1}. id=${o.id} 单号=${o.orderSn} 收货人=${o.receiverName ?? '-'} 金额=${money(o.totalAmount)} 状态=${o.status} 下单/支付时间=${o.paymentTime ?? '-'}`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(records, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ② 只读：拆单预览 + 签发 confirm_token
  server.registerTool(
    'preview_split',
    {
      title: '预览拆单方案',
      description:
        '预览某笔待审核订单会被拆成几个包裹、分别从哪个仓发。纯预览不修改数据。调用后拿到 confirm_token，只有用户明确同意后才能用来执行。',
      inputSchema: {
        orderId: z.number().int().positive().describe('待审核订单 id'),
      },
    },
    async ({ orderId }) => {
      try {
        const plan = await splitPreview(orderId);
        if (!Array.isArray(plan) || plan.length === 0) {
          return text(`订单 ${orderId} 的拆单方案为空（可能没有明细），无法执行审核。`);
        }
        const { token } = issue({ tool: 'audit_pass', orderId, plan });
        return text(
          `订单 ${orderId} 将拆成 ${plan.length} 个包裹：\n${planToText(plan)}\n\n` +
            `confirm_token: ${token}\n` +
            `（方案 hash=${hashPlan(plan).slice(0, 12)}…，5 分钟内有效、仅可使用一次。请把方案念给用户确认，用户明确同意后再调用 execute_audit_pass。）\n\n` +
            `方案 JSON:\n${JSON.stringify(plan, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → 订单域只注册 2 个只读工具');
    return;
  }

  // ③ 写：确认执行审核（真改数据）
  server.registerTool(
    'execute_audit_pass',
    {
      title: '执行审核通过（写）',
      description:
        '⚠️ 会真实修改数据：订单从待审核变待发货，并按方案生成发货单。调用前必须已 preview_split 且用户已确认。token 必须是 preview_split 返回的那个。',
      inputSchema: {
        token: z.string().min(1).describe('preview_split 返回的 confirm_token（一次性，5 分钟有效）'),
      },
    },
    async ({ token }) => {
      try {
        // 四道校验（存在/未过期/用途/一次性），失败也已被消费
        const { orderId, planHash } = consume(token, 'audit_pass');

        // 关键：重新预览一次，比对方案指纹 —— 防「确认的是 A、执行成 B」
        const freshPlan = await splitPreview(orderId);
        const freshHash = hashPlan(freshPlan);
        if (freshHash !== planHash) {
          throw new Error(
            `订单 ${orderId} 的拆单方案已变化（仓库或库存被改动），拒绝执行。请重新调用 preview_split 预览并让用户确认。`
          );
        }

        const deliveries = await auditPass(orderId);
        const list = Array.isArray(deliveries) ? deliveries : [];
        if (list.length !== freshPlan.length) {
          return {
            isError: true,
            content: [
              {
                type: 'text',
                text: `⚠️ 订单 ${orderId} 已审核通过（状态 7→8），但生成包裹数 ${list.length} 与方案 ${freshPlan.length} 不一致，请人工核查。\n${deliveriesToText(list)}`,
              },
            ],
          };
        }
        return text(
          `✅ 订单 ${orderId} 已审核通过（7→8），按方案生成 ${list.length} 个发货单：\n${deliveriesToText(list)}\n\n` +
            `建议接着调用 list_deliveries 回读确认。\n\n发货单 JSON:\n${JSON.stringify(list, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ④ 只读：回读发货单
  server.registerTool(
    'list_deliveries',
    {
      title: '查看发货单',
      description: '查看某笔订单拆出来的所有发货单（仓库、状态、包裹单号）。执行完审核后用它确认结果。',
      inputSchema: {
        orderId: z.number().int().positive().describe('订单 id'),
      },
    },
    async ({ orderId }) => {
      try {
        const list = await listDeliveries(orderId);
        const rows = Array.isArray(list) ? list : [];
        return text(
          `订单 ${orderId} 共 ${rows.length} 个发货单：\n${deliveriesToText(rows)}\n\nJSON:\n${JSON.stringify(rows, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );
}
