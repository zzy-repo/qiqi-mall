-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑 台账与取证/台账七类 那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 一头包裹一路走下来都在台账里：行数 / 包裹数 / 订单数，每行都带包裹单号快照
select count(*) as rows_total,
       count(distinct delivery_id) as deliveries,
       count(distinct order_id) as orders,
       sum(delivery_sn is not null) as with_delivery_sn
  from delivery_status_ledger;
-- => rows_total=48, deliveries=17, orders=17, with_delivery_sn=48

-- @@ 订单被删除时包裹被真的删掉：这批订单在主表里已不存在，台账行数一行没少、单号快照还在
select l.order_id,
       count(*) as ledger_rows,
       count(distinct l.delivery_id) as deliveries,
       max(l.delivery_sn) as delivery_sn_snapshot,
       (select count(*) from `order` o where o.id = l.order_id) as order_exists,
       (select count(*) from delivery_order d where d.order_id = l.order_id) as delivery_exists
  from delivery_status_ledger l
 group by l.order_id
having order_exists = 0 and ledger_rows >= 3
 order by ledger_rows desc, l.order_id
 limit 3;
-- => order_id=26, ledger_rows=3, deliveries=1, delivery_sn_snapshot=DL20261114153244525103, order_exists=0, delivery_exists=0
-- => order_id=30, ledger_rows=3, deliveries=1, delivery_sn_snapshot=DL20261114153428641973, order_exists=0, delivery_exists=0
-- => order_id=31, ledger_rows=3, deliveries=1, delivery_sn_snapshot=DL20261114153501219729, order_exists=0, delivery_exists=0

-- @@ 包裹本身被物理删除后，这批台账行仍然按 order_id 反查得到（证据不跟着包裹走）
select count(*) as rows_total,
       count(distinct l.delivery_id) as deliveries,
       count(distinct l.order_id) as orders
  from delivery_status_ledger l
 where not exists (select 1 from delivery_order d where d.id = l.delivery_id);
-- => rows_total=24, deliveries=10, orders=10

-- @@ 同一个事件仓库重复推两次只留一行：行数 = 去重键数（键 = 包裹ID:流转序号）
select count(*) as rows_total,
       count(distinct idem_key) as idem_keys,
       count(distinct event_key) as event_keys
  from delivery_status_ledger;
-- => rows_total=48, idem_keys=48, event_keys=0
