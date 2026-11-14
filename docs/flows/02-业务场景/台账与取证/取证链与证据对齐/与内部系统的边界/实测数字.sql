-- 库: qiqi_mall_e2e_b（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 18:53（e2e 分片 b 跑完「渠道·多平台接入」那批 spec 那一刻的快照；本文件 18:59 复核，数字未变）
-- 复现: 重跑那批 spec 再跑本文件 —— 条数随当次数据变，「一张平台单只翻成一张内部单 / 一次外呼一行 / 键只闸回传单据行 / 仓侧一次交互两行 / 闸在库上」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）

-- @@ 身份靠映射表翻译：平台单号在 channel_order_map 里翻成内部订单 id，翻出来的那张单照旧有自己的出生台账（4 行映射逐列）
select m.shop_id, m.platform_sn, m.order_id as mapped_order_id, o.order_sn, o.channel,
       (select count(*) from order_status_ledger l
         where l.order_id = o.id and l.reason = 'ORDER_CREATE') as order_create_rows
  from channel_order_map m
  join `order` o on o.id = m.order_id
 order by m.id;
-- => shop_id=1, platform_sn=QQ20261114160310296157, mapped_order_id=16, order_sn=QQ20261114160314733832, channel=MALL, order_create_rows=1
-- => shop_id=2, platform_sn=TB-E2E43380158-1, mapped_order_id=17, order_sn=QQ20261114160315377789, channel=TAOBAO, order_create_rows=1
-- => shop_id=2, platform_sn=TB-E2E43380158-2, mapped_order_id=18, order_sn=QQ20261114160315569963, channel=TAOBAO, order_create_rows=1
-- => shop_id=3, platform_sn=DY-E2E43380158-1, mapped_order_id=19, order_sn=QQ20261114160315608576, channel=DOUYIN, order_create_rows=1

-- @@ 平台侧一次外呼一行：成败都留、重试就多一行，第几次尝试写在幂等键与 attempt 列里
select id, ref_type, ref_id, attempt, result, reason, idem_key, latency_ms
  from channel_sync_log
 where action = 'SHIP_PUSH'
 order by ref_id, attempt, id;
-- => 包裹1: id=10, attempt=1, result=OK, reason=回传成功（OK）, idem_key=2:SHIP_PUSH:1:1, latency_ms=8
-- => 包裹2: id=11, attempt=1, result=FAIL, reason=HTTP 503：mock 平台返回 503, idem_key=3:SHIP_PUSH:2:1, latency_ms=3
-- => 包裹2: id=12, attempt=2, result=OK, reason=回传成功（OK）, idem_key=3:SHIP_PUSH:2:2, latency_ms=4

-- @@ 唯一键只闸「回传发货」的单据行：拉单的汇总行（OK）与单张平台单的明细行（SKIP）幂等键一律为空，不参与唯一约束
select action, result, count(*) as rows_total, count(idem_key) as rows_with_idem_key
  from channel_sync_log
 group by action, result
 order by action, result;
-- => PULL/OK: rows_total=6, rows_with_idem_key=0
-- => PULL/SKIP: rows_total=3, rows_with_idem_key=0
-- => SHIP_PUSH/OK: rows_total=2, rows_with_idem_key=2
-- => SHIP_PUSH/FAIL: rows_total=1, rows_with_idem_key=1

-- @@ 仓库侧一次交互两行（与平台侧「一次外呼一行」是两种粒度）：包裹 1 先记要发什么，再记回执，再两条单据回传
select id, delivery_id, direction, attempt_no, result, latency_ms, idem_key,
       length(request_payload) as request_chars, length(response_payload) as response_chars
  from wms_exchange_ledger
 where delivery_id = 1
 order by id;
-- => id=1, direction=REQ, attempt_no=1, result=NULL, latency_ms=NULL, idem_key=1:REQ:1, request_chars=73（要发什么）, response_chars=NULL（回执列留空）
-- => id=2, direction=ACK, attempt_no=1, result=SUCCESS, latency_ms=67, idem_key=1:ACK:1, request_chars=NULL, response_chars=32（回执报文）
-- => id=3, direction=ACK, attempt_no=2, result=SUCCESS, latency_ms=NULL, idem_key=1:ACK:2, request_chars=89（报文在请求列）, response_chars=NULL
-- => id=4, direction=ACK, attempt_no=3, result=SUCCESS, latency_ms=NULL, idem_key=1:ACK:3, request_chars=93, response_chars=NULL

-- @@ 两把闸的分工写在库上（uk_map 管拉单幂等 / uk_sync_idem 管回传幂等 / uk_idem 管仓库侧），且这三张台账一个外键都没有
select table_name, index_name, non_unique,
       group_concat(column_name order by seq_in_index separator ',') as cols
  from information_schema.statistics
 where table_schema = database()
   and table_name in ('channel_order_map','channel_sync_log','wms_exchange_ledger')
   and non_unique = 0
 group by table_name, index_name, non_unique
 order by table_name, index_name;
select count(*) as foreign_keys
  from information_schema.key_column_usage
 where table_schema = database() and referenced_table_name is not null
   and table_name in ('channel_order_map','channel_sync_log','wms_exchange_ledger');
-- => channel_order_map/uk_map(shop_id,platform_sn)、channel_sync_log/uk_sync_idem(idem_key)、wms_exchange_ledger/uk_idem(idem_key)：全部 non_unique=0
-- => 三张台账的外键数 foreign_keys=0
