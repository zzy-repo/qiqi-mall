// 商品域（catalog）只读工具：6 个，全部只读 → 不需要确认码。
//
// 确认码口径（全项目统一）：
//   只读工具 → 不需要确认码
//   写工具   → 透传后端签发的 confirmCode（split-preview / dryRun=true 签发，后端校验）
//
// 与后端接口的对应关系（已读 Controller 确认真实形态）：
//   search_products     → GET /admin/products（后端只支持 publishStatus，不分页/不搜关键字 → 工具层过滤+分页）
//   get_product_detail  → GET /admin/products/{id}（{product,gallery}）+ GET /admin/skus?productId=
//   list_categories     → GET /admin/categories（扁平，工具层拼树）
//   list_product_types  → GET /admin/product-types + 每个类型 GET /admin/attributes?typeId=（后端不聚合）
//   list_attributes     → GET /admin/attributes?typeId=
//   list_skus           → GET /admin/skus?productId=

import { z } from 'zod';
import {
  listProducts,
  productDetail,
  listCategories,
  listProductTypes,
  listAttributes,
  listSkus,
} from '../mall.mjs';
import { text, money, fail, clampPage, paginate, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

// ---------- 展示辅助 ----------

const publishLabel = (v) => (v === 1 ? '上架' : v === 0 ? '下架' : `未知(${v ?? '-'})`);

/** 分页参数的 zod 片段：page/size 全项目统一 */
const pageSchema = {
  page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
  // 注意：不设 .max()，超出的按后端口径**夹到 50**（不是报错）—— 模型传 999 也能拿到结果
  size: z
    .number()
    .int()
    .min(1)
    .optional()
    .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
};

/** 把扁平分类表拼成树，并支持取某分类的全部后代 id */
function buildCategoryTree(rows) {
  const byParent = new Map();
  for (const c of rows) {
    const pid = c.parentId ?? 0;
    if (!byParent.has(pid)) byParent.set(pid, []);
    byParent.get(pid).push(c);
  }
  const build = (pid) =>
    (byParent.get(pid) || []).map((c) => ({ ...c, children: build(c.id) }));
  return build(0);
}

function descendantIds(rows, rootId) {
  const ids = new Set([rootId]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const c of rows) {
      if (ids.has(c.parentId) && !ids.has(c.id)) {
        ids.add(c.id);
        grew = true;
      }
    }
  }
  return ids;
}

function categoryToText(tree, depth = 0, out = []) {
  for (const c of tree) {
    out.push(`${'  '.repeat(depth)}- ${c.name}（id=${c.id}）`);
    if (c.children?.length) categoryToText(c.children, depth + 1, out);
  }
  return out;
}

// ---------- 注册 ----------

/**
 * 注册商品域只读工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx 本域全是只读工具，暂不消费 writeEnabled
 */
export function registerCatalogTools(server, _ctx = {}) {
  // ① 搜索商品
  server.registerTool(
    'search_products',
    {
      title: '搜索商品',
      description:
        '按名称关键词 / 分类 / 商品类型 / 上下架状态查商品，返回分页结果。用户问"有哪些商品""某个分类下有什么""搜一下 XX"时用。' +
        'categoryId 会连带子分类一起查（传顶级分类=整棵子树）。',
      inputSchema: {
        keyword: z.string().min(1).optional().describe('商品名称关键词（模糊匹配，忽略大小写），不传=不按名称过滤'),
        categoryId: z.number().int().positive().optional().describe('分类 id；会自动包含其所有子分类'),
        typeId: z.number().int().positive().optional().describe('商品类型 id（如 1=手机）'),
        publishStatus: z.number().int().min(0).max(1).optional().describe('上下架状态：1 上架 / 0 下架；不传=全部'),
        ...pageSchema,
      },
    },
    async ({ keyword, categoryId, typeId, publishStatus, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });

        // 后端 /admin/products 只认 publishStatus，其余条件只能在工具层过滤
        const all = await listProducts({ publishStatus });
        let rows = Array.isArray(all) ? all : [];

        if (keyword) {
          const kw = keyword.trim().toLowerCase();
          rows = rows.filter((it) => String(it.name ?? '').toLowerCase().includes(kw));
        }
        if (typeId !== undefined) rows = rows.filter((it) => Number(it.typeId) === Number(typeId));
        if (categoryId !== undefined) {
          const cats = await listCategories();
          const allowed = descendantIds(Array.isArray(cats) ? cats : [], Number(categoryId));
          rows = rows.filter((it) => allowed.has(Number(it.categoryId)));
        }

        const paged = paginate(rows, { page: p, size: s });
        const cond = [
          keyword ? `关键词=${keyword}` : null,
          categoryId !== undefined ? `分类=${categoryId}（含子分类）` : null,
          typeId !== undefined ? `类型=${typeId}` : null,
          publishStatus !== undefined ? `上下架=${publishLabel(publishStatus)}` : null,
        ].filter(Boolean);
        const head = `商品搜索：${pageHeader('', paged)}｜条件：${cond.length ? cond.join('，') : '无（全部商品）'}`;

        if (!paged.records.length) {
          return text(
            `${head}\n没有找到匹配的商品。可以换个关键词，或先调用 list_categories / list_product_types 确认分类与类型 id。` +
              `\n\nJSON:\n${JSON.stringify(paged, null, 2)}`
          );
        }

        const body = paged.records
          .map(
            (it, i) =>
              `  ${i + 1}. id=${it.id} ${it.name} 分类=${it.categoryId ?? '-'} 类型=${it.typeId ?? '-'} 状态=${publishLabel(it.publishStatus)}`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ② 商品详情
  server.registerTool(
    'get_product_detail',
    {
      title: '查看商品详情',
      description:
        '查单个商品的完整信息：基本信息、相册、以及它的 SKU（规格/价格/库存）。用户问"这个商品具体什么情况""XX 有哪些规格多少钱"时用。',
      inputSchema: {
        productId: z.number().int().positive().describe('商品 id（SPU id）'),
      },
    },
    async ({ productId }) => {
      try {
        const detail = await productDetail(productId);
        const product = detail?.product ?? {};
        const gallery = Array.isArray(detail?.gallery) ? detail.gallery : [];
        // 规格列表 = 该商品的 SKU 列表（后端 SKU 列表自带 specText）
        const skus = await listSkus({ productId });
        const skuRows = Array.isArray(skus) ? skus : [];

        const galleryText = gallery.length
          ? gallery.map((g, i) => `    ${i + 1}. ${g.url}`).join('\n')
          : '    （无相册图）';
        const skuText = skuRows.length
          ? skuRows.map((k, i) => `    ${i + 1}. skuId=${k.id} 规格=${k.specText || '-'} 价格=${money(k.price)} 库存=${money(k.stock)}`).join('\n')
          : '    （该商品还没有 SKU，可能未配置规格）';

        return text(
          `商品详情 id=${productId}：${product.name ?? '-'}\n` +
            `  分类=${product.categoryId ?? '-'} 类型=${product.typeId ?? '-'} 状态=${publishLabel(product.publishStatus)}\n` +
            `  描述：${product.description ?? '-'}\n` +
            `  主图：${product.pic ?? '-'}\n` +
            `  相册 ${gallery.length} 张：\n${galleryText}\n` +
            `  SKU ${skuRows.length} 个：\n${skuText}\n\n` +
            `JSON:\n${JSON.stringify({ product, gallery, skus: skuRows }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ③ 分类树
  server.registerTool(
    'list_categories',
    {
      title: '查看商品分类树',
      description:
        '列出全部商品分类（树形，含每个分类 id）。用户问"有哪些分类""这个商品属于哪个分类"、或需要拿 categoryId 去搜商品时用。配置类数据，量小，全量返回不分页。',
      inputSchema: {},
    },
    async () => {
      try {
        const rows = await listCategories();
        const flat = Array.isArray(rows) ? rows : [];
        if (!flat.length) return text('当前没有任何商品分类。\n\nJSON:\n[]');
        const tree = buildCategoryTree(flat);
        const tops = flat.filter((c) => (c.parentId ?? 0) === 0).length;
        return text(
          `商品分类共 ${flat.length} 个（顶级 ${tops} 个）：\n${categoryToText(tree).join('\n')}\n\n` +
            `JSON:\n${JSON.stringify({ total: flat.length, tree }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ④ 商品类型（含属性）
  server.registerTool(
    'list_product_types',
    {
      title: '查看商品类型及属性',
      description:
        '列出全部商品类型，并带出每个类型下有哪些属性（属性决定该类型商品能配哪些规格）。用户问"有哪些商品类型""手机类型有哪些属性""颜色/内存这种属性是哪来的"时用。配置类数据，全量返回不分页。',
      inputSchema: {},
    },
    async () => {
      try {
        const types = await listProductTypes();
        const rows = Array.isArray(types) ? types : [];
        if (!rows.length) return text('当前没有任何商品类型。\n\nJSON:\n[]');
        // 后端 product-types 不聚合属性 → 逐个类型补一次 /admin/attributes?typeId=
        const records = [];
        for (const t of rows) {
          const attrs = await listAttributes({ typeId: t.id });
          records.push({ ...t, attributes: Array.isArray(attrs) ? attrs : [] });
        }
        const body = records
          .map((t, i) => {
            const attrs = t.attributes.length
              ? t.attributes.map((a) => `${a.name}（id=${a.id}${a.isFilter === 1 ? '，可筛选' : ''}）`).join('、')
              : '（无属性）';
            return `  ${i + 1}. id=${t.id} ${t.name} 属性 ${t.attributes.length} 个：${attrs}`;
          })
          .join('\n');
        return text(
          `商品类型共 ${records.length} 个：\n${body}\n\nJSON:\n${JSON.stringify({ total: records.length, records }, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ⑤ 某类型下的属性
  server.registerTool(
    'list_attributes',
    {
      title: '查看属性列表',
      description:
        '列出商品属性（可按商品类型过滤），返回 id/名称/是否可筛选。用户问"手机类型有哪些属性""这个属性 id 是多少"时用；建 SKU 规格前要先拿到 attributeId。',
      inputSchema: {
        typeId: z.number().int().positive().optional().describe('商品类型 id；不传=返回全部类型的属性（可能较多）'),
        ...pageSchema,
      },
    },
    async ({ typeId, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const rows = await listAttributes({ typeId });
        const paged = paginate(Array.isArray(rows) ? rows : [], { page: p, size: s });
        const scope = typeId !== undefined ? `类型 ${typeId} 下` : '全部类型';
        const head = `属性列表（${scope}）：${pageHeader('', paged)}`;
        if (!paged.records.length) {
          return text(`${head}\n没有找到属性（${scope}还没有配置属性）。\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
        }
        const body = paged.records
          .map((a, i) => `  ${i + 1}. id=${a.id} ${a.name} 类型=${a.typeId ?? '-'} 可筛选=${a.isFilter === 1 ? '是' : '否'}`)
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  // ⑥ 某商品的 SKU 列表
  server.registerTool(
    'list_skus',
    {
      title: '查看商品 SKU 列表',
      description:
        '列出某个商品下的全部 SKU（规格组合、价格、库存）。用户问"这个商品有哪些规格""哪个规格多少钱/还有多少库存"时用。',
      inputSchema: {
        productId: z.number().int().positive().describe('商品 id（SPU id）'),
        ...pageSchema,
      },
    },
    async ({ productId, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const rows = await listSkus({ productId });
        const paged = paginate(Array.isArray(rows) ? rows : [], { page: p, size: s });
        const head = `商品 ${productId} 的 SKU：${pageHeader('', paged)}`;
        if (!paged.records.length) {
          return text(
            `商品 ${productId} 下没有 SKU（可能还没配置规格，或商品 id 不存在）。\n\nJSON:\n${JSON.stringify(paged, null, 2)}`
          );
        }
        const body = paged.records
          .map(
            (k, i) =>
              `  ${i + 1}. skuId=${k.id} 规格=${k.specText || '-'} 价格=${money(k.price)} 库存=${money(k.stock)}${k.pic ? ` 图=${k.pic}` : ''}`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(paged, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );
}
