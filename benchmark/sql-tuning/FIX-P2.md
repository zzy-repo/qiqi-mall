# 修复方案：P2 复合索引（C端订单列表查询）

> 背景：P1 分页已落地（`Page` + 主键倒序），实测 C 端接口 30~170ms 已达标。
> P2 解决的是**EXPLAIN 里仍可见的隐患**：`idx_member` 过滤后 rows≈20 万、`Extra: Backward index scan`——
> 主键倒序读本身不慢，但 member 的段内没有 payment_time 排序支撑，一旦排序键换成 payment_time 或加时间范围筛选，就会退化成 filesort。

## 问题查询（C端"我的订单"）

```sql
SELECT * FROM `order` WHERE member_id = ? ORDER BY id DESC LIMIT 10
```

现状：走 `idx_member(member_id)`，ref 定位段，按主键倒序扫描段内行。当前 50 万行下实测 30~100ms，可用。

## 改动：一条 DDL

```sql
ALTER TABLE `order` ADD INDEX idx_member_time (member_id, payment_time);
```

同时把该 DDL 写入 `sql/schema.sql` 的 order 表定义处（保持建表脚本与库一致）。

## 排序键的取舍说明（实施时注意）

方案里有一个**两难**：
- `ORDER BY id DESC`（现状）：走主键倒序，现在就快，但与 idx_member_time 无关
- `ORDER BY payment_time DESC`：业务语义等价（下单时间序），但**需要 idx_member_time 段内有序才能免 filesort**

**实施步骤**：
1. 先加索引 `idx_member_time`
2. 把 C 端 `list()` 的 `orderByDesc(Order::getId)` 改为 `orderByDesc(Order::getPaymentTime)`
3. EXPLAIN 验证：`key=idx_member_time`、`Extra` 无 filesort
4. 若 EXPLAIN 显示仍走主键（优化器判断支付时间为空的行会跳段），保留 id 排序亦可——两者语义差异仅是 payment_time 为 NULL（未支付订单）的排位，此时按 id 排也是时间近似序，可接受

## 边界与不做的事

- **B 端不加此索引**：B 端列表无 member_id 过滤（看全量），idx_member_time 对它无用；B 端 status 筛选走 idx_status（rows 14 万，暂可接受，等出现真实慢查询再处理——复现优先）
- **不做 payment_record 的 order_sn 外键**（审计 B4 已确认索引即可）
- **不动 idx_status**：单列保留，status 等值筛选有它兜底

## 一句话给实施会话

加一条 `idx_member_time(member_id, payment_time)` 索引（代码 + schema.sql），C 端 list() 排序键改为 payment_time，EXPLAIN 确认无 filesort 即完成。遇到 EXPLAIN 不走新索引，停下来报告，不要自行加 force index。
