#!/usr/bin/env node
// 入口：McpServer + StdioServerTransport
// ⚠️ stdio 模式下 stdout 是 JSON-RPC 流 —— 这里绝不能出现 console.log。
//    （本文件内所有日志一律 console.error → stderr）

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { registerTools } from './tools.mjs';
import { mallBase } from './mall.mjs';

const writeEnabled = process.env.MCP_ENABLE_WRITE === '1';

const server = new McpServer(
  { name: 'qiqi-mall-mcp', version: '0.1.0' },
  {
    instructions:
      '七七商城后台运维工具。查询类：list_pending_orders、preview_split、list_deliveries。' +
      '写操作 execute_audit_pass 必须先 preview_split 拿到 confirm_token、并由用户明确确认后才能调用。',
  }
);

registerTools(server, { writeEnabled });

const transport = new StdioServerTransport();
await server.connect(transport);

console.error(
  `[mcp] qiqi-mall-mcp 已启动（stdio）| base=${mallBase} | write=${writeEnabled ? 'on(4 tools)' : 'off(2 tools)'} | user=${process.env.MALL_USER || '(unset)'}`
);

// 客户端关掉 stdin 就优雅退出，不留僵尸进程
process.stdin.on('end', () => process.exit(0));
