-- 七七商城 示例数据（开发调试用，可重复执行）
-- ⚠️ 库名不写在本文件里（无 USE 语句、无建库语句），必须由调用方指定：
--       mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/data.sql
--    docker-compose 的 mysql 首次初始化会自动按 MYSQL_DATABASE 选库，不需要 USE。
--    写死库名会让"重置某个库"变成"重置它写死的那个库"（2026-11-13 两次事故的根因，
--    详见 sql/_rollback/README.txt「库名不许写进 SQL」一节）。

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE cart;
TRUNCATE TABLE member_address;
TRUNCATE TABLE return_apply;
TRUNCATE TABLE order_item;
TRUNCATE TABLE `order`;
TRUNCATE TABLE stock_ledger;
TRUNCATE TABLE order_status_ledger;
TRUNCATE TABLE delivery_status_ledger;
TRUNCATE TABLE return_status_ledger;
TRUNCATE TABLE admin_audit_log;
TRUNCATE TABLE wms_exchange_ledger;
TRUNCATE TABLE return_reason;
TRUNCATE TABLE order_setting;
TRUNCATE TABLE payment_record;
TRUNCATE TABLE delivery_logistics;
TRUNCATE TABLE delivery_item;
TRUNCATE TABLE delivery_order;
TRUNCATE TABLE inventory;
TRUNCATE TABLE warehouse;
TRUNCATE TABLE sku_bucket;
TRUNCATE TABLE sku_spec;
TRUNCATE TABLE product_gallery;
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
-- 图片存相对路径 /qiqi-mall/seed/**，由前端 nginx / vite 反代到 MinIO（换访问域名不用改数据）；
-- 这些对象由 `node scripts/fetch-seed-data.mjs` 首次运行时写入 MinIO。
INSERT INTO product (id, name, category_id, type_id, publish_status, pic) VALUES
  (1, '七七 Pro 15 智能手机', 4, 1, 1, '/qiqi-mall/seed/product/1-cover.webp'),
  (2, '七七 Air 蓝牙耳机',   5, 1, 1, '/qiqi-mall/seed/product/2-cover.webp'),
  (3, '七七 Book 14 轻薄本', 6, 1, 0, '/qiqi-mall/seed/product/3-cover.webp'),
  (4, '纯棉基础款T恤',       7, 2, 1, '/qiqi-mall/seed/product/4-cover.webp'),
  (5, '手冲单品咖啡豆',      10, 3, 1, '/qiqi-mall/seed/product/5-cover.webp');

-- SKU（聚合于商品）
INSERT INTO sku (id, product_id, price, stock, pic) VALUES
  (1, 1, 3999.00, 100, '/qiqi-mall/seed/sku/1.webp'),
  (2, 1, 3999.00, 80,  '/qiqi-mall/seed/sku/2.webp'),
  (3, 1, 4599.00, 50,  '/qiqi-mall/seed/sku/3.webp'),
  (4, 2, 599.00,  200, '/qiqi-mall/seed/sku/4.webp'),
  (5, 3, 5499.00, 30,  '/qiqi-mall/seed/sku/5.webp'),
  (6, 3, 7499.00, 15,  '/qiqi-mall/seed/sku/6.webp'),
  (7, 4, 99.00,   500, '/qiqi-mall/seed/sku/7.webp'),
  (8, 4, 99.00,   300, '/qiqi-mall/seed/sku/8.webp'),
  (9, 5, 128.00,  1000,'/qiqi-mall/seed/sku/9.webp');

-- SKU 规格值（颜色/内存/尺码/净含量，引用 attribute 定义）
-- attribute: 1=手机-颜色 2=手机-内存 3=手机-屏幕尺寸 4=服装-尺码 5=服装-颜色 6=食品-净含量
-- ---- 库存分桶：把上面每个 SKU 的 sku.stock 平均拆到 8 个桶（余数进 0 号桶）----
-- 口径与 sql/migrate-sku-bucket.sql 的回填**完全一致**：这是同一份库存的物理拆分，不是第二份库存。
-- 拆分后 SUM(sku_bucket.stock) == sku.stock（守恒）；之后 sku.stock 只是展示缓存，
-- 真正的扣减/回补只动 sku_bucket。NOT EXISTS 守卫 = 已有桶的 SKU 整行跳过（可反复重跑）。
INSERT INTO sku_bucket (sku_id, bucket_no, stock)
SELECT s.id, seq.n,
       FLOOR(s.stock / 8) + IF(seq.n = 0, s.stock - FLOOR(s.stock / 8) * 8, 0)
  FROM sku s
  CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
              UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) seq
 WHERE NOT EXISTS (SELECT 1 FROM sku_bucket b WHERE b.sku_id = s.id);

INSERT INTO sku_spec (id, sku_id, attribute_id, value) VALUES
  (1, 1, 1, '黑'), (2, 1, 2, '256G'),
  (3, 2, 1, '白'), (4, 2, 2, '256G'),
  (5, 3, 1, '黑'), (6, 3, 2, '512G'),
  (7, 4, 1, '星空灰'),
  (8, 5, 2, '16G'),
  (9, 6, 2, '32G'),
  (10, 7, 5, '白'), (11, 7, 4, 'M'),
  (12, 8, 5, '黑'), (13, 8, 4, 'L'),
  (14, 9, 6, '250g');

-- 商品相册
INSERT INTO product_gallery (id, product_id, url, sort) VALUES
  (1, 1, '/qiqi-mall/seed/product/1-1.webp', 1),
  (2, 1, '/qiqi-mall/seed/product/1-2.webp', 2),
  (3, 1, '/qiqi-mall/seed/product/1-3.webp', 3),
  (4, 2, '/qiqi-mall/seed/product/2-1.webp', 1),
  (5, 4, '/qiqi-mall/seed/product/4-1.webp', 1),
  (6, 5, '/qiqi-mall/seed/product/5-1.webp', 1);

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

-- 支付流水（订单1、2 已支付；订单1 尚未退款）
INSERT INTO payment_record (id, order_sn, amount, type, channel, trade_no) VALUES
  (1, 'QQ20261109001', 7998.00, 1, 1, 'MOCKPAY20261108000001'),
  (2, 'QQ20261109002', 599.00,  1, 2, 'MOCKPAY20261108000002');

-- ============ 拆单 / 发货单 ============

-- 仓库字典
INSERT INTO warehouse (id, name, status) VALUES
  (1, '深圳仓', 1),
  (2, '杭州仓', 1),
  (3, '北京仓', 1);

-- 分仓库存：SKU1/2/3 分别落在三个仓，便于演示"一单跨三仓"拆分
-- 其余 SKU 默认放深圳仓；inventory.stock 与 sku.stock 保持总量一致（对账口径）
INSERT INTO inventory (id, sku_id, warehouse_id, stock) VALUES
  (1, 1, 1, 100), (2, 1, 2, 0),   (3, 1, 3, 0),
  (4, 2, 1, 0),   (5, 2, 2, 80),  (6, 2, 3, 0),
  (7, 3, 1, 0),   (8, 3, 2, 0),   (9, 3, 3, 50),
  (10, 4, 1, 200),(11, 5, 1, 30), (12, 6, 1, 15),
  (13, 7, 1, 500),(14, 8, 1, 300),(15, 9, 1, 1000);

-- 兜底：凡「一条分仓记录都没有」的规格，一律按它的总库存落默认仓（深圳仓 id=1）。
-- 口径与 scripts/backfill-inventory.sh 完全一致：**只补缺口、绝不覆盖**人工配过的分仓数据，
-- 默认仓 = warehouse.status=1 里 id 最小的那个（与 SplitOrderService 的兜底同一口径）。
-- 上面的显式 INSERT 已经把当前种子的 SKU 1–9 配齐，所以正常导入时这条插入 0 行 ——
-- 它守的是「以后种子加了 SKU 却忘了配分仓」这种情形（那种单会因「无法确定发货仓」卡在待审核）。
INSERT INTO inventory (sku_id, warehouse_id, stock)
SELECT s.id, 1, s.stock FROM sku s
WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.sku_id = s.id);

-- 订单设置（全局单行）
INSERT INTO order_setting (id, auto_confirm_days, auto_close_minutes) VALUES
  (1, 7, 30);
