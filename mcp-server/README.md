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

## 用 MCP Inspector 调试

```bash
# ⚠️ v2.15 的 stdio 子进程不继承父进程环境变量，必须显式 -e 传
npx -y @modelcontextprotocol/inspector \
  -e MALL_BASE=http://127.0.0.1:18080 \
  -e MALL_USER=ai_operator \
  -e MALL_PASS='Ai@2026#ops' \
  -e MCP_ENABLE_WRITE=1 \
  node mcp-server/src/index.mjs
```

踩坑记录（v2.15.0 实测）：

- **stdio 子进程拿不到父进程 env** → 不传 `-e` 会只注册 2 个只读工具（`MCP_ENABLE_WRITE` 不生效）
- UI URL 参数是 `?MCP_INSPECTOR_API_TOKEN=…`（不是旧版的 `MCP_PROXY_AUTH_TOKEN`）
- UI 用的是 **Mantine**，不是 MUI：Run 按钮文案是 `Execute Tool`，连服务器点 `span.mantine-Switch-track`
- 成功后参数输入框会被 React 受控组件清空（想连点两次同参，靠 state 保留而不是输入框）
- **默认拒绝 `HOST=0.0.0.0`**：要 `DANGEROUSLY_BIND_ALL_INTERFACES=true` 才放行（它自己会警告 backend 能 spawn 本地进程）
- **明文 HTTP + 非 localhost = 非安全上下文**：`crypto.randomUUID` 直接 `undefined`，连服务器必失败；必须 HTTPS 或 localhost
- **还有一层 Origin 校验**：跨机访问要点「连接」需把浏览器 Origin 登记进 `ALLOWED_ORIGINS`，否则 `403 Invalid origin`（该变量一设就整体覆盖默认值，localhost/127.0.0.1 要一并列上）
- **token 可固定**：`MCP_INSPECTOR_API_TOKEN=<固定串>` 能顶掉随机 `randomBytes(32)`（`MCP_INSPECTOR_SECRET_KEY` 只加密凭据存储，**不影响** token，别搞混）

## 从文档首页打开

不想每次手敲上面那条命令的话，用脚本起一个**常驻、地址稳定**的实例：

```bash
bash scripts/start-inspector.sh    # 在仓库根目录执行
```

- **地址在文档站首页**「测试环境」表格的「MCP 调试器」那一行，直接点开即可
  （`http://100.113.135.55:3027/`）。脚本每次启动都会把这个格子刷成最新的带 token 链接。
- 脚本会把实例跑成 systemd 单元 `mcp-inspector`（`qiqi-svc.slice`），不占当前 shell / DSH 的资源和生命周期，退出终端也不影响。
- token 通过 `MCP_INSPECTOR_API_TOKEN` 固定，正常重启地址不变；**但地址里的 token 每次重启仍可能变**（比如你改过 `INSPECTOR_TOKEN` 或用命令行手工起过），所以**重启后要再跑一次 `bash scripts/start-inspector.sh`**，它会自动刷新首页链接。
- 停用：`bash scripts/stop-inspector.sh`（只按 systemd 单元名停，不会误杀别的进程）。

对外地址为什么是 `https://<主机名>.ts.net:8444` 而不是 `http://<IP>:6274`：

- `6274` 只绑 `127.0.0.1`，对外由 `tailscale serve` 提供 HTTPS 入口（脚本会自动确保这条映射）。
- 从**非 localhost 的明文 HTTP** 打开时浏览器不给「安全上下文」，`crypto.randomUUID` / `crypto.subtle` 为 `undefined`，Inspector 一连接就报 `crypto.randomUUID is not a function`。所以走 HTTPS 不是为了好看，是**必须**。
- 想强行直接暴露端口（仅限可信网络）：`HOST=0.0.0.0 BIND_HOST=0.0.0.0 bash scripts/start-inspector.sh`，脚本会加 `DANGEROUSLY_BIND_ALL_INTERFACES=true` 绕过它的拦截，但页面如上所述会连不上，不建议。

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
