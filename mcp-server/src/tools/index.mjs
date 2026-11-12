// 工具统一注册入口：按域 import 各自的 register 函数。
//
// ┌─ 场景验收判据（07-AI集成）─────────────────────────────────────────────
// │ MCP 场景没有网页 UI，Playwright 不适用，所以 e2e 判据就是本仓库的 MCP 测试全套：
// │     MCP 场景的场景验收判据 = test/*.mjs 全套两轮全绿；视频见 e2e/report/videos/07-AI集成/
// │ 具体口径：`npm test`（= loop.mjs + catalog.mjs + order-read.mjs + optimistic-lock.mjs
// │          + delivery.mjs + returns.mjs + config.mjs + member.mjs + misc.mjs）
// │ 连跑两轮，**第二轮不 reset 数据库**，两轮都必须 0 失败。测试自带「造数据 + 清数据」，
// │ 所以两轮之间不需要复位脚本；任何一轮红都算场景不达标（不许靠放宽断言过关）。
// │ 覆盖场景：1-MCP最小闭环（loop.mjs）、2-并发冲突（AI端）（optimistic-lock.mjs），
// │           catalog.mjs / order-read.mjs 是两个域的只读回归，
// │           第 4 批（delivery/returns/config/member/misc）是履约·退货·配置·会员·概览的回归 + 新写工具验收。
// └────────────────────────────────────────────────────────────────────
//
// ┌─ 新增一个域，照这个模式加一个文件，别往别人的文件里塞 ────────────────
// │ 1) 新建 src/tools/<域>.mjs，导出 `export function register<域>Tools(server, ctx)`
// │    - ctx 目前是 { writeEnabled }（写开关）
// │ 2) 域内一个工具一次 server.registerTool(...)：
// │    title 中文、description 中文且写清「什么时候用」、inputSchema 用 zod + .describe()
// │    - 「一个资源一个工具」的写法（如 delivery/return_apply/member，用 action 参数区分动作）时：
// │      write=0 要把写动作从 action 枚举里摘掉（工具仍在、只读动作照常可用），
// │      并在 handler 里再判一次（第二道闸）
// │ 3) 分页一律用 ./_shared.mjs 的 clampPage + paginate（page 默认 1，size 默认 10、最大 50），
// │    即使后端接口不分页也在工具层夹取 —— 10 批工具的分页语义必须完全一致
// │ 4) 返回「文本摘要 + JSON:」两段（照 preview_split 的写法），空结果给明确文案
// │ 5) 报错统一 fail(e)；**写工具**用 _shared.mjs 的 failWrite(e, {...})，
// │    它会把后端 bizCode（400 缺版本 / 409 版本冲突）显式带进文本，并给出「重新读」的下一步
// │ 6) 并发控制口径：**version 乐观锁**（改数据必须带上次读到的 version）。
// │    写工具必须强制 version 参数（zod 必填），成功后回读一次拿新 version；
// │    ⚠️ 但「强制」的方式看后端真实入参：
// │      · 该接口收 version（update_product / update_sku_stock / member update /
// │        delivery update|split|merge|delete）→ 必须传，缺了后端 fail-closed 回 400；
// │        能 zod 必填就 zod 必填（member update），一个工具里混了不吃 version 的动作时
// │        （delivery dispatch/cancel）由工具层+后端两步兜住，并在 .describe() 里写清哪个 action 必填；
// │      · 该表**没有 version 列**（return_apply / return_reason / warehouse / order_setting /
// │        member_address / cart）→ 后端根本不收 version，并发靠「影响行数 0 → 409」闸门；
// │        这时**不能凭空造一个 version 参数**（会让每次调用都被当成非法入参）。
// │    只读工具（含 dry-run 预演）无副作用，可反复调用。
// │ 7) 在本文件下面 import 并调用一行；纯只读域直接注册，写域自带 writeEnabled 判定
// └────────────────────────────────────────────────────────────────────

import { registerOrderTools } from './order.mjs';
import { registerCatalogTools } from './catalog.mjs';
import { registerCatalogWriteTools } from './catalog-write.mjs';
import { registerCatalogDictTools } from './catalog-dict.mjs';
import { registerDeliveryTools } from './delivery.mjs';
import { registerReturnsTools } from './returns.mjs';
import { registerConfigTools } from './config.mjs';
import { registerMemberTools } from './member.mjs';
import { registerMiscTools } from './misc.mjs';

/**
 * 注册全部工具。
 * @param {import('@modelcontextprotocol/sdk/server/mcp.js').McpServer} server
 * @param {{ writeEnabled: boolean }} ctx 由入口按 MCP_ENABLE_WRITE 决定
 */
export function registerAll(server, ctx = {}) {
  const { writeEnabled = false } = ctx;

  // 域可以并行/串行注册，互相独立：某域不注册写工具不会影响别的域
  registerOrderTools(server, { writeEnabled }); // 订单域：6 只读 + 6 写（execute_audit_pass / create_order / update_order / audit_reject_order / ship_order / delete_order）
  registerCatalogTools(server, { writeEnabled }); // 商品域：8 只读（+get_sku_detail / list_sku_attributes，全部不写库）
  registerCatalogWriteTools(server, { writeEnabled }); // 商品域写：6 个（改：update_product / update_sku_stock；增删：create_product / delete_product / create_sku / delete_sku）
  registerCatalogDictTools(server, { writeEnabled }); // 商品字典：3 个（category / product_type / attribute，各 action=get|create|update|delete）

  // 第 4 批：履约 / 退货 / 基础配置 / 会员 / 概览
  registerDeliveryTools(server, { writeEnabled }); // 履约：delivery(action) list|get 只读，其余 7 个 L3 动作（含 wms_callback）受写开关控制
  registerReturnsTools(server, { writeEnabled }); // 退货：return_apply(list|get|audit|create|delete) + return_reason(list|create|update|delete)
  registerConfigTools(server, { writeEnabled }); // 配置：warehouse(list|create|update|delete) + order_setting(get|update)
  registerMemberTools(server, { writeEnabled }); // 会员：member / member_address / member_cart(含 create|update|delete)
  registerMiscTools(server, { writeEnabled }); // 概览：dashboard_overview（只读；upload_image 刻意未做，见 misc.mjs 尾注）
}

export default registerAll;
