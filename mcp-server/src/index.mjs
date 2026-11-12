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
      '商品域查询类（只读）：search_products、get_product_detail、get_sku_detail、list_categories、list_product_types、list_attributes、list_skus、list_sku_attributes；' +
      '商品域写：create_product / update_product / delete_product（商品增改删，改必须带 version）、create_sku / update_sku_stock / delete_sku（规格增改删）；' +
      '商品字典：category(action=get|create|update|delete)、product_type(同)、attribute(同)（分类/类型/属性维护，三张表无 version）；' +
      '履约域：delivery(action=list|get|update|split|merge|delete|dispatch|cancel|wms_callback)（发货单：查 / 改仓改量 / 拆合 / 删除 / 下发 / 取消 / 模拟 WMS 回传）；' +
      '退货域：return_apply(action=list|get|audit|create|delete)（退货申请，audit=审核：通过=退款+回补库存；create=代客登记、delete=删记录，都不退款）、' +
      'return_reason(action=list|create|update|delete)（原因字典）；' +
      '配置域：warehouse(action=list|create|update|delete)（仓库）、order_setting(action=get|update)（订单全局设置）；' +
      '会员域：member(action=list|get|create|update|delete)、member_address(action=list|save|delete)、member_cart(action=list|create|update|delete)；' +
      '订单写（第 5 批补齐）：create_order（后台新增订单）/ update_order（改收货信息，必须带 version）/ audit_reject_order（审核不通过，回补库存）/ ship_order（直发发货）/ delete_order（级联删除），后三个支持 dryRun 预演；' +
      '概览：dashboard_overview（看板指标，只读）。' +
      '订单状态码 0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款 7待审核 8待发货 9部分发货；' +
      '发货单状态码 0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消。' +
      '写操作（需 MCP_ENABLE_WRITE=1）：execute_audit_pass（先 preview_split 把 willDo/方案念给用户并取得明确同意，再把同一个 orderId 传回去）、' +
      'update_product / update_sku_stock / member(update)（改商品/SKU/会员，**必须带 version**）：' +
      'version 来自最近一次读取（list_products / get_product_detail / list_skus / member）；' +
      '后端用 version 做乐观锁 —— 缺 version 会被拒（400），期间被别人改过会被拒（409「数据已被他人修改」）。' +
      '遇到 409 请重新读取最新 version 再让用户确认后重试，绝不要拿旧 version 反复重试。' +
      'L3 高影响动作（delivery 的 update/split/merge/delete/dispatch/cancel、return_apply 的 audit、member 的 delete）：' +
      '这些多数支持 dryRun=true 预演（只回 willDo、不落库），**先预演并把后果念给用户、拿到明确同意再执行**；' +
      '其中 dispatch 下发后不可撤回（只能等仓库回传），return_apply(auditStatus=1) 会立刻退款并回补库存。' +
      '注意：return_apply / return_reason / warehouse / order_setting / member_address 这几张表后端没有 version 列，' +
      '它们的写接口不收 version（并发靠「影响行数 0 → 409」兜底），所以这些工具里没有 version 参数属于正常，不要编造。',
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
