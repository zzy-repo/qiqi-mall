-- 把商品图片地址从「绝对地址」改成「相对路径」
--
-- 背景：上传接口返回的是 publicBaseUrl + /bucket/object，publicBaseUrl 默认写成了
-- http://127.0.0.1:9000，导致库里存的是绝对地址——用户从自己电脑访问 http://<服务器>:5174 时，
-- 浏览器会去请求「用户本机」的 127.0.0.1:9000，图片必挂。
--
-- 现在：后端 public-base-url 留空（MINIO_PUBLIC_URL 可覆盖），上传返回 /qiqi-mall/**
-- 相对路径；两个前端的 nginx 把 /qiqi-mall/ 反代到 minio:9000，dev 栈由 vite 代理。
-- 这样图片地址与实际访问域名解耦，换地址/加域名都不用动数据。
--
-- 幂等：用 LIKE 'http://127.0.0.1:9000/%' 限定，重跑不会二次处理。
-- 执行：docker exec -i -e MYSQL_PWD=... qiqi-mysql mysql --default-character-set=utf8mb4 -uroot < sql/migrate-image-relative-url.sql

-- ---------- 测试库 qiqi_mall_test ----------
UPDATE qiqi_mall_test.product         SET pic = REPLACE(pic, 'http://127.0.0.1:9000', '') WHERE pic LIKE 'http://127.0.0.1:9000/%';
UPDATE qiqi_mall_test.sku             SET pic = REPLACE(pic, 'http://127.0.0.1:9000', '') WHERE pic LIKE 'http://127.0.0.1:9000/%';
UPDATE qiqi_mall_test.product_gallery SET url = REPLACE(url, 'http://127.0.0.1:9000', '') WHERE url LIKE 'http://127.0.0.1:9000/%';

-- ---------- dev 库 qiqi_mall（同样规则，当前可能 0 行）----------
UPDATE qiqi_mall.product         SET pic = REPLACE(pic, 'http://127.0.0.1:9000', '') WHERE pic LIKE 'http://127.0.0.1:9000/%';
UPDATE qiqi_mall.sku             SET pic = REPLACE(pic, 'http://127.0.0.1:9000', '') WHERE pic LIKE 'http://127.0.0.1:9000/%';
UPDATE qiqi_mall.product_gallery SET url = REPLACE(url, 'http://127.0.0.1:9000', '') WHERE url LIKE 'http://127.0.0.1:9000/%';

-- ---------- 清理指向 example.com 的坏图商品（早期场景1 造的假 URL）----------
-- 只删「名字是 E2E 造的商品 且 主图是 example.com」的记录，种子商品不受影响；
-- 相册随 product 级联删除（fk_gallery_product ON DELETE CASCADE），SKU 也级联。
DELETE FROM qiqi_mall.product      WHERE name LIKE 'E2E场景1商品%' AND pic LIKE '%example.com%';
DELETE FROM qiqi_mall_test.product WHERE name LIKE 'E2E场景1商品%' AND pic LIKE '%example.com%';
