# mock 平台服务（淘宝 / 抖音）

「外部电商平台」的替身，给多平台接入模块提供拉单与回传发货两个接口。**单文件、零依赖、独立端口
19090、不进 docker compose**。

## 启动（一条命令）

```bash
MOCK_RUN_ID=$(date +%s) MOCK_SKU_IDS=1,2,3 node mock-platform/server.mjs
```

- 端口默认 `19090`，可用 `MOCK_PORT` 改；
- `MOCK_RUN_ID` 拼进平台单号（`TB-<runId>-1`、`DY-<runId>-1`），换个值就是「一批新单」，
  不换则每次拉单都返回**同一批**（这正是重复拉单验幂等的前提）；
- `MOCK_SKU_IDS` 是明细里用到的本地 `sku.id` 列表（平台商品编码写成 `SKU-<id>`）；
- 停止：`Ctrl-C`，或对**这个进程的 PID** 发 `SIGTERM`（脚本里用 `kill <pid>`，不要 `pkill -f` 宽匹配）。

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
