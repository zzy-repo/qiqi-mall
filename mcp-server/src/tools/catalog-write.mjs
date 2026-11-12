// 商品域（catalog）**写**工具：6 个 → 受 MCP_ENABLE_WRITE 控制。
//   · 改（吃 version 乐观锁）：update_product / update_sku_stock
//   · 增删（第 5 批覆盖补齐，对应后台「新增商品/新增规格/删除商品/删除规格」四个按钮）：
//     create_product → POST   /admin/products     （新建即 version=0，后端置）
//     delete_product → DELETE /admin/products/{id}?dryRun=true
//     create_sku     → POST   /admin/skus         （新建即 version=0，后端置；productId 必填）
//     delete_sku     → DELETE /admin/skus/{id}?dryRun=true
//     增删都不吃 version（新建没有旧版本；删除整行幂等，靠「引用守卫」兜底）。
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
import { updateProduct, productDetail, updateSku, skuDetail, createProduct, deleteProduct, createSku, deleteSku } from '../mall.mjs';
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
    console.error(
      '[mcp] MCP_ENABLE_WRITE!=1 → 商品域写工具（update_product / update_sku_stock / create_product / delete_product / create_sku / delete_sku）不注册'
    );
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

  // ③ 写：新增商品（SPU）
  server.registerTool(
    'create_product',
    {
      title: '新增商品（写）',
      description:
        '在后台新建一个商品（SPU）。用户在后台商品页点「新增商品」时用。⚠️ 会真写数据。' +
        '字段口径与 update_product 一致：Product 实体只有 name / description / categoryId / typeId / publishStatus / pic，' +
        '**没有副标题、没有价格**（价格在 SKU 上，建完商品请用 create_sku 建规格）。' +
        '新建后 version=0（后端置），返回里会带回 id 与 version。' +
        '⚠️ 新建商品**不会**自动生成 SKU —— 没有 SKU 的商品买家看不到、也下不了单。',
      inputSchema: {
        name: z.string().min(1).describe('商品名称'),
        description: z.string().optional().describe('商品描述（详情页文案）'),
        categoryId: z.number().int().positive().optional().describe('分类 id（先用 list_categories 查）'),
        typeId: z.number().int().positive().optional().describe('商品类型 id（先用 list_product_types 查；绑了类型才能配规格属性）'),
        publishStatus: z.number().int().min(0).max(1).optional().describe('上下架：1 上架 / 0 下架（建议先 0，配好图与 SKU 再上架）'),
        pic: z.string().min(1).optional().describe('主图 URL（MinIO 路径）。⚠️ MCP 传不了图片二进制，见 README「不能靠补工具解决的」'),
        gallery: z
          .array(z.object({ url: z.string().min(1).describe('图片 URL'), sort: z.number().int().min(0).optional().describe('排序，小的在前') }))
          .optional()
          .describe('相册（可选）：只接受**已有 URL**；上传图片本身 MCP 做不到（后端 /admin/upload/image 只吃 multipart）'),
      },
    },
    async ({ name, description, categoryId, typeId, publishStatus, pic, gallery }) => {
      const product = { name };
      for (const [k, v] of Object.entries({ description, categoryId, typeId, publishStatus, pic })) {
        if (v !== undefined) product[k] = v;
      }
      try {
        const created = await createProduct({ product, gallery: gallery?.map((g, i) => ({ url: g.url, sort: g.sort ?? i })) });
        return text(
          `✅ 已新增商品：id=${created?.id} 名称=「${created?.name}」 状态=${publishLabel(created?.publishStatus)} ` +
            `version=${created?.version ?? 0}\n` +
            `  分类=${created?.categoryId ?? '-'} 类型=${created?.typeId ?? '-'} 相册=${gallery?.length ?? 0} 张\n` +
            `  下一步：用 create_sku（productId=${created?.id}）给它建规格，否则买家看不到也买不了。\n\n` +
            `JSON:\n${JSON.stringify(created, null, 2)}`
        );
      } catch (e) {
        return failWrite(e, { action: '新增商品', label: `名称=「${name}」`, rereadTool: 'search_products' });
      }
    }
  );

  // ④ 写：删除商品（级联 SKU/相册，被引用时拒绝）
  server.registerTool(
    'delete_product',
    {
      title: '删除商品（写，不可恢复）',
      description:
        '删除商品，并级联删掉它的 SKU 与相册。⚠️ 不可恢复。' +
        '先 dryRun=true 预演：后端会回 willDo（含会连带删掉几个 SKU、当前被多少条订单明细/购物车引用），' +
        '把这段话念给用户、拿到明确同意再真执行。' +
        '守卫：被订单明细或购物车引用时**拒绝删除**——此时正确做法是改用 update_product 下架（publishStatus=0），而不是硬删。',
      inputSchema: {
        productId: z.number().int().positive().describe('商品 id（SPU id）'),
        dryRun: z.boolean().optional().describe('true=只预演（回 willDo，不落库）'),
      },
    },
    async ({ productId, dryRun }) => {
      try {
        const resp = await deleteProduct(productId, { dryRun });
        if (dryRun === true) {
          return text(`预演（未落库）— 删除商品 ${productId}：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`);
        }
        return text(
          `✅ 商品 ${productId} 已删除（下辖 SKU 与相册一并删除）。\n\nJSON:\n${JSON.stringify({ productId, dryRun: false, deleted: true, response: resp }, null, 2)}`
        );
      } catch (e) {
        return failWrite(e, { action: '删除商品', label: `productId=${productId}`, rereadTool: 'search_products' });
      }
    }
  );

  // ⑤ 写：新增 SKU（规格）
  server.registerTool(
    'create_sku',
    {
      title: '新增 SKU（写）',
      description:
        '给某个商品新增一个 SKU（具体规格组合 + 价格 + 库存）。用户在后台商品页点「新增规格」时用。⚠️ 会真写数据。' +
        'sku 实体只有 productId / price / stock / pic，**规格值走 specs**（attributeId + value，可多条）。' +
        '规格名（attributeId）先用 list_sku_attributes(productId=…) 查（它返回该商品所属类型下的属性）。' +
        '新建后 version=0（后端置）。同一个商品下不要建出两个规格值完全相同的 SKU。',
      inputSchema: {
        productId: z.number().int().positive().describe('所属商品 id（必填，后端会拦空值）'),
        price: z.number().positive().describe('售价（元，必须 > 0）'),
        stock: z.number().int().min(0).describe('库存件数（0 表示售罄）'),
        pic: z.string().min(1).optional().describe('该规格的图片 URL（可选）'),
        specs: z
          .array(
            z.object({
              attributeId: z.number().int().positive().describe('属性 id（规格名，先用 list_sku_attributes 查）'),
              value: z.string().min(1).describe('规格值，如「深空黑」「256G」'),
            })
          )
          .optional()
          .describe('规格值（可多条）：如 [{attributeId:1,value:"深空黑"},{attributeId:2,value:"256G"}]'),
      },
    },
    async ({ productId, price, stock, pic, specs }) => {
      const sku = { productId, price, stock };
      if (pic !== undefined) sku.pic = pic;
      try {
        const created = await createSku({ sku, specs });
        const specText = (specs || []).map((s) => `${s.value}`).join(' / ') || '（无规格值）';
        return text(
          `✅ 已新增 SKU：skuId=${created?.id}（商品 productId=${created?.productId}）\n` +
            `  价格=${money(created?.price)} 库存=${money(created?.stock)} version=${created?.version ?? 0} 规格=${specText}\n` +
            `  提醒：库存是「总池」口径，分仓库存请到后台库存页分配。\n\n` +
            `JSON:\n${JSON.stringify(created, null, 2)}`
        );
      } catch (e) {
        return failWrite(e, { action: '新增 SKU', label: `productId=${productId}`, rereadTool: `list_skus(productId=${productId})` });
      }
    }
  );

  // ⑥ 写：删除 SKU（被订单明细/购物车引用时拒绝）
  server.registerTool(
    'delete_sku',
    {
      title: '删除 SKU（写，不可恢复）',
      description:
        '删除一个 SKU（规格）。⚠️ 不可恢复。' +
        '先 dryRun=true 预演：后端回 willDo（含售价、库存、当前被多少条订单明细/购物车引用），念给用户确认后再真删。' +
        '守卫：被订单明细或购物车引用时拒绝删除——此时正确做法是用 update_sku_stock 把库存改成 0（售罄），而不是硬删。',
      inputSchema: {
        skuId: z.number().int().positive().describe('SKU id（先用 list_skus 查）'),
        dryRun: z.boolean().optional().describe('true=只预演（回 willDo，不落库）'),
      },
    },
    async ({ skuId, dryRun }) => {
      try {
        const resp = await deleteSku(skuId, { dryRun });
        if (dryRun === true) {
          return text(`预演（未落库）— 删除 SKU ${skuId}：\n  ${resp?.willDo ?? '(后端没有返回 willDo)'}\n\nJSON:\n${JSON.stringify(resp, null, 2)}`);
        }
        return text(`✅ SKU ${skuId} 已删除。\n\nJSON:\n${JSON.stringify({ skuId, dryRun: false, deleted: true, response: resp }, null, 2)}`);
      } catch (e) {
        return failWrite(e, { action: '删除 SKU', label: `skuId=${skuId}`, rereadTool: 'list_skus' });
      }
    }
  );
}
