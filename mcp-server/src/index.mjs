#!/usr/bin/env node
// 入口：McpServer + StdioServerTransport
// ⚠️ stdio 模式下 stdout 是 JSON-RPC 流 —— 这里绝不能出现 console.log。
//    （本文件内所有日志一律 console.error → stderr）
//
// 工具按域拆分在 ./tools/ 下，注册清单见 ./tools/index.mjs。

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { registerAll } from './tools/index.mjs';
import { mallBase } from './mall.mjs';

const writeEnabled = process.env.MCP_ENABLE_WRITE === '1';

const server = new McpServer(
  { name: 'qiqi-mall-mcp', version: '0.1.0' },
  {
    instructions:
      '七七商城后台运维工具。' +
      '订单域查询类（只读）：list_orders（按状态/订单号分页查）、get_order_detail（明细+退货申请）、list_pending_orders、count_deliveries（批量包裹数）、list_deliveries、preview_split（拆单预演，返回 willDo + 方案）；' +
      '商品域查询类（只读）：search_products、get_product_detail、list_categories、list_product_types、list_attributes、list_skus；' +
      '订单状态码 0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货。' +
      '写操作（需 MCP_ENABLE_WRITE=1）：execute_audit_pass（先 preview_split 把 willDo/方案念给用户并取得明确同意，再把 orderId 传回去即可，不需要确认码）、' +
      'update_product / update_sku_stock（改商品/SKU，**必须带 version**）：' +
      'version 来自最近一次读取（list_products / get_product_detail / list_skus）；' +
      '后端用 version 做乐观锁 —— 缺 version 会被拒（400），期间被别人改过会被拒（409「数据已被他人修改」）。' +
      '遇到 409 请重新读取最新 version 再让用户确认后重试，绝不要拿旧 version 反复重试。',
  }
);

registerAll(server, { writeEnabled });

const transport = new StdioServerTransport();
await server.connect(transport);

console.error(
  `[mcp] qiqi-mall-mcp 已启动（stdio）| base=${mallBase} | write=${writeEnabled ? 'on' : 'off'} | ` +
    `apiKey=${process.env.MALL_API_KEY ? `${process.env.MALL_API_KEY.slice(0, 8)}…` : '(未设置！请求会失败)'}`
);

// 客户端关掉 stdin 就优雅退出，不留僵尸进程
process.stdin.on('end', () => process.exit(0));
