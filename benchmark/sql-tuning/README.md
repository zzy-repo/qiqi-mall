# MySQL 慢查询调优实战（复现阶段，未修复）

> 方法论：复现优先——先拿到"慢"的证据并定位根因，再动手优化
> 数据规模：order 50 万行 / order_item 50 万行（存储过程 gen_orders 生成，39 秒）
> 复现时间：2026-11-10

## 一、发现的问题（三个，全部真实复现）

### P1【代码缺陷】C端订单列表无分页，全量返回 10 万行 ⭐最严重
- `GET /portal/orders` 实现：`orderService.list(eq(memberId))` —— **没有分页**，member 1 返回 **100,181 条 / 23.6MB** 响应体
- 接口耗时 **3.4 秒**（网络传输+序列化为主）；并发 300 请求下成功率仅 **7/300**（后端序列化与带宽打满，雪崩）
- 讽刺点：前端传的 page/size 参数**被接口完全忽略**
- 根因：代码缺陷（非索引问题），SQL 本身毫秒级，慢在"把 10 万行序列化成 23MB JSON 再传输"

### P2【索引缺失】订单查询 Using filesort
```
EXPLAIN: type=ref, key=idx_member, rows=195332, Extra=Using filesort
```
- `WHERE member_id=? ORDER BY payment_time DESC` 只能用 idx_member 过滤，**排序在 10 万行上 filesort**
- SQL 层 0.2 秒（勉强可用），但这是 filesort 上限——数据再翻倍线性恶化

### P3【聚合慢】商品销量报表 join 3 表 1.8 秒
- `order_item join order join product` + GROUP BY：order_item 上 12 万行扫描，product 全表（6行小表但type=ALL）
- 后台报表场景 1.8 秒，无法接受；且随订单增长线性恶化

## 二、环境准备（已完成）

- 慢查询日志：已开启 `slow_query_log=ON, long_query_time=0.3`
- 索引现状：order 表有 idx_member / idx_status（单列），无复合索引
- 压测方法：curl 并发 + MySQL EXPLAIN + SQL_NO_CACHE 真实计时

## 三、修复方案（待实施）

| 问题 | 修复 | 预期 |
|---|---|---|
| P1 | `list()` 改 MyBatis-Plus 分页（Page<Order>），接口只返回当前页 | 3.4s → <100ms，响应 23MB → ~5KB |
| P2 | 复合索引 `(member_id, status, payment_time)` 或 `(member_id, payment_time)` | filesort 消失，EXPLAIN Extra 干净 |
| P3 | order_item 加 `(product_id, product_quantity)` 或预聚合表；报表 SQL 改写 | 1.8s → <300ms |

## 四、验收标准（修复后同法复测）

1. EXPLAIN：P2 的 Extra 无 filesort，rows 扫描 <1000
2. 接口：单次 <200ms；300 并发成功率 >99%
3. 慢日志：long_query_time=0.3 下，业务查询零命中
4. 量化对比表：优化前/后 的 EXPLAIN rows、接口耗时、QPS 三组数据
