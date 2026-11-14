-- 库: qiqi_mall_test（容器栈测试库）| 与 e2e 分片库、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 18:54（六笔演示单 + 一轮审核跑完之后的快照；本文件 18:59 复核，数字未变）
-- 复现: 重跑「订单·自动审核」那批 spec（或 scripts/reset-test-db.sh 重铺六笔演示单后再跑一轮）再跑本文件 ——
--       条数随当次数据变，「强对齐靠 run_id 同一条因果链 / 不推进状态就不写台账 / 冻结只留标与审计」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）

-- @@ 强对齐不是「序号刚好相同」，是同一条因果链：订单 15 第 1 轮的决策行与它推进出来的那行台账，逐列摆在一行里看
select d.id as decision_id, d.run_id, d.round_no, d.result, d.matched_policy_name, d.order_status_seq,
       l.id as ledger_id, l.ref_type, l.ref_id, l.reason as ledger_reason,
       cast(substring_index(l.idem_key, ':', -1) as unsigned) as ledger_seq,
       (l.ref_type = 'AUDIT_RUN' and l.ref_id = d.run_id) as strong_align
  from order_audit_decision d
  join order_status_ledger l on l.order_id = d.order_id
 where d.order_id = 15;
-- => decision_id=3, run_id=1, round_no=1, result=AUTO_PASS, matched_policy_name=淘宝-标准单, order_status_seq=1, ledger_id=1, ref_type=AUDIT_RUN, ref_id=1, ledger_reason=AUDIT_AUTO_PASS, ledger_seq=1, strong_align=1

-- @@ 三分档全库计数：强对齐靠 run_id=ref_id；弱对齐靠序号相同但不是本轮推进；都没有就是「未对齐」（本批 49 行全是直接落库的种子单，压根没有台账行可配）
select
  sum(exists (select 1 from order_status_ledger l
               where l.order_id = d.order_id and l.ref_type = 'AUDIT_RUN' and l.ref_id = d.run_id)) as strong_rows,
  sum(not exists (select 1 from order_status_ledger l
                   where l.order_id = d.order_id and l.ref_type = 'AUDIT_RUN' and l.ref_id = d.run_id)
      and exists (select 1 from order_status_ledger l
                   where l.order_id = d.order_id
                     and cast(substring_index(l.idem_key, ':', -1) as unsigned) = d.order_status_seq)) as weak_rows,
  sum(not exists (select 1 from order_status_ledger l
                   where l.order_id = d.order_id and l.ref_type = 'AUDIT_RUN' and l.ref_id = d.run_id)
      and not exists (select 1 from order_status_ledger l
                       where l.order_id = d.order_id
                         and cast(substring_index(l.idem_key, ':', -1) as unsigned) = d.order_status_seq)) as unaligned_rows,
  count(*) as decisions_total
  from order_audit_decision d;
-- => strong_rows=1, weak_rows=0, unaligned_rows=49, decisions_total=50

-- @@ 「每单每轮一行」而不是「每单一行」：同一批单连跑 8 轮就有 8 行决策，但只要那轮没推进状态，状态台账一行都不写
select o.id as order_id, o.order_sn, o.status, count(d.id) as decision_rows,
       group_concat(distinct d.result order by d.result) as results,
       count(distinct d.round_no) as rounds,
       (select count(*) from order_status_ledger l where l.order_id = o.id) as status_rows
  from `order` o
  left join order_audit_decision d on d.order_id = o.id
 where o.id in (1, 3, 15, 16, 17, 18, 19, 20)
 group by o.id, o.order_sn, o.status
 order by o.id;
-- => order_id=1, order_sn=QQ202611111001, status=7, decision_rows=8, results=NO_MATCH, rounds=8, status_rows=0
-- => order_id=3, order_sn=QQ202611111003, status=7, decision_rows=8, results=NO_MATCH, rounds=8, status_rows=0
-- => order_id=15, order_sn=QT-1001, status=8, decision_rows=1, results=AUTO_PASS, rounds=1, status_rows=1
-- => order_id=16, order_sn=QT-1002, status=7, decision_rows=8, results=MANUAL, rounds=8, status_rows=0
-- => order_id=17, order_sn=QT-1003, status=7, decision_rows=8, results=NO_MATCH, rounds=8, status_rows=0
-- => order_id=18, order_sn=QT-1004, status=7, decision_rows=8, results=BLOCKED, rounds=8, status_rows=0
-- => order_id=19, order_sn=QT-1005, status=7, decision_rows=1, results=FROZEN, rounds=1, status_rows=0
-- => order_id=20, order_sn=QT-1006, status=7, decision_rows=8, results=NO_MATCH, rounds=8, status_rows=0

-- @@ 冻结只留标与审计：决策 1 行（seq 还是 0）、FROZEN 标生效 1 行、AUDIT_FREEZE 审计 1 行、状态台账 0 行（状态没变就没有流转行）
select (select count(*) from order_audit_decision d where d.order_id = 19) as decision_rows,
       (select d.result from order_audit_decision d where d.order_id = 19) as decision_result,
       (select d.order_status_seq from order_audit_decision d where d.order_id = 19) as decision_seq,
       (select count(*) from order_audit_tag t where t.order_id = 19 and t.active = 1) as active_tag_rows,
       (select group_concat(t.tag_code) from order_audit_tag t where t.order_id = 19 and t.active = 1) as tag_code,
       (select count(*) from admin_audit_log a where a.order_id = 19) as audit_rows,
       (select group_concat(a.action) from admin_audit_log a where a.order_id = 19) as audit_action,
       (select count(*) from order_status_ledger l where l.order_id = 19) as status_rows;
-- => decision_rows=1, decision_result=FROZEN, decision_seq=0, active_tag_rows=1, tag_code=FROZEN, audit_rows=1, audit_action=AUDIT_FREEZE, status_rows=0
