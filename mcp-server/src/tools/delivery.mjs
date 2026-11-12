// 履约域（delivery）工具：1 个合并工具 `delivery(action)`，覆盖后端 DeliveryController 的 8 个动作。
//
// ── 为什么「一个资源一个工具」────────────────────────────────────────────
// 发货单的 8 个动作共用同一套参数（deliveryId / version / 明细），拆成 8 个工具会让模型在
// 「改仓 vs 拆分」之间反复试错；合并成 `delivery(action=...)` 后，action 枚举本身就是文档。
// ⚠️ 但**写动作的门禁不能省**：MCP_ENABLE_WRITE!=1 时 action 枚举里只剩 list / get（见 ACTIONS_READONLY）。
//
// ── 并发控制口径（用户定的，本域最容易踩坑，逐条对着后端代码抄的）───────
//   version 收不收，取决于后端的**真实入参**（已读 DeliveryController.java 确认）：
//     PUT    /admin/deliveries/{id}        收 body.version   → action=update 需要
//     POST   /admin/deliveries/{id}/split  收 body.version   → action=split  需要
//     POST   /admin/deliveries/{id}/merge  收 body.version   → action=merge  需要
//     DELETE /admin/deliveries/{id}        **收 query.version**（不是 body）→ action=delete 需要
//     POST   /admin/deliveries/{id}/dispatch、/{id}/cancel  **不收 version**
//            —— 它们由「仅待下发」状态守卫 + UPDATE 影响行数闸门兜住（后端刻意如此：
//               WMS/运维拿不到前端页面上的 version）
//   所以本工具的 `version` 只能是 zod 可选 + 描述里写清「哪些 action 必填」，
//   而**必填性由后端 fail-closed 保证**：缺了直接 400「缺少版本号」，failWrite 会把它带出来。
//   （`member(update)` / `update_product` 那种能 zod 必填的，是因为整张表的写都吃同一个 version 字段。）
//
//   dryRun=true → 后端只回 {willDo}：不落库、不需要 version、**会跑业务守卫**
//   （「这包裹已下发不能改」在预演阶段就说清）。L3 动作建议先预演再执行。
//
// ── 后端实际形态（已读代码，跟「标准 REST」不一样的地方）─────────────────
//   ① GET /admin/deliveries **orderId 必填**（@RequestParam Long orderId），返回 List 且**不分页**
//   ② **没有** GET /admin/deliveries/{id} → action=get 必须同时给 orderId（工具层从订单的发货单列表里挑那一条）
//   ③ 四个编辑动作成功后返回 {orderId, splitStatus, deliveries:[{...,version,items}]} ——
//      里面已经是**改完之后的最新 version**，所以回读新 version 直接用这个响应（不再多发一次 GET）
//   ④ dispatch / cancel 返回的是 DeliveryOrder 实体（没有明细、没有仓名）→ 工具层再回读一次
//      GET /admin/deliveries?orderId=（实体里有 orderId），把新 version 与状态交给模型

import { z } from 'zod';
import {
  listDeliveries,
  updateDelivery,
  splitDelivery,
  mergeDelivery,
  deleteDelivery,
  dispatchDelivery,
  cancelDelivery,
} from '../mall.mjs';
import { text, fail, failWrite, clampPage, paginate, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

// ---------- 状态码中文化 ----------
// ⚠️ 映射来源：oms/entity/DeliveryOrder.java 常量注释（PENDING=0 … SIGNED=5 / CANCELLED=9），别猜。
//    注意 6/7/8 后端**没有定义**：真出现了就原样带出「未知状态(7)」，不静默吞掉。
const DELIVERY_STATUS_TEXT = {
  0: '待下发',
  1: '已下发',
  2: '已拣货',
  3: '已出库',
  4: '运输中',
  5: '已签收',
  9: '已取消',
};
const deliveryStatusLabel = (v) => DELIVERY_STATUS_TEXT[v] ?? `未知状态(${v ?? '-'})`;

/** 来源：0 自动拆单 / 1 人工调整（DeliveryOrder.SOURCE_AUTO / SOURCE_MANUAL） */
const deliverySourceLabel = (v) => (v === 1 ? '人工调整' : v === 0 ? '自动拆单' : `未知(${v ?? '-'})`);

// 本工具的 action 全集；write=0 时只剩只读的那两个。
// 需要 version 的是 update / split / merge / delete（后端收 body.version、delete 收 query.version）；
// dispatch / cancel 后端不收 version。六个写动作全是 L3，所以描述里逐条写了后果。
const ACTIONS_ALL = ['list', 'get', 'update', 'split', 'merge', 'delete', 'dispatch', 'cancel'];
const ACTIONS_READONLY = ['list', 'get'];

/** 分页参数（全项目统一：page 默认 1、size 默认 10、最大 50，超出夹取不报错） */
const pageSchema = {
  page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
  size: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
};

/** 「改量」的一行：注意 deliveryItemId 是 delivery_item.id，**不是** order_item.id（后端 DTO 注释明写） */
const itemQtySchema = z.object({
  deliveryItemId: z.number().int().positive().describe('发货单明细 id（delivery_item.id，不是 orderItemId）'),
  quantity: z.number().int().min(1).describe('目标数量（拆分时 = 要拆出的数量，改量时 = 改成的数量）'),
});

// ---------- 展示辅助 ----------

function itemsToText(items) {
  const rows = Array.isArray(items) ? items : [];
  if (!rows.length) return '    （该包裹没有明细）';
  return rows
    .map(
      (it, i) =>
        `    ${i + 1}. deliveryItemId=${it.id} orderItemId=${it.orderItemId ?? '-'} skuId=${it.productSkuId ?? '-'} 数量=${it.quantity}`
    )
    .join('\n');
}

function deliveryRowText(d, i) {
  const wh = d.warehouseName ? `${d.warehouseName}（id=${d.warehouseId}）` : `id=${d.warehouseId}`;
  const extra = [
    d.company ? `物流=${d.company}` : null,
    d.trackNo ? `运单号=${d.trackNo}` : null,
    Number.isInteger(d.version) ? `version=${d.version}` : null,
  ]
    .filter(Boolean)
    .join(' ');
  return `  ${i + 1}. id=${d.id} 单号=${d.deliverySn ?? '-'} 仓库=${wh} 状态=${deliveryStatusLabel(d.status)}(${d.status}) 来源=${deliverySourceLabel(d.source)} 明细=${(d.items || []).length} 条${extra ? ` ${extra}` : ''}`;
}

/** 编辑类接口返回 {orderId, splitStatus, deliveries} → 直接当「回读结果」用（已带最新 version） */
function summaryToText(summary) {
  const rows = Array.isArray(summary?.deliveries) ? summary.deliveries : [];
  return rows.map(deliveryRowText).join('\n') || '  （该订单当前没有发货单）';
}

// ---------- 注册 ----------

/**
 * 注册履约域工具（1 个：delivery）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerDeliveryTools(server, { writeEnabled } = {}) {
  // write=0 → action 枚举里去掉写动作（工具仍在，只读的两个照常可用）。
  // ⚠️ 不能整工具不注册：list / get 是只读的，write=0 时也必须能用（与 order.mjs 的教训一致）。
  const actions = writeEnabled ? ACTIONS_ALL : ACTIONS_READONLY;
  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → delivery 工具只开放 list / get（update/split/merge/delete/dispatch/cancel 不开放）');
  }

  server.registerTool(
    'delivery',
    {
      title: '发货单履约操作（查 / 改仓改量 / 拆合 / 删除 / 下发 / 取消）',
      description:
        '按 action 操作发货单（拆单后的包裹）。' +
        'list=按订单查发货单（分页）、get=看某个包裹详情（含明细与 version）；' +
        'update=改仓/改量、split=拆成两个包裹、merge=并把一个包裹并入另一个、delete=删除包裹、' +
        'dispatch=下发给仓库（WMS）、cancel=取消并回补库存。' +
        '⚠️ 写动作全是 L3（高影响）：update/split/merge/delete/dispatch/cancel 都会真改数据，' +
        '其中 **dispatch 下发后不可撤回**（只能等仓库 WMS 回传拣货/出库/运输/签收），' +
        '**cancel 会回补库存并重算订单状态**，**delete 就是删掉这个包裹**。' +
        '动手前先 list / get 看清当前状态与 version，并把要做什么念给用户确认。' +
        '所有写动作都支持 dryRun=true 预演：只返回 willDo（后端会跑同一套业务守卫），不落库、不需要 version。' +
        'update/split/merge/delete **必须带 version**（后端 fail-closed：缺了回 400，期间被别人改过回 409）；' +
        'dispatch/cancel 后端不收 version，靠「仅待下发」状态守卫兜住。' +
        '只有「待下发(0)」的包裹能改：已下发的必须先取消（会回补库存）才谈得上改。',
      inputSchema: {
        action: z.enum(actions).describe(
          'list=列表(只读) / get=详情(只读) / update=改仓改量(L3,需version) / split=拆分(L3,需version) / ' +
            'merge=合并(L3,需version) / delete=删除(L3,需version) / dispatch=下发仓库(L3,不可撤回) / cancel=取消(L3,回补库存)'
        ),
        orderId: z
          .number()
          .int()
          .positive()
          .optional()
          .describe('订单 id：**list / get 必填**（后端只有「按订单查发货单」这一个查询入口，没有全局列表也没有单条详情接口）'),
        deliveryId: z.number().int().positive().optional().describe('发货单 id：get 与全部写动作必填（list 时忽略）'),
        targetId: z
          .number()
          .int()
          .positive()
          .optional()
          .describe('merge=要并入的目标包裹 id；delete=还有明细时必须给（等同并入别处）。delete 时为空包裹才能直接删'),
        warehouseId: z.number().int().positive().optional().describe('update=新的发货仓 id（先用 warehouse(action=list) 查；不给=不改仓）'),
        newWarehouseId: z.number().int().positive().optional().describe('split=新包裹的发货仓 id（不给=沿用原包裹的仓）'),
        items: z
          .array(itemQtySchema)
          .min(1)
          .optional()
          .describe('update=要改量的明细；split=要拆出的明细与数量（split 必填，后端拒绝拆出空包）'),
        version: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe(
            '发货单的版本号，**来自最近一次 delivery(action=get/list) 的返回**；' +
              'update/split/merge/delete **必填**（后端缺了直接回 400，不传就是浪费一次往返）；' +
              'dispatch/cancel 不需要（后端不收）；dryRun 预演时也不需要'
          ),
        dryRun: z
          .boolean()
          .optional()
          .describe('true=只预演：返回「将要发生什么」，不落库、不需要 version（强烈建议 L3 动作先预演一次）'),
        ...pageSchema,
      },
    },
    async ({ action, orderId, deliveryId, targetId, warehouseId, newWarehouseId, items, version, dryRun, page, size }) => {
      // ① 写动作门禁（write=0 时 action 枚举里已经没有它们，这里是第二道闸）
      if (!writeEnabled && !ACTIONS_READONLY.includes(action)) {
        return fail(new Error(`写操作未启用：delivery(action=${action}) 需要 MCP_ENABLE_WRITE=1（当前只开放 ${ACTIONS_READONLY.join(' / ')}）`));
      }

      try {
        // ---------- ② 只读：list ----------
        if (action === 'list') {
          if (!orderId) throw new Error('action=list 必须给 orderId（后端只有「按订单查发货单」这一个入口，没有全局发货单列表）');
          const { page: p, size: s } = clampPage({ page, size });
          const rows = await listDeliveries(orderId);
          const all = Array.isArray(rows) ? rows : [];
          const paged = paginate(all, { page: p, size: s });
          const head = `订单 ${orderId} 的发货单：${pageHeader('', paged)}（待下发 ${all.filter((d) => d.status === 0).length} 个、已取消 ${all.filter((d) => d.status === 9).length} 个）`;
          if (!all.length) {
            return text(
              `订单 ${orderId} 没有任何发货单（还没拆单？先用 preview_split / execute_audit_pass 生成包裹）。\n\n` +
                `JSON:\n${JSON.stringify(paged, null, 2)}`
            );
          }
          const body = paged.records.map(deliveryRowText).join('\n');
          return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }

        // ---------- ③ 只读：get（后端没有单条详情接口 → 按订单列表挑一条）----------
        if (action === 'get') {
          if (!deliveryId) throw new Error('action=get 必须给 deliveryId');
          if (!orderId) {
            throw new Error(
              'action=get 还必须给 orderId：后端没有 GET /admin/deliveries/{id} 这种单条详情接口，' +
                '只能先按订单取发货单列表再挑出这一条（orderId 可用 list_deliveries / list_orders 查）'
            );
          }
          const rows = await listDeliveries(orderId);
          const found = (Array.isArray(rows) ? rows : []).find((d) => Number(d.id) === Number(deliveryId));
          if (!found) {
            throw new Error(`订单 ${orderId} 下没有 id=${deliveryId} 的发货单（请用 delivery(action=list, orderId=${orderId}) 核对该订单的包裹 id）`);
          }
          return text(
            `发货单详情 id=${found.id} 单号=${found.deliverySn ?? '-'}（订单 ${orderId}）\n` +
              `  仓库=${found.warehouseName || '-'}（id=${found.warehouseId}） 状态=${deliveryStatusLabel(found.status)}(${found.status}) 来源=${deliverySourceLabel(found.source)}\n` +
              `  收货人=${found.receiverName ?? '-'} 物流=${found.company ?? '-'} 运单号=${found.trackNo ?? '-'}\n` +
              `  version=${found.version ?? '-'}（改它必须带上这个号）\n` +
              `  明细 ${(found.items || []).length} 条：\n${itemsToText(found.items)}\n\n` +
              `JSON:\n${JSON.stringify(found, null, 2)}`
          );
        }

        // ---------- ④ 写：dispatch（不可撤回）----------
        if (action === 'dispatch') {
          if (!deliveryId) throw new Error('action=dispatch 必须给 deliveryId');
          const resp = await dispatchDelivery(deliveryId, { dryRun });
          if (dryRun === true) {
            return text(`预演（未落库）— 下发发货单 ${deliveryId}：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`);
          }
          const orderIdOf = resp?.orderId ?? null;
          let after = null;
          if (orderIdOf) {
            try {
              const rows = await listDeliveries(orderIdOf);
              after = (Array.isArray(rows) ? rows : []).find((d) => Number(d.id) === Number(deliveryId)) ?? null;
            } catch {
              after = null; // 回读失败不影响「已下发」这个事实，下面文案会说清
            }
          }
          return text(
            `✅ 发货单 ${deliveryId} 已下发给仓库（状态 → 已下发(1)）。\n` +
              `  ⚠️ 下发**不可撤回**：接下来只能等仓库 WMS 回传（拣货 → 出库 → 运输中 → 签收）。\n` +
              (after
                ? `  回读：状态=${deliveryStatusLabel(after.status)}(${after.status}) 仓库=${after.warehouseName || after.warehouseId} version=${after.version ?? '-'}\n`
                : `  回读失败（orderId=${orderIdOf ?? '未知'}），请再调 delivery(action=list, orderId=…) 确认状态。\n`) +
              `\nJSON:\n${JSON.stringify({ deliveryId, dryRun: false, dispatched: true, delivery: after ?? resp }, null, 2)}`
          );
        }

        // ---------- ⑤ 写：cancel（回补库存）----------
        if (action === 'cancel') {
          if (!deliveryId) throw new Error('action=cancel 必须给 deliveryId');
          const resp = await cancelDelivery(deliveryId, { dryRun });
          if (dryRun === true) {
            return text(`预演（未落库）— 取消发货单 ${deliveryId}：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`);
          }
          const orderIdOf = resp?.orderId ?? null;
          let after = null;
          if (orderIdOf) {
            try {
              const rows = await listDeliveries(orderIdOf);
              after = (Array.isArray(rows) ? rows : []).find((d) => Number(d.id) === Number(deliveryId)) ?? null;
            } catch {
              after = null;
            }
          }
          return text(
            `✅ 发货单 ${deliveryId} 已取消（状态 → 已取消(9)），该包裹的明细数量已**回补到总池库存**，订单状态已重算。\n` +
              (after ? `  回读：状态=${deliveryStatusLabel(after.status)}(${after.status}) version=${after.version ?? '-'}\n` : '') +
              `\nJSON:\n${JSON.stringify({ deliveryId, dryRun: false, cancelled: true, delivery: after ?? resp }, null, 2)}`
          );
        }

        // ---------- ⑥ 写：update / split / merge / delete（四个编辑动作，吃 version）----------
        if (!deliveryId) throw new Error(`action=${action} 必须给 deliveryId`);

        // dryRun 预演：不落库、不需要 version（后端在版本校验之前就走预演分支）
        if (dryRun === true) {
          let preview;
          if (action === 'update') preview = await updateDelivery(deliveryId, { warehouseId, items, dryRun: true });
          else if (action === 'split') preview = await splitDelivery(deliveryId, { newWarehouseId, items, dryRun: true });
          else if (action === 'merge') preview = await mergeDelivery(deliveryId, { targetId, dryRun: true });
          else preview = await deleteDelivery(deliveryId, { targetId, dryRun: true });
          return text(
            `预演（未落库）— ${action} 发货单 ${deliveryId}：\n  ${preview?.willDo ?? '(后端没有返回 willDo)'}\n\n` +
              `JSON:\n${JSON.stringify(preview, null, 2)}`
          );
        }

        // 参数自检（后端也会拦，但工具层先说清「哪个 action 缺哪个参数」，省一次往返）
        if (action === 'update' && warehouseId === undefined && !(Array.isArray(items) && items.length)) {
          throw new Error('action=update 至少要给 warehouseId（改仓）或 items（改量）其中一个，否则等于什么都没改');
        }
        if (action === 'split' && !(Array.isArray(items) && items.length)) {
          throw new Error('action=split 必须给 items（要拆出的明细与数量），后端拒绝拆出空包');
        }
        if (action === 'merge' && !targetId) throw new Error('action=merge 必须给 targetId（要并入的目标包裹）');

        let summary;
        const label = `发货单 ${deliveryId}`;
        const reread = `delivery(action=get, orderId=…, deliveryId=${deliveryId})`;
        try {
          if (action === 'update') summary = await updateDelivery(deliveryId, { warehouseId, items, version });
          else if (action === 'split') summary = await splitDelivery(deliveryId, { newWarehouseId, items, version });
          else if (action === 'merge') summary = await mergeDelivery(deliveryId, { targetId, version });
          else summary = await deleteDelivery(deliveryId, { targetId, version });
        } catch (e) {
          // 400（缺 version）/ 409（版本冲突或被抢拆）/ 业务守卫（非待下发）都从这里出
          return failWrite(e, {
            action: `${action} 发货单`,
            label,
            oldVersion: version,
            rereadTool: reread,
          });
        }

        const orderIdOf = summary?.orderId ?? orderId ?? null;
        const head =
          action === 'update'
            ? `✅ 发货单 ${deliveryId} 已按人工调整改好（改仓/改量），订单拆单标记已重算。`
            : action === 'split'
              ? `✅ 发货单 ${deliveryId} 已拆成两个待下发包裹（新包见下方列表）。`
              : action === 'merge'
                ? `✅ 发货单 ${deliveryId} 已并入包裹 ${targetId}（源包已消失）。`
                : `✅ 发货单 ${deliveryId} 已删除${targetId ? `（明细已并入包裹 ${targetId}）` : '（空包裹）'}。`;
        return text(
          `${head}\n` +
            `订单 ${orderIdOf ?? '(未知)'} 现有包裹（含**改完之后的最新 version**，下次再改必须用新的）：\n` +
            `${summaryToText(summary)}\n\n` +
            `JSON:\n${JSON.stringify({ action, deliveryId, targetId: targetId ?? null, oldVersion: version ?? null, summary }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );
}
