-- ============================================================================
-- 迁移：SKU 库存台账 stock_ledger（幂等 DDL，可反复重跑）
--
-- 背景（缺陷：同一件货被回补两次）：
--   入口 A「审核不通过」按整单回补、入口 B「取消包裹」按包裹回补，
--   两边都是直接 `UPDATE sku SET stock = stock + N`，没有记录、没有查重、没有互斥。
--   于是「审核不通过 / 审核通过后取消包裹 / 退货退款」这些入口叠加时，
--   同一件货会被补两次 —— 库存虚增，钱货不一致。
--
-- 修法：所有扣/补 SKU 库存的地方都先记一行台账，再动 sku.stock。
--   ① idem_key = orderId:skuId:reason:refId（refId 缺省 0），UNIQUE 保证同一个触发不会记第二遍；
--   ② 数量守恒：同一 (order_id, sku_id) 的「已回补总量」不得超过该订单该规格的下单总量
--      （order_item.product_quantity 之和），超出部分直接跳过 —— 这正是「同一件货不会补两次」。
--
-- 为什么不用函数索引：MySQL 8 支持表达式索引，但把幂等键落成一列 idem_key 更直白
--   （EXPLAIN / 手工对账 / 排查时直接可读），也不依赖 IFNULL() 的索引兼容性。
--
-- 无外键：台账是**审计证据**，不允许随订单/规格被 CASCADE 静默带走。
--   订单删除（OrderPurgeService）时台账行保留，delete 操作的证据链不断。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `stock_ledger` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`   BIGINT      NOT NULL COMMENT '关联订单（无外键：台账是审计证据，不随订单级联删除）',
  `sku_id`     BIGINT      NOT NULL COMMENT '规格（无外键，同上）',
  `delta`      INT         NOT NULL COMMENT '库存变化量：负数=扣减（下单），正数=回补',
  `reason`     VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATE / AUDIT_REJECT / DELIVERY_CANCEL / RETURN_REFUND',
  `ref_type`   VARCHAR(16) NULL     COMMENT '触发单据类型：DELIVERY / RETURN_APPLY；下单为 NULL',
  `ref_id`     BIGINT      NULL     COMMENT '触发单据 id：发货单 id / 退货申请 id；下单为 NULL',
  `idem_key`   VARCHAR(96) NOT NULL COMMENT '幂等键 orderId:skuId:reason:refId（refId 缺省 0）',
  `created_at` DATETIME    NOT NULL COMMENT '记账时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_order_sku` (`order_id`, `sku_id`),
  KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU 库存台账（扣/补全量记账 + 幂等去重）';

-- 建表语句与 sql/schema.sql、sql/testdb/01-schema.sql 保持一致（新建库时直接建好）。
