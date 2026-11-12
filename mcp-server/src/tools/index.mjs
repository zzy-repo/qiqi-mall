// 工具统一注册入口：按域 import 各自的 register 函数。
//
// ┌─ 新增一个域，照这个模式加一个文件，别往别人的文件里塞 ────────────────
// │ 1) 新建 src/tools/<域>.mjs，导出 `export function register<域>Tools(server, ctx)`
// │    - ctx 目前是 { writeEnabled }（写开关）
// │ 2) 域内一个工具一次 server.registerTool(...)：
// │    title 中文、description 中文且写清「什么时候用」、inputSchema 用 zod + .describe()
// │ 3) 分页一律用 ./_shared.mjs 的 clampPage + paginate（page 默认 1，size 默认 10、最大 50），
// │    即使后端接口不分页也在工具层夹取 —— 10 批工具的分页语义必须完全一致
// │ 4) 返回「文本摘要 + JSON:」两段（照 preview_split 的写法），空结果给明确文案
// │ 5) 报错统一 fail(e)；**写工具**用 _shared.mjs 的 failWrite(e, {...})，
// │    它会把后端 bizCode（400 缺版本 / 409 版本冲突）显式带进文本，并给出「重新读」的下一步
// │ 6) 并发控制口径：**只留 version 乐观锁**（确认码整套已删除）。
// │    写工具必须强制 version 参数（zod 必填），成功后回读一次拿新 version；
// │    只读工具（含 dry-run 预演 preview_split）不需要任何票据。
// │ 7) 在本文件下面 import 并调用一行；写工具的注册要放在 `if (writeEnabled)` 里
// └────────────────────────────────────────────────────────────────────

import { registerOrderTools } from './order.mjs';
import { registerCatalogTools } from './catalog.mjs';
import { registerCatalogWriteTools } from './catalog-write.mjs';

/**
 * 注册全部工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx 由入口按 MCP_ENABLE_WRITE 决定
 */
export function registerAll(server, ctx = {}) {
  const { writeEnabled = false } = ctx;

  // 域可以并行/串行注册，互相独立：某域不注册写工具不会影响别的域
  registerOrderTools(server, { writeEnabled }); // 订单域：6 只读 + 1 写（execute_audit_pass 受 writeEnabled 控制）
  registerCatalogTools(server, { writeEnabled }); // 商品域：6 只读（全部不写库）
  registerCatalogWriteTools(server, { writeEnabled }); // 商品域写：2 个（update_product / update_sku_stock，自带写开关门禁）
}

export default registerAll;
