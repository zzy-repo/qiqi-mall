// 订单域工具：6 只读 + 1 写（写工具受 MCP_ENABLE_WRITE 控制）
// 确认码口径：**由后端 split-preview 签发、audit-pass 校验**（MCP 只透传，不再自造 token）
//   · 第 1 批 4 个：list_pending_orders / preview_split / list_deliveries / execute_audit_pass（行为一字未改）
//   · 第 3 批 3 个：list_orders / get_order_detail / count_deliveries（全部只读，无操作票据）
// ⚠️ 只读工具必须注册在 `if (!writeEnabled) return;` **之前** —— 否则 MCP_ENABLE_WRITE=0 时只读工具也跟着消失。
//    （第 1 批把 list_deliveries 落在门后面了，本批顺手挪到门前面，行为只多不少：write=0 时现在也能用。）

import { z } from 'zod';
import { listPendingOrders, splitPreview, auditPass, listDeliveries, listOrders, orderDetail, deliveryCounts, DELIVERY_COUNTS_CHUNK } from '../mall.mjs';
import { text, money, fail, clampPage, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

// ---------- 状态码中文化 ----------
// ⚠️ 映射来源：order/entity/Order.java 注释 + admin-web/src/views/order/index.vue（两处一致，别猜）：
//    0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货
//    注意 7/8/9 是拆单履约链后加的，**顺序和业务时间线不一致**（待审核在最前，已发货在中间）。
const ORDER_STATUS_TEXT = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货'];

/** 订单状态码 → 中文名（未知码原样带出，别静默吞掉） */
function orderStatusLabel(code) {
  return ORDER_STATUS_TEXT[code] ?? `未知状态(${code ?? '-'})`;
}

/** 支付方式：0未支付 1支付宝 2微信（Order.java 注释） */
const PAY_TYPE_TEXT = ['未支付', '支付宝', '微信'];
const payTypeLabel = (v) => PAY_TYPE_TEXT[v] ?? `未知(${v ?? '-'})`;

/** 退货申请状态：0待处理 1已通过 2已拒绝（ReturnApply.java 注释 + admin-web 一致） */
const RETURN_STATUS_TEXT = ['待处理', '已通过', '已拒绝'];
const returnStatusLabel = (v) => RETURN_STATUS_TEXT[v] ?? `未知(${v ?? '-'})`;

/** 分页参数的 zod 片段：与商品域完全一致（page 默认 1，size 默认 10、最大 50，超出夹取不报错） */
const pageSchema = {
  page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
  size: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
};

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

  // ② 只读：拆单预览（confirmCode 由**后端**签发，这里只做透传，不自己造票据）
  server.registerTool(
    'preview_split',
    {
      title: '预览拆单方案',
      description:
        '预览某笔待审核订单会被拆成几个包裹、分别从哪个仓发。纯预览不修改数据。' +
        '返回里带一个后端签发的 confirmCode：把它连同 orderId 原样交给 execute_audit_pass 才能真的审核通过。' +
        '请先把方案和包裹数念给用户，用户明确同意后再执行。',
      inputSchema: {
        orderId: z.number().int().positive().describe('待审核订单 id'),
      },
    },
    async ({ orderId }) => {
      try {
        // ⚠️ 后端返回 { plan, confirmCode, expiresInSec }（不再是裸数组）；两个字段都是执行时必传的
        const resp = await splitPreview(orderId);
        const plan = Array.isArray(resp?.plan) ? resp.plan : [];
        if (plan.length === 0) {
          return text(`订单 ${orderId} 的拆单方案为空（可能没有明细），无法执行审核。`);
        }
        const confirmCode = typeof resp?.confirmCode === 'string' ? resp.confirmCode : '';
        if (!confirmCode) {
          return fail(new Error(`后端没有返回 confirmCode（split-preview 响应：${JSON.stringify(resp).slice(0, 200)}），无法继续审核`));
        }
        const expiresInSec = Number(resp?.expiresInSec ?? 300);
        return text(
          `订单 ${orderId} 将拆成 ${plan.length} 个包裹：\n${planToText(plan)}\n\n` +
            `confirmCode: ${confirmCode}\n` +
            `（${expiresInSec} 秒内有效，仅可使用一次。请把方案念给用户确认；用户明确同意后，把这个 confirmCode 和 orderId=${orderId} 一起传给 execute_audit_pass。别自己编码、也别跨订单用。）\n\n` +
            `JSON:\n${JSON.stringify({ plan, confirmCode, expiresInSec }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ③ 只读：订单列表（后端真分页 + status/orderSn 筛选）
  server.registerTool(
    'list_orders',
    {
      title: '按条件查订单列表',
      description:
        '分页查订单，可按订单状态、订单号（模糊）筛选，返回带中文状态名的订单摘要。用户问"有哪些订单""最近下了什么单""退款中/待发货/已完成的单有哪些""单号 QQ2026xxx 那笔"时用。' +
        '不传任何条件=按 id 倒序的全部订单（每页 10 条）。注意：状态码是 0~9，7=待审核 8=待发货 9=部分发货。',
      inputSchema: {
        status: z
          .number()
          .int()
          .min(0)
          .max(9)
          .optional()
          .describe(
            '订单状态码（精确匹配）：0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货；不传=全部状态'
          ),
        orderSn: z.string().min(1).optional().describe('订单号，后端是**模糊匹配**（传 "QQ2026" 能匹配所有 QQ2026 开头的单）；不传=不按单号过滤'),
        ...pageSchema,
      },
    },
    async ({ status, orderSn, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        // 后端 /admin/orders 是真分页接口（不再工具层切片，避免二次切页把第 2 页切成空）
        const data = await listOrders({ page: p, size: s, status, orderSn });
        const records = Array.isArray(data?.records) ? data.records : [];
        const envelope = {
          records: records.map((o) => ({ ...o, statusText: orderStatusLabel(o.status) })),
          total: Number(data?.total ?? records.length),
          page: Number(data?.current ?? p),
          size: Number(data?.size ?? s),
          pages: Number(data?.pages ?? 0),
        };
        const cond = [status !== undefined ? `状态=${orderStatusLabel(status)}(${status})` : null, orderSn ? `单号含「${orderSn}」` : null].filter(Boolean);
        const head = `订单列表：${pageHeader('', envelope)}｜条件：${cond.length ? cond.join('，') : '无（全部状态）'}`;

        if (!envelope.records.length) {
          return text(
            `${head}\n没有符合条件的订单。可以去掉 status/orderSn 再查一次，或确认单号没打错（单号是模糊匹配，传 4~8 位前缀即可）。` +
              `\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`
          );
        }

        const body = envelope.records
          .map(
            (o, i) =>
              `  ${i + 1}. id=${o.id} 单号=${o.orderSn} 状态=${o.statusText} 金额=${money(o.totalAmount)} ` +
              `收货人=${o.receiverName ?? '-'} 支付=${payTypeLabel(o.payType)} 支付时间=${o.paymentTime ?? '-'}`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ④ 只读：订单详情（后端已聚合明细 + 退货申请）
  server.registerTool(
    'get_order_detail',
    {
      title: '查看订单详情',
      description:
        '查单笔订单的完整情况：订单主信息（含中文状态）、商品明细（快照名/单价/数量/小计）、退货申请记录（含处理状态）。' +
        '用户问"这单买了什么""金额对不对""有没有退货申请""为什么状态是这个"时用。判断该单能不能审核/发货前，也先用它看状态。',
      inputSchema: {
        orderId: z.number().int().positive().describe('订单 id（不是订单号；订单号请用 list_orders 的 orderSn 查 id）'),
      },
    },
    async ({ orderId }) => {
      try {
        const detail = await orderDetail(orderId);
        const order = detail?.order;
        // ⚠️ 后端对不存在的 id 返回 code=0 + order=null（不报错），必须工具层自己判空
        if (!order) throw new Error(`订单不存在（id=${orderId}），请用 list_orders 先确认订单 id`);

        const items = Array.isArray(detail?.items) ? detail.items : [];
        const returnApplies = Array.isArray(detail?.returnApplies) ? detail.returnApplies : [];

        const itemText = items.length
          ? items
              .map((it, i) => {
                const sub = (Number(it.productPrice) || 0) * (Number(it.productQuantity) || 0);
                return `    ${i + 1}. ${it.productName ?? '-'} ×${money(it.productQuantity)} 单价=${money(it.productPrice)} 小计=${sub.toFixed(2)}（orderItemId=${it.id}, skuId=${it.productSkuId}）`;
              })
              .join('\n')
          : '    （该订单没有明细，可能是数据异常）';
        const returnText = returnApplies.length
          ? returnApplies
              .map((r, i) => `    ${i + 1}. 退货申请 id=${r.id} 状态=${returnStatusLabel(r.status)} 原因=${r.returnReason ?? '-'} 说明=${r.description ?? '-'}`)
              .join('\n')
          : '    （没有退货申请）';

        const payload = {
          order: { ...order, statusText: orderStatusLabel(order.status), payTypeText: payTypeLabel(order.payType) },
          items,
          returnApplies: returnApplies.map((r) => ({ ...r, statusText: returnStatusLabel(r.status) })),
          itemCount: items.length,
          returnApplyCount: returnApplies.length,
        };

        return text(
          `订单详情 id=${order.id} 单号=${order.orderSn}\n` +
            `  状态=${payload.order.statusText}（${order.status}） 金额=${money(order.totalAmount)} 拆单标记=${order.splitStatus === 1 ? '已拆' : '未拆'}\n` +
            `  收货人=${order.receiverName ?? '-'} 电话=${order.receiverPhone ?? '-'}\n` +
            `  地址=${order.receiverAddress ?? '-'}\n` +
            `  支付=${payload.order.payTypeText} 支付时间=${order.paymentTime ?? '-'}\n` +
            `  商品明细 ${items.length} 条：\n${itemText}\n` +
            `  退货申请 ${returnApplies.length} 条：\n${returnText}\n\n` +
            `JSON:\n${JSON.stringify(payload, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ⑤ 只读：批量统计包裹数
  server.registerTool(
    'count_deliveries',
    {
      title: '批量统计订单的包裹数',
      description:
        '一次问多个订单各有几个发货单（包裹），返回 订单 id → 包裹数。用户问"这几单都拆成几包""哪几单还没拆"、或看订单列表想批量知道包裹数时用。' +
        `语义与后台订单列表页的「N 个包裹」标签一致：**已取消的发货单不计入**；单次最多 ${DELIVERY_COUNTS_CHUNK} 个 id，超过会自动分批请求后合并。`,
      inputSchema: {
        orderIds: z
          .array(z.number().int().positive())
          .min(1)
          .max(200)
          .describe(`订单 id 数组（如 [1,2,3]），单次最多 ${DELIVERY_COUNTS_CHUNK} 个会分批、上限 200 个；拿到 id 前先用 list_orders 查`),
      },
    },
    async ({ orderIds }) => {
      try {
        // 去重保序：模型常把同一个 id 传两遍
        const ids = [...new Set(orderIds.map((n) => Number(n)))];
        const map = await deliveryCounts(ids);
        const records = ids.map((id) => ({ orderId: id, deliveryCount: map.get(String(id)) ?? 0 }));
        const total = records.reduce((a, r) => a + r.deliveryCount, 0);
        const split = records.filter((r) => r.deliveryCount > 0).length;
        const chunks = Math.ceil(ids.length / DELIVERY_COUNTS_CHUNK);
        const head =
          `包裹数统计：请求 ${records.length} 个订单，其中 ${split} 个已拆单，包裹合计 ${total} 个` +
          `（不含已取消发货单${chunks > 1 ? `，分 ${chunks} 批请求` : ''}）`;
        const body = records.map((r) => `  ${r.orderId}: ${r.deliveryCount} 个包裹${r.deliveryCount === 0 ? '（未拆单或包裹已全部取消）' : ''}`).join('\n');
        if (!records.length) {
          return text(`包裹数统计：没有可用订单 id。\n\nJSON:\n${JSON.stringify({ records: [] }, null, 2)}`);
        }
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify({ total, splitOrders: split, records }, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ⑥ 只读：回读发货单（第 1 批原样，仅注册位置挪到写开关前面）
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

  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → 订单域只注册 6 个只读工具（写工具 execute_audit_pass 不注册）');
    return;
  }

  // ⑦ 写：确认执行审核（真改数据）。
  //    ⚠️ 设计说明：confirmCode 的「码 → 订单」映射在后端 KV 里，MCP 解不出来，
  //    所以 orderId 必须由调用方一起给（方案 A）。后端在 audit-pass 里校验
  //    「码里的 orderId == 路径里的 orderId」，跨单用码会被拒（确认码与订单不匹配）。
  //    另一个方案（新增「用码换 orderId」接口）要多一次往返，没必要。
  server.registerTool(
    'execute_audit_pass',
    {
      title: '执行审核通过（写）',
      description:
        '⚠️ 会真实修改数据：订单从待审核(7)变待发货(8)，并按方案生成发货单。' +
        '调用前必须先 preview_split 并把方案念给用户确认。' +
        'orderId 与 confirmCode **都必须来自刚才 preview_split 的同一次结果**（同一次预览、同一笔订单）；' +
        'confirmCode 是后端签发的 UUID，**绝不要自己编造、不要复用、不要跨订单使用**——后端会校验码与订单是否匹配、是否已被用过或过期（默认 300 秒）。' +
        '被拒（码无效/过期/不匹配/方案已变）时，重新 preview_split 并再次征得用户同意，不要重试同一个码。',
      inputSchema: {
        orderId: z
          .number()
          .int()
          .positive()
          .describe('待审核订单 id：必须与刚才 preview_split 预览的是同一笔（后端会校验确认码是否属于这个订单）'),
        confirmCode: z
          .string()
          .min(1)
          .describe('preview_split 返回的 confirmCode（一次性、默认 300 秒、绑定该订单；不允许自己编）'),
      },
    },
    async ({ orderId, confirmCode }) => {
      try {
        // 确认码校验、方案指纹比对、状态守卫都在后端做（缺码/无效/已用/过期/不匹配都会带中文原因抛回来）
        const deliveries = await auditPass(orderId, confirmCode);
        const list = Array.isArray(deliveries) ? deliveries : [];
        if (list.length === 0) {
          return {
            isError: true,
            content: [
              {
                type: 'text',
                text: `⚠️ 订单 ${orderId} 审核接口返回成功，但没有生成任何发货单，请人工核查。`,
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
}
