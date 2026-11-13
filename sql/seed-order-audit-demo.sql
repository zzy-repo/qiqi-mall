-- ============================================================================
-- 自动审核：演示/回归数据集（**幂等**，可重复执行）
-- ============================================================================
--
-- 内容 = ① 策略集（3 AUTO + 2 BLOCK + 2 EXCEPTION + 1 条全局排除）
--        ② 六笔演示单 QT-1001..QT-1006（用户原表，字段值照抄）
--        ③ 配套会员 / 商品 / SKU / 库存桶（只在首次执行时创建）
--
-- 策略与订单的对应关系（与 AuditEngineTest / AuditRunIntegrationTest 逐字一致）：
--   QT-1001 淘宝 268 元 2 件 毛利 62            → AUTO_PASS  命中 淘宝-标准单
--   QT-1002 淘宝 268 元 2 件 客服备注「改地址」  → MANUAL     命中 淘宝-标准单 但被全局排除
--   QT-1003 抖音 1580 元 8 件                   → NO_MATCH   差在 应收 1580 < 500
--   QT-1004 直营门店 199 元 毛利 −12            → BLOCKED    命中 毛利不为正；would_be=直营-小单
--   QT-1005 淘宝 320 元 已有退款申请             → FROZEN →（处理申请+解冻）→ 二轮 AUTO_PASS
--   QT-1006 淘宝 420 元 6 件 6 个样             → NO_MATCH   差在 种类数 6 ≤ 5
--            （库存不足要留到**人工审核**时才发现 → 人工「审核不通过」7→4，不归自动引擎管）
--
-- 关于「异常策略」为什么是两条：
--   设计里是「有退款申请 <b>或</b> 平台备注被改（近 24 小时）」。行模型条件只有 **AND** 语义
--   （audit_policy_condition 没有 group/OR 列），一条策略放两个条件会变成「同时成立」。
--   所以「或」= 两条同参数的 EXCEPTION 策略：任一命中即冻结，等价于一条 OR 策略，
--   且台账里能看出「这次被哪一条冻的」。
--
-- 口径：
--   · 只插不删、只补不改：已存在同名策略 / 同单号订单就整条跳过（改策略请走接口，
--     那样才有 version_no + 版本快照 + 审计）。
--   · 全局排除（客服备注含「改」）policy_id 必须 NULL —— 决策 C1，它不属于任何策略。
--
-- 执行：
--   mysql --default-character-set=utf8mb4 -uroot -p <库名> < sql/seed-order-audit-demo.sql
-- ============================================================================

SET @db := DATABASE();
SELECT '目标库' AS guard, @db AS db;

-- ############################################################################
-- 一、策略集
-- ############################################################################

-- ---------- AUTO-1 直营-小单：客户标签=直营门店 且 应收 < 300 ----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'AUTO', 100, '直营-小单', 1, 1, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'AUTO' AND `name` = '直营-小单');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'AUTO' AND `name` = '直营-小单' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'CUSTOMER_TAG', 'EQ', 'TEXT', NULL, '直营门店', NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'CUSTOMER_TAG');
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 1, 'MATCH', 'POLICY', 'RECEIVABLE_AMOUNT', 'LT', 'NUM', 300, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'RECEIVABLE_AMOUNT');

-- ---------- AUTO-2 淘宝-标准单：渠道=淘宝 且 应收 < 500 且 种类数 <= 5 ----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'AUTO', 200, '淘宝-标准单', 1, 1, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'AUTO' AND `name` = '淘宝-标准单');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'AUTO' AND `name` = '淘宝-标准单' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'CHANNEL', 'EQ', 'TEXT', NULL, '淘宝', NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'CHANNEL');
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 1, 'MATCH', 'POLICY', 'RECEIVABLE_AMOUNT', 'LT', 'NUM', 500, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'RECEIVABLE_AMOUNT');
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 2, 'MATCH', 'POLICY', 'ITEM_KIND_COUNT', 'LTE', 'NUM', 5, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'ITEM_KIND_COUNT');

-- ---------- AUTO-3 抖音-标准单：渠道=抖音 且 应收 < 500 且 种类数 <= 5 ----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'AUTO', 300, '抖音-标准单', 1, 1, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'AUTO' AND `name` = '抖音-标准单');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'AUTO' AND `name` = '抖音-标准单' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'CHANNEL', 'EQ', 'TEXT', NULL, '抖音', NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'CHANNEL');
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 1, 'MATCH', 'POLICY', 'RECEIVABLE_AMOUNT', 'LT', 'NUM', 500, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'RECEIVABLE_AMOUNT');
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 2, 'MATCH', 'POLICY', 'ITEM_KIND_COUNT', 'LTE', 'NUM', 5, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'ITEM_KIND_COUNT');

-- ---------- BLOCK-1 毛利不为正：GROSS_PROFIT <= 0 ----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'BLOCK', 10, '毛利不为正', 1, 1, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'BLOCK' AND `name` = '毛利不为正');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'BLOCK' AND `name` = '毛利不为正' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'GROSS_PROFIT', 'LTE', 'NUM', 0, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'GROSS_PROFIT');

-- ---------- BLOCK-2 双地址：ADDRESS_MULTI = 是 ----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'BLOCK', 20, '双地址', 1, 1, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'BLOCK' AND `name` = '双地址');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'BLOCK' AND `name` = '双地址' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'ADDRESS_MULTI', 'EQ', 'BOOL', 1, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'ADDRESS_MULTI');

-- ---------- EXCEPTION-1 退款申请冻结：HAS_RETURN_APPLY = 是 ----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'EXCEPTION', 10, '退款申请冻结', 1, 1,
       CAST('{"tag":"FROZEN","reason":"存在待处理退款申请"}' AS JSON) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'EXCEPTION' AND `name` = '退款申请冻结');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'EXCEPTION' AND `name` = '退款申请冻结' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'HAS_RETURN_APPLY', 'EQ', 'BOOL', 1, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'HAS_RETURN_APPLY');

-- ---------- EXCEPTION-2 平台备注冻结：平台备注 24 小时内被改过（设计里与上一条是「或」关系）----------
INSERT INTO `audit_policy` (`type`, `priority`, `name`, `enabled`, `version_no`, `action_json`)
SELECT 'EXCEPTION', 20, '平台备注冻结', 1, 1,
       CAST('{"tag":"FROZEN","reason":"平台备注 24 小时内被修改过"}' AS JSON) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy` WHERE `type` = 'EXCEPTION' AND `name` = '平台备注冻结');
SET @p := (SELECT `id` FROM `audit_policy` WHERE `type` = 'EXCEPTION' AND `name` = '平台备注冻结' LIMIT 1);
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT @p, 0, 'MATCH', 'POLICY', 'PLATFORM_REMARK_UPDATED', 'LTE', 'NUM', 24, NULL, NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition` WHERE `policy_id` = @p AND `field_code` = 'PLATFORM_REMARK_UPDATED');

-- ---------- 全局排除（ALL_AUTO）：客服备注含「改」（决策 C1，不属于任何策略）----------
INSERT INTO `audit_policy_condition` (`policy_id`,`seq`,`kind`,`scope`,`field_code`,`op_code`,`value_type`,`value_num`,`value_text`,`value_json`)
SELECT NULL, 0, 'EXCLUDE', 'ALL_AUTO', 'CS_REMARK', 'CONTAINS', 'TEXT', NULL, '改', NULL FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `audit_policy_condition`
                   WHERE `scope` = 'ALL_AUTO' AND `field_code` = 'CS_REMARK' AND `value_text` = '改');

-- ############################################################################
-- 二、演示基础数据（会员 / 商品 / SKU / 桶）—— 只在首次执行时创建
-- ############################################################################

-- 两个会员：普通（QT-1001/1002/1003/1005/1006）/ 直营门店（QT-1004 命中直营-小单用）
INSERT INTO `member` (`username`,`password`,`nickname`,`status`,`create_time`,`version`,`customer_tag`)
SELECT 'audit_demo_member','','自动审核演示-普通',1,NOW(),0,'普通' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `member` WHERE `username` = 'audit_demo_member');
INSERT INTO `member` (`username`,`password`,`nickname`,`status`,`create_time`,`version`,`customer_tag`)
SELECT 'audit_demo_store','','自动审核演示-直营门店',1,NOW(),0,'直营门店' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `member` WHERE `username` = 'audit_demo_store');
SET @m_user  := (SELECT `id` FROM `member` WHERE `username` = 'audit_demo_member' LIMIT 1);
SET @m_store := (SELECT `id` FROM `member` WHERE `username` = 'audit_demo_store' LIMIT 1);

-- 演示商品
INSERT INTO `product` (`name`,`description`,`category_id`,`type_id`,`publish_status`,`version`)
SELECT '自动审核演示商品','sql/seed-order-audit-demo.sql 造数',
       (SELECT MIN(`id`) FROM `category`), (SELECT MIN(`id`) FROM `product_type`), 1, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `product` WHERE `name` = '自动审核演示商品');
SET @prod := (SELECT `id` FROM `product` WHERE `name` = '自动审核演示商品' LIMIT 1);

-- 9 个 SKU（1 淘宝 + 1 抖音 + 1 直营 + 6 个给 QT-1006 凑「6 个样」）：一个都没有时才铺
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod, 268.00, 1000, 0, 200.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 0;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod, 150.00, 1000, 0, 100.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 1;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod, 199.00, 1000, 0, 150.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 2;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod,  70.00, 1000, 0,  50.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 3;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod,  70.00, 1000, 0,  50.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 4;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod,  70.00, 1000, 0,  50.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 5;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod,  70.00, 1000, 0,  50.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 6;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod,  70.00,    0, 0,  50.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 7;
INSERT INTO `sku` (`product_id`,`price`,`stock`,`version`,`cost_price`)
SELECT @prod,  70.00, 1000, 0,  50.00 FROM DUAL WHERE (SELECT COUNT(*) FROM `sku` WHERE `product_id` = @prod) = 8;

SET @sku_taobao := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1);
SET @sku_douyin := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 1);
SET @sku_direct := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 2);
SET @sku_x1 := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 3);
SET @sku_x2 := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 4);
SET @sku_x3 := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 5);
SET @sku_x4 := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 6);
SET @sku_x5 := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 7);
SET @sku_x6 := (SELECT `id` FROM `sku` WHERE `product_id` = @prod ORDER BY `id` LIMIT 1 OFFSET 8);

-- 桶（权威库存）：每个 SKU 一个桶；@sku_x5 故意留 0 —— 人工审核 QT-1006 时才会暴露「库存不足」
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`)
SELECT @sku_taobao, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_taobao);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`)
SELECT @sku_douyin, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_douyin);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`)
SELECT @sku_direct, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_direct);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`) SELECT @sku_x1, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_x1);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`) SELECT @sku_x2, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_x2);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`) SELECT @sku_x3, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_x3);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`) SELECT @sku_x4, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_x4);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`) SELECT @sku_x5, 0,    0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_x5);
INSERT INTO `sku_bucket` (`sku_id`,`bucket_no`,`stock`) SELECT @sku_x6, 0, 1000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sku_bucket` WHERE `sku_id` = @sku_x6);

-- ############################################################################
-- 三、六笔演示单（用户原表；order_sn 已存在则整单跳过）
-- ############################################################################

-- QT-1001 淘宝 268 元 2 件 毛利 62 → AUTO_PASS（淘宝-标准单）
INSERT INTO `order` (`order_sn`,`member_id`,`total_amount`,`status`,`pay_type`,`payment_time`,`created_at`,
                     `receiver_name`,`receiver_phone`,`receiver_address`,`split_status`,`version`,`status_seq`,
                     `channel`,`shop_name`,`gross_profit`,`cs_remark`,`audit_addr_multi`)
SELECT 'QT-1001', @m_user, 268.00, 7, 1, NOW(), NOW(), '张三', '13800000000', '北京市朝阳区xx路1号', 0, 0, 0,
       '淘宝', '官方旗舰店', 62.00, NULL, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order` WHERE `order_sn` = 'QT-1001');
SET @o := (SELECT `id` FROM `order` WHERE `order_sn` = 'QT-1001' LIMIT 1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_taobao, '自动审核演示商品', 134.00, 2, 200.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o);

-- QT-1002 同 QT-1001，但客服备注含「改地址」→ 被全局排除 → MANUAL
INSERT INTO `order` (`order_sn`,`member_id`,`total_amount`,`status`,`pay_type`,`payment_time`,`created_at`,
                     `receiver_name`,`receiver_phone`,`receiver_address`,`split_status`,`version`,`status_seq`,
                     `channel`,`shop_name`,`gross_profit`,`cs_remark`,`audit_addr_multi`)
SELECT 'QT-1002', @m_user, 268.00, 7, 1, NOW(), NOW(), '李四', '13800000001', '北京市海淀区yy路2号', 0, 0, 0,
       '淘宝', '官方旗舰店', 62.00, '客户改地址：海淀区yy路2号', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order` WHERE `order_sn` = 'QT-1002');
SET @o := (SELECT `id` FROM `order` WHERE `order_sn` = 'QT-1002' LIMIT 1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_taobao, '自动审核演示商品', 134.00, 2, 200.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o);

-- QT-1003 抖音 1580 元 8 件 → NO_MATCH（差在应收 1580 < 500）
INSERT INTO `order` (`order_sn`,`member_id`,`total_amount`,`status`,`pay_type`,`payment_time`,`created_at`,
                     `receiver_name`,`receiver_phone`,`receiver_address`,`split_status`,`version`,`status_seq`,
                     `channel`,`shop_name`,`gross_profit`,`cs_remark`,`audit_addr_multi`)
SELECT 'QT-1003', @m_user, 1580.00, 7, 1, NOW(), NOW(), '王五', '13800000002', '上海市浦东新区zz路3号', 0, 0, 0,
       '抖音', '抖音小店', 300.00, NULL, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order` WHERE `order_sn` = 'QT-1003');
SET @o := (SELECT `id` FROM `order` WHERE `order_sn` = 'QT-1003' LIMIT 1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_douyin, '自动审核演示商品', 790.00, 8, 100.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o);

-- QT-1004 直营门店 199 元 毛利 −12 → BLOCKED（毛利不为正），would_be=直营-小单
INSERT INTO `order` (`order_sn`,`member_id`,`total_amount`,`status`,`pay_type`,`payment_time`,`created_at`,
                     `receiver_name`,`receiver_phone`,`receiver_address`,`split_status`,`version`,`status_seq`,
                     `channel`,`shop_name`,`gross_profit`,`cs_remark`,`audit_addr_multi`)
SELECT 'QT-1004', @m_store, 199.00, 7, 1, NOW(), NOW(), '赵六', '13800000003', '广州市天河区ww路4号', 0, 0, 0,
       '直营', '直营门店-广州', -12.00, NULL, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order` WHERE `order_sn` = 'QT-1004');
SET @o := (SELECT `id` FROM `order` WHERE `order_sn` = 'QT-1004' LIMIT 1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_direct, '自动审核演示商品', 199.00, 1, 150.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o);

-- QT-1005 淘宝 320 元，已有待处理退款申请 → FROZEN
INSERT INTO `order` (`order_sn`,`member_id`,`total_amount`,`status`,`pay_type`,`payment_time`,`created_at`,
                     `receiver_name`,`receiver_phone`,`receiver_address`,`split_status`,`version`,`status_seq`,
                     `channel`,`shop_name`,`gross_profit`,`cs_remark`,`audit_addr_multi`)
SELECT 'QT-1005', @m_user, 320.00, 7, 1, NOW(), NOW(), '孙七', '13800000004', '深圳市南山区vv路5号', 0, 0, 0,
       '淘宝', '官方旗舰店', 80.00, NULL, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order` WHERE `order_sn` = 'QT-1005');
SET @o := (SELECT `id` FROM `order` WHERE `order_sn` = 'QT-1005' LIMIT 1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_taobao, '自动审核演示商品', 160.00, 2, 200.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o);
INSERT INTO `return_apply` (`order_id`,`member_id`,`return_reason`,`description`,`status`,`reason_id`,`status_seq`)
SELECT @o, @m_user, '不想要了', '自动审核演示：待处理退款申请', 0, (SELECT MIN(`id`) FROM `return_reason`), 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `return_apply` WHERE `order_id` = @o);

-- QT-1006 淘宝 420 元 6 件 6 个样 → NO_MATCH（差在种类数 6 <= 5）；其中 @sku_x5 桶为 0
INSERT INTO `order` (`order_sn`,`member_id`,`total_amount`,`status`,`pay_type`,`payment_time`,`created_at`,
                     `receiver_name`,`receiver_phone`,`receiver_address`,`split_status`,`version`,`status_seq`,
                     `channel`,`shop_name`,`gross_profit`,`cs_remark`,`audit_addr_multi`)
SELECT 'QT-1006', @m_user, 420.00, 7, 1, NOW(), NOW(), '周八', '13800000005', '杭州市西湖区uu路6号', 0, 0, 0,
       '淘宝', '官方旗舰店', 100.00, NULL, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order` WHERE `order_sn` = 'QT-1006');
SET @o := (SELECT `id` FROM `order` WHERE `order_sn` = 'QT-1006' LIMIT 1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_x1, '自动审核演示商品', 70.00, 1, 50.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o AND `product_sku_id` = @sku_x1);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_x2, '自动审核演示商品', 70.00, 1, 50.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o AND `product_sku_id` = @sku_x2);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_x3, '自动审核演示商品', 70.00, 1, 50.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o AND `product_sku_id` = @sku_x3);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_x4, '自动审核演示商品', 70.00, 1, 50.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o AND `product_sku_id` = @sku_x4);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_x5, '自动审核演示商品', 70.00, 1, 50.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o AND `product_sku_id` = @sku_x5);
INSERT INTO `order_item` (`order_id`,`product_id`,`product_sku_id`,`product_name`,`product_price`,`product_quantity`,`cost_price`)
SELECT @o, @prod, @sku_x6, '自动审核演示商品', 70.00, 1, 50.00 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `order_item` WHERE `order_id` = @o AND `product_sku_id` = @sku_x6);

-- ############################################################################
-- 四、自证
-- ############################################################################
SELECT p.`type`, p.`priority`, p.`name`, p.`enabled`, p.`version_no`,
       (SELECT COUNT(*) FROM `audit_policy_condition` c WHERE c.`policy_id` = p.`id`) AS conds
FROM `audit_policy` p ORDER BY FIELD(p.`type`,'BLOCK','EXCEPTION','AUTO'), p.`priority`;
SELECT '全局排除(ALL_AUTO)' AS scope_check, `field_code`, `op_code`, `value_text`
FROM `audit_policy_condition` WHERE `scope` = 'ALL_AUTO';
SELECT o.`order_sn`, o.`channel`, o.`total_amount`, o.`gross_profit`, o.`cs_remark`,
       (SELECT COUNT(*) FROM `order_item` i WHERE i.`order_id` = o.`id`) AS item_rows,
       (SELECT COUNT(DISTINCT i.`product_sku_id`) FROM `order_item` i WHERE i.`order_id` = o.`id`) AS kinds,
       (SELECT COALESCE(SUM(i.`product_quantity`),0) FROM `order_item` i WHERE i.`order_id` = o.`id`) AS qty
FROM `order` o WHERE o.`order_sn` LIKE 'QT-100%' ORDER BY o.`order_sn`;
