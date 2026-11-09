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
DROP TABLE IF EXISTS return_reason;
DROP TABLE IF EXISTS order_setting;
DROP TABLE IF EXISTS sku;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS attribute;
DROP TABLE IF EXISTS product_type;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS admin;
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
  `category_id` BIGINT NOT NULL COMMENT '所属分类',
  `type_id` BIGINT NOT NULL COMMENT '所属类型',
  `publish_status` TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
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
  `spec_json` VARCHAR(255) DEFAULT NULL COMMENT '规格，如 {"颜色":"黑","内存":"256G"}',
  `price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '价格',
  `stock` INT NOT NULL DEFAULT 0 COMMENT '库存',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_sku_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU';

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
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待付款 1已付款 2已发货 3已完成 4已关闭',
  `pay_type` TINYINT NOT NULL DEFAULT 0 COMMENT '0未支付 1支付宝 2微信',
  `payment_time` DATETIME DEFAULT NULL COMMENT '支付时间',
  `receiver_name` VARCHAR(50) NOT NULL COMMENT '收货人快照',
  `receiver_phone` VARCHAR(20) DEFAULT NULL COMMENT '收货电话快照',
  `receiver_address` VARCHAR(255) DEFAULT NULL COMMENT '收货地址快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  KEY `idx_member` (`member_id`),
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

-- 订单设置（全局单行配置）
CREATE TABLE `order_setting` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `auto_confirm_days` INT NOT NULL DEFAULT 7 COMMENT '自动收货天数',
  `auto_close_minutes` INT NOT NULL DEFAULT 30 COMMENT '未付款自动关单分钟数',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单设置';
