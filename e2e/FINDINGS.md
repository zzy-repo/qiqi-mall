# E2E 测试发现的问题清单

> 运行方式见 `README.md`。
> 首轮发现于 2026-11-10（4 个场景 3 失败），当天按 `docs/flows/14-订单状态图`、`15-e2e修复方案时序图` 修复完毕，现 **5 个用例全绿**。

## 一、修复结果总览

| 编号 | 问题 | 修复内容 | 状态 |
|---|---|---|---|
| P1 | 管理员无「发货」按钮 | 后端 `POST /admin/orders/{id}/ship`（守卫 status=1）+ admin 订单行「发货」按钮（仅已付款显示，带确认框） | ✅ 已修复 |
| P2 | 买家无「确认收货」 | 后端 `POST /portal/orders/{id}/confirm`（守卫 status=2 且归属本人）+ portal 行内按钮 | ✅ 已修复 |
| P3 | 买家看不到退货结果 | portal 我的订单接入 `GET /portal/returns`，新增「售后」列：退款处理中 / 退款成功 / 退货被拒绝 | ✅ 已修复 |
| P4 | 商品分类树选不了父节点 | 商品弹窗 `el-tree-select` 补 `check-strictly` | ✅ 已修复 |
| G1 | 通用更新可任意改 status | `PUT /admin/orders/{id}` 剥离 `status`，状态只能走专用接口 + 守卫 | ✅ 已修复 |

## 二、按状态图补齐的状态机与联动

依据 `docs/flows/14-订单状态图.mmd`：

| 流转 | 接口 | 守卫 | 联动 |
|---|---|---|---|
| 待付款 → 已付款 | `POST /portal/orders/{id}/pay` | status=0 | 写支付流水（type=1） |
| 已付款 → 已发货 | `POST /admin/orders/{id}/ship` | status=1 | — |
| 已发货 → 已完成 | `POST /portal/orders/{id}/confirm` | status=2 且本人订单 | — |
| 已付款/已发货 → 退款中 | `POST /portal/returns` | status∈{1,2}，且无待处理申请 | 订单置 5 |
| 退款中 → 已退款 | `PUT /admin/return-applies/{id}` (status=1) | 仅 return_apply.status=0 | 退款流水（type=2）+ **库存回补** |
| 退款中 → 已付款 | 同上 (status=2) | 同上 | 订单回到 1 |

`order.status` 取值扩展到 `0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款`；
新增 `payment_record`（支付流水：order_sn / amount / type / channel / trade_no / created_at）用于对账。

## 三、仍存在的缺口与文档不一致（未处理，需决策）

### D1 自动关单无法实现：order 表没有下单时间字段

`14-订单状态图` 要求「[定时任务] 待付款超时 → 已关闭（守卫：超时 auto_close_minutes，联动：释放预占库存）」，
但 `order` 表（见 `docs/er/12-ER图-订单域`）**没有 create_time / 下单时间字段**，无法判定超时。

- 需要先定：是否给 `order` 加 `create_time`（ER 变更），还是另建订单事件表。
- 另：当前实现是**下单即扣库存**（非预占），所以「释放预占库存」对应的是「关单回补库存」，语义需确认。

### D2 场景4 与状态图的守卫冲突

- `6-场景4-售后退货`：B1「我的订单选**已收货**订单」
- `14-订单状态图`：`已付款 → 退款中`，守卫「仅 status=1 或 2」

即状态图不允许「已完成(3)」的订单再申请退货，与场景4 文案矛盾。当前实现**按状态图**（只允许 1/2），
若要做「收货后仍可退」，需把守卫放宽到 status=3 并定义「已完成 → 退款中」的流转。

### D3 高并发下的死锁（超出本次 fix 文档范围）

见 `benchmark/oversell/README.md` 第 8 节：200 并发抢 10 库存时，1 条请求返回数据库死锁原文。
数据一致性没问题（事务整体回滚），但错误信息透给了前端。建议加一层异常映射或重试。

## 四、E2E 用例与文档对应

| 文件 | 场景 | 结果 |
|---|---|---|
| `tests/scenario1-browse.spec.js` | 场景1 商品浏览（含 P4 父分类回归断言） | ✅ |
| `tests/scenario2-checkout.spec.js` | 场景2 购买下单（本地购物车合并、快照、扣库存、模拟支付） | ✅ |
| `tests/scenario3-fulfillment.spec.js` | 场景3 订单履约（发货 → 确认收货） | ✅ ✅ |
| `tests/scenario4-return.spec.js` | 场景4 售后退货（申请 → 审核通过 → 买家可见） | ✅ |
