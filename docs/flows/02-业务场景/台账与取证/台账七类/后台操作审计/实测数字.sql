-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑 台账与取证/台账七类 那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 一次请求一行：行数 = 去重键数；请求号数 ≤ 行数（一次请求写多行时才少）
select count(*) as rows_total,
       count(distinct request_id) as request_ids,
       count(distinct idem_key) as idem_keys
  from admin_audit_log;
-- => rows_total=83, request_ids=79, idem_keys=83

-- @@ 去重键只能由请求号派生：每一行的 idem_key 前缀都正好等于 request_id
select count(*) as rows_total,
       sum(substring_index(idem_key, ':', 1) = request_id) as prefix_is_request_id
  from admin_audit_log;
-- => rows_total=83, prefix_is_request_id=83

-- @@ 同一个目标连点三次 = 三行，三个请求号各不相同（第二次、第三次失败本身也是证据，照样留行）
select action, target_type, target_id,
       count(*) as rows_cnt,
       count(distinct request_id) as request_ids,
       group_concat(substring_index(idem_key, ':', -1) order by id separator ',') as seqs
  from admin_audit_log
 group by action, target_type, target_id
having rows_cnt > 1
 order by rows_cnt desc, action
 limit 3;
-- => action=SKU_STOCK_CHANGE, target_type=SKU, target_id=1, rows_cnt=12, request_ids=12, seqs=1,1,1,1,1,1,1,1,1,1,1,1
-- => action=AUDIT_RUN, target_type=ORDER, target_id=NULL, rows_cnt=7, request_ids=7, seqs=1,2,2,1,2,1,2
-- => action=AUDIT_FREEZE, target_type=ORDER, target_id=53, rows_cnt=4, request_ids=4, seqs=1,1,1,1

-- @@ 一次请求确实要写两行时，用同一个前缀的两个段号（:1 / :2），不会互相撞键
select request_id, count(*) as rows_cnt,
       group_concat(substring_index(idem_key, ':', -1) order by id separator ',') as seqs
  from admin_audit_log
 group by request_id
having rows_cnt > 1
 order by request_id;
-- => request_id=7ebe9ebc-3f2b-48f5-a17d-d796c96fb0b7, rows_cnt=2, seqs=1,2
-- => request_id=96094d53-8a5e-41e8-9f78-3e876941af90, rows_cnt=2, seqs=1,2
-- => request_id=f8eb04fb-94d3-43bf-aafe-743e34685e4d, rows_cnt=2, seqs=1,2
-- => request_id=f98fb960-6075-44b3-8d64-7d40cee1dd53, rows_cnt=2, seqs=1,2

-- @@ 目标被删掉，单号快照还在：删单审计行一条不少，订单主表一行不剩，快照照样读得出是哪一单
select count(*) as delete_audit_rows,
       sum(a.target_sn is not null) as with_target_sn,
       sum(not exists (select 1 from `order` o where o.id = a.order_id)) as order_gone
  from admin_audit_log a
 where a.action = 'ORDER_DELETE';
-- => delete_audit_rows=18, with_target_sn=18, order_gone=18
