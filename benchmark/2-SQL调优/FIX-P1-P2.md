# 修复方案：P1 订单列表分页 + P2 复合索引（sql-tuning 报告第三节实施版）

> 依据：`benchmark/sql-tuning/README.md`（复现已完成，本方案是第三节的落地版）
> 工作量：约 1 小时 ｜ 改动面：1 个配置类 + 1 个接口 + 前端 2 文件 + 1 条 DDL
> ⚠️ 与 MinIO 实装（进行中）无文件冲突，可并行

## 变更清单（按提交顺序）

### Commit 1：P2 复合索引（一条 DDL，先行——P1 的分页要靠它受益）

```sql
ALTER TABLE `order` ADD INDEX idx_member_time (member_id, payment_time);
-- 写入 sql/schema.sql 的 order 表定义处（保持建表脚本与库结构一致），并同步在现有容器上执行
```

**为什么不加 status 进索引**：查询 WHERE 是 `member_id=? ORDER BY payment_time`，status 只是筛选不是排序键，放进去对免排序无贡献（范围/IN 之后列的有序性被切碎）。
**验证**：
```sql
EXPLAIN SELECT id FROM `order` WHERE member_id=1 ORDER BY payment_time DESC LIMIT 10;
-- 期望: type=ref, key=idx_member_time, rows≈10, Extra 无 filesort（修复前 rows≈195332 + Using filesort）
```

### Commit 2：P1 后端分页（配置 + 接口）

**2a. 新增 `config/MybatisPlusConfig.java`**（⚠️ 前置条件：不配这个 Page 会静默全量查询）：
```java
@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor i = new MybatisPlusInterceptor();
        PaginationInnerInterceptor p = new PaginationInnerInterceptor(DbType.MYSQL);
        p.setMaxLimit(100L);            // 防恶意大size
        i.addInnerInterceptor(p);
        return i;
    }
}
```

**2b. `PortalOrderController.list()` 改造**：
```java
@GetMapping
public Result<Page<Order>> list(@RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "10") long size) {
    if (size < 1 || size > 50) size = 10;   // size上限
    Page<Order> p = orderService.page(new Page<>(page, size),
            new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, CurrentUser.id())
                .orderByDesc(Order::getId));   // 按主键倒序:主键索引天然有序,免filesort,语义=新订单在前
    return Result.ok(p);
}
```
- 返回类型 `List<Order>` → `Page<Order>`（含 records/total/pages），**破坏性变更**
- 排序选 id 不选 payment_time：主键索引免 filesort 且语义等价（下单即有时间序）；避免依赖 idx_member_time 才能排序

### Commit 3：P1 前端适配

**3a. `portal-web/src/api/order.js`**：
```js
export const listOrders = (page = 1, size = 10) =>
  request.get('/orders', { params: { page, size } })
```

**3b. `portal-web/src/views/order/index.vue`**：
- `el-pagination`：`v-model:current-page` + `:page-size` + `:total`（来自响应 total）+ `@current-change=load`
- 响应解构：`res.data.records` → 表格数据；`res.data.total` → 分页总数（**原 `d.data` 是数组，模板 v-for 与 Promise.all 里的 listOrders 调用都要适配 .records**）
- 售后状态联表逻辑保持不变（listMyReturns 不受影响）

## 验收（对照 benchmark/sql-tuning/README 第三节，四项全做）

1. **EXPLAIN**：上面 Commit 1 的验证 SQL，filesort 消失、rows <1000
2. **单接口**：member 1（10 万行）`GET /portal/orders?page=1&size=10` 耗时 **<200ms**、响应 <20KB（修复前 3.4s/23.6MB）
3. **并发**：300 请求（同 sql-tuning 压测法）成功率 **>99%**（修复前 7/300）
4. **慢日志**：`long_query_time=0.3` 下业务查询零命中（修复前无慢SQL,此项为回归确认）
5. **e2e 回归**：场景 2/3/4 全绿（订单页返回结构变更可能需小改 e2e 选择器）

## 完成后

- 修复前后数据填入 `benchmark/sql-tuning/README.md` 第四节对比表
- `docs/er/12-ER图-订单域` 的订单实体补充索引说明（可不画，SQL 为准）
- commit message 引用：`fix: P1 订单列表分页(23MB→KB级) + P2 复合索引消除filesort (sql-tuning#P1#P2)`

## 明确不做

- 深分页游标化（当前用户翻不到500页,观察项）
- 总数 COUNT 缓存/关掉（先压测,有证据再优化）
- payment_record 相关改动（另一会话职责）
