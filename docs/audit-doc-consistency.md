# 文档一致性审计（2026-11-10 第二次）

> 审计范围：`docs/flows/*`（1~18）、`docs/er/*`（10~13）、`docs/er-diagrams.md`、`README.md`、`docs/docker.md`
> 对照对象：`qiqi-mall-server` 源码 + `admin-web` / `portal-web` 源码 + `sql/schema.sql` + `docker-compose.yml` + `e2e/`
> 结论：**仍有 6 处文档自相矛盾、8 处文档未同步到代码、3 处代码缺口、2 处索引错误。**

---

## A. 文档自相矛盾（文档 vs 文档）

### A1 图2 与 图5/14/15 对「发货 / 确认收货」的结论相反
- 图2 `2-主链路总览图`：`B8[确认收货 ⛔缺接口]`、`A3[订单管理-发货 ⛔缺按钮]`
- 图5 `5-场景3-订单履约`：`✅方案已定·图15 POST /admin/orders/:id/ship`、`✅方案已定·图15 POST /portal/orders/:id/confirm`
- 图14/15 也是「已实现」口径，代码确实已实现 → **图2 过期**

### A2 `er-diagrams.md` 的「场景覆盖现状」仍是缺口口径
```
⛔ 缺口：场景3 的"买家确认收货接口"与"管理员发货按钮"（订单状态机中间段）
```
与图5/14/15、代码、`e2e/FINDINGS.md`（5 用例全绿）都冲突。

### A3 图6（场景4）与 图14（状态图）的退货前置条件冲突
- 图6：`B1[我的订单选已收货订单 ✅]`（已收货 = status 3）
- 图14：`已付款 --> 退款中`，守卫「仅 status=1 或 2」
- 即：**状态图不允许已完成订单退货，场景图要求可以**。代码按状态图实现（1/2）。

### A4 图6 把「审核通过」与「退款中」绑在一起，与 图14/代码 的两段流转不一致
- 图6：`A2 -- 通过 --> A3[标记通过 状态机:退款中]`
- 图14 / 代码：**申请退货时** → `5 退款中`；**审核通过时** → `6 已退款`
- 图6 漏掉「已退款」，易被读成「通过后还停在退款中」

### A5 图14 的「已收货」中间态在 ER 里不存在
- 图14：`已发货 → 已收货 → 已完成`（两跳）
- 图12 `order.status` 枚举只有 `0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款`，**没有「已收货」**
- 代码：确认收货一步 `2 → 3`。若要保留「已收货」，必须改枚举。

### A6 库存模型表述混用：图14「释放预占库存」 vs 图4「下单即扣库存」
- 图4：`POST /portal/orders 快照+扣库存`（下单即扣，代码如此）
- 图14：`待付款 → 已关闭 …联动:释放预占库存`（预占模型）
- 两种模型不能混用：若下单即扣，关单应写「回补库存」；若预占，则下单不应扣 `sku.stock`。

### A7 图7 场景A 与 图8 冲突（买家是否也有验证码/失败计数）
- 图7：`alt 密码错误 → BE->>CS: 失败计数+1`（买家登录也计数）
- 图8：通篇只讲 admin（`participant DB as admin表`），且代码只对 admin 做计数与验证码
- 买家侧代码：`PortalAuthController` 无任何验证码/计数逻辑（grep 结果 0 处引用）

---

## B. 文档未同步到代码实现

### B1 接口路径缺 `/auth` 段（图7、图8）
| 文档写的 | 代码实际 |
|---|---|
| `GET /admin/captcha-required` | `GET /admin/auth/captcha-required` |
| `GET /admin/captcha` | `GET /admin/auth/captcha` |
| `POST /admin/login` | `POST /admin/auth/login` |

### B2 MinIO 与图片上传：文档当成已设计能力，代码完全没有
- 图9 `9-图片上传时序图`：`POST /admin/upload/image`（multipart、文件头白名单、≤5MB、`PutObject(bucket: qiqi-mall)`、返回 url）
- 图1：架构图把 MinIO 画成与 MySQL 并列的存储，admin-web「含图片上传」，AdminAPI「…/上传」
- **代码现状**：无 upload 接口、`pom.xml` 无 MinIO 依赖、`docker-compose.yml` 无 minio 服务；admin 商品/SKU 弹窗只有「图片 URL 文本框」
- 连带：种子数据 URL 是 `http://minio.local/...`，前端图片全部加载失败（显示「无图」占位）

### B3 图14 的「待付款 → 已关闭（定时任务）」未实现
- 全项目只有 1 个 `@Scheduled`（`CaptchaStore` 清扫），没有关单任务
- 且 `order` 表无下单时间字段（见图12/代码），**无法判定超时** —— 需先定 ER 变更

### B4 图12 把「支付流水」标为「规划」，实际已建表并写入
- 图12：`订单 ||..o{ 支付流水 : "对账使用(规划)"`、`退货申请 ||..o| 支付流水 : "退款流水(规划)"`
- 代码：`payment_record` 表已建，支付与退款各写一条（`PaymentRecordService.record`）
- 结构差异：图12 写 `order_sn FK`，实现只有普通索引 `idx_order_sn`，**没有外键约束**

### B5 图10（总览 ER）缺「支付流水」，与图12 不一致
- 图10 只有 16 个业务实体，**没有支付流水**
- `er-diagrams.md` 又写「15 实体全关系总览」；数据库实际 **17 张表**（含 payment_record）
- 三处数字互不相同（16 实体 / 15 实体 / 17 表）

### B6 README 未同步状态机与新增能力
- C 端页面表 `/orders`：只写「详情 / 模拟支付 / 申请退货」，缺 **确认收货** 与 **售后状态列**
- B 端页面段：未提 **发货按钮**
- 全文没有 `5 退款中 / 6 已退款`、退款流水、库存回补的说明

### B7 图16/17/18（问题图）状态标记过期
- 内容仍是「现状缺陷」（如 `操作列只有[明细][编辑]`、`el-tree-select 缺 check-strictly`）
- 三项均已修复；索引里写「修复完成后可归档」但没标已修复 → 读者会误判现状

### B8 `e2e/FINDINGS.md` 与 `er-diagrams.md` 互相打架（见 A2）
- FINDINGS：5 用例全绿、P1~P4 + 守卫已修
- 索引：仍列 ⛔ 缺口

---

## C. 代码缺口（文档要求，代码无入口）

### C1 `4 已关闭` 是死状态
- 枚举里有 `4已关闭`，但**没有任何接口/按钮**能把订单置为 4（`PUT /admin/orders/{id}` 已剥离 status，自动关单未实现）
- 结果：该状态永远无法到达，状态图里 `已关闭 --> [*]` 分支不可演示

### C2 MinIO 上传链路整体缺失（同 B2）
- 若要做「图片上传」，需要：minio 服务 + `minio` SDK + `POST /admin/upload/image`（校验 + PutObject + 返回 URL）+ admin 弹窗换成上传控件

### C3 自动关单 + 库存回补（同 B3）
- 依赖 `order.create_time`（需 ER 变更）与关单回补库存的接口/任务

---

## D. 索引与引用错误

### D1 README 引用了已删除的文件
```
README.md: 「见 docs/captcha-flow.mmd」
```
该文件已不存在（现为 `docs/flows/8-验证码机制时序图.mmd`）。

### D2 `er-diagrams.md` 的「存放位置」描述含糊
```
存放位置：全部在 flows/（编号 10~13 的 ER 图存于 er/ 子目录同名引用）
```
自相矛盾（前半句说全部在 flows/，后半句说 10~13 在 er/）。

---

## E. 已核对一致的部分（无需改动）

| 文档 | 与代码一致的点 |
|---|---|
| 图8 验证码机制 | 4 位 Hutool 图形码、TTL 5 分钟、`remove` 一次性消费、失败 ≥5 次触发、5 分钟窗口、10 分钟清扫 |
| 图13 用户域 | member / member_address / cart / admin 四表字段与聚合关系 |
| 图11 商品域 | sku_spec（UNIQUE(sku_id,attribute_id)）、product_gallery、product.pic、sku.pic、SpecTextHelper 规格文本 |
| 图15 修复方案 | P1 ship、P2 confirm、P3 售后可见、P4 check-strictly、通用更新剥离 status —— 全部实现且有 e2e 覆盖 |
| 图14 主链 + 退款分支 | pay(0→1)、ship(1→2)、confirm(2→3)、申请退货(1/2→5)、审核通过(5→6 + 退款流水 + 库存回补)、审核拒绝(5→1) |
| 图1 双过滤器链 | `/admin/**` 与 `/portal/**` 各自 token 与过滤器链、401 统一返回 |
| README 端口/部署 | 5173/5175 开发、5174/5176 部署、8081/8082 后端、nginx 反代路径 |

---

## 建议的处理顺序

1. **文档先自洽**（A1、A2、A4、A5、B5、B7、D1、D2）：纯文档改动，十分钟的事
2. **定口径**（A3、A6、B3）：退货前置状态、库存模型（即扣 vs 预占）、是否加 `order.create_time`
3. **再决定要不要做**（B2/C2 MinIO 上传、C1/C3 关闭订单入口与自动关单）
