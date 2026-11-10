# 实施方案：OMS 拆单/发货单体系

> 设计依据：docs/flows/04-状态机与修复/14-订单状态图(已升级) · docs/flows/22-拆单时序图 · docs/flows/23-发货单状态图 · docs/er/21-ER图-拆单合单
> 本方案是把上述四张图落成代码的唯一规格。冲突时先停下报告，不要自行改设计。
> 预估：1~2 天 ｜ 建议拆 4 个 commit，按序执行

---

## Commit 1：数据层（DDL + schema.sql 同步）

```sql
-- 新表1: 仓库(字典,先插2~3条种子:深圳仓/杭州仓/北京仓)
CREATE TABLE warehouse (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1
) COMMENT '仓库';

-- 新表2: 库存记录(SKU×仓库,分仓库存——拆单的前提)
CREATE TABLE inventory (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_sku_wh (sku_id, warehouse_id),
  KEY idx_wh (warehouse_id),
  FOREIGN KEY (sku_id) REFERENCES sku(id),
  FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
) COMMENT '分仓库存记录';

-- 新表3: 发货单(新聚合根)
CREATE TABLE delivery_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  delivery_sn VARCHAR(50) NOT NULL,          -- DL+时间戳+随机,同OrderSnGenerator风格
  order_id BIGINT NOT NULL,                  -- 父订单
  warehouse_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,         -- 0待下发1已下发2已拣货3已出库4运输中5已签收 9已取消
  receiver_name VARCHAR(50) NOT NULL,        -- 快照冗余(多包各自贴单)
  receiver_phone VARCHAR(20) NOT NULL,
  receiver_address VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_delivery_sn (delivery_sn),
  KEY idx_order (order_id),
  FOREIGN KEY (order_id) REFERENCES `order`(id) ON DELETE CASCADE,
  FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
) COMMENT '发货单';

-- 新表4: 发货单明细
CREATE TABLE delivery_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  delivery_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,             -- 拆分来源(溯源)
  product_sku_id BIGINT NOT NULL,
  quantity INT NOT NULL,                     -- 本发货单承担的数量
  KEY idx_delivery (delivery_id),
  FOREIGN KEY (delivery_id) REFERENCES delivery_order(id) ON DELETE CASCADE
) COMMENT '发货单明细';

-- 新表5: 物流信息(1:1回传)
CREATE TABLE delivery_logistics (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  delivery_id BIGINT NOT NULL,
  company VARCHAR(30) NOT NULL,
  track_no VARCHAR(50) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_delivery (delivery_id),
  UNIQUE KEY uk_track_no (track_no),
  FOREIGN KEY (delivery_id) REFERENCES delivery_order(id)
) COMMENT '物流信息(WMS回传)';

-- 旧表改1: order 加一列(拆单标记)
ALTER TABLE `order` ADD COLUMN split_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未拆(单仓直发)1已拆';

-- ⚠️ 明确不加: order.create_time(自动关单遗留项,另一批任务,勿顺手加)
```

**种子数据**：2~3 个仓库；给现有 SKU 补分仓库存（如 SKU1→深圳仓500/SKU2→杭州仓500/SKU3→北京仓300，保证"一单跨三仓"可演示）；data.sql 同步。

## Commit 2：拆单服务（核心逻辑）

**2a. 状态枚举扩展**：订单状态机加 `待审核`(在"已付款"之后)与 `待发货`。图14 的流转全部配守卫（已付款→待审核=OrderFacade提交；待审核→待发货=审核通过；待审核→已关闭=审核不通过+回补库存；待发货→已发货=全部子发货单≥已出库）。通用更新继续剥离 status。

**2b. 新增 `SplitOrderService`（oms 包，按 boundary-plan 包结构）**：
```java
public List<DeliveryOrder> split(Long orderId) {
    // 1. 守卫:订单status=已审核,且未拆过(split_status=0),且有明细
    // 2. 读明细 join inventory,按 warehouse_id 分组
    // 3. 每组生成一个 DeliveryOrder(status=待下发) + 明细(带order_item_id溯源)
    //    + 从订单复制收货三件套快照
    // 4. 单仓订单:只生成1个发货单(split_status保持0也可,或置1但只有一单——实现时二选一,注释说明)
    // 5. 全程一个事务;order.split_status=1
}
```

**2c. 审核接口**（守卫版）：
```java
POST /admin/orders/{id}/audit     // 已付款→待审核(守卫:仅status=1)
POST /admin/orders/{id}/audit-pass  // 待审核→待发货(守卫:仅待审核) → 内部触发SplitOrderService
POST /admin/orders/{id}/audit-reject // 待审核→已关闭(守卫:仅待审核) → 联动回补库存
```

**2d. 库存语义变更**：下单即扣的现状改为**扣"分仓库存"**——下单时无法确定仓，先扣"总池"（维持现状的 sku.stock 原子扣减不动），审核拆单后**锁定到仓**（inventory 扣减，同样原子 SQL `AND stock>=n`）。两层库存的规则：sku.stock = Σ inventory.stock（用对账思维保证）。若发现两层同步有竞态，停下报告。

## Commit 3：下发与回传（WMS mock + 状态机）

**3a. WMS Mock**：`wms/MockWmsService.java`——`dispatch(deliverySn)` 返回受理ACK（随机延迟）；`simulateCallback(deliverySn, event)` 手动触发回传（拣货/出库+随机运单号/揽收/签收）——**模拟回传用管理端按钮或 curl 触发，不是定时任务**（演示可控）。

**3b. 发货单接口**（守卫版，全部在 oms 包）：
```java
POST /admin/deliveries/{id}/dispatch      // 待下发→已下发,守卫:仅status=0,调WMS mock
POST /admin/deliveries/{id}/cancel        // 待下发→已取消,守卫:仅status=0,联动:回补inventory
POST /admin/deliveries/{id}/wms-callback  // WMS回传入口:拣货/出库(+运单号写delivery_logistics)/揽收/签收
                                          // 守卫:状态合法流转+幂等(同一回传重复推送只生效一次)
GET  /admin/deliveries?orderId=           // 按父订单查子发货单
GET  /portal/deliveries?orderId=          // 买家查自己订单的包裹及物流(归属校验:本人订单)
```

**3c. 父订单聚合规则**（图23黄框，实现在回传处理器里）：
每次发货单状态变更后重算父订单——全部子单≥已出库→订单status=已发货（此时为已发货的语义起点）；全部≥已签收→订单已完成（可选：也可等买家confirm，**二选一在实现注释里说明**，若选择自动则confirm接口保留用于手动场景）。部分出库不驱动订单状态（保持当前态）。

## Commit 4：前端 + e2e

**前端**：
- admin 订单详情抽屉加「发货单」区块：子单列表（各仓/各自状态/运单号）+ [下发][取消] 按钮（按状态显隐）+ [模拟WMS回传] 下拉（拣货/出库/签收——演示驱动器）
- admin 新增「仓库管理」页（字典 CRUD，简单）
- portal 我的订单：订单行展示「包裹」列表（各子单状态+运单号）

**e2e（场景5：拆单履约，新写一个用例）**：
```
管理员造跨仓商品(2 SKU分属2仓) → 买家下一单买2 SKU → 管理员审核通过
→ 断言:自动拆成2个发货单(各自正确仓库)
→ 逐个下发+模拟出库 → 断言:2个子单出库后订单才变已发货(部分发货中间态验证)
→ 回传签收全部 → 订单已完成
```

**回归**：场景1~4 全绿（下单链路动了状态机，必须回归）。

## 明确不做

- 拣货/波次/货位等 WMS 内部细节（mock 到"出库"为止）
- 电子面单、运费计算
- 智能分仓算法（仓库由 inventory 数据自然决定，不做"就近发货"优化）
- 合单（同一买家多单合并——一期只做拆，合单需要时间窗口规则，后置）
- order.create_time / 自动关单（另一遗留项，勿顺手做）

## 交付标准

- 4 个 commit 按序推送，各自可编译
- e2e：新场景5全绿 + 场景1~4回归全绿
- 遇到与图21/22/23/14冲突或文档未定义的细节（如两层库存竞态、单仓是否也生成发货单），**停下报告不要自行设计**
