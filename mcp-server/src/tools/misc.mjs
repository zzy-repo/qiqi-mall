// 概览 + 上传（misc 域，第 4 批；第 5 批补了 upload_image_from_url）
//   dashboard_overview()      GET  /admin/dashboard/overview          —— 只读，看板全部数字一次取回
//   upload_image_from_url()   POST /admin/upload/image-from-url       —— **写**（受 MCP_ENABLE_WRITE 控制），按 URL 拉图存对象存储
//
// ── 为什么图片上传只能做成「传 URL」（不是文件、不是本地路径、不是 base64）──────
//   后端原有入口 POST /admin/upload/image 的签名是 @RequestParam("file") MultipartFile，只吃 multipart 文件流；
//   而 MCP 的入参是**模型自己产出的 JSON 文本**，两条路都是半成品：
//     ① base64：5MB 图 → 6~7MB 文本进上下文，模型产不出来；
//     ② 服务器本地路径：MCP 就变成「任意本地文件读取 + 外发对象存储」的通道。
//   所以后端新加了 POST /admin/upload/image-from-url（body 只有 {"url":"https://..."}），
//   拉取由后端做，并带全套 SSRF 防护（见 MinioStorageService.fetchFromUrl 的 6 条注释）。
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

import { z } from 'zod';
import { dashboardOverview, uploadImageFromUrl } from '../mall.mjs';
import { text, fail } from './_shared.mjs';

/** 订单状态码 → 中文（与 order.mjs 同一张表；这里只需短名，重复定义会漂移，所以直接对齐 0~9） */
const ORDER_STATUS_TEXT = ['待付款', '已付款', '已发货', '已完成', '已关闭', '退款中', '已退款', '待审核', '待发货', '部分发货'];
const orderStatusLabel = (v) => ORDER_STATUS_TEXT[v] ?? `未知状态(${v ?? '-'})`;

/**
 * 注册概览域工具：dashboard_overview（只读）+ upload_image_from_url（写，受开关控制）。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx 写开关：MCP_ENABLE_WRITE=1 才注册 upload_image_from_url
 */
export function registerMiscTools(server, { writeEnabled = false } = {}) {
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
  // ---- 写工具：按 URL 上传图片（受 MCP_ENABLE_WRITE 控制）----
  // 只读模式下**不注册**：连「有一条能把外部内容写进对象存储的通道」都不留。
  if (!writeEnabled) {
    console.error('[mcp] MCP_ENABLE_WRITE!=1 → 写工具 upload_image_from_url 不注册（只读模式不留上传通道）');
    return;
  }

  server.registerTool(
    'upload_image_from_url',
    {
      title: '按 URL 上传图片（写）',
      description:
        '把一个公开可访问的 https 图片抓下来存进对象存储，返回可直接用于商品主图/相册的路径（与后台人工上传同一个 bucket、同一个 products/年/月 目录）。' +
        '用户说"用这个链接的图片当商品图""把这张图传上去"时用；拿到的路径可直接传给 create_product（pic）或 update_product（pic）。' +
        '硬限制（后端强制，不是建议）：**只支持 https**（http / file / ftp 一律拒绝）；**只允许 443 端口**（URL 里写成 :8443、:8080 等其它端口一律拒绝，本接口不用于探测其它端口，URL 不写端口即可）；单图 **≤5MB**；只接受 **jpg / png / webp**（按文件头嗅探，不看服务端声明的 Content-Type）。' +
        '另外**不允许内网 / 回环 / 链路本地 / CGNAT 地址**（127./10./172.16-31./192.168./169.254./100.64-127. 以及 ::1、fc00::/7、fe80::/10），' +
        '**IPv6 转换前缀也整段禁**（NAT64 的 64:ff9b::/96、64:ff9b:1::/48，6to4 的 2002::/16，Teredo 的 2001::/32 —— 它们能把 127.0.0.1 / 169.254.169.254 这类 IPv4 地址嵌进 v6 地址里），' +
        '也**不跟随重定向**（3xx 直接拒），所以它不能用来读本机或内网资源。' +
        '失败会给明确中文原因：域名解析失败 / 端口不是 443 / 返回的是网页不是图片 / 超过 5MB / 指向内网 / 超时 —— 按原因换一个图片地址即可，不要反复重试同一个 URL。',
      inputSchema: {
        url: z
          .string()
          .min(1)
          .describe('图片的 https 完整地址（必须是最终地址，不能是会跳转的短链/相册页；jpg/png/webp，≤5MB），例如 https://cdn.rose7.cn/qiqi-mall/products/2026/11/xxx.webp'),
      },
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,   // 只新增一张新命名的图，不覆盖也不删除任何已有对象
        idempotentHint: false,    // 每次都生成新的 uuid 文件名，同一 URL 调两次会得到两张图
        openWorldHint: true,      // 会替调用方访问外部网络（因此后端有 SSRF 防护）
      },
    },
    async ({ url }) => {
      try {
        const d = await uploadImageFromUrl(url);
        return text(
          `已按 URL 上传图片：\n` +
            `  图片路径：${d?.url ?? '-'}\n` +
            `  objectName：${d?.objectName ?? '-'}\n` +
            `  用途：直接填进 create_product 的 pic，或 update_product 的 pic（相册 gallery 暂不支持由本工具改）\n\n` +
            `JSON:\n${JSON.stringify(d ?? null, null, 2)}`
        );
      } catch (e) {
        // 后端拒绝原因（中文）原样透出：400 参数/目标站、403 内网、413 超 5MB、415 不是图片、502/504 网络超时
        return fail(e);
      }
    }
  );
}

export default registerMiscTools;
