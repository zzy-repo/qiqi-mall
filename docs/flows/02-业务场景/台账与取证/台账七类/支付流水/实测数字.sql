-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑 台账与取证/台账七类 那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 两道闸都在库上：uk_channel_trade(渠道,渠道流水号) 挡对方重复通知，uk_idem(去重键) 挡自己连点重试
select index_name,
       group_concat(column_name order by seq_in_index separator ',') as cols,
       non_unique as non_unique
  from information_schema.statistics
 where table_schema = database() and table_name = 'payment_record' and non_unique = 0
 group by index_name, non_unique
 order by index_name;
-- => INDEX_NAME=PRIMARY, cols=id, non_unique=0
-- => INDEX_NAME=uk_channel_trade, cols=channel,trade_no, non_unique=0
-- => INDEX_NAME=uk_idem, cols=idem_key, non_unique=0

-- @@ 渠道流水号重复 0：行数 = (渠道,渠道流水号) 组合数；带去重键的行 = 去重键数
select count(*) as rows_total,
       count(distinct channel, trade_no) as channel_trades,
       count(idem_key) as rows_with_idem,
       count(distinct idem_key) as idem_keys
  from payment_record;
-- => rows_total=27, channel_trades=27, rows_with_idem=13, idem_keys=13

-- @@ 退款那条必须指回原支付流水：每一笔退款都能回答「退的是哪一笔钱」（同一订单 + 金额一致）
select r.id as refund_flow_id, r.origin_flow_id as points_to_pay_flow, p.type as origin_type,
       r.order_id = p.order_id as same_order, r.amount = p.amount as same_amount
  from payment_record r
  join payment_record p on p.id = r.origin_flow_id
 where r.type = 2
 order by r.id;
-- => refund_flow_id=37, points_to_pay_flow=36, origin_type=1, same_order=1, same_amount=1
-- => refund_flow_id=40, points_to_pay_flow=39, origin_type=1, same_order=1, same_amount=1

-- @@ 对账键只能是 order_sn：14 行 order_id 未回填的历史行，靠 order_sn 快照仍能在 order 里找到对应订单
select count(*) as rows_total,
       sum(order_id is null) as rows_without_order_id,
       sum(order_sn is not null) as rows_with_order_sn
  from payment_record;
-- => rows_total=27, rows_without_order_id=14, rows_with_order_sn=27
