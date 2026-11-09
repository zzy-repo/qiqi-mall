-- 七七商城 数据库结构
-- 依据 ER 图：商品分类 / 商品类型 / 属性 / 商品(SPU) / SKU

CREATE DATABASE IF NOT EXISTS qiqi_mall
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE qiqi_mall;

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
