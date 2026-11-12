// 商品域（catalog）**写**工具：2 个，全部走 version 乐观锁 → 受 MCP_ENABLE_WRITE 控制。
//
// ── 这一批为什么要有它们 ───────────────────────────────────────────────
// 并发控制口径（用户定）：**version 乐观锁** —— 改数据必须带上次读到的 version。
// 于是「写工具会不会正确使用 version」就成了整条链路的验收前提：
//   · 工具必须**强制**参数带 version（zod 必填，缺了连后端都调不到）；
//   · 后端 409（version 过期）必须原样透给模型，且要让模型明白「该重新读，不是该重试」；
//   · 成功后后端**不回新版本号**（data:null），所以由工具层回读一次，把新 version 交给模型。
//
// 字段口径（已读后端 ProductController.update / SkuController.update 确认，别照"标准商品"想当然）：
//   POST/PUT /admin/products/{id}  body = { product, gallery }
//     · Product 实体只有：name / description / categoryId / typeId / publishStatus / pic / version
//     · **没有 subtitle，也没有 price**（价格在 SKU 上）；gallery 是「整组替换相册」，
//       误传会把相册删光 → 本工具刻意不支持相册，只改 SPU 标量字段
//   PUT /admin/skus/{id}           body = { sku, specs }
//     · Sku 实体只有：productId / price / stock / pic / version；specs 是整组替换 → 本工具只改 stock/price
//   ⚠️ 两个接口都用 MyBatis-Plus updateById：**只更新非 null 字段**，所以「只传要改的字段」是安全的，
//      但 version 必须每级都带上——缺了后端直接 code=400（fail-closed）。

import { z } from 'zod';
import { updateProduct, productDetail, updateSku, skuDetail } from '../mall.mjs';
import { text, money, fail, failWrite } from './_shared.mjs';

const publishLabel = (v) => (v === 1 ? '上架' : v === 0 ? '下架' : `未知(${v ?? '-'})`);

/** 版本号字段的 zod 片段：两个写工具口径一致（必填，且不允许负数） */
const versionSchema = z
  .number()
  .int()
  .min(0)
  .describe('版本号，**必须来自最近一次读取**（list_products / get_product_detail / list_skus 返回的 version）；不要凭记忆猜');

/**
 * 注册商品域写工具（update_product / update_sku_stock）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx
 */
export function registerCatalogWriteTools(server, { writeEnabled } = {}) {
  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → 商品域写工具（update_product / update_sku_stock）不注册');
    return;
  }

  // ① 写：改商品（SPU）
  server.registerTool(
    'update_product',
    {
      title: '修改商品（写）',
      description:
        '修改商品的名称/描述/分类/类型/上下架状态/主图。⚠️ 会真改数据：先把改动念给用户确认再调。' +
        '⚠️ 必须传 version（从 list_products 或 get_product_detail 拿到的版本号）。' +
        '若期间有人改过这个商品，后端会拒绝并返回「数据已被他人修改」，此时请重新读取最新数据再试，不要用旧 version 重试。' +
        '注意：商品主体没有「副标题」和「价格」字段 —— 价格在 SKU 上，改价格请用 update_sku_stock；' +
        '相册（gallery 是整组替换，误传会删图）本工具不支持，需要改相册请在后台商品编辑页操作。',
      inputSchema: {
        productId: z.number().int().positive().describe('商品 id（SPU id）'),
        name: z.string().min(1).optional().describe('商品名称；不改就别传'),
        description: z.string().optional().describe('商品描述（详情页文案）；不改就别传'),
        categoryId: z.number().int().positive().optional().describe('分类 id；不改就别传（先用 list_categories 查）'),
        typeId: z.number().int().positive().optional().describe('商品类型 id；不改就别传（先用 list_product_types 查）'),
        publishStatus: z.number().int().min(0).max(1).optional().describe('上下架：1 上架 / 0 下架'),
        pic: z.string().min(1).optional().describe('主图 URL（MinIO 路径，如 /qiqi-mall/products/2026/11/xxx.webp）'),
        version: versionSchema,
      },
    },
    async ({ productId, name, description, categoryId, typeId, publishStatus, pic, version }) => {
      // 只把「调用方真的给了」的字段放进 patch —— updateById 跳 null，所以没给的一定不要塞 null/undefined
      const patch = { version };
      const changed = [];
      const setField = (key, val) => {
        if (val === undefined) return;
        patch[key] = val;
        changed.push(key);
      };
      setField('name', name);
      setField('description', description);
      setField('categoryId', categoryId);
      setField('typeId', typeId);
      setField('publishStatus', publishStatus);
      setField('pic', pic);

      if (!changed.length) {
        return fail(
          new Error('没有要改的字段：至少要给 name / description / categoryId / typeId / publishStatus / pic 其中一个（只传 productId + version 不会产生任何修改）')
        );
      }

      try {
        await updateProduct(productId, patch);
      } catch (e) {
        // 400（缺版本）/ 409（版本冲突）等：把 bizCode + 「重新读」的下一步原样交给模型
        return failWrite(e, {
          action: '商品修改',
          label: `productId=${productId}${name !== undefined ? ` 目标名称=「${name}」` : ''}`,
          oldVersion: version,
          rereadTool: 'get_product_detail',
        });
      }

      // 成功：后端只回 {code:0,data:null}，新 version 必须回读（这也是「乐观锁确认生效」的证据）
      let after = null;
      try {
        const detail = await productDetail(productId);
        after = detail?.product ?? null;
      } catch (e) {
        return text(
          `✅ 商品 ${productId} 已提交修改（${changed.join('、')}），但回读最新版本失败：${e?.message || e}\n` +
            '请再调用一次 get_product_detail 核对最新 version 与字段值。'
        );
      }

      const newVersion = after?.version ?? null;
      const bump = typeof newVersion === 'number' && typeof version === 'number' ? newVersion - version : null;
      const valueLines = changed
        .map((k) => {
          const v = after?.[k];
          if (k === 'publishStatus') return `  ${k} = ${v}（${publishLabel(v)}）`;
          return `  ${k} = ${v ?? '-'}`;
        })
        .join('\n');

      return text(
        `✅ 商品已更新：id=${productId}「${after?.name ?? name ?? '-'}」\n` +
          `  改动字段：${changed.join('、')}\n` +
          `  version：${version} → ${newVersion ?? '(回读失败)'}${bump === 1 ? '（+1，乐观锁生效）' : ''}\n` +
          `  当前值：\n${valueLines}\n` +
          `  下次再改这个商品请用 version=${newVersion}（旧 version 会被后端 409 拒掉）。\n\n` +
          `JSON:\n${JSON.stringify({ productId, changed, oldVersion: version, newVersion, product: after }, null, 2)}`
      );
    }
  );

  // ② 写：改 SKU 库存/价格
  server.registerTool(
    'update_sku_stock',
    {
      title: '修改 SKU 库存/价格（写）',
      description:
        '修改某个 SKU 的可售库存或售价。⚠️ 会真改数据：先把改动念给用户确认再调。' +
        '⚠️ 同样必须传 version（从 list_skus 或 get_product_detail 拿到的该 SKU 的版本号）。' +
        '若期间有人改过这个 SKU（比如下单扣了库存），后端会拒绝并返回「数据已被他人修改」，此时请重新读取最新数据再试，不要用旧 version 重试。',
      inputSchema: {
        skuId: z.number().int().positive().describe('SKU id（先用 get_product_detail / list_skus 查）'),
        stock: z.number().int().min(0).optional().describe('修改后的库存件数（0 表示售罄）；不改就别传'),
        price: z.number().positive().optional().describe('修改后的售价（元，必须 > 0）；不改就别传'),
        version: z
          .number()
          .int()
          .min(0)
          .describe('该 SKU 的版本号，**必须来自最近一次读取**（list_skus / get_product_detail 返回的 version）'),
      },
    },
    async ({ skuId, stock, price, version }) => {
      const patch = { version };
      const changed = [];
      const setField = (key, val) => {
        if (val === undefined) return;
        patch[key] = val;
        changed.push(key);
      };
      setField('stock', stock);
      setField('price', price);

      if (!changed.length) {
        return fail(new Error('没有要改的字段：stock / price 至少给一个（只传 skuId + version 不会产生任何修改）'));
      }

      try {
        await updateSku(skuId, patch);
      } catch (e) {
        return failWrite(e, {
          action: 'SKU 修改',
          label: `skuId=${skuId}`,
          oldVersion: version,
          rereadTool: 'list_skus（按该 SKU 所属商品的 productId 查）',
        });
      }

      let after = null;
      try {
        const detail = await skuDetail(skuId);
        after = detail?.sku ?? null;
      } catch (e) {
        return text(
          `✅ SKU ${skuId} 已提交修改（${changed.join('、')}），但回读最新版本失败：${e?.message || e}\n` +
            '请再调用一次 list_skus 核对最新 version 与库存/价格。'
        );
      }

      const newVersion = after?.version ?? null;
      const bump = typeof newVersion === 'number' && typeof version === 'number' ? newVersion - version : null;

      return text(
        `✅ SKU 已更新：skuId=${skuId}（所属商品 productId=${after?.productId ?? '-'}）\n` +
          `  改动字段：${changed.join('、')}　价格=${money(after?.price)} 库存=${money(after?.stock)}\n` +
          `  version：${version} → ${newVersion ?? '(回读失败)'}${bump === 1 ? '（+1，乐观锁生效）' : ''}\n` +
          `  下次再改这个 SKU 请用 version=${newVersion}（旧 version 会被后端 409 拒掉）。\n\n` +
          `JSON:\n${JSON.stringify({ skuId, changed, oldVersion: version, newVersion, sku: after }, null, 2)}`
      );
    }
  );
}
