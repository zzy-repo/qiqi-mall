// 概览 + 上传（misc 域，第 4 批）
//   dashboard_overview()   GET /admin/dashboard/overview —— 只读，看板全部数字一次取回
//   upload_image()         **本批刻意没做**（原因见文件末尾注释，以及交付报告）
//
// ── dashboard 口径（已读 AdminDashboardController 确认，别按「标准指标」想当然）──
//   今日成交额 / 今日订单：按 order.payment_time 落在今天，且状态属于「已付款且未退款」
//     （1已付款 2已发货 3已完成 7待审核 8待发货 9部分发货）；0待付款/4已关闭/5退款中/6已退款 都不算
//   趋势：最近 7 天（含今天），同样按 payment_time 归日
//   状态分布：**全部订单**（不限时间）按 status 分组 —— 各组之和 = 订单总数
//   Top5 商品：从 order_item 聚合（按商品名，取前 5，按销量降序）
//   库存告警：sku.stock <= 10 的规格，最多 5 条
//   退货待处理：return_apply.status=0 的条数
//   ⚠️ scope：ApiKeyAuthFilter 里 /admin/dashboard 的读/写 scope **都是 dashboard:read**（只有读接口）

import { dashboardOverview } from '../mall.mjs';
import { text, fail } from './_shared.mjs';

/** 订单状态码 → 中文（与 order.mjs 同一张表；这里只需短名，重复定义会漂移，所以直接对齐 0~9） */
const ORDER_STATUS_TEXT = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货'];
const orderStatusLabel = (v) => ORDER_STATUS_TEXT[v] ?? `未知状态(${v ?? '-'})`;

/**
 * 注册概览域工具（1 个只读工具）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx 本域全只读，不消费 writeEnabled
 */
export function registerMiscTools(server, _ctx = {}) {
  server.registerTool(
    'dashboard_overview',
    {
      title: '后台数据概览（看板）',
      description:
        '一次取回后台首页看板的全部数字：商品总数/在架数、订单总数、待审核数、待发货数、' +
        '今日成交额与今日订单数、待处理退货数、最近 7 天趋势、订单状态分布、Top5 热销商品、库存告警（stock≤10）。' +
        '用户问"今天卖了多少""有多少单待审核/待发货""哪些商品快没库存了""整体情况怎么样"时用。只读，可反复调用。' +
        '口径注意：今日成交额只算「已付款且未退款」的订单（按支付时间归日），退款中/已退款/未付款/已关闭都不计入；' +
        '状态分布是**全部订单**按状态分组。',
      inputSchema: {},
    },
    async () => {
      try {
        const d = await dashboardOverview();
        const stockAlerts = Array.isArray(d?.stockAlerts) ? d.stockAlerts : [];
        const trend = Array.isArray(d?.trend) ? d.trend : [];
        const statusDistribution = Array.isArray(d?.statusDistribution) ? d.statusDistribution : [];
        const topProducts = Array.isArray(d?.topProducts) ? d.topProducts : [];

        const statusText = statusDistribution.length
          ? statusDistribution.map((s) => `${orderStatusLabel(s.status)} ${s.count}`).join('｜')
          : '（还没有订单）';
        const trendText = trend.length
          ? trend.map((t) => `    ${t.date}　${t.orderCount} 单　¥${t.amount}`).join('\n')
          : '    （暂无趋势数据）';
        const topText = topProducts.length
          ? topProducts.map((p, i) => `    ${i + 1}. ${p.productName} 销量=${p.qty} 金额=¥${p.amount}`).join('\n')
          : '    （还没有销量数据）';
        const alertText = stockAlerts.length
          ? stockAlerts.map((s, i) => `    ${i + 1}. skuId=${s.skuId} ${s.productName ?? '-'} ${s.specText ?? ''} 库存=${s.stock}`).join('\n')
          : '    （没有库存告警：所有 SKU 库存都 > 10）';

        return text(
          `后台数据概览：\n` +
            `  商品：共 ${d?.productTotal ?? 0} 个，在架 ${d?.productOnline ?? 0} 个\n` +
            `  订单：共 ${d?.orderTotal ?? 0} 笔｜待审核 ${d?.orderPendingAudit ?? 0}｜待发货 ${d?.orderPendingShip ?? 0}\n` +
            `  今日：${d?.todayOrderCount ?? 0} 单，成交额 ¥${d?.todayAmount ?? '0.00'}（只算已付款且未退款）\n` +
            `  退货：待处理 ${d?.returnPending ?? 0} 条\n` +
            `  订单状态分布：${statusText}\n` +
            `  最近 7 天趋势：\n${trendText}\n` +
            `  Top5 热销商品：\n${topText}\n` +
            `  库存告警（≤10 件）：\n${alertText}\n\n` +
            `JSON:\n${JSON.stringify(d ?? null, null, 2)}`
        );
      } catch (e) {
        return fail(e);
      }
    }
  );
  // ⚠️ upload_image 为什么没做（不是漏了）：
  //   后端只有 POST /admin/upload/image，签名是 `@RequestParam("file") MultipartFile file` ——
  //   **只接受 multipart/form-data 的文件流**，没有「传路径 / 传 URL / 传 base64」的 JSON 变体。
  //   MCP 工具的入参是 JSON（模型产出的是文本），因此要么：
  //     ① 让模型在参数里塞 5MB 以内的 base64 → 6~7MB 的文本进上下文，模型根本产不出来，属半成品；
  //     ② 让参数是一个「服务器本地文件路径」→ MCP server 就成了「任意本地文件读取 + 外发到对象存储」的通道，
  //        安全面比它带来的便利大得多。
  //   两条都不是「成熟模式上加一个文件」，所以本批**刻意不提供 upload_image**，
  //   图片上传仍由后台人工在商品编辑页完成（POST /admin/upload/image，scope catalog:write）。
}

export default registerMiscTools;
