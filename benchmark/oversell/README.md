# 优化项1：库存超卖 —— 复现报告（基线）

> 状态：**已修复（方案 v1 SQL 原子扣减）**，复现与验收记录如下
> 复现时间：2026-11-10 ｜ 修复时间：2026-11-10
> 被测接口：`POST /portal/orders`（创建订单，内含 check-then-act 扣库存）

## 1. 问题代码定位

`PortalOrderController.create()`（约 L83-88）：

```java
Sku sku = skuService.getById(input.getProductSkuId());   // ① 读
if (sku.getStock() == null || sku.getStock() < qty) {     // ② 检查
    throw new IllegalStateException("库存不足");
}
sku.setStock(sku.getStock() - qty);                       // ③ 用旧值计算
skuService.updateById(sku);                               // ④ 写回
```

根因：**check-then-act 竞态**——②③④ 三步之间无原子性保障，并发线程都读到同一旧值（getById 读到的 stock），各自减完后覆盖写回，后写覆盖先写。

## 2. 复现环境

| 项 | 值 |
|---|---|
| 被测 SKU | id=1（初始库存重置为 10） |
| 每单数量 | 1 |
| 正确预期 | 最多成功 10 单，失败 40/190 单提示"库存不足" |
| 客户端 | curl 并发（`&` 后台 + wait），脚本 `race.sh` |

## 3. 复现结果（两轮）

### 第一轮：50 并发抢 10 库存

| 指标 | 期望 | 实际 |
|---|---|---|
| 成功订单 | ≤10 | **49** |
| 接口返回失败 | 40 | **0** |
| 压测后 stock | 0 | 3（错乱中间值） |
| DB 净增销售件数 | 10 | **49** |

→ **超卖 39 件**；且 stock=3 这个值本身就证明覆盖写发生（49 次"成功"但最终值既不是 0 也不是 -39）。

### 第二轮：200 并发抢 10 库存

| 指标 | 期望 | 实际 |
|---|---|---|
| 成功订单 | ≤10 | **98** |
| 接口返回失败 | 190 | **0** |
| 压测后 stock | 0 | **0**（巧合归零） |
| DB 净增销售件数 | 10 | **98** |

→ **超卖 88 件**。注意 stock=0 是覆盖写碰巧归零的假象——**看库存字段判断有没有超卖不可靠，必须以"成交明细数"为准**（本轮净增 98 vs 库存 10）。

## 4. 复现结论

1. 超卖稳定复现，且并发越高超卖越多（50 并发→39 件，200 并发→88 件）
2. **接口层无任何失败反馈**（0 个"库存不足"），用户视角完全无感，问题隐蔽
3. stock 最终值不可信（第一轮=3 是覆盖写的残渣），**验收对账必须用订单明细数 vs 库存基准**

## 5. 下一步（未执行，待批准）

- 方案 v1：SQL 原子扣减 `UPDATE sku SET stock=stock-? WHERE id=? AND stock>=?`，影响行数=0 即库存不足
- 验收标准：同脚本重跑，成功订单 ≤10、净增销售=10、失败数=并发-10
- 对比指标：成功订单数、超卖件数、接口失败反馈率

---

## 6. 修复实施（方案 v1：SQL 原子扣减）

`SkuMapper` 新增原子 SQL，把「判断 + 减法」压进一条语句，由 MySQL 行锁保证串行：

```java
@Update("UPDATE sku SET stock = stock - #{qty} WHERE id = #{id} AND stock >= #{qty}")
int deductStock(@Param("id") Long id, @Param("qty") int qty);
```

`PortalOrderController.create()` 改为：

```java
// 原子扣减：影响行数=0 即库存不足（不再「读旧值→计算→写回」）
if (!skuService.deductStock(sku.getId(), qty)) {
    throw new IllegalStateException("库存不足：" + product.getName());
}
```

失败时抛异常触发 `@Transactional` 回滚，此前已扣的库存一并回退。

## 7. 修复后验收（同一脚本 `race.sh`）

| 轮次 | 并发 | 期望 | 实际 |
|---|---|---|---|
| 修复后 #1（200 并发抢 10） | 200 | 成功 ≤10，净增销售=10 | 成功 **10**，库存 **0**，净增销售 **10** |
| 修复后 #2（同上，复跑） | 200 | 同上 | 成功 **10**，失败 189，另 1 条死锁报错（见下） |

**超卖已消除**：200 并发下成功订单严格等于库存基准 10，成交明细净增 10 件，库存归零。

## 8. 修复后新发现（未处理，待决策）

200 并发里有 **1 条**请求返回的不是「库存不足」，而是数据库死锁：

```
MySQLTransactionRollbackException: Deadlock found when trying to get lock
```

- **不违反一致性**：该事务整体回滚，没有多卖，库存与明细仍然对得上。
- **但用户侧体验差**：接口把 SQL 原文透给了前端（`GlobalExceptionHandler` 直接回 `e.getMessage()`）。
- 可选处置（按成本排序）：
  1. 异常映射：把 `DeadlockLoserDataAccessException` / `MySQLTransactionRollbackException` 统一回「系统繁忙，请重试」（一行 handler，不改语义）
  2. 重试：对死锁做 1~3 次幂等重试（需保证重试不重复扣减，配合 `@Transactional` 新事务）
  3. 降低冲突：多 SKU 订单按 `sku_id` 升序加锁（本问题单 SKU 也出现，收益有限）

> 注：`race.sh` 的失败计数原按 `"库存不足"`（带结尾引号）grep，匹配不到实际文案「库存不足：<商品名>」，已修正为关键词匹配。

## 9. 死锁复现（v1 副作用验证，2026-11-10）

### 复现工具：`deadlock.sh`
与 race.sh（单SKU抢库存）不同，本脚本制造**锁顺序交叉**：
- 一半订单按 [SKU1→SKU2] 顺序下单，另一半按 [SKU2→SKU1]
- 两个并发事务以**相反顺序**请求相同两行资源 → 循环等待

### 结果（100 并发，双 SKU 各 1000 库存——库存充足排除库存不足干扰）

| 指标 | 结果 |
|---|---|
| 成功 | 46 |
| **死锁异常（原文透出）** | **53** |
| 其他失败 | 1 |

死锁触发率 **53%**——远高于 race.sh 的单行热点（200并发仅1次死锁），**多行交叉顺序是死锁的主催化剂**，稳定可复现。

### 死锁异常原文样例
```
MySQLTransactionRollbackException: Deadlock found when trying to get lock; try restarting transaction
```
（`GlobalExceptionHandler` 直接返回 `e.getMessage()`，SQL 原文透给前端）

### 结论
- 数据一致性完好（牺牲事务整体回滚，无超卖）
- 用户侧灾难：53% 的下单看到 SQL 报错原文
- → v1.5 治理（异常映射 + 幂等重试）的必要性已量化
