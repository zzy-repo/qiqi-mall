-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑 台账与取证/台账七类 那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 一张申请走完全程就几行，且每行都额外记着订单号（删单后还能按订单反查）
select count(*) as rows_total,
       count(distinct return_apply_id) as applies,
       count(distinct order_id) as orders,
       sum(order_id is not null) as with_order_id
  from return_status_ledger;
-- => rows_total=16, applies=9, orders=9, with_order_id=16

-- @@ 审核拒绝那一行 + 订单回聚合状态那一行：两张台账在同一单上对得起来
select r.return_apply_id, r.order_id,
       r.reason as return_reason, r.to_status as return_status,
       l.reason as order_reason, l.from_status as order_from, l.to_status as order_to
  from return_status_ledger r
  join order_status_ledger l on l.id = (
         select min(l2.id) from order_status_ledger l2
          where l2.order_id = r.order_id and l2.reason = 'DELIVERY_AGGREGATE' and l2.id > r.id)
 where r.reason = 'RETURN_REJECT'
 order by r.id;
-- => return_apply_id=3, order_id=32, return_reason=RETURN_REJECT, return_status=2, order_reason=DELIVERY_AGGREGATE, order_from=8, order_to=2
-- => return_apply_id=6, order_id=38, return_reason=RETURN_REJECT, return_status=2, order_reason=DELIVERY_AGGREGATE, order_from=8, order_to=2
-- => return_apply_id=8, order_id=40, return_reason=RETURN_REJECT, return_status=2, order_reason=DELIVERY_AGGREGATE, order_from=8, order_to=2

-- @@ 同一笔申请被两个人同时点，也只有一条通过：通过行数 = 申请数（不会出现两条通过）
select count(*) as approved_rows,
       count(distinct return_apply_id) as applies,
       (select count(*) from (select 1 from return_status_ledger where to_status = 1
          group by return_apply_id having count(*) > 1) t) as dup_approved
  from return_status_ledger
 where to_status = 1;
-- => approved_rows=4, applies=4, dup_approved=0

-- @@ 申请行连订单一起被物理删掉之后，台账那几行仍在：order_id 列还指着原单，售后流水照样按订单查得到
select l.id, l.return_apply_id, l.order_id, l.reason, l.to_status,
       (select count(*) from return_apply r where r.id = l.return_apply_id) as apply_exists,
       (select count(*) from `order` o where o.id = l.order_id) as order_exists
  from return_status_ledger l
 where not exists (select 1 from return_apply r where r.id = l.return_apply_id);
-- => id=1, return_apply_id=1, order_id=30, reason=RETURN_CREATE, to_status=0, apply_exists=0, order_exists=0
-- => id=2, return_apply_id=2, order_id=31, reason=RETURN_CREATE, to_status=0, apply_exists=0, order_exists=0
-- => id=3, return_apply_id=2, order_id=31, reason=RETURN_APPROVE, to_status=1, apply_exists=0, order_exists=0
-- => id=4, return_apply_id=3, order_id=32, reason=RETURN_CREATE, to_status=0, apply_exists=0, order_exists=0
-- => id=5, return_apply_id=3, order_id=32, reason=RETURN_REJECT, to_status=2, apply_exists=0, order_exists=0
-- => id=6, return_apply_id=4, order_id=35, reason=RETURN_CREATE, to_status=0, apply_exists=0, order_exists=0
-- => id=7, return_apply_id=4, order_id=35, reason=RETURN_APPROVE, to_status=1, apply_exists=0, order_exists=0
-- => id=16, return_apply_id=9, order_id=44, reason=RETURN_CREATE, to_status=0, apply_exists=0, order_exists=0
