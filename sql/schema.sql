-- 七七商城 数据库结构
-- 依据 ER 图：商品模块（分类/类型/属性/商品/SKU）
--             订单模块（订单/明细/退货申请/退货原因/订单设置）
--             用户模块（会员/收货地址/购物车/管理员）

CREATE DATABASE IF NOT EXISTS qiqi_mall
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE qiqi_mall;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS cart;
DROP TABLE IF EXISTS member_address;
DROP TABLE IF EXISTS return_apply;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS stock_ledger;
DROP TABLE IF EXISTS return_reason;
DROP TABLE IF EXISTS order_setting;
DROP TABLE IF EXISTS delivery_logistics;
DROP TABLE IF EXISTS delivery_item;
DROP TABLE IF EXISTS delivery_order;
DROP TABLE IF EXISTS inventory;
DROP TABLE IF EXISTS warehouse;
DROP TABLE IF EXISTS payment_record;
DROP TABLE IF EXISTS sku_bucket;
DROP TABLE IF EXISTS sku_spec;
DROP TABLE IF EXISTS product_gallery;
DROP TABLE IF EXISTS sku;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS attribute;
DROP TABLE IF EXISTS product_type;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS admin;
DROP TABLE IF EXISTS ai_api_key;
DROP TABLE IF EXISTS wms_exchange_ledger;
DROP TABLE IF EXISTS admin_audit_log;
DROP TABLE IF EXISTS return_status_ledger;
DROP TABLE IF EXISTS delivery_status_ledger;
DROP TABLE IF EXISTS order_status_ledger;
-- 旧版本残留表（多租户方案已废弃）
DROP TABLE IF EXISTS order_audit_decision;
DROP TABLE IF EXISTS order_audit_tag;
DROP TABLE IF EXISTS audit_run;
DROP TABLE IF EXISTS audit_policy_version;
DROP TABLE IF EXISTS audit_policy_condition;
DROP TABLE IF EXISTS audit_policy;
DROP TABLE IF EXISTS tenant_plan;
DROP TABLE IF EXISTS tenant_user;
DROP TABLE IF EXISTS plan;
DROP TABLE IF EXISTS tenant;
DROP TABLE IF EXISTS channel_sync_log;
DROP TABLE IF EXISTS channel_order_map;
DROP TABLE IF EXISTS channel_shop;

SET FOREIGN_KEY_CHECKS = 1;

-- ============ 用户模块 ============

-- 会员（C 端用户，全局唯一登录名）
CREATE TABLE `member` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` VARCHAR(50) NOT NULL COMMENT '登录名-全局唯一',
  `password` VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'BCrypt（登录功能未实现，新增时暂为空）',
  `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
  `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `customer_tag` VARCHAR(32) DEFAULT NULL COMMENT '客户标签：CUSTOMER_TAG 字段来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员';

-- 收货地址（聚合于会员）
CREATE TABLE `member_address` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` BIGINT NOT NULL COMMENT '所属会员',
  `name` VARCHAR(50) NOT NULL COMMENT '收件人',
  `phone` VARCHAR(20) NOT NULL COMMENT '收件电话',
  `province` VARCHAR(50) NOT NULL COMMENT '省',
  `city` VARCHAR(50) NOT NULL COMMENT '市',
  `region` VARCHAR(50) NOT NULL COMMENT '区/县',
  `detail` VARCHAR(200) NOT NULL COMMENT '详细地址',
  `default_status` TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认地址:1是 0否',
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`),
  CONSTRAINT `fk_addr_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址';

-- 购物车（会员 × SKU）
CREATE TABLE `cart` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` BIGINT NOT NULL COMMENT '会员ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID（SPU）',
  `product_sku_id` BIGINT NOT NULL COMMENT '具体规格SKU',
  `quantity` INT NOT NULL DEFAULT 1 COMMENT '数量',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`),
  CONSTRAINT `fk_cart_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

-- 管理员（后台登录，暂不做鉴权）
CREATE TABLE `admin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` VARCHAR(50) NOT NULL COMMENT '后台登录名',
  `password` VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'BCrypt（登录功能未实现，新增时暂为空）',
  `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员';

-- ============ 商品模块 ============

-- 商品分类（树形，parent_id=0 为顶级）
CREATE TABLE `category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父分类ID，0=顶级',
  `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

-- 商品类型
CREATE TABLE `product_type` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(50) NOT NULL COMMENT '类型名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品类型';

-- 属性（聚合于商品类型）
CREATE TABLE `attribute` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type_id` BIGINT NOT NULL COMMENT '所属类型',
  `name` VARCHAR(50) NOT NULL COMMENT '属性名称',
  `is_filter` TINYINT NOT NULL DEFAULT 0 COMMENT '是否筛选属性:1是 0否',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type_id`),
  CONSTRAINT `fk_attr_type` FOREIGN KEY (`type_id`) REFERENCES `product_type` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='属性';

-- 商品（SPU）
CREATE TABLE `product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(100) NOT NULL COMMENT '商品名称（SPU）',
  `description` TEXT COMMENT '商品描述',
  `category_id` BIGINT NOT NULL COMMENT '所属分类',
  `type_id` BIGINT NOT NULL COMMENT '所属类型',
  `publish_status` TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  `pic` VARCHAR(500) DEFAULT NULL COMMENT '主图URL（指向 MinIO）',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_type` (`type_id`),
  CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`),
  CONSTRAINT `fk_product_type` FOREIGN KEY (`type_id`) REFERENCES `product_type` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品（SPU）';

-- SKU（聚合于商品）
CREATE TABLE `sku` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_id` BIGINT NOT NULL COMMENT '所属商品',
  `price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '价格',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '总库存——展示缓存：由 SUM(sku_bucket.stock) 周期回写，扣减/回补不写它、也不拿它裁决',
  `pic` VARCHAR(500) DEFAULT NULL COMMENT 'SKU图URL（指向 MinIO）',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `cost_price` DECIMAL(12,2) DEFAULT NULL COMMENT '成本价（当前值）；0/NULL=未维护成本',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_sku_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU';

-- SKU 库存分桶（一份库存物理拆成 N 行；权威裁决处，sku.stock 是它的展示缓存）
-- 详见 sql/migrate-sku-bucket.sql。热点 SKU 的扣减不再挤在 sku 一把行锁上，
-- 而是分散到 N 行（UNIQUE(sku_id, bucket_no) 保证桶号唯一、每个桶只有一把行锁）。
-- CHECK (stock >= 0)：单桶不许为负 ⇒ SUM(stock) 永远 >= 0 —— 「SUM 为负 / 超卖」在库层面就写不进去。
CREATE TABLE `sku_bucket` (
  `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id`     BIGINT   NOT NULL COMMENT '所属规格',
  `bucket_no`  INT      NOT NULL COMMENT '桶号 0..N-1（N = qiqi.stock.bucket-count，默认 8）',
  `stock`      INT      NOT NULL DEFAULT 0 COMMENT '本桶可用库存（权威值；sku.stock 只是它的缓存）',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变动时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_bucket` (`sku_id`, `bucket_no`),
  KEY `idx_sku` (`sku_id`),
  CONSTRAINT `ck_bucket_stock` CHECK (`stock` >= 0),
  CONSTRAINT `fk_bucket_sku` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU 库存分桶（一份库存物理拆成 N 行，权威裁决处；sku.stock 是它的展示缓存）';

-- SKU 规格值（聚合于 SKU，引用属性定义）
CREATE TABLE `sku_spec` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` BIGINT NOT NULL COMMENT '所属SKU',
  `attribute_id` BIGINT NOT NULL COMMENT '指向属性定义',
  `value` VARCHAR(100) NOT NULL COMMENT '属性值，如 黑色',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_attribute` (`sku_id`, `attribute_id`),
  KEY `idx_attribute` (`attribute_id`),
  CONSTRAINT `fk_spec_sku` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_spec_attribute` FOREIGN KEY (`attribute_id`) REFERENCES `attribute` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU规格值';

-- 商品相册（聚合于商品）
CREATE TABLE `product_gallery` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_id` BIGINT NOT NULL COMMENT '所属商品',
  `url` VARCHAR(500) NOT NULL COMMENT '图片URL（指向 MinIO）',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_gallery_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品相册';

-- ============ 订单模块 ============

-- 退货原因（字典）
CREATE TABLE `return_reason` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(50) NOT NULL COMMENT '原因名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货原因';

-- 订单（status 状态机：0待付款 1已付款 2已发货 3已完成 4已关闭）
CREATE TABLE `order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_sn` VARCHAR(50) NOT NULL COMMENT '订单号-全局唯一',
  `member_id` BIGINT NOT NULL COMMENT '买家ID',
  `total_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款',
  `pay_type` TINYINT NOT NULL DEFAULT 0 COMMENT '0未支付 1支付宝 2微信',
  `payment_time` DATETIME DEFAULT NULL COMMENT '支付时间',
  `created_at` DATETIME DEFAULT NULL COMMENT '下单时间（历史行可能为 NULL）',
  `receiver_name` VARCHAR(50) NOT NULL COMMENT '收货人快照',
  `receiver_phone` VARCHAR(20) DEFAULT NULL COMMENT '收货电话快照',
  `receiver_address` VARCHAR(255) DEFAULT NULL COMMENT '收货地址快照',
  `split_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未拆(单仓直发) 1已拆',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `status_seq` INT NOT NULL DEFAULT 0 COMMENT '状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源',
  `channel` VARCHAR(16) DEFAULT NULL COMMENT '销售渠道（直营/分销/…）：CHANNEL 字段来源',
  `shop_name` VARCHAR(64) DEFAULT NULL COMMENT '店铺名：SHOP_NAME 字段来源',
  `gross_profit` DECIMAL(12,2) DEFAULT NULL COMMENT '毛利：GROSS_PROFIT 字段来源；NULL=没算过',
  `cs_remark` VARCHAR(255) DEFAULT NULL COMMENT '客服备注：CS_REMARK 字段来源（含「改」= 全局排除）',
  `platform_remark` VARCHAR(255) DEFAULT NULL COMMENT '平台备注（买家留言同步）',
  `platform_remark_updated_at` DATETIME DEFAULT NULL COMMENT '平台备注最后更新时间；NULL=从未更新',
  `remark_consumed_at` DATETIME DEFAULT NULL COMMENT '备注被审核消费的时间',
  `audit_addr_multi` TINYINT NOT NULL DEFAULT 0 COMMENT '多地址订单标记：ADDRESS_MULTI 字段来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  KEY `idx_member` (`member_id`),
  -- C 端「我的订单」按 member 过滤 + payment_time 倒序，复合索引避免 filesort
  KEY `idx_member_time` (`member_id`, `payment_time`),
  KEY `idx_status` (`status`),
  CONSTRAINT `fk_order_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- 订单明细（聚合于订单）
CREATE TABLE `order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID（SPU快照）',
  `product_sku_id` BIGINT NOT NULL COMMENT 'SKU ID（快照）',
  `product_name` VARCHAR(100) NOT NULL COMMENT '下单时商品名（快照）',
  `product_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '成交价（快照）',
  `product_quantity` INT NOT NULL DEFAULT 1 COMMENT '购买数量',
  `cost_price` DECIMAL(12,2) DEFAULT NULL COMMENT '下单时成本价快照；0/NULL=未维护成本',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_item_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_item_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT `fk_item_sku` FOREIGN KEY (`product_sku_id`) REFERENCES `sku` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- 退货申请（聚合于订单）
CREATE TABLE `return_apply` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `member_id` BIGINT NOT NULL COMMENT '申请人ID',
  `return_reason` VARCHAR(200) NOT NULL COMMENT '退货原因描述',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '详细说明',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1通过 2拒绝',
  `reason_id` BIGINT NOT NULL COMMENT '退货原因字典ID',
  `status_seq` INT NOT NULL DEFAULT 0 COMMENT '状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源',
  `pending_order_id` BIGINT GENERATED ALWAYS AS (IF(`status` = 0, `order_id`, NULL)) VIRTUAL COMMENT '待处理请求的订单ID：仅 status=0 时非空（预留列，本轮不建唯一键，见头部【已知问题·待办】）',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_return_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_return_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_return_reason` FOREIGN KEY (`reason_id`) REFERENCES `return_reason` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货申请';

-- ============ 拆单 / 发货单（docs/er/21-ER图-拆单合单） ============

-- 仓库（字典）
CREATE TABLE `warehouse` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(50) NOT NULL COMMENT '仓库名称',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库';

-- 分仓库存（SKU × 仓库，拆单的前提）
CREATE TABLE `inventory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` BIGINT NOT NULL COMMENT 'SKU',
  `warehouse_id` BIGINT NOT NULL COMMENT '仓库',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '该仓库存量',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_wh` (`sku_id`, `warehouse_id`),
  KEY `idx_wh` (`warehouse_id`),
  CONSTRAINT `fk_inv_sku` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`),
  CONSTRAINT `fk_inv_wh` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分仓库存记录';

-- 发货单（拆单后的新聚合根）
CREATE TABLE `delivery_order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_sn` VARCHAR(50) NOT NULL COMMENT '发货单号（DL开头）',
  `order_id` BIGINT NOT NULL COMMENT '父订单',
  `warehouse_id` BIGINT NOT NULL COMMENT '发货仓',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消',
  `receiver_name` VARCHAR(50) NOT NULL COMMENT '收货人快照（多包各自贴单）',
  `receiver_phone` VARCHAR(20) NOT NULL COMMENT '收货电话快照',
  `receiver_address` VARCHAR(255) NOT NULL COMMENT '收货地址快照',
  `source` TINYINT NOT NULL DEFAULT 0 COMMENT '来源：0自动拆单 1人工调整（改仓/改量/拆分/合并/删除）',
  `split_rule_id` BIGINT NULL COMMENT '预留：命中的拆单规则ID，层次二规则引擎启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `status_seq` INT NOT NULL DEFAULT 0 COMMENT '状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery_sn` (`delivery_sn`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_delivery_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_delivery_wh` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货单';

-- 发货单明细（拆分来源可溯源）
CREATE TABLE `delivery_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` BIGINT NOT NULL COMMENT '发货单',
  `order_item_id` BIGINT NOT NULL COMMENT '拆分来源（订单明细）',
  `product_sku_id` BIGINT NOT NULL COMMENT 'SKU',
  `quantity` INT NOT NULL COMMENT '本发货单承担的数量',
  PRIMARY KEY (`id`),
  KEY `idx_delivery` (`delivery_id`),
  CONSTRAINT `fk_ditem_delivery` FOREIGN KEY (`delivery_id`) REFERENCES `delivery_order` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发货单明细';

-- 物流信息（WMS 回传，1:1）
CREATE TABLE `delivery_logistics` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` BIGINT NOT NULL COMMENT '发货单',
  `company` VARCHAR(30) NOT NULL COMMENT '快递公司',
  `track_no` VARCHAR(50) NOT NULL COMMENT '运单号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回传时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery` (`delivery_id`),
  UNIQUE KEY `uk_track_no` (`track_no`),
  CONSTRAINT `fk_logi_delivery` FOREIGN KEY (`delivery_id`) REFERENCES `delivery_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流信息';

-- 支付流水（对账用：支付 / 退款各一条）
CREATE TABLE `payment_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_sn` VARCHAR(50) NOT NULL COMMENT '关联订单号（对账键）',
  `order_id` BIGINT DEFAULT NULL COMMENT '关联订单ID（回填自 order_sn；order 被物理删后可能悬空）',
  `amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '金额',
  `type` TINYINT NOT NULL DEFAULT 1 COMMENT '1支付 2退款',
  `channel` TINYINT NOT NULL DEFAULT 0 COMMENT '0模拟 1支付宝 2微信',
  `trade_no` VARCHAR(64) DEFAULT NULL COMMENT '第三方流水号（模拟为本地生成）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  `ref_type` VARCHAR(24) DEFAULT NULL COMMENT '触发单据类型',
  `ref_id` BIGINT DEFAULT NULL COMMENT '触发单据ID',
  `origin_flow_id` BIGINT DEFAULT NULL COMMENT '退款指向的原支付流水ID（type=2 时必填）',
  `operator_type` VARCHAR(16) DEFAULT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作者名快照',
  `idem_key` VARCHAR(96) DEFAULT NULL COMMENT '幂等键（历史行为 NULL，新写入必填）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_channel_trade` (`channel`, `trade_no`),
  KEY `idx_order_sn` (`order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水';

-- 订单设置（全局单行配置）
CREATE TABLE `order_setting` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `auto_confirm_days` INT NOT NULL DEFAULT 7 COMMENT '自动收货天数',
  `auto_close_minutes` INT NOT NULL DEFAULT 30 COMMENT '未付款自动关单分钟数',
  `auto_audit_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '自动审核总开关：0=定时任务不跑',
  `audit_stock_guard` VARCHAR(8) NOT NULL DEFAULT 'STRICT' COMMENT '库存判据档位：STRICT / LENIENT',
  `audit_run_interval_min` INT NOT NULL DEFAULT 30 COMMENT '定时审核间隔（分钟）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单设置';

-- AI 接口访问密钥（详见 sql/migrate-ai-api-key.sql）
-- 明文不落库：只存 sha256(明文) 与明文前 8 位；删记录 = 立即吊销，无需黑名单。
CREATE TABLE `ai_api_key` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `name`         VARCHAR(64)  NOT NULL COMMENT '用途名称，如 运营助手-只读',
  `key_prefix`   VARCHAR(16)  NOT NULL COMMENT '明文前 8 位，列表展示用',
  `key_hash`     CHAR(64)     NOT NULL COMMENT 'sha256(明文)，明文不落库',
  `scopes`       VARCHAR(512) NOT NULL COMMENT '逗号分隔，如 order:read,catalog:read',
  `expires_at`   DATETIME     NULL     COMMENT 'NULL = 永不过期',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `last_used_at` DATETIME     NULL,
  `created_by`   VARCHAR(64)  NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 接口访问密钥';

-- SKU 库存台账（详见 sql/migrate-stock-ledger.sql）
-- 所有扣/补 sku.stock 的地方都先记一行台账：idem_key 唯一 = 同一触发不会记第二遍；
-- 无外键：台账是审计证据，不随订单/规格被级联删除。
CREATE TABLE `stock_ledger` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`   BIGINT      NOT NULL COMMENT '关联订单（无外键：台账是审计证据，不随订单级联删除）',
  `sku_id`     BIGINT      NOT NULL COMMENT '规格（无外键，同上）',
  `delta`      INT         NOT NULL COMMENT '库存变化量：负数=扣减（下单），正数=回补',
  `reason`     VARCHAR(32) NOT NULL COMMENT 'ORDER_CREATE / AUDIT_REJECT / DELIVERY_CANCEL / RETURN_REFUND',
  `ref_type`   VARCHAR(16) NULL     COMMENT '触发单据类型：DELIVERY / RETURN_APPLY；下单为 NULL',
  `ref_id`     BIGINT      NULL     COMMENT '触发单据 id：发货单 id / 退货申请 id；下单为 NULL',
  `idem_key`   VARCHAR(96) NOT NULL COMMENT '幂等键 [前缀:]orderId:skuId:reason:refId（refId 缺省 0）；前缀=orderId % idem-key-shards，可关（详见 migrate-ledger-idem-shard.sql）',
  `bucket_no`  INT         NULL     COMMENT '扣减/回补落点桶号（可空：历史台账）；只用于回补定位，不参与守恒计算',
  `created_at` DATETIME    NOT NULL COMMENT '记账时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_order_sku` (`order_id`, `sku_id`),
  KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU 库存台账（扣/补全量记账 + 幂等去重）';

-- ============ 台账体系（sql/migrate-ledger.sql 的等价 DDL；P2 状态台账） ============
-- 这 5 张表是**只追加**证据表：只 insert，不 update、不软删、不加 updated_at。
-- 与 migrate-ledger.sql 逐字同构（列/索引/CHECK/排序规则）；两处改动必须同步。
-- ⚠️ return_apply.pending_order_id 的 UNIQUE KEY `uk_pending_order` 本轮**刻意不建**
--    （见 migrate-ledger.sql 头部【已知问题·待办】），所以这里也不要加。

-- 订单状态台账（只追加证据链）
CREATE TABLE `order_status_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID（业务主键·无外键：证据不随订单级联删除）',
  `from_status` TINYINT DEFAULT NULL COMMENT '变更前状态；NULL=出生事件（建单时无前态）',
  `to_status` TINYINT NOT NULL COMMENT '变更后状态',
  `reason` VARCHAR(32) NOT NULL COMMENT '流转原因码',
  `reason_detail` VARCHAR(255) DEFAULT NULL COMMENT '原因补充说明',
  `operator_type` VARCHAR(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID（系统事件可空）',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type` VARCHAR(24) DEFAULT NULL COMMENT '触发单据类型：DELIVERY / RETURN_APPLY / PAYMENT …',
  `ref_id` BIGINT DEFAULT NULL COMMENT '触发单据ID',
  `event_key` VARCHAR(96) DEFAULT NULL COMMENT '外部事件号（重投只留一条；允许多 NULL）',
  `idem_key` VARCHAR(96) NOT NULL COMMENT '幂等键 [shard:]{orderId}:{statusSeq}',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`order_id`, `created_at`),
  CONSTRAINT `ck_order_status_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单状态台账（只追加证据链）';

-- 包裹状态台账（带单号快照 + 冗余订单ID：OrderPurgeService 会物理删包裹行，单号与 order_id 都必须留在台账里）
CREATE TABLE `delivery_status_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` BIGINT NOT NULL COMMENT '包裹ID（业务主键·无外键：证据不随包裹级联删除）',
  `delivery_sn` VARCHAR(50) NOT NULL COMMENT '【已登记冗余列】发货单号快照（包裹被物理删除后仍可读）',
  `order_id` BIGINT DEFAULT NULL COMMENT '【已登记冗余列】冗余订单ID：包裹行被物理删除（OrderPurgeService）后，仍能从订单反查包裹状态流水',
  `from_status` TINYINT DEFAULT NULL COMMENT '变更前状态；NULL=出生事件（建包裹）',
  `to_status` TINYINT NOT NULL COMMENT '变更后状态',
  `reason` VARCHAR(32) NOT NULL COMMENT '流转原因码',
  `reason_detail` VARCHAR(255) DEFAULT NULL COMMENT '原因补充说明',
  `operator_type` VARCHAR(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID（系统事件可空）',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type` VARCHAR(24) DEFAULT NULL COMMENT '触发单据类型',
  `ref_id` BIGINT DEFAULT NULL COMMENT '触发单据ID',
  `event_key` VARCHAR(96) DEFAULT NULL COMMENT '仓侧事件号（重投只留一条；允许多 NULL）',
  `idem_key` VARCHAR(96) NOT NULL COMMENT '幂等键 [shard:]{deliveryId}:{statusSeq}',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`delivery_id`, `created_at`),
  KEY `idx_order` (`order_id`, `created_at`),
  CONSTRAINT `ck_delivery_status_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='包裹状态台账（只追加证据链；带单号快照 + 冗余订单ID）';

-- 售后状态台账（冗余 order_id：return_apply 随订单级联删除/物理删单后仍能反查）
CREATE TABLE `return_status_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `return_apply_id` BIGINT NOT NULL COMMENT '退货申请ID（业务主键·无外键：证据不随申请级联删除）',
  `order_id` BIGINT DEFAULT NULL COMMENT '【已登记冗余列】冗余订单ID：return_apply 随订单级联删除/物理删单后，仍能从订单反查售后流水',
  `from_status` TINYINT DEFAULT NULL COMMENT '变更前状态；NULL=出生事件（提交申请）',
  `to_status` TINYINT NOT NULL COMMENT '变更后状态',
  `reason` VARCHAR(32) NOT NULL COMMENT '流转原因码',
  `reason_detail` VARCHAR(255) DEFAULT NULL COMMENT '原因补充说明',
  `operator_type` VARCHAR(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID（系统事件可空）',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type` VARCHAR(24) DEFAULT NULL COMMENT '触发单据类型',
  `ref_id` BIGINT DEFAULT NULL COMMENT '触发单据ID',
  `event_key` VARCHAR(96) DEFAULT NULL COMMENT '外部事件号（重投只留一条；允许多 NULL）',
  `idem_key` VARCHAR(96) NOT NULL COMMENT '幂等键 [shard:]{returnApplyId}:{statusSeq}',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`return_apply_id`, `created_at`),
  CONSTRAINT `ck_return_status_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='售后状态台账（只追加证据链）';

-- 后台操作审计（⚠️ idem_key 必须由 request_id 派生，严禁业务键派生：见 migrate-ledger.sql 列注释红线）
CREATE TABLE `admin_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator_type` VARCHAR(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作者名快照',
  `action` VARCHAR(48) NOT NULL COMMENT '动作码：改价/删单/拆单/合并/发货/退货审核…',
  `target_type` VARCHAR(24) NOT NULL COMMENT '目标类型（多态）：ORDER/PACKAGE/RETURN/SKU/MEMBER/APIKEY',
  `target_id` BIGINT DEFAULT NULL COMMENT '目标ID（可空：目标可能已被物理删除）',
  `target_sn` VARCHAR(64) DEFAULT NULL COMMENT '目标单号或名称快照（目标被删后仍可读）',
  `summary` VARCHAR(255) DEFAULT NULL COMMENT '一句人话摘要，列表页直接展示',
  `reason` VARCHAR(32) DEFAULT NULL COMMENT '管理员填写的原因，敏感动作必填',
  `order_id` BIGINT DEFAULT NULL COMMENT '落在哪张订单上（冗余，便于按订单检索）',
  `before_json` JSON DEFAULT NULL COMMENT '变更前快照',
  `after_json` JSON DEFAULT NULL COMMENT '变更后快照',
  `request_id` VARCHAR(64) DEFAULT NULL COMMENT '请求追踪号，一次请求一条',
  `idem_key` VARCHAR(96) NOT NULL COMMENT '⚠️红线：必须由 request_id 派生（如 request_id 或 request_id:seq），严禁由业务键派生。业务键派生会让"连点两次"的第二次撞 uk_idem → INSERT 失败 → 事务回滚 → 本该留下的失败证据被抹掉，与"一次请求一条、连点留两条"的审计语义直接冲突',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_operator` (`operator_id`, `created_at`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `ck_admin_audit_log_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='后台操作审计日志（只追加，不修改不软删）';

-- WMS 下发/回传（REQ/ACK 两阶段 write-ahead）
CREATE TABLE `wms_exchange_ledger` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` BIGINT NOT NULL COMMENT '包裹ID（无外键：证据不随包裹级联删除）',
  `direction` VARCHAR(8) NOT NULL COMMENT 'REQ=下发 / ACK=回传',
  `attempt_no` INT NOT NULL DEFAULT 1 COMMENT '第几次尝试（重试用，从 1 开始）',
  `request_payload` JSON DEFAULT NULL COMMENT '下发报文',
  `response_payload` JSON DEFAULT NULL COMMENT '回传报文（REQ 行落库时为 NULL）',
  `result` VARCHAR(16) DEFAULT NULL COMMENT 'SUCCESS / FAIL / TIMEOUT（REQ 行未定论时为 NULL）',
  `latency_ms` INT DEFAULT NULL COMMENT '往返耗时（毫秒）',
  `operator_type` VARCHAR(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID（系统重试可空）',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `idem_key` VARCHAR(96) NOT NULL COMMENT '幂等键 {deliveryId}:{direction}:{attemptNo}（如 3364:REQ:1）',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_delivery` (`delivery_id`, `created_at`),
  CONSTRAINT `ck_wms_exchange_ledger_direction` CHECK (`direction` IN ('REQ','ACK')),
  CONSTRAINT `ck_wms_exchange_ledger_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WMS 下发/回传台账（REQ/ACK 两阶段 write-ahead）';

-- ============================================================================
-- 自动审核模块（sql/migrate-order-audit.sql 的等价 DDL）
--   策略 → 判定 → 决策落库 → 过审；冻结用标（order_audit_tag）不用状态码。
-- ============================================================================

CREATE TABLE `audit_policy` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `type`        ENUM('AUTO','BLOCK','EXCEPTION') NOT NULL     COMMENT 'AUTO=自动过审候选 / BLOCK=前置拦截 / EXCEPTION=异常冻结',
  `priority`    INT         NOT NULL DEFAULT 100             COMMENT '同类型内升序执行：数字小的先跑',
  `name`        VARCHAR(64) NOT NULL                         COMMENT '策略名（决策行会快照它，改名不影响历史决策）',
  `enabled`     TINYINT     NOT NULL DEFAULT 1               COMMENT '1启用 0停用（停用不删，历史决策仍指得到）',
  `version_no`  INT         NOT NULL DEFAULT 1               COMMENT '策略版本号：每次改条件 +1，决策行记当时版本',
  `action_json` JSON        DEFAULT NULL                     COMMENT 'EXCEPTION 专属冻结动作：{"tag":"FROZEN","reason":"..."}；AUTO/BLOCK 必须 NULL（见 CHECK）',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_name` (`type`, `name`),
  KEY `idx_type_priority` (`type`, `priority`, `enabled`),
  -- type ↔ 专属列：EXCEPTION 必须有冻结动作；AUTO/BLOCK 不许带动作（带了就说明有人在配一个不会被执行的动作）
  CONSTRAINT `ck_audit_policy_type_action`
    CHECK ((`type` = 'EXCEPTION' AND `action_json` IS NOT NULL)
        OR (`type` IN ('AUTO','BLOCK') AND `action_json` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核策略主体（AUTO/BLOCK/EXCEPTION）';

-- 2. 策略条件（行模型）----------
CREATE TABLE `audit_policy_condition` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `policy_id`   BIGINT      DEFAULT NULL                     COMMENT '所属策略；scope=ALL_AUTO 时必须 NULL（全局排除不属于任何策略）',
  `seq`         INT         NOT NULL DEFAULT 0                COMMENT '展示/求值顺序（升序）',
  `kind`        ENUM('MATCH','EXCLUDE') NOT NULL              COMMENT 'MATCH=必须满足 / EXCLUDE=命中即排除',
  `scope`       ENUM('POLICY','ALL_AUTO') NOT NULL DEFAULT 'POLICY' COMMENT 'POLICY=本策略级 / ALL_AUTO=全部 AUTO 策略共用（全局排除）',
  `field_code`  VARCHAR(48) NOT NULL                          COMMENT '字段码；**不做 DDL CHECK 闭集**，由 FieldRegistry 校验',
  `op_code`     VARCHAR(24) NOT NULL                          COMMENT '操作符码；由 OperationRegistry 校验',
  `value_type`  ENUM('NUM','TEXT','BOOL','JSON') NOT NULL     COMMENT '值的类型（决定读哪个 value_* 列）',
  `value_num`   DECIMAL(20,4) DEFAULT NULL                    COMMENT 'NUM/BOOL 值（BOOL 用 1/0）',
  `value_text`  VARCHAR(255) DEFAULT NULL                     COMMENT 'TEXT 值',
  `value_json`  JSON        DEFAULT NULL                      COMMENT 'JSON 值（预留：IN 列表 / 区间等）',
  PRIMARY KEY (`id`),
  KEY `idx_policy_seq` (`policy_id`, `seq`),
  KEY `idx_scope_kind` (`scope`, `kind`),
  CONSTRAINT `fk_audit_policy_condition_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `audit_policy` (`id`) ON DELETE CASCADE,
  -- 全局排除只能是 EXCLUDE（ALL_AUTO 的 MATCH 没有语义：没有 owner 就没有「它的策略」可满足）
  CONSTRAINT `ck_audit_policy_condition_scope_kind`
    CHECK (`scope` = 'POLICY' OR `kind` = 'EXCLUDE'),
  -- 归属：POLICY 级必须挂策略，ALL_AUTO 级必须不挂（否则删策略会顺手删掉全局排除）
  CONSTRAINT `ck_audit_policy_condition_owner`
    CHECK ((`scope` = 'POLICY' AND `policy_id` IS NOT NULL)
        OR (`scope` = 'ALL_AUTO' AND `policy_id` IS NULL)),
  -- value_type ↔ value_* 列对应；一元操作符（EXISTS / NOT_EXISTS）允许三列全 NULL
  CONSTRAINT `ck_audit_policy_condition_value`
    CHECK ((`value_num` IS NULL AND `value_text` IS NULL AND `value_json` IS NULL)
        OR (`value_type` = 'NUM'  AND `value_num`  IS NOT NULL AND `value_text` IS NULL     AND `value_json` IS NULL)
        OR (`value_type` = 'BOOL' AND `value_num`  IS NOT NULL AND `value_text` IS NULL     AND `value_json` IS NULL)
        OR (`value_type` = 'TEXT' AND `value_text` IS NOT NULL AND `value_num`  IS NULL     AND `value_json` IS NULL)
        OR (`value_type` = 'JSON' AND `value_json` IS NOT NULL AND `value_num`  IS NULL     AND `value_text` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核策略条件（行模型：一条条件一行，不用 JSON 列）';

CREATE TABLE `audit_policy_version` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT        COMMENT '主键',
  `policy_id`     BIGINT      NOT NULL                       COMMENT '所属策略（随策略级联删除）',
  `version_no`    INT         NOT NULL                       COMMENT '版本号（与 audit_policy.version_no 对齐）',
  `snapshot_json` JSON        NOT NULL                       COMMENT '策略 + 全部条件的快照（改完再看历史决策能还原当时配置）',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_version` (`policy_id`, `version_no`),
  CONSTRAINT `fk_audit_policy_version_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `audit_policy` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='审核策略版本快照（append-only）';

CREATE TABLE `audit_run` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT         COMMENT '主键',
  `trigger_type` ENUM('MANUAL','SCHEDULE') NOT NULL           COMMENT 'MANUAL=人工触发 / SCHEDULE=定时任务',
  `idem_key`     VARCHAR(96) DEFAULT NULL                     COMMENT '⚠️仅 SCHEDULE 填 {trigger}:{yyyyMMddHHmm}；严禁业务键派生，MANUAL 一律 NULL',
  `total`        INT         NOT NULL DEFAULT 0               COMMENT '本轮产出决策的单数（含 SKIPPED：五类之和可能小于 total）',
  `auto_pass`    INT         NOT NULL DEFAULT 0               COMMENT '自动过审数',
  `manual`       INT         NOT NULL DEFAULT 0               COMMENT '转人工数（含降级）',
  `blocked`      INT         NOT NULL DEFAULT 0               COMMENT '被 BLOCK 策略拦截数',
  `frozen`       INT         NOT NULL DEFAULT 0               COMMENT '异常冻结数',
  `no_match`     INT         NOT NULL DEFAULT 0               COMMENT '没有任何 AUTO 策略命中数',
  `created_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='一次自动审核 run（计数在建 run 时写 0、跑完回写一次）';

-- 5. 决策台账（每单每轮一行）----------
CREATE TABLE `order_audit_decision` (
  `id`                        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`                  BIGINT      NOT NULL                COMMENT '订单ID（业务主键·无外键：证据不随订单级联删除）',
  `run_id`                    BIGINT      NOT NULL                COMMENT '所属 run（audit_run.id，无外键同理）',
  `round_no`                  INT         NOT NULL                COMMENT '该订单的第几轮审核（1 起，按 MAX+1）',
  `result`                    ENUM('AUTO_PASS','MANUAL','BLOCKED','FROZEN','NO_MATCH','SKIPPED') NOT NULL COMMENT '本轮判定结论',
  `matched_policy_id`         BIGINT      DEFAULT NULL            COMMENT '命中的策略（AUTO_PASS/BLOCKED/FROZEN）',
  `matched_policy_name`       VARCHAR(64) DEFAULT NULL            COMMENT '命中策略名快照',
  `matched_policy_version_no` INT         DEFAULT NULL            COMMENT '命中策略当时的版本号',
  `matched_condition_json`    JSON        DEFAULT NULL            COMMENT '命中/满足的条件明细（含 expected vs actual）',
  `excluded_by_json`          JSON        DEFAULT NULL            COMMENT '排除条件命中明细（POLICY ∪ ALL_AUTO）',
  `miss_reason_json`          JSON        DEFAULT NULL            COMMENT '逐条策略「差在哪」（NO_MATCH / 未命中时）',
  `would_be_auto_policy_id`   BIGINT      DEFAULT NULL            COMMENT '只读回填：若没被拦截/冻结，会命中哪条 AUTO 策略',
  `would_be_auto_policy_name` VARCHAR(64) DEFAULT NULL            COMMENT '只读回填的策略名快照',
  `downgrade_reason`          VARCHAR(32) DEFAULT NULL            COMMENT 'AUTO_PASS 后预检降级原因：STOCK_SHORT / COST_MISSING',
  `evidence_json`             JSON        DEFAULT NULL            COMMENT '证据：人话原因（含具体数值差）+ 库存/成本/冻结动作细节',
  `order_status_seq`          INT         DEFAULT NULL            COMMENT '本轮订单状态流转后的 status_seq（对得上 order_status_ledger）',
  `operator_type`             VARCHAR(16) NOT NULL                COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id`               BIGINT      DEFAULT NULL            COMMENT '操作者ID（系统/调度为 NULL）',
  `operator_name`             VARCHAR(64) DEFAULT NULL            COMMENT '操作者名快照',
  `idem_key`                  VARCHAR(96) NOT NULL                COMMENT '幂等键 {orderId}:{roundNo}',
  `created_at`                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '判定时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_order_round` (`order_id`, `round_no`),
  KEY `idx_run_result` (`run_id`, `result`),
  KEY `idx_order` (`order_id`, `created_at`),
  CONSTRAINT `ck_order_audit_decision_operator_type`
    CHECK (`operator_type` IN ('ADMIN','MEMBER','APIKEY','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单审核决策台账（每单每轮一行，只追加）';

-- 6. 订单审核标记（冻结/挂起不是状态码）----------
CREATE TABLE `order_audit_tag` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT          COMMENT '主键',
  `order_id`    BIGINT      NOT NULL                         COMMENT '订单ID',
  `tag_code`    VARCHAR(32) NOT NULL                         COMMENT 'FROZEN / STOCK_SHORT / COST_MISSING …',
  `active_tag`  VARCHAR(48) GENERATED ALWAYS AS (IF(`active` = 1, CONCAT(`order_id`, ':', `tag_code`), NULL)) VIRTUAL
                                                            COMMENT '仅 active 时非空的唯一键载体：{orderId}:{tagCode}',
  `active`      TINYINT     NOT NULL DEFAULT 1               COMMENT '1生效 0已解除',
  `reason`      VARCHAR(255) DEFAULT NULL                    COMMENT '为什么打这个标',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '打标时间',
  `released_at` DATETIME(3) DEFAULT NULL                     COMMENT '解除时间（解冻/恢复）',
  `released_by` VARCHAR(64) DEFAULT NULL                     COMMENT '解除人（ADMIN 用户名 / APIKEY 名 / system）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active` (`active_tag`),
  KEY `idx_order` (`order_id`, `tag_code`),
  CONSTRAINT `ck_order_audit_tag_active` CHECK (`active` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单审核标记（冻结/挂起用标表达，不动 order.status）';

-- ============================================================================
-- 业务状态时间线只读视图（P3 UI 用；派生自三张状态台账，UNION ALL 带 biz_type）
-- 完整注释见 sql/migrate-ledger-view.sql（含查询示例与口径说明）。
-- ============================================================================
DROP VIEW IF EXISTS `v_biz_status_timeline`;
CREATE VIEW `v_biz_status_timeline` AS
SELECT
    CAST('ORDER' AS CHAR(8))          AS `biz_type`,
    CAST(l.`order_id` AS SIGNED)      AS `biz_id`,
    CAST(l.`order_id` AS SIGNED)      AS `order_id`,
    CAST(NULL AS CHAR(50))            AS `delivery_sn`,
    CAST(l.`from_status` AS SIGNED)   AS `from_status`,
    CAST(l.`to_status` AS SIGNED)     AS `to_status`,
    CAST(CASE l.`from_status`
              WHEN 0 THEN '待付款' WHEN 1 THEN '已付款' WHEN 2 THEN '已发货' WHEN 3 THEN '已完成'
              WHEN 4 THEN '已关闭' WHEN 5 THEN '退款中' WHEN 6 THEN '已退款' WHEN 7 THEN '待审核'
              WHEN 8 THEN '待发货' WHEN 9 THEN '部分发货' ELSE NULL END AS CHAR(12)) AS `from_status_text`,
    CAST(CASE l.`to_status`
              WHEN 0 THEN '待付款' WHEN 1 THEN '已付款' WHEN 2 THEN '已发货' WHEN 3 THEN '已完成'
              WHEN 4 THEN '已关闭' WHEN 5 THEN '退款中' WHEN 6 THEN '已退款' WHEN 7 THEN '待审核'
              WHEN 8 THEN '待发货' WHEN 9 THEN '部分发货' ELSE NULL END AS CHAR(12)) AS `to_status_text`,
    CAST(l.`reason` AS CHAR(32))                          AS `reason`,
    CAST(l.`reason_detail` AS CHAR(255))                  AS `reason_detail`,
    CAST(l.`operator_type` AS CHAR(16))                   AS `operator_type`,
    CAST(l.`operator_id` AS SIGNED)                       AS `operator_id`,
    CAST(l.`operator_name` AS CHAR(64))                   AS `operator_name`,
    CAST(l.`ref_type` AS CHAR(24))                        AS `ref_type`,
    CAST(l.`ref_id` AS SIGNED)                            AS `ref_id`,
    CAST(l.`event_key` AS CHAR(96))                       AS `event_key`,
    CAST(l.`idem_key` AS CHAR(96))                        AS `idem_key`,
    l.`created_at`                                        AS `created_at`,
    CAST(l.`id` AS SIGNED)                                AS `id`
FROM `order_status_ledger` l
UNION ALL
SELECT
    CAST('DELIVERY' AS CHAR(8)),
    CAST(l.`delivery_id` AS SIGNED),
    CAST(l.`order_id` AS SIGNED),
    CAST(l.`delivery_sn` AS CHAR(50)),
    CAST(l.`from_status` AS SIGNED),
    CAST(l.`to_status` AS SIGNED),
    CAST(CASE l.`from_status`
              WHEN 0 THEN '待下发' WHEN 1 THEN '已下发' WHEN 2 THEN '已拣货' WHEN 3 THEN '已出库'
              WHEN 4 THEN '运输中' WHEN 5 THEN '已签收' WHEN 9 THEN '已取消' ELSE NULL END AS CHAR(12)),
    CAST(CASE l.`to_status`
              WHEN 0 THEN '待下发' WHEN 1 THEN '已下发' WHEN 2 THEN '已拣货' WHEN 3 THEN '已出库'
              WHEN 4 THEN '运输中' WHEN 5 THEN '已签收' WHEN 9 THEN '已取消' ELSE NULL END AS CHAR(12)),
    CAST(l.`reason` AS CHAR(32)),
    CAST(l.`reason_detail` AS CHAR(255)),
    CAST(l.`operator_type` AS CHAR(16)),
    CAST(l.`operator_id` AS SIGNED),
    CAST(l.`operator_name` AS CHAR(64)),
    CAST(l.`ref_type` AS CHAR(24)),
    CAST(l.`ref_id` AS SIGNED),
    CAST(l.`event_key` AS CHAR(96)),
    CAST(l.`idem_key` AS CHAR(96)),
    l.`created_at`,
    CAST(l.`id` AS SIGNED)
FROM `delivery_status_ledger` l
UNION ALL
SELECT
    CAST('RETURN' AS CHAR(8)),
    CAST(l.`return_apply_id` AS SIGNED),
    CAST(l.`order_id` AS SIGNED),
    CAST(NULL AS CHAR(50)),
    CAST(l.`from_status` AS SIGNED),
    CAST(l.`to_status` AS SIGNED),
    CAST(CASE l.`from_status`
              WHEN 0 THEN '待处理' WHEN 1 THEN '已通过' WHEN 2 THEN '已拒绝' ELSE NULL END AS CHAR(12)),
    CAST(CASE l.`to_status`
              WHEN 0 THEN '待处理' WHEN 1 THEN '已通过' WHEN 2 THEN '已拒绝' ELSE NULL END AS CHAR(12)),
    CAST(l.`reason` AS CHAR(32)),
    CAST(l.`reason_detail` AS CHAR(255)),
    CAST(l.`operator_type` AS CHAR(16)),
    CAST(l.`operator_id` AS SIGNED),
    CAST(l.`operator_name` AS CHAR(64)),
    CAST(l.`ref_type` AS CHAR(24)),
    CAST(l.`ref_id` AS SIGNED),
    CAST(l.`event_key` AS CHAR(96)),
    CAST(l.`idem_key` AS CHAR(96)),
    l.`created_at`,
    CAST(l.`id` AS SIGNED)
FROM `return_status_ledger` l;


-- ============================================================================
-- 多平台接入（channel_*）：只做接入闭环，详见 sql/migrate-channel.sql
--   三张表都不带外键（台账/日志表一律无外键）；uk_map 是重复拉单的幂等闸门。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `channel_shop` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform_type` ENUM('MALL','TAOBAO_MOCK','DOUYIN_MOCK') NOT NULL COMMENT '平台类型：MALL=商城自营开放接口；后两个=本地 mock 平台',
  `shop_code` VARCHAR(64) NOT NULL COMMENT '平台侧店铺编码（调接口时带上）',
  `shop_name` VARCHAR(64) NOT NULL COMMENT '店铺名（后台卡片标题）',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用（停用后不允许拉单/回传）',
  `watermark_at` DATETIME DEFAULT NULL COMMENT '拉单水位：上次成功拉单的开始时刻，下次 since 传它',
  `last_pull_at` DATETIME DEFAULT NULL COMMENT '上次拉单结束时刻（含失败），卡片上展示',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop` (`platform_type`, `shop_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道店铺';

CREATE TABLE IF NOT EXISTS `channel_order_map` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` BIGINT NOT NULL COMMENT 'channel_shop.id（无外键）',
  `platform_sn` VARCHAR(64) NOT NULL COMMENT '平台单号（MALL 直接用商城 order_sn）',
  `order_id` BIGINT NOT NULL COMMENT '内部订单 order.id',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_map` (`shop_id`, `platform_sn`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台单号→内部订单映射（幂等闸门）';

CREATE TABLE IF NOT EXISTS `channel_sync_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` BIGINT NOT NULL COMMENT 'channel_shop.id（无外键）',
  `action` ENUM('PULL','SHIP_PUSH') NOT NULL COMMENT '动作：PULL=拉单 SHIP_PUSH=回传发货',
  `ref_type` VARCHAR(32) DEFAULT NULL COMMENT '关联对象类型：ORDER / PLATFORM_ORDER / DELIVERY',
  `ref_id` BIGINT DEFAULT NULL COMMENT '关联对象 id',
  `request_payload` TEXT COMMENT '请求报文（截断）',
  `response_payload` TEXT COMMENT '响应报文（截断）',
  `result` ENUM('OK','FAIL','SKIP') NOT NULL COMMENT 'OK=成功 FAIL=失败 SKIP=幂等跳过',
  `reason` VARCHAR(255) DEFAULT NULL COMMENT '结果原因：IDEMPOTENT / SKU_NOT_MATCHED / 异常信息',
  `attempt` INT NOT NULL DEFAULT 1 COMMENT '第几次尝试（回传重试时第 2 次记 attempt=2）',
  `latency_ms` INT DEFAULT NULL COMMENT '外呼耗时（毫秒）',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_time` (`shop_id`, `created_at`),
  KEY `idx_action_result` (`action`, `result`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道同步日志（只追加）';

INSERT INTO `channel_shop` (`platform_type`, `shop_code`, `shop_name`) VALUES
  ('MALL', 'MALL-001', '七七商城自营'),
  ('TAOBAO_MOCK', 'TB-001', '淘宝旗舰店（模拟）'),
  ('DOUYIN_MOCK', 'DY-001', '抖音小店（模拟）')
ON DUPLICATE KEY UPDATE `id` = `id`;
