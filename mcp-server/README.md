# qiqi-mall MCP Server（stdio 最小闭环）

把商城后台「订单审核 + 拆单」暴露成 MCP 工具，给 Claude Desktop / 任意 MCP 客户端调用。
**后端一行没改** —— 完全是 REST 客户端。

## 闭环

```
① list_pending_orders()     列出待审核订单（只读）
② preview_split(orderId)    拆单方案（dry-run 不落库）+ confirm_token
③ execute_audit_pass(token) 订单 7→8 并真的生成发货单（写）
④ list_deliveries(orderId)  回读，确认包裹数与方案一致
```

## 运行

```bash
cd mcp-server
npm install
export MALL_BASE=http://127.0.0.1:18080
export MALL_USER=ai_operator MALL_PASS='Ai@2026#ops'
export MCP_ENABLE_WRITE=1     # 0=只读（默认 0），1=开放写工具
npm start
```

`npm test` 跑闭环测试：用 SDK client 通过 stdio 真启动本 server，逐条断言 12 步
（含「预览不落库」「假 token 被拒」「同一 token 只能用一次」「写开关只注册 2 个工具」）。
测试会消费 `qiqi_mall_test` 里 **id=3** 的订单，跑完记得复位：

```bash
bash ../scripts/reset-test-db.sh     # 或 cd ../e2e && npm run reset:test
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MALL_BASE` | `http://127.0.0.1:18080` | 后端网关地址 |
| `MALL_USER` / `MALL_PASS` | — | 专用运维账号（不要用 admin） |
| `MCP_ENABLE_WRITE` | `0` | `0`=只注册 2 个只读工具；`1`=注册全部 4 个 |

见 `.env.example`。

## 客户端配置示例

```json
{
  "mcpServers": {
    "qiqi-mall": {
      "command": "node",
      "args": ["/root/qiqi-mall/mcp-server/src/index.mjs"],
      "env": {
        "MALL_BASE": "http://127.0.0.1:18080",
        "MALL_USER": "ai_operator",
        "MALL_PASS": "Ai@2026#ops",
        "MCP_ENABLE_WRITE": "1"
      }
    }
  }
}
```

## 安全性

- **写开关**：`MCP_ENABLE_WRITE=0` 时写工具根本不注册，模型连看都看不到。
- **confirm_token**：`preview_split` 签发，内存 Map，5 分钟 TTL，一次性（用完即删，失败也删），绑定用途 + 订单。
- **防偷换**：`execute_audit_pass` 会**重新**调用 `split-preview` 比对方案指纹（`sha256(JSON.stringify(plan))`），方案变了就拒绝，避免「确认的是 A、执行成 B」。
- **stdio 纪律**：所有日志走 `console.error`，stdout 只留给 JSON-RPC。
