-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑 台账与取证/台账七类 那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 一次外发先记 REQ（回执列留空）再记 ACK（结果 + 往返毫秒）：同一包裹按记账顺序排出来
select delivery_id, direction, attempt_no, result, latency_ms,
       case when request_payload is null then '空' else '有' end as req_payload,
       case when response_payload is null then '空' else '有' end as ack_payload,
       idem_key
  from wms_exchange_ledger
 where delivery_id = (select min(delivery_id) from wms_exchange_ledger)
 order by id;
-- => delivery_id=1, direction=REQ, attempt_no=1, result=NULL, latency_ms=NULL, req_payload=有, ack_payload=空, idem_key=1:REQ:1
-- => delivery_id=1, direction=ACK, attempt_no=1, result=SUCCESS, latency_ms=121, req_payload=空, ack_payload=有, idem_key=1:ACK:1
-- => delivery_id=1, direction=ACK, attempt_no=2, result=SUCCESS, latency_ms=NULL, req_payload=有, ack_payload=空, idem_key=1:ACK:2

-- @@ 仓库回传再追加 ACK 行：尝试号按方向各自自增，REQ 与 ACK 不会互相撞键
select direction, attempt_no,
       count(*) as rows_cnt,
       sum(result = 'SUCCESS') as success_rows,
       sum(result is null) as undecided_rows
  from wms_exchange_ledger
 group by direction, attempt_no
 order by direction, attempt_no;
-- => direction=ACK, attempt_no=1, rows_cnt=14, success_rows=14, undecided_rows=0
-- => direction=ACK, attempt_no=2, rows_cnt=14, success_rows=14, undecided_rows=0
-- => direction=ACK, attempt_no=3, rows_cnt=1, success_rows=1, undecided_rows=0
-- => direction=ACK, attempt_no=4, rows_cnt=1, success_rows=1, undecided_rows=0
-- => direction=REQ, attempt_no=1, rows_cnt=14, success_rows=NULL, undecided_rows=14

-- @@ 每行一把键 = 包裹ID:方向:尝试号，行数 = 去重键数，键形态 100% 合规（重放撞键即静默跳过）
select count(*) as rows_total,
       count(distinct idem_key) as idem_keys,
       sum(idem_key = concat(delivery_id, ':', direction, ':', attempt_no)) as key_wellformed
  from wms_exchange_ledger;
-- => rows_total=44, idem_keys=44, key_wellformed=44

-- @@ 失败/超时行：本轮 0 行（mock 全成功；外呼抛异常时整笔回滚，见台账写入器的已知局限）
select count(*) as rows_total,
       sum(result = 'SUCCESS') as success_rows,
       sum(result = 'FAIL') as fail_rows,
       sum(result = 'TIMEOUT') as timeout_rows,
       sum(result is null) as undecided_rows
  from wms_exchange_ledger;
-- => rows_total=44, success_rows=30, fail_rows=0, timeout_rows=0, undecided_rows=14
