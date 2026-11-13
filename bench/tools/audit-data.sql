-- audit-data.sql —— 压测后的**数据正确性**抽查（只看、不改）
--
-- 目的：压测的 ok/s 是「HTTP 成功数」，它不等于「账记得对」。这份 SQL 回答
--   ① 订单 ↔ 明细 ↔ 出生台账 三者的行数是否一一对应
--   ② 订单总金额 = 明细金额之和吗？（合并过 INSERT 的那条路径最怕这里错）
--   ③ 扣减 ↔ 库存台账 是否一致（每单每 SKU 一条 ORDER_CREATE，delta = -qty）
--   ④ 桶守恒：Σ桶 现存 + Σ扣减 = 初始 500000（分桶模式下桶才是权威值）
--   ⑤ sku.stock 这个展示缓存与桶的偏差（250ms 回写，静置后应为 0）
--   ⑥ 唯一性：order_sn / 台账 idem_key 有没有重复（快但记错账的典型症状）
--   ⑦ 桶有没有被扣成负数（CHECK 约束在，但还是要数一遍）
--
-- 口径：只统计压测会员（username like 'lg\_%'）名下的单，不碰别人的数据。

set @SKU_LO := 1, @SKU_HI := 21, @INIT := 500000;

select '① 行数对应' as chk,
       (select count(*) from `order` o join member m on m.id=o.member_id where m.username like 'lg\_%')                     as orders,
       (select count(*) from order_item i join `order` o on o.id=i.order_id
          join member m on m.id=o.member_id where m.username like 'lg\_%')                                                 as items,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%'
           and (select count(*) from order_item i where i.order_id=o.id) <> 1)                                            as orders_without_exactly_1_item,
       (select count(*) from order_item i left join `order` o on o.id=i.order_id where o.id is null)                       as orphan_items;

select '② 金额' as chk,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%'
           and o.total_amount <> (select coalesce(sum(i.product_price*i.product_quantity),0)
                                    from order_item i where i.order_id=o.id))                                             as amount_mismatch,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%' and o.total_amount <= 0)                                                            as amount_nonpositive,
       (select min(o.total_amount) from `order` o join member m on m.id=o.member_id where m.username like 'lg\_%')         as min_total_amount,
       (select max(o.total_amount) from `order` o join member m on m.id=o.member_id where m.username like 'lg\_%')         as max_total_amount;

select '③ 出生台账' as chk,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%'
           and (select count(*) from order_status_ledger l
                 where l.order_id=o.id and l.from_status is null) <> 1)                                                    as orders_without_1_birth_row,
       (select count(*) from order_status_ledger l join `order` o on o.id=l.order_id join member m on m.id=o.member_id
         where m.username like 'lg\_%' and l.from_status is null and l.to_status <> 0)                                     as birth_row_wrong_status,
       (select count(*) from order_status_ledger l join `order` o on o.id=l.order_id join member m on m.id=o.member_id
         where m.username like 'lg\_%' and l.reason <> 'ORDER_CREATE' and l.from_status is null)                            as birth_row_wrong_reason;

select '④ 扣减台账' as chk,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%'
           and (select count(*) from stock_ledger l
                 where l.order_id=o.id and l.reason='ORDER_CREATE') <>
               (select count(*) from order_item i where i.order_id=o.id))                                                  as orders_ledger_rows_neq_items,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%'
           and (select coalesce(sum(l.delta),0) from stock_ledger l
                 where l.order_id=o.id and l.reason='ORDER_CREATE') <>
               -(select coalesce(sum(i.product_quantity),0) from order_item i where i.order_id=o.id))                      as orders_ledger_delta_neq_qty,
       (select count(*) from stock_ledger l join `order` o on o.id=l.order_id join member m on m.id=o.member_id
         where m.username like 'lg\_%' and l.delta >= 0)                                                                   as order_create_delta_not_negative;

select '⑤ 桶守恒' as chk,
       (select count(*) from (
          select s.id,
                 coalesce((select sum(b.stock) from sku_bucket b where b.sku_id=s.id),0)
               + coalesce((select -sum(l.delta) from stock_ledger l where l.sku_id=s.id and l.reason='ORDER_CREATE'),0) as recomputed
            from sku s where s.id between @SKU_LO and @SKU_HI) t
        where t.recomputed <> @INIT)                                                                                        as skus_not_conserved,
       (select coalesce(sum(b.stock),0) from sku_bucket b where b.sku_id between @SKU_LO and @SKU_HI)                       as bucket_stock_sum,
       (select coalesce(sum(-l.delta),0) from stock_ledger l where l.sku_id between @SKU_LO and @SKU_HI
          and l.reason='ORDER_CREATE')                                                                                      as total_deducted;

select '⑥ 缓存/唯一性' as chk,
       (select count(*) from sku s where s.id between @SKU_LO and @SKU_HI
          and s.stock <> coalesce((select sum(b.stock) from sku_bucket b where b.sku_id=s.id),0))                            as cache_drift_skus,
       (select count(*) from (select order_sn from `order` group by order_sn having count(*)>1) t)                          as dup_order_sn,
       (select count(*) from (select idem_key from stock_ledger group by idem_key having count(*)>1) t)                      as dup_stock_ledger_idem,
       (select count(*) from (select idem_key from order_status_ledger group by idem_key having count(*)>1) t)              as dup_status_ledger_idem,
       (select count(*) from sku_bucket where stock < 0)                                                                    as negative_buckets;

select '⑦ 支付（本压测不下单支付，应为 0）' as chk,
       (select count(*) from payment_record p join `order` o on o.order_sn=p.order_sn
          join member m on m.id=o.member_id where m.username like 'lg\_%')                                                  as payment_rows,
       (select count(*) from `order` o join member m on m.id=o.member_id
         where m.username like 'lg\_%' and o.status <> 0)                                                                    as orders_not_pending;
