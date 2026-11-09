-- 示例数据（开发调试用）
USE qiqi_mall;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE sku;
TRUNCATE TABLE product;
TRUNCATE TABLE attribute;
TRUNCATE TABLE product_type;
TRUNCATE TABLE category;
TRUNCATE TABLE `order`;
TRUNCATE TABLE order_item;
TRUNCATE TABLE return_apply;
TRUNCATE TABLE return_reason;
TRUNCATE TABLE order_setting;
SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO category (id, parent_id, name) VALUES
  (1, 0, '手机数码'),
  (2, 0, '服装'),
  (3, 0, '食品饮料'),
  (4, 1, '手机'),
  (5, 1, '耳机'),
  (6, 1, '笔记本'),
  (7, 2, '男装'),
  (8, 2, '女装'),
  (9, 3, '零食'),
  (10, 3, '咖啡');

-- 商品类型
INSERT INTO product_type (id, name) VALUES
  (1, '手机'),
  (2, '服装'),
  (3, '食品');

-- 属性
INSERT INTO attribute (id, type_id, name, is_filter) VALUES
  (1, 1, '颜色', 1),
  (2, 1, '内存', 1),
  (3, 1, '屏幕尺寸', 0),
  (4, 2, '颜色', 1),
  (5, 2, '尺码', 1),
  (6, 3, '净含量', 0);

-- 商品 SPU
INSERT INTO product (id, name, category_id, type_id, publish_status) VALUES
  (1, '七七 Pro 15 智能手机', 4, 1, 1),
  (2, '七七 Air 蓝牙耳机', 5, 1, 1),
  (3, '七七 Book 14 轻薄本', 6, 1, 0),
  (4, '纯棉基础款T恤', 7, 2, 1),
  (5, '手冲单品咖啡豆', 10, 3, 1);

-- SKU
INSERT INTO sku (id, product_id, spec_json, price, stock) VALUES
  (1, 1, '{"颜色":"曜石黑","内存":"12G+256G"}', 3999.00, 120),
  (2, 1, '{"颜色":"曜石黑","内存":"16G+512G"}', 4599.00, 80),
  (3, 1, '{"颜色":"月光白","内存":"12G+256G"}', 3999.00, 60),
  (4, 2, '{"颜色":"钛银"}', 599.00, 300),
  (5, 2, '{"颜色":"曜石黑"}', 599.00, 280),
  (6, 3, '{"颜色":"深空灰","内存":"16G+1T"}', 6299.00, 45),
  (7, 4, '{"颜色":"白色","尺码":"L"}', 99.00, 500),
  (8, 4, '{"颜色":"黑色","尺码":"M"}', 99.00, 480),
  (9, 5, '{"净含量":"250g"}', 128.00, 200);

-- ============ 订单模块示例数据 ============

-- 退货原因字典
INSERT INTO return_reason (id, name) VALUES
  (1, '质量问题'),
  (2, '七天无理由'),
  (3, '发错货'),
  (4, '不想要了');

-- 订单（member_id 用 1 表示同一个测试买家）
INSERT INTO `order` (id, order_sn, member_id, total_amount, status, pay_type, payment_time, receiver_name) VALUES
  (1, 'QQ20261109001', 1, 7998.00, 1, 1, '2026-11-08 10:20:00', '七七'),
  (2, 'QQ20261109002', 1, 599.00,  2, 2, '2026-11-08 14:35:00', '七七'),
  (3, 'QQ20261109003', 1, 128.00,  0, 0, NULL, '七七'),
  (4, 'QQ20261109004', 1, 99.00,   4, 0, NULL, '七七');

-- 订单明细
INSERT INTO order_item (id, order_id, product_id, product_sku_id, product_name, product_price, product_quantity) VALUES
  (1, 1, 1, 1, '七七 Pro 15 智能手机', 3999.00, 2),
  (2, 2, 2, 4, '七七 Air 蓝牙耳机', 599.00, 1),
  (3, 3, 5, 9, '手冲单品咖啡豆', 128.00, 1),
  (4, 4, 4, 7, '纯棉基础款T恤', 99.00, 1);

-- 退货申请（订单2 发错货申请中，订单1 已通过）
INSERT INTO return_apply (id, order_id, member_id, return_reason, description, status, reason_id) VALUES
  (1, 2, 1, '发错货', '收到的是黑色，下单的是钛银色', 0, 3),
  (2, 1, 1, '质量问题', '屏幕有坏点', 1, 1);

-- 订单设置（全局单行）
INSERT INTO order_setting (id, auto_confirm_days, auto_close_minutes) VALUES
  (1, 7, 30);
