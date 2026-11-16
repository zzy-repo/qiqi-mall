-- 库: qiqi_mall_test（演示库 = 公网演示站背后的同一个库）
-- 实测时间: 2026-11-17 01:20（scripts/reset-test-db.sh 刚跑完那一刻的状态快照）
-- 复现: 跑 `bash scripts/reset-test-db.sh` 再跑本文件 —— 具体单号与数字随当次数据变，
--       「队列有行 / 待审核单带标 / 按标筛筛得出 / 标一条没漏」这四条结论不变
-- 只读: 全文只有 select；构建 build-material 时会校验，出现任何写语句直接失败
-- 重跑: 整份文件丢给 mysql 客户端即可（先 use qiqi_mall_test）
--
-- 为什么这几条数字值得钉住：队列是 FROM order_audit_decision 的，按标筛读的是
-- order_audit_tag 的 active 行。两张表任一为空，页面就是「空表 + 筛出 0 行」，
-- 而功能本身是好的 —— 这种「看起来坏了其实是没数据」的坑，只能靠这几个数自证。

-- @@ 队列有 8 行；待审核 7 张单里有 7 张挂着结论标，一共 4 种标（演示要「几种标都能筛」就得 ≥3 种）
select
  (select count(*) from order_audit_decision d
    where d.id in (select max(id) from order_audit_decision group by order_id)) as queue_rows,
  (select count(*) from `order` where status = 7) as pending_total,
  (select count(*) from `order` o where o.status = 7 and exists (
      select 1 from order_audit_tag t where t.order_id = o.id and t.active = 1
        and (t.tag_code in ('NO_POLICY_MATCH','AUDIT_PENDING','BLOCKED','STOCK_SHORT','COST_MISSING')
             or t.tag_code like 'FROZEN%'))) as pending_tagged,
  (select count(distinct t.tag_code) from order_audit_tag t join `order` o on o.id = t.order_id
    where t.active = 1 and o.status = 7) as tag_kinds;
-- => queue_rows=8, pending_total=7, pending_tagged=7, tag_kinds=4

-- @@ 四种标各自的单数：不命中 4、待人工 1、阻止 1、冻结 1 —— 每一类都筛得出一批单
select t.tag_code, count(*) as orders
  from order_audit_tag t join `order` o on o.id = t.order_id
 where t.active = 1 and o.status = 7
 group by t.tag_code
 order by orders desc, t.tag_code;
-- => tag_code=NO_POLICY_MATCH, orders=4
-- => tag_code=AUDIT_PENDING, orders=1
-- => tag_code=BLOCKED, orders=1
-- => tag_code=FROZEN, orders=1

-- 缺标就是「按标筛 0 行」那个 bug 的形态，下一条数字就是它的哨兵（「SQL 段里不许夹注释行」——构建期只读校验
-- 要求 SQL 段第一行就是 select，所以说明写在这里，不写进 SQL 里）
-- @@ 按标筛「不命中」筛得出单，而且一条都不漏落标：4 条 NO_MATCH 决策行，缺标的 0 条
select count(*) as no_match_decisions, sum(x.has_tag = 0) as tag_missing
  from (select d.order_id,
               exists (select 1 from order_audit_tag t
                        where t.order_id = d.order_id and t.active = 1
                          and t.tag_code = 'NO_POLICY_MATCH') as has_tag
          from order_audit_decision d
         where d.id in (select max(id) from order_audit_decision group by order_id)
           and d.result = 'NO_MATCH') x;
-- => no_match_decisions=4, tag_missing=0

-- 「最接近那条」是算出来的，不是写死的（这单的三条 AUTO 策略里，只有「抖音-标准单」差 1 个条件，
-- 另两条是分类不符、差 2 个条件；判定依据的三层正是按「缺几个条件」排出来的）
-- @@ 「最接近那条 + 差什么」是算出来的，不是写死的：QT-1003 三条 AUTO 策略里只有抖音那条只差 1 个条件
select jt.idx, jt.policy_name, json_length(jt.reasons) as missed, jt.reasons
  from (select miss_reason_json from order_audit_decision
         where order_id = (select id from `order` where order_sn = 'QT-1003')
         order by id desc limit 1) d,
       json_table(d.miss_reason_json, '$[*]' columns (
         idx for ordinality,
         policy_name varchar(64) path '$.policyName',
         reasons json path '$.reasons'
       )) jt
 order by missed, jt.idx;
-- => idx=3, policy_name=抖音-标准单, missed=1, reasons=["应收金额 1580.00 < 500.00"]
-- => idx=1, policy_name=直营-小单, missed=2, reasons=["客户标签 = 直营门店（实际 普通）", "应收金额 1580.00 < 300.00"]
-- => idx=2, policy_name=淘宝-标准单, missed=2, reasons=["渠道 = 淘宝（实际 抖音）", "应收金额 1580.00 < 500.00"]
