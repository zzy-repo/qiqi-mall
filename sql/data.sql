-- 七七商城 示例数据（开发调试用，可重复执行）
USE qiqi_mall;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE cart;
TRUNCATE TABLE member_address;
TRUNCATE TABLE return_apply;
TRUNCATE TABLE order_item;
TRUNCATE TABLE `order`;
TRUNCATE TABLE return_reason;
TRUNCATE TABLE order_setting;
TRUNCATE TABLE sku;
TRUNCATE TABLE product;
TRUNCATE TABLE attribute;
TRUNCATE TABLE product_type;
TRUNCATE TABLE category;
TRUNCATE TABLE member;
TRUNCATE TABLE admin;
SET FOREIGN_KEY_CHECKS = 1;

-- ============ 用户模块 ============

-- 会员（密码统一 123456，BCrypt 存储）
INSERT INTO member (id, username, password, nickname, phone, status) VALUES
  (1, 'qiqi',      '$2a$10$V1FYkp/NYOZy3z2ZgUW/f.NCGkOcVTPS.AEMu.W3U.pvhKssTDJGS', '七七',   '13800000001', 1),
  (2, 'xiaoming',  '$2a$10$V1FYkp/NYOZy3z2ZgUW/f.NCGkOcVTPS.AEMu.W3U.pvhKssTDJGS', '小明',   '13800000002', 1),
  (3, 'laowang',   '$2a$10$V1FYkp/NYOZy3z2ZgUW/f.NCGkOcVTPS.AEMu.W3U.pvhKssTDJGS', '老王',   '13800000003', 0);

-- 收货地址
INSERT INTO member_address (id, member_id, name, phone, province, city, region, detail, default_status) VALUES
  (1, 1, '七七',  '13800000001', '广东省', '深圳市', '南山区', '科技园南路 1 号 301', 1),
  (2, 1, '七七',  '13800000001', '北京市', '北京市', '朝阳区', '建国路 88 号 A 座 1502', 0),
  (3, 2, '小明',  '13800000002', '上海市', '上海市', '浦东新区', '张江路 200 号', 1);

-- 购物车
INSERT INTO cart (id, member_id, product_id, product_sku_id, quantity) VALUES
  (1, 1, 1, 1, 1),
  (2, 1, 2, 4, 2),
  (3, 2, 4, 7, 1);

-- 管理员（密码统一 123456，BCrypt 存储）
INSERT INTO admin (id, username, password, nickname, email, status) VALUES
  (1, 'admin', '$2a$10$V1FYkp/NYOZy3z2ZgUW/f.NCGkOcVTPS.AEMu.W3U.pvhKssTDJGS', '超级管理员', 'admin@qiqi-mall.local', 1);

-- ============ 商品模块 ============

-- 商品分类（两级树）
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

-- 属性（聚合于类型）
INSERT INTO attribute (id, type_id, name, is_filter) VALUES
  (1, 1, '颜色', 1),
  (2, 1, '内存', 1),
  (3, 1, '屏幕尺寸', 0),
  (4, 2, '尺码', 1),
  (5, 2, '颜色', 1),
  (6, 3, '净含量', 0);

-- 商品（SPU）
INSERT INTO product (id, name, category_id, type_id, publish_status) VALUES
  (1, '七七 Pro 15 智能手机', 4, 1, 1),
  (2, '七七 Air 蓝牙耳机',   5, 1, 1),
  (3, '七七 Book 14 轻薄本', 6, 1, 0),
  (4, '纯棉基础款T恤',       7, 2, 1),
  (5, '手冲单品咖啡豆',      10, 3, 1);

-- SKU（聚合于商品）
INSERT INTO sku (id, product_id, spec_json, price, stock) VALUES
  (1, 1, '{"颜色":"黑","内存":"256G"}',  3999.00, 100),
  (2, 1, '{"颜色":"白","内存":"256G"}',  3999.00, 80),
  (3, 1, '{"颜色":"黑","内存":"512G"}',  4599.00, 50),
  (4, 2, '{"颜色":"星空灰"}',            599.00, 200),
  (5, 3, '{"内存":"16G","硬盘":"512G"}', 5499.00, 30),
  (6, 3, '{"内存":"32G","硬盘":"1T"}',   7499.00, 15),
  (7, 4, '{"颜色":"白","尺码":"M"}',      99.00, 500),
  (8, 4, '{"颜色":"黑","尺码":"L"}',      99.00, 300),
  (9, 5, '{"净含量":"250g"}',            128.00, 1000);

-- ============ 订单模块 ============

-- 退货原因字典
INSERT INTO return_reason (id, name) VALUES
  (1, '质量问题'),
  (2, '七天无理由'),
  (3, '发错货'),
  (4, '不想要了');

-- 订单
INSERT INTO `order` (id, order_sn, member_id, total_amount, status, pay_type, payment_time, receiver_name, receiver_phone, receiver_address) VALUES
  (1, 'QQ20261109001', 1, 7998.00, 1, 1, '2026-11-08 10:20:00', '七七', '13800000001', '广东省深圳市南山区科技园南路 1 号 301'),
  (2, 'QQ20261109002', 1, 599.00,  2, 2, '2026-11-08 14:35:00', '七七', '13800000001', '广东省深圳市南山区科技园南路 1 号 301'),
  (3, 'QQ20261109003', 2, 128.00,  0, 0, NULL, '小明', '13800000002', '上海市上海市浦东新区张江路 200 号'),
  (4, 'QQ20261109004', 2, 99.00,   4, 0, NULL, '小明', '13800000002', '上海市上海市浦东新区张江路 200 号');

-- 订单明细（下单快照）
INSERT INTO order_item (id, order_id, product_id, product_sku_id, product_name, product_price, product_quantity) VALUES
  (1, 1, 1, 1, '七七 Pro 15 智能手机', 3999.00, 2),
  (2, 2, 2, 4, '七七 Air 蓝牙耳机',    599.00, 1),
  (3, 3, 5, 9, '手冲单品咖啡豆',       128.00, 1),
  (4, 4, 4, 7, '纯棉基础款T恤',         99.00, 1);

-- 退货申请
INSERT INTO return_apply (id, order_id, member_id, return_reason, description, status, reason_id) VALUES
  (1, 2, 1, '发错货',   '收到的是黑色，下单的是星空灰', 0, 3),
  (2, 1, 1, '质量问题', '屏幕有坏点',                   1, 1);

-- 订单设置（全局单行）
INSERT INTO order_setting (id, auto_confirm_days, auto_close_minutes) VALUES
  (1, 7, 30);
