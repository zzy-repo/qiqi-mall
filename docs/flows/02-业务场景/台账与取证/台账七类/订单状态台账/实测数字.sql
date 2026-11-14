-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑 台账与取证/台账七类 那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 一张单的状态串：status_seq=N 的单，台账就是 N+1 行（出生行 + N 次迁移），去重键后缀 0..N 全覆盖
select o.id as order_id, o.status, o.status_seq,
       (select count(*) from order_status_ledger l where l.order_id = o.id) as ledger_rows,
       (select group_concat(l.idem_key order by l.id separator ' ') from order_status_ledger l
         where l.order_id = o.id) as idem_keys
  from `order` o
 where o.status_seq = (select max(status_seq) from `order`)
   and exists (select 1 from order_status_ledger l where l.order_id = o.id)
 order by o.id
 limit 3;
-- => order_id=37, status=6, status_seq=5, ledger_rows=6, idem_keys=37:0 37:1 37:2 37:3 37:4 37:5
-- => order_id=38, status=2, status_seq=5, ledger_rows=6, idem_keys=38:0 38:1 38:2 38:3 38:4 38:5
-- => order_id=39, status=6, status_seq=5, ledger_rows=6, idem_keys=39:0 39:1 39:2 39:3 39:4 39:5

-- @@ 全量核对：每个有台账的订单，行数都等于 status_seq + 1，例外 0
select count(*) as orders_with_ledger,
       sum(case when (select count(*) from order_status_ledger l where l.order_id = o.id)
                     <> o.status_seq + 1 then 1 else 0 end) as exceptions
  from `order` o
 where exists (select 1 from order_status_ledger l where l.order_id = o.id);
-- => orders_with_ledger=11, exceptions=0

-- @@ 重复推送不多记：行数 = 去重键数（同一序号只认第一次；event_key 这一版全空，去重全靠 idem_key）
select count(*) as rows_total,
       count(distinct idem_key) as idem_keys,
       count(distinct event_key) as event_keys
  from order_status_ledger;
-- => rows_total=103, idem_keys=103, event_keys=0

-- @@ 一行只回答「谁干的」：操作人类型与操作人都落在行上（不是回头去查业务表）
select operator_type, count(*) as rows_cnt, count(distinct coalesce(operator_name, '')) as operators
  from order_status_ledger
 group by operator_type
 order by operator_type;
-- => operator_type=ADMIN, rows_cnt=53, operators=1
-- => operator_type=MEMBER, rows_cnt=50, operators=20

-- @@ 订单被物理删掉，状态台账照样在：这些行反查不到订单，但每一行都还读得出来
select count(*) as rows_cnt, count(distinct l.order_id) as orders
  from order_status_ledger l
 where not exists (select 1 from `order` o where o.id = l.order_id);
-- => rows_cnt=56, orders=20
