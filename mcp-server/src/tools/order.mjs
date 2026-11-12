// 订单域工具：6 只读 + 1 写（写工具受 MCP_ENABLE_WRITE 控制）
// 并发控制口径（用户定的）：**version 乐观锁 + 后端业务守卫** ——
//   · preview_split     = dry-run 预演，回答「会做什么」(willDo) + 「拆成几包」(plan)，纯读、不落库
//   · execute_audit_pass= 输入就是 { orderId }；重复执行 / 预演后被改过，由后端业务守卫 + version 拒掉
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

  // ② 只读：拆单预览（dry-run 预演；回答「会做什么 + 拆成几包」，纯读不落库）
  server.registerTool(
    'preview_split',
    {
      title: '预览拆单方案',
      description:
        '预览某笔待审核订单会被拆成几个包裹、分别从哪个仓发（纯读，不修改数据、不落库）。' +
        '返回两样东西：willDo（一句中文「将会做什么」）和 plan（每个包裹的仓库与明细）。' +
        '先把方案和包裹数念给用户，用户明确同意后再调用 execute_audit_pass（执行时传同一个 orderId 即可）。',
      inputSchema: {
        orderId: z.number().int().positive().describe('待审核订单 id'),
      },
    },
    async ({ orderId }) => {
      try {
        // ⚠️ 后端返回 { willDo, plan }：预演只回答「会做什么 + 拆成几包」
        const resp = await splitPreview(orderId);
        const plan = Array.isArray(resp?.plan) ? resp.plan : [];
        const willDo = typeof resp?.willDo === 'string' && resp.willDo.trim() ? resp.willDo.trim() : '';
        if (plan.length === 0) {
          return text(
            `订单 ${orderId} 的拆单方案为空（可能没有明细，或该订单不是待审核状态），无法执行审核。\n` +
              (willDo ? `后端预演描述：${willDo}\n` : '') +
              `\nJSON:\n${JSON.stringify({ willDo, plan }, null, 2)}`
          );
        }
        return text(
          `订单 ${orderId} 将拆成 ${plan.length} 个包裹：\n${planToText(plan)}\n\n` +
            (willDo ? `预演描述（willDo）：${willDo}\n\n` : '') +
            `用户明确同意后，直接调用 execute_audit_pass 并传 orderId=${orderId}（执行只需这一个参数）。\n\n` +
            `JSON:\n${JSON.stringify({ willDo, plan }, null, 2)}`
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
  //    ⚠️ 设计说明：参数就是「只要 orderId」——把 preview_split 预演过的同一笔订单 id 传回来即可。
  //    并发保护来自后端的业务守卫（只有 status=7 才能审核通过）+ 热表 version 乐观锁：
  //      · 重复执行 → 后端「只有待审核订单可以审核通过」（订单已是 8）
  //      · 期间被别人改过 → 后端版本冲突 code=409
  //    两种都是中文原因，MCP 原样透给模型，让模型知道「不是重试的问题，是状态变了」。
  server.registerTool(
    'execute_audit_pass',
    {
      title: '执行审核通过（写）',
      description:
        '⚠️ 会真实修改数据：订单从待审核(7)变待发货(8)，并按方案生成发货单。' +
        '**执行只需要 orderId**（先 preview_split 让用户看清会做什么，执行时把同一个 orderId 传回来）。' +
        '⚠️ 后端不校验你是否预演过，顺序全靠你：' +
        '**请务必先调用 preview_split，把 willDo 和包裹方案念给用户听、拿到用户明确同意之后再执行**；' +
        '用户没明确说过「同意/执行/通过」就不要调本工具。' +
        '后端仍会自己拦重复执行（订单已不是待审核 → 「只有待审核订单可以审核通过」）和期间被改动（409 版本冲突），' +
        '被拒时请把中文原因照实告诉用户、重新查一次订单状态再决定，不要盲目重试。',
      inputSchema: {
        orderId: z.number().int().positive().describe('待审核订单 id：必须与刚才 preview_split 预览的是同一笔'),
      },
    },
    async ({ orderId }) => {
      try {
        // 校验全部在后端做（存在性 / status=7 守卫 / 版本冲突都会带中文原因抛回来）
        const deliveries = await auditPass(orderId);
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
