// 基础配置域（config）工具：2 个
//   warehouse(action)      list / create / update / delete —— 仓库字典（L2）
//   order_setting(action)  get / update                   —— 订单全局设置（update 是 L2，但影响全站拆单/关单口径）
//
// ── 后端实际形态（已读 WarehouseController / OrderSettingController 确认）──
//   ① GET/POST/PUT/DELETE /admin/warehouses：List **不分页**；表**无 version 列** →
//      PUT 不带版本号，靠「影响 0 行 → 409 记录已被删除」兜底。DELETE 无守卫：
//      ⚠️ 但 warehouse 被 inventory / delivery_order 外键引用，删不掉的库会直接报外键错误（中文原因透出即可）。
//   ② GET /admin/order-setting：全局**只有一行**；库里没有行时后端返回**空对象 {}**（不是 null、不是报错）
//      → 工具层必须能优雅表达「未初始化」这种形态。
//      PUT 是 upsert（有则改、无则插），**不校验影响行数、也没有 version** —— 后端刻意如此
//      （否则「改个自动收货天数」会因为别人刚好删了那行变成随机 409）。
//   ③ 所以本域**没有可用 version 的写接口**：这不是我省略校验，而是后端这两张表就
//      没有 version 列（OptimisticLockGuard 注释里点名的就是这一类表）。

import { z } from 'zod';
import {
  listWarehouses,
  createWarehouse,
  updateWarehouse,
  deleteWarehouse,
  getOrderSetting,
  updateOrderSetting,
} from '../mall.mjs';
import { text, fail, failWrite, clampPage, paginate, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

const warehouseStatusLabel = (v) => (v === 1 ? '启用' : v === 0 ? '停用' : `未知(${v ?? '-'})`);

const pageSchema = {
  page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
  size: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
};

/**
 * 注册基础配置域工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerConfigTools(server, { writeEnabled } = {}) {
  if (!writeEnabled) console.error('[mcp] MCP_ENABLE_WRITE!=1 → warehouse 只开放 list、order_setting 只开放 get');

  // ---------------- ① 仓库 ----------------
  server.registerTool(
    'warehouse',
    {
      title: '仓库字典（查 / 增 / 改 / 删）',
      description:
        '仓库是拆单的归属依据（每个 SKU 按分仓库存落到某个仓，发货单也挂在某个仓上）。' +
        'list=查全部仓库（只读，量小不分页）；create=新增；update=改名/启停；delete=删除（L2）。' +
        '⚠️ 写操作会影响拆单：新建仓之后还要给 SKU 配分仓库存（inventory）才会被拆到；' +
        '**停用(0) 只是标记，不改历史发货单**；**delete 会真删这一行**，被 inventory / 发货单引用的仓删不掉（外键报错会原样透出）。' +
        '仓库表没有 version 列：改一条刚被别人删掉的仓 → 409「记录已被删除」，重新 list 一次即可。',
      inputSchema: {
        action: z.enum(writeEnabled ? ['list', 'create', 'update', 'delete'] : ['list']).describe('list=列表(只读) / create=新增 / update=改名或启停 / delete=删除'),
        warehouseId: z.number().int().positive().optional().describe('仓库 id：update / delete 必填（先用 list 查）'),
        name: z.string().min(1).optional().describe('仓库名（如「上海一号仓」）：create 必填；update 时不传=不改名'),
        status: z.number().int().min(0).max(1).optional().describe('1 启用 / 0 停用：create 不传默认 1；update 不传=不改'),
        ...pageSchema,
      },
    },
    async ({ action, warehouseId, name, status, page, size }) => {
      try {
        if (action === 'list') {
          const { page: p, size: s } = clampPage({ page, size });
          const rows = await listWarehouses();
          const all = Array.isArray(rows) ? rows : [];
          const paged = paginate(all, { page: p, size: s });
          const head =
            `仓库字典：${pageHeader('', paged)}` +
            `（启用 ${all.filter((w) => w.status === 1).length} 个、停用 ${all.filter((w) => w.status === 0).length} 个）`;
          if (!all.length) {
            return text(`${head}\n（还没有任何仓库，可用 warehouse(action=create, name="…") 建一个）\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
          }
          const body = paged.records.map((w, i) => `  ${i + 1}. id=${w.id} ${w.name} 状态=${warehouseStatusLabel(w.status)}`).join('\n');
          return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }

        if (!writeEnabled) {
          return fail(new Error(`写操作未启用：warehouse(action=${action}) 需要 MCP_ENABLE_WRITE=1`));
        }

        if (action === 'create') {
          if (!name) throw new Error('action=create 必须给 name（仓库名）');
          const created = await createWarehouse({ name, status });
          return text(
            `✅ 已新增仓库：id=${created?.id ?? '(后端未返回)'}「${created?.name ?? name}」状态=${warehouseStatusLabel(created?.status)}。\n` +
              `  下一步（若要它真的被拆单命中）：给 SKU 配这个仓的分仓库存（inventory）。\n\n` +
              `JSON:\n${JSON.stringify(created, null, 2)}`
          );
        }

        if (!warehouseId) throw new Error(`action=${action} 必须给 warehouseId`);
        if (action === 'update' && name === undefined && status === undefined) {
          throw new Error('action=update 至少要给 name（改名）或 status（启停）其中一个');
        }

        let after = null;
        if (action === 'update') {
          try {
            await updateWarehouse(warehouseId, { name, status });
          } catch (e) {
            return failWrite(e, { action: '仓库修改', label: `warehouseId=${warehouseId}`, rereadTool: 'warehouse（action=list）' });
          }
        } else {
          try {
            await deleteWarehouse(warehouseId);
          } catch (e) {
            return failWrite(e, { action: '仓库删除', label: `warehouseId=${warehouseId}`, rereadTool: 'warehouse（action=list）' });
          }
        }

        // 回读（无 version 概念，回读只为把真实结果交给模型）
        try {
          const rows = await listWarehouses();
          after = (Array.isArray(rows) ? rows : []).find((w) => Number(w.id) === Number(warehouseId)) ?? null;
        } catch {
          after = null;
        }
        return text(
          action === 'update'
            ? `✅ 仓库 ${warehouseId} 已更新：名称=${after?.name ?? name ?? '-'} 状态=${warehouseStatusLabel(after?.status)}\n\nJSON:\n${JSON.stringify({ warehouseId, warehouse: after }, null, 2)}`
            : `✅ 仓库 ${warehouseId} 已删除（回读确认已不在列表里=${after === null}）。⚠️ 若还有 SKU 的库存记在这个仓，拆单时不会再有它。\n\nJSON:\n${JSON.stringify({ warehouseId, deleted: after === null }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ---------------- ② 订单全局设置 ----------------
  server.registerTool(
    'order_setting',
    {
      title: '订单全局设置（查 / 改）',
      description:
        '订单系统的全局配置（库里只有一行）：autoConfirmDays=自动收货天数、autoCloseMinutes=未付款自动关单分钟数。' +
        'get=查看当前值（只读，未初始化时返回空配置）；update=修改（**L2，但影响全站**）。' +
        '⚠️ 这个工具改的不是某一笔订单，而是**全站所有订单的行为**：把 autoCloseMinutes 调小会让一堆待付款单提前被关，' +
        '把 autoConfirmDays 调小会让已发货单提前自动确认收货（并进入完成态）。' +
        '所以 update 前必须把「改哪个字段、从多少改成多少、影响哪一类订单」念给用户确认；' +
        '只传要改的字段（不传的保持原值）。该接口后端是「有则改、无则插」的 upsert，**没有 version 也没有并发冲突报错**，' +
        '意味着后端不会替你拦「两个人在同时改」—— 工具层唯一的安全带就是「先 get 看清当前值再改」。',
      inputSchema: {
        action: z.enum(writeEnabled ? ['get', 'update'] : ['get']).describe('get=查看当前设置(只读) / update=修改(影响全站)'),
        autoConfirmDays: z
          .number()
          .int()
          .min(1)
          .optional()
          .describe('自动收货天数（>0 的整数）；不传=不改。⚠️ 调小会让已发货订单提前自动确认收货'),
        autoCloseMinutes: z
          .number()
          .int()
          .min(1)
          .optional()
          .describe('未付款自动关单分钟数（>0 的整数）；不传=不改。⚠️ 调小会让待付款订单提前被关闭'),
      },
    },
    async ({ action, autoConfirmDays, autoCloseMinutes }) => {
      try {
        const current = await getOrderSetting();
        // 后端在「表里没有行」时返回空对象 {}，且没有 id —— 工具层必须表达清楚这是「未初始化」
        const initialized = !!(current && current.id);

        if (action === 'get') {
          if (!initialized) {
            return text(
              '订单全局设置：**尚未初始化**（库里还没有配置行，后端返回空对象）。' +
                '此时系统用的是代码里的默认口径；第一次 update 时后端会自动插入这一行。\n\n' +
                `JSON:\n${JSON.stringify({ initialized: false, setting: current ?? null }, null, 2)}`
            );
          }
          return text(
            `订单全局设置（id=${current.id}）：\n` +
              `  自动收货天数 autoConfirmDays=${current.autoConfirmDays ?? '-'}\n` +
              `  未付款自动关单分钟数 autoCloseMinutes=${current.autoCloseMinutes ?? '-'}\n\n` +
              `JSON:\n${JSON.stringify({ initialized: true, setting: current }, null, 2)}`
          );
        }

        if (!writeEnabled) return fail(new Error('写操作未启用：order_setting(action=update) 需要 MCP_ENABLE_WRITE=1'));
        if (autoConfirmDays === undefined && autoCloseMinutes === undefined) {
          throw new Error('action=update 至少要给 autoConfirmDays 或 autoCloseMinutes 其中一个（否则等于什么都没改）');
        }

        const changed = [
          autoConfirmDays !== undefined ? `autoConfirmDays ${current?.autoConfirmDays ?? '(未设置)'} → ${autoConfirmDays}` : null,
          autoCloseMinutes !== undefined ? `autoCloseMinutes ${current?.autoCloseMinutes ?? '(未设置)'} → ${autoCloseMinutes}` : null,
        ].filter(Boolean);

        let resp;
        try {
          resp = await updateOrderSetting({ autoConfirmDays, autoCloseMinutes });
        } catch (e) {
          return failWrite(e, { action: '订单设置修改', label: 'order_setting', rereadTool: 'order_setting（action=get）' });
        }

        let after = null;
        try {
          after = await getOrderSetting();
        } catch {
          after = null;
        }
        return text(
          `✅ 订单全局设置已更新（影响全站订单行为）：\n  ${changed.join('\n  ')}\n` +
            `  当前值：autoConfirmDays=${after?.autoConfirmDays ?? '-'} autoCloseMinutes=${after?.autoCloseMinutes ?? '-'}\n` +
            `  ⚠️ 这是全局配置：新口径对之后的状态流转生效，请把这次改动告知用户/运营。\n\n` +
            `JSON:\n${JSON.stringify({ before: current ?? null, changed, response: resp ?? null, after: after ?? null }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );
}
