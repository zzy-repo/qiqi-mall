// 订单域工具：6 只读 + 6 写（写工具受 MCP_ENABLE_WRITE 控制）
//   · 第 1~3 批：preview_split（预演）/ execute_audit_pass（审核通过）
//   · 第 5 批（覆盖补齐）：create_order / update_order / audit_reject_order / ship_order / delete_order
//     —— 对应后端 OrderController 的 POST、PUT、audit-reject、ship、DELETE 五个端点
//     （后台订单页的「新增/编辑/审核不通过/发货/删除」五个按钮，全部是人工操作，必须能被 MCP 覆盖）
// 并发控制口径（用户定的）：**version 乐观锁 + 后端业务守卫** ——
//   · preview_split     = dry-run 预演，回答「会做什么」(willDo) + 「拆成几包」(plan)，纯读、不落库
//   · execute_audit_pass= 输入就是 { orderId }；重复执行 / 预演后被改过，由后端业务守卫 + version 拒掉
// ⚠️ 只读工具必须注册在 `if (!writeEnabled) return;` **之前** —— 否则 MCP_ENABLE_WRITE=0 时只读工具也跟着消失。
//    （第 1 批把 list_deliveries 落在门后面了，本批顺手挪到门前面，行为只多不少：write=0 时现在也能用。）

import { z } from 'zod';
import {
  listPendingOrders,
  splitPreview,
  auditPass,
  listDeliveries,
  listOrders,
  orderDetail,
  deliveryCounts,
  createOrder,
  updateOrder,
  auditRejectOrder,
  shipOrder,
  deleteOrder,
  DELIVERY_COUNTS_CHUNK,
} from '../mall.mjs';
import { text, money, fail, failWrite, clampPage, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

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
    console.error(
      '[mcp] MCP_ENABLE_WRITE!=1 → 订单域只注册 6 个只读工具' +
        '（写工具 execute_audit_pass / create_order / update_order / audit_reject_order / ship_order / delete_order 不注册）'
    );
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

  // ---------- 第 5 批（覆盖补齐）：订单的另外 5 个人工写操作 ----------
  // 后端入参口径（已读 OrderController.java 确认）：
  //   POST   /admin/orders                    body = { order, items }，总额按明细快照价×数量重算
  //   PUT    /admin/orders/{id}               **收 body.version（缺 400 / 冲突 409）**，status 被后端强制置 null
  //   POST   /admin/orders/{id}/audit-reject  不收 version，守卫 status=7，联动**回补总池库存**
  //   POST   /admin/orders/{id}/ship          不收 version，守卫 status=1（直发路径，不拆单）
  //   DELETE /admin/orders/{id}               级联删明细/发货单/物流，不可恢复
  // 后三个都支持 ?dryRun=true（只回 willDo，不落库、不需要 version）。

  const orderItemSchema = z.object({
    productSkuId: z.number().int().positive().describe('SKU id（先用 list_skus / get_product_detail 查）'),
    productId: z.number().int().positive().optional().describe('所属商品 id（SPU）；不传后端也能存，但建议给，便于日后对账'),
    productName: z.string().min(1).optional().describe('下单时的商品名快照（建议给，订单列表直接显示它）'),
    productPrice: z.number().nonnegative().describe('下单时的单价快照（元）'),
    productQuantity: z.number().int().min(1).describe('数量（件）'),
  });

  // ⑧ 写：后台新增订单
  server.registerTool(
    'create_order',
    {
      title: '后台新增订单（写）',
      description:
        '在后台手工建一笔订单（含明细快照）。用户在后台订单页点「新增订单」时用。⚠️ 会真写数据。' +
        '金额不用传：后端按「明细单价 × 数量」自动重算并写进 total_amount（传了也会被覆盖）。' +
        'orderSn 不传会自动生成（形如 QQT+时间戳）；status 不传默认 0（待付款）。' +
        '注意：本接口只落库，不会扣库存、不会支付；要让订单走到「待审核」，需要另走买家支付（/portal/orders/{id}/pay）。' +
        '建完请用 get_order_detail 回读，把 id / 单号 / 金额念给用户确认。',
      inputSchema: {
        receiverName: z.string().min(1).describe('收货人姓名（下单快照）'),
        receiverPhone: z.string().min(1).describe('收货人电话（下单快照）'),
        receiverAddress: z.string().min(1).describe('收货地址（下单快照）'),
        items: z.array(orderItemSchema).min(1).describe('订单明细，至少 1 条（单价与数量决定总额）'),
        orderSn: z.string().min(1).optional().describe('订单号；不传=后端自动生成'),
        memberId: z
          .number()
          .int()
          .positive()
          .optional()
          .describe('下单会员 id，**必填**（后端 order.member_id 是 NOT NULL，缺了只会 500）：先用 member(action=list) 查'),
        status: z.number().int().min(0).max(9).optional().describe('初始状态码，默认 0 待付款（0待付款 1已付款 … 7待审核 …）；一般别指定'),
        payType: z.number().int().min(0).max(2).optional().describe('支付方式：0未支付 / 1支付宝 / 2微信，默认 0'),
      },
    },
    async ({ receiverName, receiverPhone, receiverAddress, items, orderSn, memberId, status, payType }) => {
      // ⚠️ 后端 POST /admin/orders 是裸 save，不校验必填：`order.member_id` 在库里是 **NOT NULL**，
      //    不给就是 SQL 报错 → 用户只会看到「数据处理失败，请稍后重试」。所以这里先拦并说清缺什么。
      if (memberId === undefined || memberId === null) {
        return fail(
          new Error(
            'create_order 必须给 memberId：后端 order.member_id 是 NOT NULL，缺了只会回「数据处理失败」。' +
              '请先用 member(action=list) 查一个会员 id（后台代客下单必须挂在一个真实会员上）'
          )
        );
      }
      try {
        const created = await createOrder({
          order: {
            ...(orderSn === undefined ? {} : { orderSn }),
            ...(memberId === undefined ? {} : { memberId }),
            ...(status === undefined ? {} : { status }),
            ...(payType === undefined ? {} : { payType }),
            receiverName,
            receiverPhone,
            receiverAddress,
          },
          items,
        });
        const expected = items.reduce((sum, it) => sum + Number(it.productPrice) * Number(it.productQuantity), 0);
        return text(
          `✅ 已新增订单：id=${created?.id} 单号=${created?.orderSn} 状态=${orderStatusLabel(created?.status)}(${created?.status}) ` +
            `金额=${money(created?.totalAmount)}（明细 ${items.length} 条，按快照价重算应为 ${expected}）\n` +
            `  version=${created?.version ?? '-'}（接下来改这笔单必须带这个号）\n` +
            `  收货人=${created?.receiverName} 电话=${created?.receiverPhone} 地址=${created?.receiverAddress}\n\n` +
            `JSON:\n${JSON.stringify(created, null, 2)}`
        );
      } catch (e) {
        return failWrite(e, { action: '新增订单', label: `收货人=${receiverName}`, rereadTool: 'list_orders' });
      }
    }
  );

  // ⑨ 写：改订单基础信息（收货人/电话/地址/支付方式），必须带 version
  server.registerTool(
    'update_order',
    {
      title: '修改订单收货信息（写）',
      description:
        '修改订单的收货人 / 电话 / 地址 / 支付方式。⚠️ 会真改数据：先把改动念给用户确认再调。' +
        '⚠️ 必须传 version（来自 list_orders 或 get_order_detail）。期间被别人改过 → 后端 409，请重新读再试，不要拿旧 version 重试。' +
        '注意：**订单状态不能通过本工具改**（后端会强制忽略 status）—— 状态只能走审核通过（execute_audit_pass）/ 审核不通过（audit_reject_order）/ 发货（ship_order）。' +
        '已发货/已完成的订单改收货信息只影响后台记录，不会改买家手里那张面单。',
      inputSchema: {
        orderId: z.number().int().positive().describe('订单 id'),
        version: z.number().int().min(0).describe('订单版本号，**必须来自最近一次读取**（list_orders / get_order_detail 返回的 version）'),
        receiverName: z.string().min(1).optional().describe('新收货人；不改就别传'),
        receiverPhone: z.string().min(1).optional().describe('新电话；不改就别传'),
        receiverAddress: z.string().min(1).optional().describe('新地址；不改就别传'),
        payType: z.number().int().min(0).max(2).optional().describe('支付方式：0未支付 / 1支付宝 / 2微信；不改就别传'),
      },
    },
    async ({ orderId, version, receiverName, receiverPhone, receiverAddress, payType }) => {
      const patch = { version };
      const changed = [];
      const setField = (k, v) => {
        if (v === undefined) return;
        patch[k] = v;
        changed.push(k);
      };
      setField('receiverName', receiverName);
      setField('receiverPhone', receiverPhone);
      setField('receiverAddress', receiverAddress);
      setField('payType', payType);
      if (!changed.length) {
        return fail(new Error('没有要改的字段：receiverName / receiverPhone / receiverAddress / payType 至少给一个'));
      }
      try {
        await updateOrder(orderId, patch);
      } catch (e) {
        return failWrite(e, {
          action: '订单修改',
          label: `orderId=${orderId}（改 ${changed.join('、')}）`,
          oldVersion: version,
          rereadTool: `get_order_detail(orderId=${orderId})`,
        });
      }
      let after = null;
      try {
        after = (await orderDetail(orderId))?.order ?? null;
      } catch {
        after = null;
      }
      const newVersion = after?.version ?? null;
      return text(
        `✅ 订单 ${orderId} 已更新（${changed.join('、')}）\n` +
          `  当前值：收货人=${after?.receiverName ?? receiverName ?? '-'} 电话=${after?.receiverPhone ?? receiverPhone ?? '-'} 地址=${after?.receiverAddress ?? receiverAddress ?? '-'} 支付方式=${payTypeLabel(after?.payType ?? payType)}\n` +
          `  version：${version} → ${newVersion ?? '(回读失败)'}\n` +
          `  下次再改这笔单请用 version=${newVersion}（旧 version 会被后端 409 拒掉）。\n\n` +
          `JSON:\n${JSON.stringify({ orderId, changed, oldVersion: version, newVersion, order: after }, null, 2)}`
      );
    }
  );

  // ⑩ 写：审核不通过（7 → 4 已关闭 + 回补总池库存）
  server.registerTool(
    'audit_reject_order',
    {
      title: '审核不通过订单（写）',
      description:
        '把待审核(7)订单审核不通过：状态变已关闭(4)，并**回补该单明细的总池库存**（下单时扣的那部分）。' +
        '⚠️ 会真改数据，且**不可撤销**（关闭后只能靠人工再建单）。' +
        '先 dryRun=true 预演：返回 willDo（将回补哪些 SKU 多少件），把这段话念给用户并拿到明确同意再真执行。' +
        '守卫：只有待审核(7)能拒；期间被别人改过 → 409。被拒时把中文原因照实告诉用户，不要盲目重试。',
      inputSchema: {
        orderId: z.number().int().positive().describe('待审核订单 id'),
        dryRun: z.boolean().optional().describe('true=只预演（回 willDo，不落库）；强烈建议先预演一次'),
      },
    },
    async ({ orderId, dryRun }) => {
      try {
        const resp = await auditRejectOrder(orderId, { dryRun });
        if (dryRun === true) {
          return text(
            `预演（未落库）— 审核不通过订单 ${orderId}：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`
          );
        }
        let after = null;
        try {
          after = (await orderDetail(orderId))?.order ?? null;
        } catch {
          after = null;
        }
        return text(
          `✅ 订单 ${orderId} 已审核不通过：状态=${orderStatusLabel(after?.status)}(${after?.status ?? '-'})，明细的总池库存已回补。\n` +
            `  （该单不会再生成任何发货单；如需重新走流程请让买家重新下单。）\n\n` +
            `JSON:\n${JSON.stringify({ orderId, dryRun: false, order: after, response: resp }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ⑪ 写：直发发货（1 已付款 → 2 已发货，不拆单）
  server.registerTool(
    'ship_order',
    {
      title: '订单发货（直发路径，写）',
      description:
        '把已付款(1)订单直接标记为已发货(2)——**直发路径，不拆单、不生成发货单**。' +
        '只在「历史遗留/无需拆仓」的订单上用；正常的审核通过 → 拆单 → 下发仓库请用 execute_audit_pass + delivery(action=dispatch)。' +
        '⚠️ 会真改数据。先 dryRun=true 预演（返回 willDo，含现有发货单数，让你看清走的是哪条路径），拿到用户同意再执行。' +
        '守卫：只有已付款(1)能发货；期间被别人改过 → 409。',
      inputSchema: {
        orderId: z.number().int().positive().describe('订单 id（必须是已付款状态=1）'),
        dryRun: z.boolean().optional().describe('true=只预演（回 willDo，不落库）'),
      },
    },
    async ({ orderId, dryRun }) => {
      try {
        const resp = await shipOrder(orderId, { dryRun });
        if (dryRun === true) {
          return text(
            `预演（未落库）— 订单 ${orderId} 直发发货：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`
          );
        }
        const st = resp?.status;
        return text(
          `✅ 订单 ${orderId} 已标记为已发货：状态=${orderStatusLabel(st)}(${st ?? '-'})（直发路径，未生成发货单）。\n` +
            `  如果需要仓库履约/物流单号，请改走 execute_audit_pass（拆单）+ delivery(action=dispatch)。\n\n` +
            `JSON:\n${JSON.stringify({ orderId, dryRun: false, order: resp }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ⑫ 写：删除订单（级联删明细/发货单/物流，不可恢复）
  server.registerTool(
    'delete_order',
    {
      title: '删除订单（写，不可恢复）',
      description:
        '删除一笔订单，并**级联删除**它的明细、发货单（含包裹明细与物流记录）。⚠️ 不可恢复。' +
        '先 dryRun=true 预演：返回 willDo（会连带删掉多少条明细、多少个发货单）——把数字念给用户、拿到明确同意再执行。' +
        '注意：删除订单**不会**回补库存、也不会退款；如果订单还占着库存或需要退款，请先走审核不通过（回补库存）或退货审核（退款）。',
      inputSchema: {
        orderId: z.number().int().positive().describe('要删除的订单 id'),
        dryRun: z.boolean().optional().describe('true=只预演（回 willDo，不落库）'),
      },
    },
    async ({ orderId, dryRun }) => {
      try {
        const resp = await deleteOrder(orderId, { dryRun });
        if (dryRun === true) {
          return text(
            `预演（未落库）— 删除订单 ${orderId}：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`
          );
        }
        let gone = true;
        try {
          await orderDetail(orderId);
          gone = false;
        } catch {
          gone = true;
        }
        return text(
          `✅ 订单 ${orderId} 已删除（级联清掉明细/发货单/物流）。\n` +
            `  回读核对：${gone ? '该订单已不存在（符合预期）' : '⚠️ 仍能读到该订单，请人工核查'}\n\n` +
            `JSON:\n${JSON.stringify({ orderId, dryRun: false, deleted: true, verifiedGone: gone }, null, 2)}`
        );
      } catch (e) {
        // 后端裸 removeById + 外键把关：只要还有**没有联级**的引用（典型：该单包裹已生成
        // delivery_logistics 运单记录，fk_logi_delivery 是 NO ACTION），就会回一句笼统的
        // 「数据处理失败，请稍后重试」。这不是参数问题，值得翻成人话，免得模型反复重试。
        const msg = String(e?.message ?? e);
        if (/数据处理失败/.test(msg)) {
          return fail(
            new Error(
              `${msg}\n` +
                '提示：这类「数据处理失败」几乎都是**外键把关**，不是你参数写错了。常见的两种：\n' +
                '  ① 该订单的某个包裹已经生成物流记录（WMS 回传过签收/已有运单号）——后端未给该外键做联级，物理删除会被挡住；\n' +
                '  ② 还有退货申请 / 支付流水引用着这笔单。\n' +
                '可以先 delivery(action=list, orderId=…) 看包裹是否已带运单号；这类单更适合「关闭」而不是物理删除。'
            )
          );
        }
        return fail(e);
      }
    }
  );
}
