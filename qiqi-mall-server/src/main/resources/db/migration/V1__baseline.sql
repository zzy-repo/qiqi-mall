-- ============================================================================
-- V1 · 七七商城基线（39 张表 + 1 个视图 + channel_shop 参考数据）
-- ============================================================================
-- ⚠️ 本文件是**生成物**，禁止手改。生成方式（2026-11-13）：
--      ./scripts/gen-schema-dump.sh --baseline
--    源库：qiqi_mall_test（与 qiqi_mall / qiqi_mall_e2e_a / qiqi_mall_e2e_b 结构实测一致：
--    四类指纹 [COLUMNS/STATISTICS/TABLE_CONSTRAINTS/CHECK] 逐项一致，视图定义归一化后逐字节相同）
--
-- 为什么把历史上 11 个手写脚本一次性并入 V1，而不是保留成 V2..V12
-- ----------------------------------------------------------------
--   1) 四个库结构早已一致，历史增量对任何环境都不再有意义（没有"老版本库"需要升级）。
--   2) 那 11 个脚本**不能**当迁移用：migrate-ai-api-key / image-relative-url /
--      ledger-idem-shard / sku-bucket 里硬编码了 qiqi_mall. 与 qiqi_mall_test. 的**双库写入**，
--      在 Flyway 里跑会把基线建好的库再往别的库写一遍；还混着 DML 回填、7 个
--      zz_xxx_add_col 之类的一次性幂等存储过程、以及一份只读报表（ledger-verify）。
--   3) 代价：从此无法用 Flyway 重建"历史版本"的库。我们不需要（e2e 只要最新结构），
--      历史脚本仍留在 sql/_rollback/ 可读。
--
-- 本文件在什么情况下会被执行
-- --------------------------
--   · 已有库（4 个）：**不会执行**。它们已被 flyway baseline 到版本 1（历史里是一条
--     type=BASELINE 的 V1 记录），结构早就是 V1 描述的样子。
--   · 空库（e2e 重置 / 新环境）：**全量执行**，从 0 建出 39 表 + 1 视图 + 参考数据。
--   两条路径由 spring.flyway.baseline-on-migrate + baseline-version=1 区分，见 application-*.yml。
--
-- 后续变更怎么走
-- --------------
--   加表/加列/改索引 = 新增 V2__xxx.sql（V1 永远不再改）。改完跑
--   ./scripts/gen-schema-dump.sh 刷新 sql/schema.sql（docker initdb 用），两份一起提交。
-- ============================================================================

-- ============================================================================
-- 1. 表结构（39 张，按外键依赖拓扑排序：父表在前）
-- ============================================================================

-- admin
CREATE TABLE `admin` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) NOT NULL COMMENT '后台登录名',
  `password` varchar(100) NOT NULL DEFAULT '' COMMENT 'BCrypt（登录功能未实现，新增时暂为空）',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0禁用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员';

-- admin_audit_log
CREATE TABLE `admin_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator_type` varchar(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照',
  `action` varchar(48) NOT NULL COMMENT '动作码：改价/删单/拆单/合并/发货/退货审核…',
  `target_type` varchar(24) NOT NULL COMMENT '目标类型（多态）：ORDER/PACKAGE/RETURN/SKU/MEMBER/APIKEY',
  `target_id` bigint DEFAULT NULL COMMENT '目标ID（可空：目标可能已被物理删除）',
  `target_sn` varchar(64) DEFAULT NULL COMMENT '目标单号或名称快照（目标被删后仍可读）',
  `summary` varchar(255) DEFAULT NULL COMMENT '一句人话摘要，列表页直接展示',
  `reason` varchar(32) DEFAULT NULL COMMENT '管理员填写的原因，敏感动作必填',
  `order_id` bigint DEFAULT NULL COMMENT '落在哪张订单上（冗余，便于按订单检索）',
  `before_json` json DEFAULT NULL COMMENT '变更前快照',
  `after_json` json DEFAULT NULL COMMENT '变更后快照',
  `request_id` varchar(64) DEFAULT NULL COMMENT '请求追踪号，一次请求一条',
  `idem_key` varchar(96) NOT NULL COMMENT '⚠️红线：必须由 request_id 派生（如 request_id 或 request_id:seq），严禁由业务键派生。业务键派生会让"连点两次"的第二次撞 uk_idem → INSERT 失败 → 事务回滚 → 本该留下的失败证据被抹掉，与"一次请求一条、连点留两条"的审计语义直接冲突',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_operator` (`operator_id`,`created_at`),
  KEY `idx_target` (`target_type`,`target_id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `ck_admin_audit_log_operator_type` CHECK ((`operator_type` in (_utf8mb4'ADMIN',_utf8mb4'MEMBER',_utf8mb4'APIKEY',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台操作审计日志（只追加，不修改不软删）';

-- ai_api_key
CREATE TABLE `ai_api_key` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '用途名称，如 运营助手-只读',
  `key_prefix` varchar(16) NOT NULL COMMENT '明文前 8 位，列表展示用',
  `key_hash` char(64) NOT NULL COMMENT 'sha256(明文)，明文不落库',
  `scopes` varchar(512) NOT NULL COMMENT '逗号分隔，如 order:read,catalog:read',
  `expires_at` datetime DEFAULT NULL COMMENT 'NULL = 永不过期',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0禁用',
  `last_used_at` datetime DEFAULT NULL,
  `created_by` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 接口访问密钥';

-- product_type
CREATE TABLE `product_type` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) NOT NULL COMMENT '类型名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品类型';

-- attribute
CREATE TABLE `attribute` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type_id` bigint NOT NULL COMMENT '所属类型',
  `name` varchar(50) NOT NULL COMMENT '属性名称',
  `is_filter` tinyint NOT NULL DEFAULT '0' COMMENT '是否筛选属性:1是 0否',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type_id`),
  CONSTRAINT `fk_attr_type` FOREIGN KEY (`type_id`) REFERENCES `product_type` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='属性';

-- audit_policy
CREATE TABLE `audit_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type` enum('AUTO','BLOCK','EXCEPTION') NOT NULL COMMENT 'AUTO=自动过审候选 / BLOCK=前置拦截 / EXCEPTION=异常冻结',
  `priority` int NOT NULL DEFAULT '100' COMMENT '同类型内升序执行：数字小的先跑',
  `name` varchar(64) NOT NULL COMMENT '策略名（决策行会快照它，改名不影响历史决策）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用（停用不删，历史决策仍指得到）',
  `version_no` int NOT NULL DEFAULT '1' COMMENT '策略版本号：每次改条件 +1，决策行记当时版本',
  `action_json` json DEFAULT NULL COMMENT 'EXCEPTION 专属冻结动作：{"tag":"FROZEN","reason":"..."}；AUTO/BLOCK 必须 NULL（见 CHECK）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_name` (`type`,`name`),
  KEY `idx_type_priority` (`type`,`priority`,`enabled`),
  CONSTRAINT `ck_audit_policy_type_action` CHECK ((((`type` = _utf8mb4'EXCEPTION') and (`action_json` is not null)) or ((`type` in (_utf8mb4'AUTO',_utf8mb4'BLOCK')) and (`action_json` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核策略主体（AUTO/BLOCK/EXCEPTION）';

-- audit_policy_condition
CREATE TABLE `audit_policy_condition` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `policy_id` bigint DEFAULT NULL COMMENT '所属策略；scope=ALL_AUTO 时必须 NULL（全局排除不属于任何策略）',
  `seq` int NOT NULL DEFAULT '0' COMMENT '展示/求值顺序（升序）',
  `kind` enum('MATCH','EXCLUDE') NOT NULL COMMENT 'MATCH=必须满足 / EXCLUDE=命中即排除',
  `scope` enum('POLICY','ALL_AUTO') NOT NULL DEFAULT 'POLICY' COMMENT 'POLICY=本策略级 / ALL_AUTO=全部 AUTO 策略共用（全局排除）',
  `field_code` varchar(48) NOT NULL COMMENT '字段码；**不做 DDL CHECK 闭集**，由 FieldRegistry 校验',
  `op_code` varchar(24) NOT NULL COMMENT '操作符码；由 OperationRegistry 校验',
  `value_type` enum('NUM','TEXT','BOOL','JSON') NOT NULL COMMENT '值的类型（决定读哪个 value_* 列）',
  `value_num` decimal(20,4) DEFAULT NULL COMMENT 'NUM/BOOL 值（BOOL 用 1/0）',
  `value_text` varchar(255) DEFAULT NULL COMMENT 'TEXT 值',
  `value_json` json DEFAULT NULL COMMENT 'JSON 值（预留：IN 列表 / 区间等）',
  PRIMARY KEY (`id`),
  KEY `idx_policy_seq` (`policy_id`,`seq`),
  KEY `idx_scope_kind` (`scope`,`kind`),
  CONSTRAINT `fk_audit_policy_condition_policy` FOREIGN KEY (`policy_id`) REFERENCES `audit_policy` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_audit_policy_condition_owner` CHECK ((((`scope` = _utf8mb4'POLICY') and (`policy_id` is not null)) or ((`scope` = _utf8mb4'ALL_AUTO') and (`policy_id` is null)))),
  CONSTRAINT `ck_audit_policy_condition_scope_kind` CHECK (((`scope` = _utf8mb4'POLICY') or (`kind` = _utf8mb4'EXCLUDE'))),
  CONSTRAINT `ck_audit_policy_condition_value` CHECK ((((`value_num` is null) and (`value_text` is null) and (`value_json` is null)) or ((`value_type` = _utf8mb4'NUM') and (`value_num` is not null) and (`value_text` is null) and (`value_json` is null)) or ((`value_type` = _utf8mb4'BOOL') and (`value_num` is not null) and (`value_text` is null) and (`value_json` is null)) or ((`value_type` = _utf8mb4'TEXT') and (`value_text` is not null) and (`value_num` is null) and (`value_json` is null)) or ((`value_type` = _utf8mb4'JSON') and (`value_json` is not null) and (`value_num` is null) and (`value_text` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核策略条件（行模型：一条条件一行，不用 JSON 列）';

-- audit_policy_version
CREATE TABLE `audit_policy_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `policy_id` bigint NOT NULL COMMENT '所属策略（随策略级联删除）',
  `version_no` int NOT NULL COMMENT '版本号（与 audit_policy.version_no 对齐）',
  `snapshot_json` json NOT NULL COMMENT '策略 + 全部条件的快照（改完再看历史决策能还原当时配置）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_version` (`policy_id`,`version_no`),
  CONSTRAINT `fk_audit_policy_version_policy` FOREIGN KEY (`policy_id`) REFERENCES `audit_policy` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核策略版本快照（append-only）';

-- audit_run
CREATE TABLE `audit_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trigger_type` enum('MANUAL','SCHEDULE') NOT NULL COMMENT 'MANUAL=人工触发 / SCHEDULE=定时任务',
  `idem_key` varchar(96) DEFAULT NULL COMMENT '⚠️仅 SCHEDULE 填 {trigger}:{yyyyMMddHHmm}；严禁业务键派生，MANUAL 一律 NULL',
  `total` int NOT NULL DEFAULT '0' COMMENT '本轮产出决策的单数（含 SKIPPED：五类之和可能小于 total）',
  `auto_pass` int NOT NULL DEFAULT '0' COMMENT '自动过审数',
  `manual` int NOT NULL DEFAULT '0' COMMENT '转人工数（含降级）',
  `blocked` int NOT NULL DEFAULT '0' COMMENT '被 BLOCK 策略拦截数',
  `frozen` int NOT NULL DEFAULT '0' COMMENT '异常冻结数',
  `no_match` int NOT NULL DEFAULT '0' COMMENT '没有任何 AUTO 策略命中数',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次自动审核 run（计数在建 run 时写 0、跑完回写一次）';

-- member
CREATE TABLE `member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) NOT NULL COMMENT '登录名-全局唯一',
  `password` varchar(100) NOT NULL DEFAULT '' COMMENT 'BCrypt（登录功能未实现，新增时暂为空）',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0禁用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `customer_tag` varchar(32) DEFAULT NULL COMMENT '客户标签（新客/老客/黑名单…）：CUSTOMER_TAG 字段来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员';

-- cart
CREATE TABLE `cart` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `product_id` bigint NOT NULL COMMENT '商品ID（SPU）',
  `product_sku_id` bigint NOT NULL COMMENT '具体规格SKU',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '数量',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`),
  CONSTRAINT `fk_cart_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车';

-- category
CREATE TABLE `category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父分类ID，0=顶级',
  `name` varchar(50) NOT NULL COMMENT '分类名称',
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品分类';

-- channel_order_map
CREATE TABLE `channel_order_map` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint NOT NULL COMMENT 'channel_shop.id（无外键，见文件头铁律①）',
  `platform_sn` varchar(64) NOT NULL COMMENT '平台单号（MALL 直接用商城 order_sn）',
  `order_id` bigint NOT NULL COMMENT '内部订单 order.id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_map` (`shop_id`,`platform_sn`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台单号→内部订单映射（幂等闸门）';

-- channel_shop
CREATE TABLE `channel_shop` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `platform_type` enum('MALL','TAOBAO_MOCK','DOUYIN_MOCK') NOT NULL COMMENT '平台类型：MALL=商城自营开放接口；后两个=本地 mock 平台',
  `shop_code` varchar(64) NOT NULL COMMENT '平台侧店铺编码（调接口时带上）',
  `shop_name` varchar(64) NOT NULL COMMENT '店铺名（后台卡片标题）',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用（停用后不允许拉单/回传）',
  `watermark_at` datetime DEFAULT NULL COMMENT '拉单水位：上次成功拉单的**开始时刻**，下次 since 传它',
  `last_pull_at` datetime DEFAULT NULL COMMENT '上次拉单结束时刻（含失败），卡片上展示',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop` (`platform_type`,`shop_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道店铺';

-- channel_sync_log
CREATE TABLE `channel_sync_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint NOT NULL COMMENT 'channel_shop.id（无外键，见文件头铁律①）',
  `action` enum('PULL','SHIP_PUSH') NOT NULL COMMENT '动作：PULL=拉单 SHIP_PUSH=回传发货',
  `ref_type` varchar(32) DEFAULT NULL COMMENT '关联对象类型：ORDER / PLATFORM_ORDER / DELIVERY',
  `ref_id` bigint DEFAULT NULL COMMENT '关联对象 id（内部订单 id / 发货单 id；平台单号见 request_payload）',
  `request_payload` text COMMENT '请求报文（截断，够排查即可）',
  `response_payload` text COMMENT '响应报文（截断）',
  `result` enum('OK','FAIL','SKIP') NOT NULL COMMENT 'OK=成功 FAIL=失败 SKIP=幂等跳过',
  `reason` varchar(255) DEFAULT NULL COMMENT '结果原因：IDEMPOTENT / SKU_NOT_MATCHED / 异常信息…',
  `attempt` int NOT NULL DEFAULT '1' COMMENT '第几次尝试（回传重试时第 2 次记 attempt=2）',
  `latency_ms` int DEFAULT NULL COMMENT '外呼耗时（毫秒）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_time` (`shop_id`,`created_at`),
  KEY `idx_action_result` (`action`,`result`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道同步日志（只追加）';

-- order
CREATE TABLE `order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_sn` varchar(50) NOT NULL COMMENT '订单号-全局唯一',
  `member_id` bigint NOT NULL COMMENT '买家ID',
  `total_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '订单总金额',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款',
  `pay_type` tinyint NOT NULL DEFAULT '0' COMMENT '0未支付 1支付宝 2微信',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `created_at` datetime DEFAULT NULL COMMENT '下单时间（历史行可能为 NULL）',
  `receiver_name` varchar(50) NOT NULL COMMENT '收货人快照',
  `receiver_phone` varchar(20) DEFAULT NULL COMMENT '收货电话快照',
  `receiver_address` varchar(255) DEFAULT NULL COMMENT '收货地址快照',
  `split_status` tinyint NOT NULL DEFAULT '0' COMMENT '0未拆(单仓直发) 1已拆',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `status_seq` int NOT NULL DEFAULT '0' COMMENT '状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源',
  `channel` varchar(16) DEFAULT NULL COMMENT '销售渠道（直营/分销/…）：CHANNEL 字段来源',
  `shop_name` varchar(64) DEFAULT NULL COMMENT '店铺名：SHOP_NAME 字段来源',
  `gross_profit` decimal(12,2) DEFAULT NULL COMMENT '毛利（下单时算好落库）：GROSS_PROFIT 字段来源；NULL=没算过',
  `cs_remark` varchar(255) DEFAULT NULL COMMENT '客服备注：CS_REMARK 字段来源（含「改」= 全局排除）',
  `platform_remark` varchar(255) DEFAULT NULL COMMENT '平台备注（买家留言同步）：PLATFORM_REMARK_UPDATED 字段来源',
  `platform_remark_updated_at` datetime DEFAULT NULL COMMENT '平台备注最后更新时间；NULL=从未更新（字段取值为「无值」）',
  `remark_consumed_at` datetime DEFAULT NULL COMMENT '备注被审核消费的时间（防止同一条备注反复触发排除）',
  `audit_addr_multi` tinyint NOT NULL DEFAULT '0' COMMENT '多地址订单标记：ADDRESS_MULTI 字段来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  KEY `idx_member` (`member_id`),
  KEY `idx_member_time` (`member_id`,`payment_time`),
  KEY `idx_status` (`status`),
  CONSTRAINT `fk_order_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单';

-- warehouse
CREATE TABLE `warehouse` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) NOT NULL COMMENT '仓库名称',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库';

-- delivery_order
CREATE TABLE `delivery_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_sn` varchar(50) NOT NULL COMMENT '发货单号（DL开头）',
  `order_id` bigint NOT NULL COMMENT '父订单',
  `warehouse_id` bigint NOT NULL COMMENT '发货仓',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消',
  `receiver_name` varchar(50) NOT NULL COMMENT '收货人快照（多包各自贴单）',
  `receiver_phone` varchar(20) NOT NULL COMMENT '收货电话快照',
  `receiver_address` varchar(255) NOT NULL COMMENT '收货地址快照',
  `source` tinyint NOT NULL DEFAULT '0' COMMENT '来源：0自动拆单 1人工调整（改仓/改量/拆分/合并/删除）',
  `split_rule_id` bigint DEFAULT NULL COMMENT '预留：命中的拆单规则ID，层次二规则引擎启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `status_seq` int NOT NULL DEFAULT '0' COMMENT '状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery_sn` (`delivery_sn`),
  KEY `idx_order` (`order_id`),
  KEY `fk_delivery_wh` (`warehouse_id`),
  CONSTRAINT `fk_delivery_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_delivery_wh` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发货单';

-- delivery_item
CREATE TABLE `delivery_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` bigint NOT NULL COMMENT '发货单',
  `order_item_id` bigint NOT NULL COMMENT '拆分来源（订单明细）',
  `product_sku_id` bigint NOT NULL COMMENT 'SKU',
  `quantity` int NOT NULL COMMENT '本发货单承担的数量',
  PRIMARY KEY (`id`),
  KEY `idx_delivery` (`delivery_id`),
  CONSTRAINT `fk_ditem_delivery` FOREIGN KEY (`delivery_id`) REFERENCES `delivery_order` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发货单明细';

-- delivery_logistics
CREATE TABLE `delivery_logistics` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` bigint NOT NULL COMMENT '发货单',
  `company` varchar(30) NOT NULL COMMENT '快递公司',
  `track_no` varchar(50) NOT NULL COMMENT '运单号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回传时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery` (`delivery_id`),
  UNIQUE KEY `uk_track_no` (`track_no`),
  CONSTRAINT `fk_logi_delivery` FOREIGN KEY (`delivery_id`) REFERENCES `delivery_order` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='物流信息';

-- delivery_status_ledger
CREATE TABLE `delivery_status_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` bigint NOT NULL COMMENT '包裹ID（业务主键·无外键：证据不随包裹级联删除）',
  `delivery_sn` varchar(50) NOT NULL COMMENT '【已登记冗余列】发货单号快照（包裹被物理删除后仍可读）',
  `order_id` bigint DEFAULT NULL COMMENT '【已登记冗余列】冗余订单ID：包裹行被物理删除（OrderPurgeService）后，仍能从订单反查包裹状态流水',
  `from_status` tinyint DEFAULT NULL COMMENT '变更前状态；NULL=出生事件（建包裹）',
  `to_status` tinyint NOT NULL COMMENT '变更后状态',
  `reason` varchar(32) NOT NULL COMMENT '流转原因码',
  `reason_detail` varchar(255) DEFAULT NULL COMMENT '原因补充说明',
  `operator_type` varchar(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID（系统事件可空）',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type` varchar(24) DEFAULT NULL COMMENT '触发单据类型',
  `ref_id` bigint DEFAULT NULL COMMENT '触发单据ID',
  `event_key` varchar(96) DEFAULT NULL COMMENT '仓侧事件号（重投只留一条；允许多 NULL）',
  `idem_key` varchar(96) NOT NULL COMMENT '幂等键 [shard:]{deliveryId}:{statusSeq}',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`delivery_id`,`created_at`),
  KEY `idx_order` (`order_id`,`created_at`),
  CONSTRAINT `ck_delivery_status_ledger_operator_type` CHECK ((`operator_type` in (_utf8mb4'ADMIN',_utf8mb4'MEMBER',_utf8mb4'APIKEY',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='包裹状态台账（只追加证据链；带单号快照 + 冗余订单ID）';

-- product
CREATE TABLE `product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(100) NOT NULL COMMENT '商品名称（SPU）',
  `description` text COMMENT '商品描述',
  `category_id` bigint NOT NULL COMMENT '所属分类',
  `type_id` bigint NOT NULL COMMENT '所属类型',
  `publish_status` tinyint NOT NULL DEFAULT '1' COMMENT '1上架 0下架',
  `pic` varchar(500) DEFAULT NULL COMMENT '主图URL（指向 MinIO）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`),
  KEY `idx_type` (`type_id`),
  CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`),
  CONSTRAINT `fk_product_type` FOREIGN KEY (`type_id`) REFERENCES `product_type` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品（SPU）';

-- sku
CREATE TABLE `sku` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_id` bigint NOT NULL COMMENT '所属商品',
  `price` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '价格',
  `stock` int NOT NULL DEFAULT '0' COMMENT '总库存——展示缓存：由 SUM(sku_bucket.stock) 周期回写，扣减/回补不写它、也不拿它裁决',
  `pic` varchar(500) DEFAULT NULL COMMENT 'SKU图URL（指向 MinIO）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `cost_price` decimal(12,2) DEFAULT NULL COMMENT '成本价（当前值）；0/NULL = 未维护成本',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_sku_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU';

-- inventory
CREATE TABLE `inventory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint NOT NULL COMMENT 'SKU',
  `warehouse_id` bigint NOT NULL COMMENT '仓库',
  `stock` int NOT NULL DEFAULT '0' COMMENT '该仓库存量',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_wh` (`sku_id`,`warehouse_id`),
  KEY `idx_wh` (`warehouse_id`),
  CONSTRAINT `fk_inv_sku` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`),
  CONSTRAINT `fk_inv_wh` FOREIGN KEY (`warehouse_id`) REFERENCES `warehouse` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分仓库存记录';

-- member_address
CREATE TABLE `member_address` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '所属会员',
  `name` varchar(50) NOT NULL COMMENT '收件人',
  `phone` varchar(20) NOT NULL COMMENT '收件电话',
  `province` varchar(50) NOT NULL COMMENT '省',
  `city` varchar(50) NOT NULL COMMENT '市',
  `region` varchar(50) NOT NULL COMMENT '区/县',
  `detail` varchar(200) NOT NULL COMMENT '详细地址',
  `default_status` tinyint NOT NULL DEFAULT '0' COMMENT '是否默认地址:1是 0否',
  PRIMARY KEY (`id`),
  KEY `idx_member` (`member_id`),
  CONSTRAINT `fk_addr_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收货地址';

-- order_audit_decision
CREATE TABLE `order_audit_decision` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID（业务主键·无外键：证据不随订单级联删除）',
  `run_id` bigint NOT NULL COMMENT '所属 run（audit_run.id，无外键同理）',
  `round_no` int NOT NULL COMMENT '该订单的第几轮审核（1 起，按 MAX+1）',
  `result` enum('AUTO_PASS','MANUAL','BLOCKED','FROZEN','NO_MATCH','SKIPPED') NOT NULL COMMENT '本轮判定结论',
  `matched_policy_id` bigint DEFAULT NULL COMMENT '命中的策略（AUTO_PASS/BLOCKED/FROZEN）',
  `matched_policy_name` varchar(64) DEFAULT NULL COMMENT '命中策略名快照',
  `matched_policy_version_no` int DEFAULT NULL COMMENT '命中策略当时的版本号',
  `matched_condition_json` json DEFAULT NULL COMMENT '命中/满足的条件明细（含 expected vs actual）',
  `excluded_by_json` json DEFAULT NULL COMMENT '排除条件命中明细（POLICY ∪ ALL_AUTO）',
  `miss_reason_json` json DEFAULT NULL COMMENT '逐条策略「差在哪」（NO_MATCH / 未命中时）',
  `would_be_auto_policy_id` bigint DEFAULT NULL COMMENT '只读回填：若没被拦截/冻结，会命中哪条 AUTO 策略',
  `would_be_auto_policy_name` varchar(64) DEFAULT NULL COMMENT '只读回填的策略名快照',
  `downgrade_reason` varchar(32) DEFAULT NULL COMMENT 'AUTO_PASS 后预检降级原因：STOCK_SHORT / COST_MISSING',
  `evidence_json` json DEFAULT NULL COMMENT '证据：人话原因（含具体数值差）+ 库存/成本/冻结动作细节',
  `order_status_seq` int DEFAULT NULL COMMENT '本轮订单状态流转后的 status_seq（对得上 order_status_ledger）',
  `operator_type` varchar(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID（系统/调度为 NULL）',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照',
  `idem_key` varchar(96) NOT NULL COMMENT '幂等键 {orderId}:{roundNo}',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '判定时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_order_round` (`order_id`,`round_no`),
  KEY `idx_run_result` (`run_id`,`result`),
  KEY `idx_order` (`order_id`,`created_at`),
  CONSTRAINT `ck_order_audit_decision_operator_type` CHECK ((`operator_type` in (_utf8mb4'ADMIN',_utf8mb4'MEMBER',_utf8mb4'APIKEY',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单审核决策台账（每单每轮一行，只追加）';

-- order_audit_tag
CREATE TABLE `order_audit_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `tag_code` varchar(32) NOT NULL COMMENT 'FROZEN / STOCK_SHORT / COST_MISSING …',
  `active_tag` varchar(48) GENERATED ALWAYS AS (if((`active` = 1),concat(`order_id`,_utf8mb4':',`tag_code`),NULL)) VIRTUAL COMMENT '仅 active 时非空的唯一键载体：{orderId}:{tagCode}',
  `active` tinyint NOT NULL DEFAULT '1' COMMENT '1生效 0已解除',
  `reason` varchar(255) DEFAULT NULL COMMENT '为什么打这个标',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '打标时间',
  `released_at` datetime(3) DEFAULT NULL COMMENT '解除时间（解冻/恢复）',
  `released_by` varchar(64) DEFAULT NULL COMMENT '解除人（ADMIN 用户名 / APIKEY 名 / system）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active` (`active_tag`),
  KEY `idx_order` (`order_id`,`tag_code`),
  CONSTRAINT `ck_order_audit_tag_active` CHECK ((`active` in (0,1)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单审核标记（冻结/挂起用标表达，不动 order.status）';

-- order_item
CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `product_id` bigint NOT NULL COMMENT '商品ID（SPU快照）',
  `product_sku_id` bigint NOT NULL COMMENT 'SKU ID（快照）',
  `product_name` varchar(100) NOT NULL COMMENT '下单时商品名（快照）',
  `product_price` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '成交价（快照）',
  `product_quantity` int NOT NULL DEFAULT '1' COMMENT '购买数量',
  `cost_price` decimal(12,2) DEFAULT NULL COMMENT '下单时成本价快照；0/NULL = 未维护成本（→ COST_MISSING）',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `fk_item_product` (`product_id`),
  KEY `fk_item_sku` (`product_sku_id`),
  CONSTRAINT `fk_item_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_item_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT `fk_item_sku` FOREIGN KEY (`product_sku_id`) REFERENCES `sku` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细';

-- order_setting
CREATE TABLE `order_setting` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `auto_confirm_days` int NOT NULL DEFAULT '7' COMMENT '自动收货天数',
  `auto_close_minutes` int NOT NULL DEFAULT '30' COMMENT '未付款自动关单分钟数',
  `auto_audit_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '自动审核总开关：0=定时任务不跑（人工仍可手动 run）',
  `audit_stock_guard` varchar(8) NOT NULL DEFAULT 'STRICT' COMMENT '库存判据档位：STRICT=不足即降级人工 / LENIENT=不足只告警不拦',
  `audit_run_interval_min` int NOT NULL DEFAULT '30' COMMENT '定时审核间隔（分钟）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单设置';

-- order_status_ledger
CREATE TABLE `order_status_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID（业务主键·无外键：证据不随订单级联删除）',
  `from_status` tinyint DEFAULT NULL COMMENT '变更前状态；NULL=出生事件（建单时无前态）',
  `to_status` tinyint NOT NULL COMMENT '变更后状态',
  `reason` varchar(32) NOT NULL COMMENT '流转原因码',
  `reason_detail` varchar(255) DEFAULT NULL COMMENT '原因补充说明',
  `operator_type` varchar(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID（系统事件可空）',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type` varchar(24) DEFAULT NULL COMMENT '触发单据类型：DELIVERY / RETURN_APPLY / PAYMENT …',
  `ref_id` bigint DEFAULT NULL COMMENT '触发单据ID',
  `event_key` varchar(96) DEFAULT NULL COMMENT '外部事件号（重投只留一条；允许多 NULL）',
  `idem_key` varchar(96) NOT NULL COMMENT '幂等键 [shard:]{orderId}:{statusSeq}',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`order_id`,`created_at`),
  CONSTRAINT `ck_order_status_ledger_operator_type` CHECK ((`operator_type` in (_utf8mb4'ADMIN',_utf8mb4'MEMBER',_utf8mb4'APIKEY',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单状态台账（只追加证据链）';

-- payment_record
CREATE TABLE `payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_sn` varchar(50) NOT NULL COMMENT '关联订单号（对账键）',
  `order_id` bigint DEFAULT NULL COMMENT '关联订单ID（回填自 order_sn；order 被物理删后可能悬空）',
  `amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '金额',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '1支付 2退款',
  `channel` tinyint NOT NULL DEFAULT '0' COMMENT '0模拟 1支付宝 2微信',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方流水号（模拟为本地生成）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  `ref_type` varchar(24) DEFAULT NULL COMMENT '触发单据类型',
  `ref_id` bigint DEFAULT NULL COMMENT '触发单据ID',
  `origin_flow_id` bigint DEFAULT NULL COMMENT '退款指向的原支付流水ID（type=2 时必填）',
  `operator_type` varchar(16) DEFAULT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照',
  `idem_key` varchar(96) DEFAULT NULL COMMENT '幂等键（历史行为 NULL，新写入必填）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_channel_trade` (`channel`,`trade_no`),
  KEY `idx_order_sn` (`order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付流水';

-- product_gallery
CREATE TABLE `product_gallery` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_id` bigint NOT NULL COMMENT '所属商品',
  `url` varchar(500) NOT NULL COMMENT '图片URL（指向 MinIO）',
  `sort` int NOT NULL DEFAULT '0' COMMENT '展示顺序',
  PRIMARY KEY (`id`),
  KEY `idx_product` (`product_id`),
  CONSTRAINT `fk_gallery_product` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品相册';

-- return_reason
CREATE TABLE `return_reason` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(50) NOT NULL COMMENT '原因名称',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退货原因';

-- return_apply
CREATE TABLE `return_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `member_id` bigint NOT NULL COMMENT '申请人ID',
  `return_reason` varchar(200) NOT NULL COMMENT '退货原因描述',
  `description` varchar(500) DEFAULT NULL COMMENT '详细说明',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待处理 1通过 2拒绝',
  `reason_id` bigint NOT NULL COMMENT '退货原因字典ID',
  `status_seq` int NOT NULL DEFAULT '0' COMMENT '状态流转序号，与 status 在同一条 UPDATE 里自增；幂等键来源',
  `pending_order_id` bigint GENERATED ALWAYS AS (if((`status` = 0),`order_id`,NULL)) VIRTUAL COMMENT '待处理请求的订单ID：仅 status=0 时非空（预留列，本轮不建唯一键，见头部【已知问题·待办】）',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `fk_return_member` (`member_id`),
  KEY `fk_return_reason` (`reason_id`),
  CONSTRAINT `fk_return_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`),
  CONSTRAINT `fk_return_order` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_return_reason` FOREIGN KEY (`reason_id`) REFERENCES `return_reason` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退货申请';

-- return_status_ledger
CREATE TABLE `return_status_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `return_apply_id` bigint NOT NULL COMMENT '退货申请ID（业务主键·无外键：证据不随申请级联删除）',
  `order_id` bigint DEFAULT NULL COMMENT '【已登记冗余列】冗余订单ID：return_apply 随订单级联删除/物理删单后，仍能从订单反查售后流水',
  `from_status` tinyint DEFAULT NULL COMMENT '变更前状态；NULL=出生事件（提交申请）',
  `to_status` tinyint NOT NULL COMMENT '变更后状态',
  `reason` varchar(32) NOT NULL COMMENT '流转原因码',
  `reason_detail` varchar(255) DEFAULT NULL COMMENT '原因补充说明',
  `operator_type` varchar(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID（系统事件可空）',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `ref_type` varchar(24) DEFAULT NULL COMMENT '触发单据类型',
  `ref_id` bigint DEFAULT NULL COMMENT '触发单据ID',
  `event_key` varchar(96) DEFAULT NULL COMMENT '外部事件号（重投只留一条；允许多 NULL）',
  `idem_key` varchar(96) NOT NULL COMMENT '幂等键 [shard:]{returnApplyId}:{statusSeq}',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  UNIQUE KEY `uk_event` (`event_key`),
  KEY `idx_biz` (`return_apply_id`,`created_at`),
  CONSTRAINT `ck_return_status_ledger_operator_type` CHECK ((`operator_type` in (_utf8mb4'ADMIN',_utf8mb4'MEMBER',_utf8mb4'APIKEY',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后状态台账（只追加证据链）';

-- sku_bucket
CREATE TABLE `sku_bucket` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint NOT NULL COMMENT '所属规格',
  `bucket_no` int NOT NULL COMMENT '桶号 0..N-1（N = qiqi.stock.bucket-count，默认 8）',
  `stock` int NOT NULL DEFAULT '0' COMMENT '本桶可用库存（权威值；sku.stock 只是它的缓存）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变动时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_bucket` (`sku_id`,`bucket_no`),
  KEY `idx_sku` (`sku_id`),
  CONSTRAINT `fk_bucket_sku` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_bucket_stock` CHECK ((`stock` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU 库存分桶（一份库存物理拆成 N 行，权威裁决处；sku.stock 是它的展示缓存）';

-- sku_spec
CREATE TABLE `sku_spec` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint NOT NULL COMMENT '所属SKU',
  `attribute_id` bigint NOT NULL COMMENT '指向属性定义',
  `value` varchar(100) NOT NULL COMMENT '属性值，如 黑色',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_attribute` (`sku_id`,`attribute_id`),
  KEY `idx_attribute` (`attribute_id`),
  CONSTRAINT `fk_spec_attribute` FOREIGN KEY (`attribute_id`) REFERENCES `attribute` (`id`),
  CONSTRAINT `fk_spec_sku` FOREIGN KEY (`sku_id`) REFERENCES `sku` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU规格值';

-- stock_ledger
CREATE TABLE `stock_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '关联订单（无外键：台账是审计证据，不随订单级联删除）',
  `sku_id` bigint NOT NULL COMMENT '规格（无外键，同上）',
  `delta` int NOT NULL COMMENT '库存变化量：负数=扣减（下单），正数=回补',
  `reason` varchar(32) NOT NULL COMMENT 'ORDER_CREATE / AUDIT_REJECT / DELIVERY_CANCEL / RETURN_REFUND',
  `ref_type` varchar(16) DEFAULT NULL COMMENT '触发单据类型：DELIVERY / RETURN_APPLY；下单为 NULL',
  `ref_id` bigint DEFAULT NULL COMMENT '触发单据 id：发货单 id / 退货申请 id；下单为 NULL',
  `idem_key` varchar(96) NOT NULL COMMENT '幂等键 [前缀:]orderId:skuId:reason:refId（refId 缺省 0）；前缀=orderId % idem-key-shards，可关（详见 migrate-ledger-idem-shard.sql）',
  `bucket_no` int DEFAULT NULL COMMENT '扣减/回补落点桶号（可空：历史台账）；只用于回补定位，不参与守恒计算',
  `created_at` datetime NOT NULL COMMENT '记账时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_order_sku` (`order_id`,`sku_id`),
  KEY `idx_sku` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU 库存台账（扣/补全量记账 + 幂等去重）';

-- wms_exchange_ledger
CREATE TABLE `wms_exchange_ledger` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `delivery_id` bigint NOT NULL COMMENT '包裹ID（无外键：证据不随包裹级联删除）',
  `direction` varchar(8) NOT NULL COMMENT 'REQ=下发 / ACK=回传',
  `attempt_no` int NOT NULL DEFAULT '1' COMMENT '第几次尝试（重试用，从 1 开始）',
  `request_payload` json DEFAULT NULL COMMENT '下发报文',
  `response_payload` json DEFAULT NULL COMMENT '回传报文（REQ 行落库时为 NULL）',
  `result` varchar(16) DEFAULT NULL COMMENT 'SUCCESS / FAIL / TIMEOUT（REQ 行未定论时为 NULL）',
  `latency_ms` int DEFAULT NULL COMMENT '往返耗时（毫秒）',
  `operator_type` varchar(16) NOT NULL COMMENT 'ADMIN / MEMBER / APIKEY / SYSTEM',
  `operator_id` bigint DEFAULT NULL COMMENT '操作者ID（系统重试可空）',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作者名快照（密钥名 / 登录名）',
  `idem_key` varchar(96) NOT NULL COMMENT '幂等键 {deliveryId}:{direction}:{attemptNo}（如 3364:REQ:1）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记账时间（只追加，无 updated_at）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`idem_key`),
  KEY `idx_delivery` (`delivery_id`,`created_at`),
  CONSTRAINT `ck_wms_exchange_ledger_direction` CHECK ((`direction` in (_utf8mb4'REQ',_utf8mb4'ACK'))),
  CONSTRAINT `ck_wms_exchange_ledger_operator_type` CHECK ((`operator_type` in (_utf8mb4'ADMIN',_utf8mb4'MEMBER',_utf8mb4'APIKEY',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WMS 下发/回传台账（REQ/ACK 两阶段 write-ahead）';

-- ============================================================================
-- 2. 视图（1 个）
--    去掉 DEFINER（否则换账号跑迁移会失败）与库名前缀（否则只能建在同一个库上）
-- ============================================================================

CREATE SQL SECURITY DEFINER VIEW `v_biz_status_timeline` AS select cast('ORDER' as char(8) charset utf8mb4) AS `biz_type`,cast(`l`.`order_id` as signed) AS `biz_id`,cast(`l`.`order_id` as signed) AS `order_id`,cast(NULL as char(50) charset utf8mb4) AS `delivery_sn`,cast(`l`.`from_status` as signed) AS `from_status`,cast(`l`.`to_status` as signed) AS `to_status`,cast((case `l`.`from_status` when 0 then '待付款' when 1 then '已付款' when 2 then '已发货' when 3 then '已完成' when 4 then '已关闭' when 5 then '退款中' when 6 then '已退款' when 7 then '待审核' when 8 then '待发货' when 9 then '部分发货' else NULL end) as char(12) charset utf8mb4) AS `from_status_text`,cast((case `l`.`to_status` when 0 then '待付款' when 1 then '已付款' when 2 then '已发货' when 3 then '已完成' when 4 then '已关闭' when 5 then '退款中' when 6 then '已退款' when 7 then '待审核' when 8 then '待发货' when 9 then '部分发货' else NULL end) as char(12) charset utf8mb4) AS `to_status_text`,cast(`l`.`reason` as char(32) charset utf8mb4) AS `reason`,cast(`l`.`reason_detail` as char(255) charset utf8mb4) AS `reason_detail`,cast(`l`.`operator_type` as char(16) charset utf8mb4) AS `operator_type`,cast(`l`.`operator_id` as signed) AS `operator_id`,cast(`l`.`operator_name` as char(64) charset utf8mb4) AS `operator_name`,cast(`l`.`ref_type` as char(24) charset utf8mb4) AS `ref_type`,cast(`l`.`ref_id` as signed) AS `ref_id`,cast(`l`.`event_key` as char(96) charset utf8mb4) AS `event_key`,cast(`l`.`idem_key` as char(96) charset utf8mb4) AS `idem_key`,`l`.`created_at` AS `created_at`,cast(`l`.`id` as signed) AS `id` from `order_status_ledger` `l` union all select cast('DELIVERY' as char(8) charset utf8mb4) AS `CAST('DELIVERY' AS CHAR(8))`,cast(`l`.`delivery_id` as signed) AS `CAST(l.``delivery_id`` AS SIGNED)`,cast(`l`.`order_id` as signed) AS `CAST(l.``order_id`` AS SIGNED)`,cast(`l`.`delivery_sn` as char(50) charset utf8mb4) AS `CAST(l.``delivery_sn`` AS CHAR(50))`,cast(`l`.`from_status` as signed) AS `CAST(l.``from_status`` AS SIGNED)`,cast(`l`.`to_status` as signed) AS `CAST(l.``to_status`` AS SIGNED)`,cast((case `l`.`from_status` when 0 then '待下发' when 1 then '已下发' when 2 then '已拣货' when 3 then '已出库' when 4 then '运输中' when 5 then '已签收' when 9 then '已取消' else NULL end) as char(12) charset utf8mb4) AS `Name_exp_26`,cast((case `l`.`to_status` when 0 then '待下发' when 1 then '已下发' when 2 then '已拣货' when 3 then '已出库' when 4 then '运输中' when 5 then '已签收' when 9 then '已取消' else NULL end) as char(12) charset utf8mb4) AS `Name_exp_27`,cast(`l`.`reason` as char(32) charset utf8mb4) AS `CAST(l.``reason`` AS CHAR(32))`,cast(`l`.`reason_detail` as char(255) charset utf8mb4) AS `CAST(l.``reason_detail`` AS CHAR(255))`,cast(`l`.`operator_type` as char(16) charset utf8mb4) AS `CAST(l.``operator_type`` AS CHAR(16))`,cast(`l`.`operator_id` as signed) AS `CAST(l.``operator_id`` AS SIGNED)`,cast(`l`.`operator_name` as char(64) charset utf8mb4) AS `CAST(l.``operator_name`` AS CHAR(64))`,cast(`l`.`ref_type` as char(24) charset utf8mb4) AS `CAST(l.``ref_type`` AS CHAR(24))`,cast(`l`.`ref_id` as signed) AS `CAST(l.``ref_id`` AS SIGNED)`,cast(`l`.`event_key` as char(96) charset utf8mb4) AS `CAST(l.``event_key`` AS CHAR(96))`,cast(`l`.`idem_key` as char(96) charset utf8mb4) AS `CAST(l.``idem_key`` AS CHAR(96))`,`l`.`created_at` AS `created_at`,cast(`l`.`id` as signed) AS `CAST(l.``id`` AS SIGNED)` from `delivery_status_ledger` `l` union all select cast('RETURN' as char(8) charset utf8mb4) AS `CAST('RETURN' AS CHAR(8))`,cast(`l`.`return_apply_id` as signed) AS `CAST(l.``return_apply_id`` AS SIGNED)`,cast(`l`.`order_id` as signed) AS `CAST(l.``order_id`` AS SIGNED)`,cast(NULL as char(50) charset utf8mb4) AS `CAST(NULL AS CHAR(50))`,cast(`l`.`from_status` as signed) AS `CAST(l.``from_status`` AS SIGNED)`,cast(`l`.`to_status` as signed) AS `CAST(l.``to_status`` AS SIGNED)`,cast((case `l`.`from_status` when 0 then '待处理' when 1 then '已通过' when 2 then '已拒绝' else NULL end) as char(12) charset utf8mb4) AS `Name_exp_45`,cast((case `l`.`to_status` when 0 then '待处理' when 1 then '已通过' when 2 then '已拒绝' else NULL end) as char(12) charset utf8mb4) AS `Name_exp_46`,cast(`l`.`reason` as char(32) charset utf8mb4) AS `CAST(l.``reason`` AS CHAR(32))`,cast(`l`.`reason_detail` as char(255) charset utf8mb4) AS `CAST(l.``reason_detail`` AS CHAR(255))`,cast(`l`.`operator_type` as char(16) charset utf8mb4) AS `CAST(l.``operator_type`` AS CHAR(16))`,cast(`l`.`operator_id` as signed) AS `CAST(l.``operator_id`` AS SIGNED)`,cast(`l`.`operator_name` as char(64) charset utf8mb4) AS `CAST(l.``operator_name`` AS CHAR(64))`,cast(`l`.`ref_type` as char(24) charset utf8mb4) AS `CAST(l.``ref_type`` AS CHAR(24))`,cast(`l`.`ref_id` as signed) AS `CAST(l.``ref_id`` AS SIGNED)`,cast(`l`.`event_key` as char(96) charset utf8mb4) AS `CAST(l.``event_key`` AS CHAR(96))`,cast(`l`.`idem_key` as char(96) charset utf8mb4) AS `CAST(l.``idem_key`` AS CHAR(96))`,`l`.`created_at` AS `created_at`,cast(`l`.`id` as signed) AS `CAST(l.``id`` AS SIGNED)` from `return_status_ledger` `l`
;

-- ============================================================================
-- 3. 参考数据（channel_shop 店铺种子）
--    这 3 行是结构的一部分（后端/前端都靠 shop_code 定位店铺），不是演示数据：
--    历史来源是 sql/migrate-channel.sql 的种子段，e2e 重置后必须有，否则店铺表为空。
--    02-seed.sql 既不 TRUNCATE 也不 INSERT 它 —— 所以必须跟着结构一起进基线。
-- ============================================================================

INSERT INTO `channel_shop` (`id`, `platform_type`, `shop_code`, `shop_name`, `enabled`, `watermark_at`, `last_pull_at`, `created_at`, `updated_at`) VALUES (1,'MALL','MALL-001','七七商城自营',1,NULL,NULL,'2026-11-13 20:25:55','2026-11-13 20:25:55');
INSERT INTO `channel_shop` (`id`, `platform_type`, `shop_code`, `shop_name`, `enabled`, `watermark_at`, `last_pull_at`, `created_at`, `updated_at`) VALUES (2,'TAOBAO_MOCK','TB-001','淘宝旗舰店（模拟）',1,NULL,NULL,'2026-11-13 20:25:55','2026-11-13 20:25:55');
INSERT INTO `channel_shop` (`id`, `platform_type`, `shop_code`, `shop_name`, `enabled`, `watermark_at`, `last_pull_at`, `created_at`, `updated_at`) VALUES (3,'DOUYIN_MOCK','DY-001','抖音小店（模拟）',1,NULL,NULL,'2026-11-13 20:25:55','2026-11-13 20:25:55');

