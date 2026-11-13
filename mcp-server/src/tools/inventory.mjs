// 库存对账域（第 6 批 · 只读巡检域）：2 个工具
//
//   get_inventory_reconcile → GET /admin/inventory/reconcile?skuId=            （scope: stock:read）
//   list_inventory_drifts   → GET /admin/inventory/reconcile/drift?page=&size=&threshold=   （scope: stock:read）
//
// ── 什么时候该用我 ──────────────────────────────────────────────────────────
//   「这个 SKU 到底还有多少货」「前台显示 1000 件是不是缓存没刷」「全库谁在缺货/多记」
//   「刚才那笔扣减到底扣在哪个桶、补回来没有」。库存**对账**，不是改库存 —— 这一域一个写工具都没有。
//
// ── 三个数不是一回事（口径红线，接口只如实返回，绝不「顺手修一下」）──────────
//   bucketSum = SUM(sku_bucket.stock)  —— **权威值**，库存唯一事实源；
//   skuStock  = sku.stock              —— **展示缓存**，由 StockCacheRefresher 周期回写；
//   drift     = bucketSum − skuStock   —— 这一对才「应当相等」；正=bucket 多（缓存少记），负=bucket 少（缓存多记）；
//   ledgerNet = SUM(stock_ledger.delta)—— **全生命周期进出和，不是余额**！它把该 SKU 历史上
//               所有扣/补累加（下单扣的、退货退回的、审核回补的都算），所以**不与 bucketSum 要求相等**。
//   把 ledgerNet 当余额去「对齐」库存是最常见的误读 —— 工具层的文案必须拦住它。
//
// ── 后端真实形态（已读 stock/controller/AdminInventoryController.java 确认）──
//   ① GET /reconcile?skuId=  —— skuId **必填**（缺了 HTTP 400）；SKU 不存在时后端 code=1 报错
//      （不是返回空对象），所以工具层不需要判空，fail(e) 会把中文原因透出去；
//      返回 {skuId,skuCode,skuName,bucketSum,skuStock,drift,lastRefreshAt,bucketCount,
//            expectedBucketCount,bucketCountMatch,ledgerNet,ledgerRows[最近 20 条]}；
//      ⚠️ lastRefreshAt 是**最接近的可观测时间** MAX(sku_bucket.updated_at)，不等于「缓存回写时刻」（sku 表没有时间戳列）；
//      ⚠️ bucketCount != expectedBucketCount 说明这个 SKU 还没回填够桶（分桶模式下会**假性缺货**），要照实说；
//   ② GET /reconcile/drift —— **真分页**（MP Page: records/total/size/current/pages）；
//      threshold 默认 100（100 件以内的滞后不值得报警），传 0 = 任何非零偏差都列出来（排查缓存回写是否在工作时用它）。
//
// ── 权限（别在这里加写工具）────────────────────────────────────────────────
//   stock:write **不在 ALL_SCOPES 里**（任何 key 都拿不到）：库存是「扣减裁决」，
//   AI 只能读偏差、不能写一行库存。

import { z } from 'zod';
import { inventoryReconcile, inventoryDrifts } from '../mall.mjs';
import { text, fail, clampPage, pageHeader, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from './_shared.mjs';

/** 桶数一致性的一句话（bucketCount vs 配置桶数） */
function bucketText(d) {
  const count = d.bucketCount ?? 0;
  const expected = d.expectedBucketCount;
  if (expected === undefined || expected === null) return `桶数=${count}`;
  if (d.bucketCountMatch === true) return `桶数=${count}/配置 ${expected}（一致）`;
  return `桶数=${count}/配置 ${expected}（**不一致：还没回填够桶，分桶模式下该 SKU 会假性缺货**）`;
}

/** 偏差的一句话（人话方向：谁多谁少） */
function driftText(drift) {
  const n = Number(drift ?? 0);
  if (n === 0) return '对齐（桶和 = 缓存）';
  return n > 0 ? `偏差 +${n}（桶比缓存多 ${n} 件，缓存滞后少记）` : `偏差 ${n}（桶比缓存少 ${-n} 件，缓存多记）`;
}

/**
 * 注册库存对账域工具（纯只读，不受 MCP_ENABLE_WRITE 影响）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 */
export function registerInventoryTools(server) {
  server.registerTool(
    'get_inventory_reconcile',
    {
      title: '单 SKU 库存对账（三个数 + 最近流水）',
      description:
        '查一个 SKU 的库存对账：桶权威值 bucketSum、展示缓存 skuStock、偏差 drift、台账净额 ledgerNet、' +
        '桶数一致性、以及最近 20 条库存流水（谁扣的、扣在哪个桶、有没有补回来）。' +
        '用户问"这个 SKU 到底还有多少货""前台显示的数量对不对/是不是缓存没刷""刚才那笔扣减扣到哪去了""库存补回来没有"时用。' +
        '⚠️ 三个数不是一回事，别混着讲：bucketSum（权威）与 skuStock（缓存）**才应当相等**，drift 就是它们之差；' +
        'ledgerNet 是**全生命周期进出和、不是余额**，它与 bucketSum 不要求相等 —— 拿它当库存余额去「对齐」是最常见的误读。' +
        '只读：对账接口不修库存（改了就等于把证据改成结论）。',
      inputSchema: {
        skuId: z
          .number()
          .int()
          .positive()
          .describe('SKU id（不是商品 id；商品下的规格 id 可用 list_skus 按 productId 查）'),
      },
    },
    async ({ skuId }) => {
      try {
        const d = await inventoryReconcile(skuId);
        const rows = Array.isArray(d?.ledgerRows) ? d.ledgerRows : [];

        const head =
          `SKU 对账：${d?.skuName ?? '-'}（skuId=${d?.skuId ?? skuId}，编码 ${d?.skuCode ?? '-'}）\n` +
          `  桶权威值 bucketSum=${d?.bucketSum ?? 0}｜展示缓存 skuStock=${d?.skuStock ?? 0}\n` +
          `  ${driftText(d?.drift)}\n` +
          `  ${bucketText(d ?? {})}\n` +
          `  台账净额 ledgerNet=${d?.ledgerNet ?? 0}（**全生命周期进出和，不是余额**，不与桶和对平）\n` +
          `  桶最近变动时间=${d?.lastRefreshAt ?? '无记录'}（sku 表没有回写时间戳，这是最接近的可观测时间）`;

        const ledgerText = rows.length
          ? rows
              .map(
                (r, i) =>
                  `    ${i + 1}. ${r.createdAt ?? '-'} ${r.reason ?? '-'} delta=${r.delta ?? '-'} 桶=${r.bucketNo ?? '-'}` +
                  `${r.refType ? ` 关联=${r.refType}#${r.refId ?? '-'}` : ''}${r.idemKey ? ` 幂等键=${r.idemKey}` : ''}`
              )
              .join('\n')
          : '    （该 SKU 还没有库存流水）';

        return text(`${head}\n  最近 ${rows.length} 条库存流水：\n${ledgerText}\n\nJSON:\n${JSON.stringify(d ?? null, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );

  server.registerTool(
    'list_inventory_drifts',
    {
      title: '全库库存偏差列表（谁偏了多少）',
      description:
        '列出「桶和 vs 展示缓存」偏差超过阈值的 SKU，偏差大的在前，分页返回。' +
        '用户问"有没有库存对不上""哪些商品数量不对""缓存回写是不是没在工作""全库盘点一下"时用。' +
        'threshold 默认 100（100 件以内的滞后不值得报警）；**传 0 = 任何非零偏差都列出来**（排查缓存回写是否在工作就用它）。' +
        '每行只有 skuId/skuCode/bucketSum/skuStock/drift 五个字段（列表不做聚合查询），' +
        '要看某个 SKU 的名称与流水，用 get_inventory_reconcile 传它的 skuId。',
      inputSchema: {
        threshold: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe('偏差阈值（|桶和 − 缓存| > threshold 才列出），默认 100；传 0 = 任何非零偏差都列'),
        page: z.number().int().min(1).optional().describe('页码，从 1 开始，默认 1'),
        size: z
          .number()
          .int()
          .min(1)
          .max(MAX_PAGE_SIZE)
          .optional()
          .describe(`每页条数，默认 ${DEFAULT_PAGE_SIZE}，最大 ${MAX_PAGE_SIZE}（超过会被夹到 ${MAX_PAGE_SIZE}）`),
      },
    },
    async ({ threshold, page, size }) => {
      try {
        const { page: p, size: s } = clampPage({ page, size });
        const th = threshold ?? 100;
        const data = await inventoryDrifts({ page: p, size: s, threshold: th });
        const records = Array.isArray(data?.records) ? data.records : [];
        const envelope = {
          records,
          total: Number(data?.total ?? records.length),
          page: Number(data?.current ?? p),
          size: Number(data?.size ?? s),
          pages: Number(data?.pages ?? 0),
          threshold: th,
        };
        const head = `库存偏差：${pageHeader('', envelope)}｜阈值=${th}（|桶和 − 缓存| > ${th}）`;

        if (!envelope.records.length) {
          return text(
            `${head}\n没有任何 SKU 的偏差超过 ${th} —— 桶（权威）与展示缓存是一致的。` +
              `\n（想确认「缓存回写到底有没有在工作」，把 threshold 传 0 再查一次：任何非零偏差都会被列出来。）` +
              `\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`
          );
        }

        const body = envelope.records
          .map(
            (r, i) =>
              `  ${i + 1}. skuId=${r.skuId} ${r.skuCode ?? '-'} 桶=${r.bucketSum ?? 0} 缓存=${r.skuStock ?? 0} drift=${r.drift ?? 0}（${driftText(r.drift)}）`
          )
          .join('\n');
        return text(`${head}\n${body}\n\nJSON:\n${JSON.stringify(envelope, null, 2)}`);
      } catch (e) {
        return fail(e);
      }
    }
  );
}

export default registerInventoryTools;
