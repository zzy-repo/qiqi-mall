-- 七七商城 数据库结构
-- 依据 ER 图：商品分类 / 商品类型 / 属性 / 商品(SPU) / SKU

CREATE DATABASE IF NOT EXISTS qiqi_mall
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE qiqi_mall;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS sku;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS attribute;
DROP TABLE IF EXISTS product_type;
DROP TABLE IF EXISTS category;

-- 商品分类（自关联树）
CREATE TABLE category (
  id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  parent_id BIGINT       NOT NULL DEFAULT 0     COMMENT '父分类ID，0为顶级',
  name      VARCHAR(50)  NOT NULL               COMMENT '分类名称',
  PRIMARY KEY (id),
  KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品分类';

-- 商品类型（如：手机、服装）
CREATE TABLE product_type (
  id   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '类型ID',
  name VARCHAR(50) NOT NULL               COMMENT '类型名称',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品类型';

-- 属性（属于某个类型，如 手机-颜色）
CREATE TABLE attribute (
  id        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '属性ID',
  type_id   BIGINT      NOT NULL               COMMENT '所属商品类型ID',
  name      VARCHAR(50) NOT NULL               COMMENT '属性名称，如 颜色/内存',
  is_filter TINYINT     NOT NULL DEFAULT 0     COMMENT '是否可筛选：0否 1是',
  PRIMARY KEY (id),
  KEY idx_type_id (type_id),
  CONSTRAINT fk_attribute_type FOREIGN KEY (type_id) REFERENCES product_type (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品属性';

-- 商品 SPU
CREATE TABLE product (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商品ID(SPU)',
  name           VARCHAR(100) NOT NULL               COMMENT '商品名称',
  category_id    BIGINT       NOT NULL               COMMENT '所属分类ID',
  type_id        BIGINT       NOT NULL               COMMENT '引用商品类型ID',
  publish_status TINYINT      NOT NULL DEFAULT 0     COMMENT '上下架：0下架 1上架',
  PRIMARY KEY (id),
  KEY idx_category_id (category_id),
  KEY idx_type_id (type_id),
  CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id),
  CONSTRAINT fk_product_type     FOREIGN KEY (type_id)     REFERENCES product_type (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SPU';

-- SKU
CREATE TABLE sku (
  id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'SKU ID',
  product_id BIGINT        NOT NULL               COMMENT '所属商品ID',
  spec_json  VARCHAR(255)  NOT NULL               COMMENT '规格组合，如 {"颜色":"黑","内存":"256G"}',
  price      DECIMAL(10,2) NOT NULL DEFAULT 0.00  COMMENT '价格',
  stock      INT           NOT NULL DEFAULT 0     COMMENT '库存',
  PRIMARY KEY (id),
  KEY idx_product_id (product_id),
  CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品SKU';

-- ============ 订单模块 ============

-- 订单（status 状态机：0待付款 1已付款 2已发货 3已完成 4已关闭）
CREATE TABLE IF NOT EXISTS `order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
  `order_sn` VARCHAR(50) NOT NULL COMMENT '订单号',
  `member_id` BIGINT NOT NULL COMMENT '买家ID',
  `total_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态:0待付款 1已付款 2已发货 3已完成 4已关闭',
  `pay_type` TINYINT NOT NULL DEFAULT 0 COMMENT '支付方式:0未支付 1支付宝 2微信',
  `payment_time` DATETIME DEFAULT NULL COMMENT '支付时间',
  `receiver_name` VARCHAR(50) NOT NULL COMMENT '收货人姓名（快照）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  KEY `idx_member` (`member_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- 订单明细（聚合于订单）
CREATE TABLE IF NOT EXISTS `order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `product_id` BIGINT NOT NULL COMMENT '商品ID（SPU快照）',
  `product_sku_id` BIGINT NOT NULL COMMENT 'SKU ID（快照）',
  `product_name` VARCHAR(100) NOT NULL COMMENT '下单时商品名（快照）',
  `product_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '成交价（快照）',
  `product_quantity` INT NOT NULL DEFAULT 1 COMMENT '购买数量',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_order_item_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_order_item_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT `fk_order_item_sku` FOREIGN KEY (`product_sku_id`) REFERENCES `sku` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- 退货原因（字典）
CREATE TABLE IF NOT EXISTS `return_reason` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(50) NOT NULL COMMENT '原因名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货原因';

-- 退货申请（聚合于订单；status:0待处理 1通过 2拒绝）
CREATE TABLE IF NOT EXISTS `return_apply` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `member_id` BIGINT NOT NULL COMMENT '申请人ID',
  `return_reason` VARCHAR(200) NOT NULL COMMENT '退货原因描述',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '详细说明',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态:0待处理 1通过 2拒绝',
  `reason_id` BIGINT NOT NULL COMMENT '退货原因字典ID',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `fk_return_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_return_reason` FOREIGN KEY (`reason_id`) REFERENCES `return_reason` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货申请';

-- 订单设置（全局单行配置）
CREATE TABLE IF NOT EXISTS `order_setting` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `auto_confirm_days` INT NOT NULL DEFAULT 7 COMMENT '自动收货天数',
  `auto_close_minutes` INT NOT NULL DEFAULT 30 COMMENT '未付款自动关单分钟数',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单设置';

-- ============ 多租户模块 ============

-- 租户（tenant_id 全局根源）
CREATE TABLE IF NOT EXISTS `tenant` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'tenant_id 全局根源',
  `name` VARCHAR(100) NOT NULL COMMENT '公司名',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户';

-- 租户用户
CREATE TABLE IF NOT EXISTS `tenant_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT NOT NULL COMMENT '所属租户',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `password` VARCHAR(100) NOT NULL COMMENT '密码',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  CONSTRAINT `fk_tu_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户用户';

-- 套餐
CREATE TABLE IF NOT EXISTS `plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` VARCHAR(50) NOT NULL COMMENT '如:基础版/专业版',
  `order_quota` INT NOT NULL DEFAULT 0 COMMENT '订单量配额',
  `price` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '价格',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐';

-- 租户套餐订阅
CREATE TABLE IF NOT EXISTS `tenant_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
  `plan_id` BIGINT NOT NULL COMMENT '套餐ID',
  `expire_at` DATE NOT NULL COMMENT '到期时间',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  CONSTRAINT `fk_tp_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `fk_tp_plan` FOREIGN KEY (`plan_id`) REFERENCES `plan` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户套餐订阅';

-- 商品/订单增加租户隔离字段
ALTER TABLE `product` ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID' AFTER `id`;
ALTER TABLE `order` ADD COLUMN `tenant_id` BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID' AFTER `id`;

SET FOREIGN_KEY_CHECKS = 1;
