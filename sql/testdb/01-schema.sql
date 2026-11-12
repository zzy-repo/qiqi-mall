-- 七七商城 测试环境数据库结构（库名 qiqi_mall_test）
-- 与 sql/schema.sql 完全同构，只把库名换成测试库；由 scripts/init-test-db.sh 应用到 mysql 容器。
-- 改表结构时两处都要改（dev 库 / 测试库分库，互不影响）。
-- 七七商城 数据库结构
-- 依据 ER 图：商品模块（分类/类型/属性/商品/SKU）
--             订单模块（订单/明细/退货申请/退货原因/订单设置）
--             用户模块（会员/收货地址/购物车/管理员）

CREATE DATABASE IF NOT EXISTS qiqi_mall_test
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE qiqi_mall_test;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS cart;
DROP TABLE IF EXISTS member_address;
DROP TABLE IF EXISTS return_apply;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS return_reason;
DROP TABLE IF EXISTS order_setting;
DROP TABLE IF EXISTS delivery_logistics;
DROP TABLE IF EXISTS delivery_item;
DROP TABLE IF EXISTS delivery_order;
DROP TABLE IF EXISTS inventory;
DROP TABLE IF EXISTS warehouse;
DROP TABLE IF EXISTS payment_record;
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
-- 旧版本残留表（多租户方案已废弃）
DROP TABLE IF EXISTS tenant_plan;
DROP TABLE IF EXISTS tenant_user;
DROP TABLE IF EXISTS plan;
DROP TABLE IF EXISTS tenant;

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
  `stock` INT NOT NULL DEFAULT 0 COMMENT '库存',
  `pic` VARCHAR(500) DEFAULT NULL COMMENT 'SKU图URL（指向 MinIO）',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_sku_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU';

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
  `receiver_name` VARCHAR(50) NOT NULL COMMENT '收货人快照',
  `receiver_phone` VARCHAR(20) DEFAULT NULL COMMENT '收货电话快照',
  `receiver_address` VARCHAR(255) DEFAULT NULL COMMENT '收货地址快照',
  `split_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0未拆(单仓直发) 1已拆',
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
  `amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '金额',
  `type` TINYINT NOT NULL DEFAULT 1 COMMENT '1支付 2退款',
  `channel` TINYINT NOT NULL DEFAULT 0 COMMENT '0模拟 1支付宝 2微信',
  `trade_no` VARCHAR(64) DEFAULT NULL COMMENT '第三方流水号（模拟为本地生成）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_sn` (`order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水';

-- 订单设置（全局单行配置）
CREATE TABLE `order_setting` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `auto_confirm_days` INT NOT NULL DEFAULT 7 COMMENT '自动收货天数',
  `auto_close_minutes` INT NOT NULL DEFAULT 30 COMMENT '未付款自动关单分钟数',
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
