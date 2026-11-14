# mock 平台服务（淘宝 / 抖音）

「外部电商平台」的替身，给多平台接入模块提供拉单与回传发货两个接口。**单文件、零依赖、端口
19090**，两种跑法共用这一份代码：

| 跑法 | 谁在用 | 地址 | 谁连它 |
| --- | --- | --- | --- |
| 宿主机进程 | e2e 分片（09 接入两条 spec 自己 spawn） | `127.0.0.1:19090`（**宿主机 19090 只归 e2e**） | 宿主机后端（dev profile，8091~8094） |
| compose 服务 `mock-platform` | 测试/演示栈（admin.rose7.cn 那套容器） | `mock-platform:19090`（qiqi-net 内部 DNS） | 容器里的 server-1 / server-2（test profile） |

## 启动（一条命令，宿主机）

```bash
MOCK_RUN_ID=$(date +%s) MOCK_SKU_IDS=1,2,3 node mock-platform/server.mjs
```

- 端口默认 `19090`，可用 `MOCK_PORT` 改；监听地址默认 `127.0.0.1`，可用 `MOCK_HOST` 改；
- `MOCK_RUN_ID` 拼进平台单号（`TB-<runId>-1`、`DY-<runId>-1`），换个值就是「一批新单」，
  不换则每次拉单都返回**同一批**（这正是重复拉单验幂等的前提）；
- `MOCK_SKU_IDS` 是明细里用到的本地 `sku.id` 列表（平台商品编码写成 `SKU-<id>`）；
- 停止：`Ctrl-C`，或对**这个进程的 PID** 发 `SIGTERM`（脚本里用 `kill <pid>`，不要 `pkill -f` 宽匹配）。

## 启动（容器栈里）

```bash
docker compose up -d mock-platform          # 只有它一个；已有栈的话 compose 会顺手补上
docker compose exec mock-platform wget -qO- localhost:19090/health
```

- **不映射宿主机 19090**（compose 里只有 `expose`）：宿主机 19090 是 e2e 分片的，
  容器要是也绑上去，09 spec 里 `spawn` 的那份就 `EADDRINUSE`。（这就是「别抢端口」那条。）
- 容器里必须 `MOCK_HOST=0.0.0.0`：容器内的 `127.0.0.1` 只有容器自己能连，
  server-1/-2 是另一个容器，按服务名连进来，监听回环就永远连不上。
- 后端的地址由 `channel.mock.base-url` 决定（不是店铺字段，`channel_shop` 没有 base_url 列）：
  test profile 默认 `http://mock-platform:19090`，dev profile 默认 `http://127.0.0.1:19090`，
  都可用环境变量 `CHANNEL_MOCK_BASE_URL` 覆盖。
- 不设 `MOCK_RUN_ID` = 用进程启动时间：`docker compose restart mock-platform` 就是「一批新平台单」。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查 + 本批平台单号 |
| GET | `/mock/{taobao,douyin}/orders?since=&shopCode=` | 拉单（每次返回同一批；`&fail=503` 注入本次失败） |
| POST | `/mock/{taobao,douyin}/ship` | 回传发货 `{platformSn, carrier, trackNo}`；同运单号重复回传回 `code=DUPLICATE` |
| GET | `/mock/{taobao,douyin}/state` | 平台侧事实：拉单次数、已收到的发货记录（e2e 断言用） |
| POST | `/mock/_admin/fail?code=503&count=1` | 接下来 N 次外呼失败（`count=1` = 第一次失败、重试成功） |
| POST | `/mock/_admin/fail/clear` | 清掉故障注入 |
| POST | `/mock/_admin/bad-sku?on=1` | 额外返回一张商品编码对不上的单（演示 `SKU_NOT_MATCHED`） |
| POST | `/mock/_admin/reset` | 清空注入与运行期状态 |

## 手验示例

```bash
curl -s 'http://127.0.0.1:19090/health'
curl -s 'http://127.0.0.1:19090/mock/taobao/orders?since=2026-01-01T00:00:00' | head -c 400
curl -s -X POST 'http://127.0.0.1:19090/mock/_admin/fail?code=503&count=1'
curl -s 'http://127.0.0.1:19090/mock/taobao/state'
```
