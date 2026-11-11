# e2e 专用库（shard）

E2E 要跑得快、又要互不污染，所以每个 Playwright worker 一套「库 + 后端」。

## 目录里有什么

这里**不放** schema / seed 的副本：`sql/testdb/01-schema.sql` 与 `02-seed.sql` 是唯一事实来源，
`scripts/reset-e2e-db.sh <库名>` 会把这两个文件里的库名 `qiqi_mall_test` 替换成目标库再灌进去。

这样避免「两份 DDL 各自漂移」——改表结构只改 `sql/schema.sql` + `sql/testdb/01-schema.sql`。

## 库清单

| 库 | 用途 | 后端端口 |
|---|---|---|
| `qiqi_mall_e2e_a` | Playwright shard A | 8091 |
| `qiqi_mall_e2e_b` | Playwright shard B | 8092 |

dev 库 `qiqi_mall`（8081）与容器栈测试库 `qiqi_mall_test`（18080）**完全不受影响**。

## 用法

```bash
./scripts/reset-e2e-db.sh qiqi_mall_e2e_a     # 重置单个库（幂等）
./scripts/reset-e2e-db.sh --all               # 重置 a + b
cd e2e && npm run e2e:reset                   # 同上，走 npm script
```

重置语义：整库重建 + 重灌种子，跑完打印每表行数摘要。
