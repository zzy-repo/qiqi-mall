下线留存：历史手写迁移脚本（只作留档，禁止执行）
================================================================================

这些文件曾经是"加表/加列"的唯一手段：11 个手写 migrate 脚本 × 4 个库（qiqi_mall /
qiqi_mall_test / qiqi_mall_e2e_a / qiqi_mall_e2e_b）靠人工同步。2026-11-13 起全部
下线，改由 Flyway 管理：

    唯一事实来源  qiqi-mall-server/src/main/resources/db/migration/V*.sql
    已有库        已 flyway baseline 到版本 1（历史表里是一条 type=BASELINE 的 V1 记录）
    空库          flyway migrate 从 V1 全量建出 39 表 + 1 视图 + channel_shop 参考数据

为什么整批下线、而不是保留成 V2..V12
--------------------------------------------------------------------------------
1) 四个库结构早已一致，历史增量对任何环境都不再有意义（没有"老版本库"要升级）。
2) 这些脚本**不能**直接当 Flyway 迁移跑：
   · migrate-ai-api-key.sql / migrate-image-relative-url.sql / migrate-ledger-idem-shard.sql /
     migrate-sku-bucket.sql 里硬编码了 `qiqi_mall.` 与 `qiqi_mall_test.` 的**双库写入**
     —— 在 Flyway 里执行会把基线建好的库再往别的库写一遍；
   · 混着一次性 DML 回填/清理（UPDATE/DELETE）与 7 个幂等存储过程；
   · migrate-ledger-verify.sql 根本不是迁移，是一份只读自检报表，而且断言已过期
     （写死"应为 30 表"，实际 39 表），留着会被人当检查脚本跑出误报。
3) 代价（知情接受）：从此无法用 Flyway 重建"历史版本"的库。我们不需要——e2e 只要最新结构。

本目录文件清单
--------------------------------------------------------------------------------
migrate-ai-api-key.sql         ai_api_key 表
migrate-channel.sql            channel_shop / channel_order_map / channel_sync_log + 3 家店铺种子
migrate-image-relative-url.sql 商品图片从绝对地址改相对路径（数据回填 + 清理坏图）
migrate-ledger.sql             5 张台账表 + zz_ledger_add_col/idx/ck 三个幂等过程
migrate-ledger-idem-shard.sql  幂等键前缀分片回填（qiqi_prefix_idem_keys 过程）
migrate-ledger-verify.sql      台账迁移后的只读自检报表（已过期，勿用）
migrate-ledger-view.sql        v_biz_status_timeline 视图
migrate-optimistic-lock.sql    sku / product 加 version 列（qiqi_add_version_column 过程）
migrate-order-audit.sql        6 张审核表 + order 加 11 列（zz_audit_add_col 过程）
migrate-sku-bucket.sql         sku_bucket 分桶表 + 回填（qiqi_setup_sku_bucket 过程）
migrate-stock-ledger.sql       stock_ledger 表
01-schema.sql                  测试库全量 DDL（手抄副本，已由 db/migration/V1__baseline.sql 取代）

以后要加表/加列怎么办
--------------------------------------------------------------------------------
写一个新的 qiqi-mall-server/src/main/resources/db/migration/V2__xxx.sql（V1 永不修改），
然后刷新随库文档：

    ./scripts/gen-schema-dump.sh            # 重新生成 sql/schema.sql（docker initdb 用）
    ./scripts/gen-schema-dump.sh --verify   # 自校验：四类指纹与源库逐项比对

绝不要再写 "CREATE PROCEDURE zz_xxx_add_col" 这种手写幂等块——那是这套体系被换掉的直接原因。

================================================================================
铁律：含 `USE <库名>` 或建库语句的 SQL，不许被直接 source
================================================================================
规矩：**库名必须由调用方传给 mysql，文件里不写库名。**

    ✅  mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/xxx.sql
    ✅  docker-compose 的 initdb 会自动按 MYSQL_DATABASE 选库，文件里不需要 USE
    ❌  文件第一行写 `USE qiqi_mall_test;` 然后 `mysql < sql/xxx.sql`
    ❌  调用方传了库名，但文件里的 USE 又把目标库覆盖掉（最阴的一种：看调用方代码
        完全正常，事故却出在文件内部）

为什么这条是铁律（2026-11-13 一晚栽了两次，两次都不是"手滑"，是文件结构本身有雷）
--------------------------------------------------------------------------------
事故①（schema 文件带 `USE` + `DROP TABLE`）：
    sql/schema.sql 与 sql/testdb/01-schema.sql 开头都有 `USE <库名>;` 后面跟逐表
    `DROP TABLE IF EXISTS`。谁在自己的环境里 source 一次（哪怕只是想看结构），
    删掉的就是文件里写死那个库的表，而不是他连的那个库。当晚的重建脚本正是靠这份
    手抄副本工作，新加的表忘了同步进去 → qiqi_mall_test 缺 3 张 channel 表。
    现在两份 schema 都已下线：结构唯一来源是 db/migration/V1__baseline.sql，
    sql/schema.sql 由 scripts/gen-schema-dump.sh 生成（同样不带 USE）。

事故②（种子文件带 `USE`，把 data 灌进了别的库）：
    sql/testdb/02-seed.sql 第一行是 `USE qiqi_mall_test;`。改造中有人拿一份"把 DB_NAME
    换成 zz_init_check 的 init 脚本副本"去重置临时库，脚本里明明写着 zz_init_check，
    seed 却按自己写死的 USE 灌进了真库 qiqi_mall_test —— 29 张业务表被 TRUNCATE 重灌、
    admin 口令被打回种子弱口令。调用方代码一行都没错，错在"文件能自己选库"。
    现在 02-seed.sql / data.sql 里已无任何 USE 与建库语句，两个脚本都改成
    `mysql_exec "$DB" < "$SEED"` 显式传库；生成器 scripts/fetch-seed-data.mjs 也不再写 USE
    （否则下次重生成会把雷埋回去）。

自查命令（改完任何 SQL/脚本都跑一遍）：
    grep -nE '^USE |CREATE DATABASE' sql/*.sql sql/testdb/*.sql   # 期望 0 命中

以后写新的数据/结构脚本：
  1) 文件里只写 DML/DDL，不写库名、不写建库语句；
  2) 脚本里显式 `mysql <库名>` 或 `mysql --database=<库名>`；
  3) 需要"重建整库"时 DROP DATABASE + CREATE DATABASE，而不是逐表 DROP TABLE
     （逐表 DROP 会留下 flyway_schema_history，Flyway 就会认为 V1 已应用、什么都不做）。
