// 商品域**基础字典**工具：3 个（category / product_type / attribute），覆盖后端三个 CRUD Controller。
//
// ── 为什么单独开一个文件 ───────────────────────────────────────────────
// 分类、商品类型、属性是「商品之前的字典」：后台商品页要用它们，但它们不是商品本身。
// 之前这三个 Controller 只覆盖了 list（list_categories / list_product_types / list_attributes），
// 后台「分类管理 / 商品类型管理 / 属性管理」三个页面的**增删改**在 MCP 侧是空白 —— 本文件补齐。
//
// ── 后端真实形态（已读 CategoryController / ProductTypeController / AttributeController）──
//   GET    /admin/categories[/{id}]        parentId / name
//   GET    /admin/product-types[/{id}]     name
//   GET    /admin/attributes[/{id}]        typeId / name / isFilter
//   三张表**都没有 version 列**（用户定的口径：没有 version 列就不许凭空造 version 参数）：
//     · 更新并发靠「UPDATE 影响 0 行 → 409『记录已被删除』」（后端 OptimisticLockGuard.requireAffected）
//     · 删除有引用守卫（CatalogDeleteGuard）：分类下有子分类/商品、类型被属性或商品引用、属性被 SKU 规格值使用 → 拒绝并回中文原因
//   ⚠️ 所以本文件里**没有 version 参数**，这不是漏写；用了反而每次调用都会被当成非法入参。
//
// ── 列表为什么不在这里 ─────────────────────────────────────────────────
// 列表已经有 list_categories / list_product_types / list_attributes（只读域，write=0 时也能用），
// 这里只留 get / create / update / delete，避免同一个能力出现两套入口、模型两边乱选。

import { z } from 'zod';
import {
  getCategory,
  createCategory,
  updateCategory,
  deleteCategory,
  getProductType,
  createProductType,
  updateProductType,
  deleteProductType,
  getAttribute,
  createAttribute,
  updateAttribute,
  deleteAttribute,
} from '../mall.mjs';
import { text, fail, failWrite } from './_shared.mjs';

const WRITE_ACTIONS = ['get', 'create', 'update', 'delete'];
const READONLY_ACTIONS = ['get'];

/**
 * 三个字典共用的「回读一条」：删完返回 null（不存在），改完返回新值。
 * 读不到（被删了 / 网络抖动）不抛错 —— 调用方文案里说清「回读失败」即可。
 */
async function reread(fn, id) {
  try {
    return (await fn(id)) ?? null;
  } catch {
    return null;
  }
}

/**
 * 注册字典工具（category / product_type / attribute）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerCatalogDictTools(server, { writeEnabled } = {}) {
  const actions = writeEnabled ? WRITE_ACTIONS : READONLY_ACTIONS;
  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → category / product_type / attribute 只开放 action=get（create/update/delete 不开放）');
  }

  // ① 分类
  server.registerTool(
    'category',
    {
      title: '商品分类（查 / 新增 / 改名 / 删除）',
      description:
        '维护商品分类字典（后台「分类管理」页）。get=看一个分类；create=新增（parentId 传 0 为一级分类）；' +
        'update=改名或换父级；delete=删除（有子分类或商品挂在该分类下时后端会拒绝并说明原因）。' +
        '看整棵树请用 list_categories（本工具不做列表，避免两套入口）。' +
        '⚠️ 分类表**没有版本号**：改一条刚被别人删掉的分类 → 后端 409「记录已被删除」，重新 list_categories 一次即可，不要盲目重试。',
      inputSchema: {
        action: z.enum(actions).describe('get=查看(只读) / create=新增 / update=改名或换父级 / delete=删除'),
        categoryId: z.number().int().positive().optional().describe('分类 id：get / update / delete 必填（先用 list_categories 查）'),
        name: z.string().min(1).optional().describe('分类名：create 必填；update 时传了才改名'),
        parentId: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe('父分类 id：create 不传默认 0（一级分类）；update 传了才换父级（0=抬成一级）'),
      },
    },
    async ({ action, categoryId, name, parentId }) => {
      if (!writeEnabled && action !== 'get') {
        return fail(new Error(`写操作未启用：category(action=${action}) 需要 MCP_ENABLE_WRITE=1（当前只开放 action=get）`));
      }
      try {
        if (action === 'get') {
          if (!categoryId) throw new Error('action=get 必须给 categoryId（整棵分类树请用 list_categories）');
          const row = await getCategory(categoryId);
          if (!row) return text(`分类 ${categoryId} 不存在（后端返回空）。请用 list_categories 核对 id。\n\nJSON:\nnull`);
          return text(
            `分类 ${row.id}：${row.name}（父分类=${row.parentId ?? 0}${Number(row.parentId ?? 0) === 0 ? '，即一级分类' : ''}）\n\n` +
              `JSON:\n${JSON.stringify(row, null, 2)}`
          );
        }
        if (action === 'create') {
          if (!name) throw new Error('action=create 必须给 name（分类名）');
          const created = await createCategory({ name, ...(parentId === undefined ? {} : { parentId }) });
          return text(
            `✅ 已新增分类：id=${created?.id}「${created?.name}」父分类=${created?.parentId ?? 0}\n\n` +
              `JSON:\n${JSON.stringify(created, null, 2)}`
          );
        }
        if (action === 'update') {
          if (!categoryId) throw new Error('action=update 必须给 categoryId');
          if (name === undefined && parentId === undefined) {
            throw new Error('action=update 至少要给 name 或 parentId 其中一个，否则等于什么都没改');
          }
          const patch = {};
          if (name !== undefined) patch.name = name;
          if (parentId !== undefined) patch.parentId = parentId;
          try {
            await updateCategory(categoryId, patch);
          } catch (e) {
            return failWrite(e, { action: '分类修改', label: `categoryId=${categoryId}`, rereadTool: 'list_categories' });
          }
          const after = await reread(getCategory, categoryId);
          return text(
            `✅ 分类 ${categoryId} 已更新：${Object.keys(patch).join('、')}\n` +
              `  当前值：名称=${after?.name ?? '-'} 父分类=${after?.parentId ?? '-'}\n\n` +
              `JSON:\n${JSON.stringify({ categoryId, changed: Object.keys(patch), category: after }, null, 2)}`
          );
        }
        // delete
        if (!categoryId) throw new Error('action=delete 必须给 categoryId');
        try {
          await deleteCategory(categoryId);
        } catch (e) {
          return failWrite(e, { action: '分类删除', label: `categoryId=${categoryId}`, rereadTool: 'list_categories' });
        }
        const gone = await reread(getCategory, categoryId);
        return text(
          `✅ 分类 ${categoryId} 已删除。回读核对：${gone ? '⚠️ 仍能读到，请人工核查' : '已不存在（符合预期）'}\n\n` +
            `JSON:\n${JSON.stringify({ categoryId, deleted: true, verifiedGone: !gone }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ② 商品类型
  server.registerTool(
    'product_type',
    {
      title: '商品类型（查 / 新增 / 改名 / 删除）',
      description:
        '维护商品类型字典（后台「商品类型管理」页）。商品类型决定这个商品能用哪些规格属性（属性挂在类型上）。' +
        'get=看一个类型；create=新增；update=改名；delete=删除（被属性或商品引用时后端拒绝并说明原因）。' +
        '看全部类型请用 list_product_types。⚠️ 类型表**没有版本号**，改一条刚被删的 → 409「记录已被删除」。',
      inputSchema: {
        action: z.enum(actions).describe('get=查看(只读) / create=新增 / update=改名 / delete=删除'),
        typeId: z.number().int().positive().optional().describe('类型 id：get / update / delete 必填（先用 list_product_types 查）'),
        name: z.string().min(1).optional().describe('类型名：create 必填；update 时必填（改名）'),
      },
    },
    async ({ action, typeId, name }) => {
      if (!writeEnabled && action !== 'get') {
        return fail(new Error(`写操作未启用：product_type(action=${action}) 需要 MCP_ENABLE_WRITE=1（当前只开放 action=get）`));
      }
      try {
        if (action === 'get') {
          if (!typeId) throw new Error('action=get 必须给 typeId（全部类型请用 list_product_types）');
          const row = await getProductType(typeId);
          if (!row) return text(`商品类型 ${typeId} 不存在（后端返回空）。请用 list_product_types 核对 id。\n\nJSON:\nnull`);
          return text(`商品类型 ${row.id}：${row.name}\n\nJSON:\n${JSON.stringify(row, null, 2)}`);
        }
        if (action === 'create') {
          if (!name) throw new Error('action=create 必须给 name（类型名）');
          const created = await createProductType({ name });
          return text(`✅ 已新增商品类型：id=${created?.id}「${created?.name}」\n\nJSON:\n${JSON.stringify(created, null, 2)}`);
        }
        if (action === 'update') {
          if (!typeId) throw new Error('action=update 必须给 typeId');
          if (!name) throw new Error('action=update 必须给 name（新的类型名）');
          try {
            await updateProductType(typeId, { name });
          } catch (e) {
            return failWrite(e, { action: '商品类型修改', label: `typeId=${typeId}`, rereadTool: 'list_product_types' });
          }
          const after = await reread(getProductType, typeId);
          return text(
            `✅ 商品类型 ${typeId} 已改名为「${after?.name ?? name}」\n\nJSON:\n${JSON.stringify({ typeId, productType: after }, null, 2)}`
          );
        }
        if (!typeId) throw new Error('action=delete 必须给 typeId');
        try {
          await deleteProductType(typeId);
        } catch (e) {
          return failWrite(e, { action: '商品类型删除', label: `typeId=${typeId}`, rereadTool: 'list_product_types' });
        }
        const gone = await reread(getProductType, typeId);
        return text(
          `✅ 商品类型 ${typeId} 已删除。回读核对：${gone ? '⚠️ 仍能读到，请人工核查' : '已不存在（符合预期）'}\n\n` +
            `JSON:\n${JSON.stringify({ typeId, deleted: true, verifiedGone: !gone }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ③ 商品属性（规格名）
  server.registerTool(
    'attribute',
    {
      title: '商品属性（查 / 新增 / 改名 / 删除）',
      description:
        '维护商品属性字典（后台「属性管理」页）—— 属性就是「规格名」，如「颜色」「容量」，挂在某个商品类型下。' +
        'get=看一个属性；create=新增（要挂到某个 typeId 下）；update=改名或改「是否可筛选」；' +
        'delete=删除（已被 SKU 规格值使用时后端拒绝并说明原因）。' +
        '看某个类型下的全部属性请用 list_attributes(typeId=…) 或 list_sku_attributes(productId=…)。' +
        '⚠️ 属性表**没有版本号**，改一条刚被删的 → 409「记录已被删除」。',
      inputSchema: {
        action: z.enum(actions).describe('get=查看(只读) / create=新增 / update=改名或改可筛选 / delete=删除'),
        attributeId: z.number().int().positive().optional().describe('属性 id：get / update / delete 必填（先用 list_attributes 查）'),
        typeId: z.number().int().positive().optional().describe('所属商品类型 id：create 必填；update 传了才换类型'),
        name: z.string().min(1).optional().describe('属性名（规格名，如「颜色」）：create 必填；update 传了才改名'),
        isFilter: z.number().int().min(0).max(1).optional().describe('是否作为筛选项：1 是 / 0 否；create 不传后端默认，update 传了才改'),
      },
    },
    async ({ action, attributeId, typeId, name, isFilter }) => {
      if (!writeEnabled && action !== 'get') {
        return fail(new Error(`写操作未启用：attribute(action=${action}) 需要 MCP_ENABLE_WRITE=1（当前只开放 action=get）`));
      }
      try {
        if (action === 'get') {
          if (!attributeId) throw new Error('action=get 必须给 attributeId（某类型下的属性列表请用 list_attributes）');
          const row = await getAttribute(attributeId);
          if (!row) return text(`属性 ${attributeId} 不存在（后端返回空）。请用 list_attributes 核对 id。\n\nJSON:\nnull`);
          return text(
            `属性 ${row.id}：${row.name}（类型 typeId=${row.typeId ?? '-'}，可筛选=${row.isFilter === 1 ? '是' : '否'}）\n\n` +
              `JSON:\n${JSON.stringify(row, null, 2)}`
          );
        }
        if (action === 'create') {
          if (!typeId) throw new Error('action=create 必须给 typeId（属性要挂在某个商品类型下）');
          if (!name) throw new Error('action=create 必须给 name（属性名，如「颜色」）');
          const body = { typeId, name, ...(isFilter === undefined ? {} : { isFilter }) };
          const created = await createAttribute(body);
          return text(
            `✅ 已新增属性：id=${created?.id}「${created?.name}」类型=${created?.typeId ?? typeId}\n\n` +
              `JSON:\n${JSON.stringify(created, null, 2)}`
          );
        }
        if (action === 'update') {
          if (!attributeId) throw new Error('action=update 必须给 attributeId');
          if (name === undefined && isFilter === undefined && typeId === undefined) {
            throw new Error('action=update 至少要给 name / isFilter / typeId 其中一个，否则等于什么都没改');
          }
          const patch = {};
          if (name !== undefined) patch.name = name;
          if (isFilter !== undefined) patch.isFilter = isFilter;
          if (typeId !== undefined) patch.typeId = typeId;
          try {
            await updateAttribute(attributeId, patch);
          } catch (e) {
            return failWrite(e, { action: '属性修改', label: `attributeId=${attributeId}`, rereadTool: 'list_attributes' });
          }
          const after = await reread(getAttribute, attributeId);
          return text(
            `✅ 属性 ${attributeId} 已更新：${Object.keys(patch).join('、')}\n` +
              `  当前值：名称=${after?.name ?? '-'} 类型=${after?.typeId ?? '-'} 可筛选=${after?.isFilter === 1 ? '是' : '否'}\n\n` +
              `JSON:\n${JSON.stringify({ attributeId, changed: Object.keys(patch), attribute: after }, null, 2)}`
          );
        }
        if (!attributeId) throw new Error('action=delete 必须给 attributeId');
        try {
          await deleteAttribute(attributeId);
        } catch (e) {
          return failWrite(e, { action: '属性删除', label: `attributeId=${attributeId}`, rereadTool: 'list_attributes' });
        }
        const gone = await reread(getAttribute, attributeId);
        return text(
          `✅ 属性 ${attributeId} 已删除。回读核对：${gone ? '⚠️ 仍能读到，请人工核查' : '已不存在（符合预期）'}\n\n` +
            `JSON:\n${JSON.stringify({ attributeId, deleted: true, verifiedGone: !gone }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );
}
