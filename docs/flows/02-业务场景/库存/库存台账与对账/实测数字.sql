-- 库: qiqi_mall_e2e_a（e2e 分片库）| 与容器栈库 qiqi_mall_test、生产库同构，同一份 SQL 三边都能跑
-- 实测时间: 2026-11-14 16:46（e2e 全量套件刚跑完 qiqi_mall_e2e_a 那一刻的状态快照）
-- 复现: 重跑「台账与取证/台账七类」与「库存/库存台账与对账：三数相等」那批 spec 再跑本文件 —— 具体数字随当次数据变，「对平 / 0 重复 / 0 例外」这几条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use 目标库）
--
-- @@ 桶的总和就是权威值：全库每个 SKU 的桶和 = 页面显示的库存缓存，一处不差
select count(*) as sku_total,
       sum(case when coalesce(b.bucket_sum, 0) <> s.stock then 1 else 0 end) as cache_mismatch
  from sku s
  left join (select sku_id, sum(stock) as bucket_sum from sku_bucket group by sku_id) b
         on b.sku_id = s.id;
-- => sku_total=127, cache_mismatch=0

-- @@ 同一件货摆三个数：台账净额 ≠ 余额（余额=桶和，净额=全生命周期进出和，只回答补过没有）
select l.sku_id,
       s.stock                                                              as sku_stock_cache,
       (select coalesce(sum(b.stock), 0) from sku_bucket b where b.sku_id = l.sku_id) as bucket_sum,
       sum(l.delta)                                                         as ledger_net,
       count(*)                                                             as ledger_rows
  from stock_ledger l
  join sku s on s.id = l.sku_id
 group by l.sku_id, s.stock
 order by count(*) desc, l.sku_id
 limit 1;
-- => sku_id=1, sku_stock_cache=50, bucket_sum=50, ledger_net=-10, ledger_rows=15

-- @@ 扣减量 = 下单量：ORDER_CREATE 的 delta 与订单明细逐单逐规格对得上，0 处不符
select count(*) as pairs,
       sum(case when l.delta + oi.qty <> 0 then 1 else 0 end) as mismatch
  from (select order_id, sku_id, sum(delta) as delta from stock_ledger
         where reason = 'ORDER_CREATE' group by order_id, sku_id) l
  join (select order_id, product_sku_id as sku_id, sum(product_quantity) as qty from order_item
         group by order_id, product_sku_id) oi
    on oi.order_id = l.order_id and oi.sku_id = l.sku_id;
-- => pairs=11, mismatch=0

-- @@ 回补优先回原桶：卖出的那个桶就是退回的那个桶，单笔净额归零
select c.order_id, c.sku_id,
       sum(l.delta)                       as net,
       count(distinct l.bucket_no)        as buckets,
       group_concat(concat(l.reason, ':', l.bucket_no) order by l.id separator ' ') as trace
  from stock_ledger c
  join stock_ledger l on l.order_id = c.order_id and l.sku_id = c.sku_id
 where c.reason = 'RETURN_REFUND'
 group by c.order_id, c.sku_id
 order by c.order_id
 limit 3;
-- => order_id=37, sku_id=1, net=0, buckets=1, trace=ORDER_CREATE:6 RETURN_REFUND:6
-- => order_id=39, sku_id=1, net=0, buckets=1, trace=ORDER_CREATE:2 RETURN_REFUND:2

-- @@ 同一件货不会被补第二次：行数 = 去重键数；按「订单+规格+原因+单据」分组，重复组 0
select count(*) as rows_total,
       count(distinct idem_key) as idem_keys,
       (select count(*) from (select 1 from stock_ledger
          group by order_id, sku_id, reason, coalesce(ref_id, 0) having count(*) > 1) t) as dup_groups
  from stock_ledger;
-- => rows_total=15, idem_keys=15, dup_groups=0
