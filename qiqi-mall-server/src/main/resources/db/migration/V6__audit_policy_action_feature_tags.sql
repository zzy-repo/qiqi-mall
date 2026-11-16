-- ============================================================================
-- V6：audit_policy.action_json 从「EXCEPTION 专属冻结动作」升级为「三类型通用动作」
-- ============================================================================
-- 期 2 步骤 2：策略命中要能打「特征」标（策略 A 打标 → 下一轮策略 B 读标）。
--
-- 为什么必须动 CHECK（不能绕过）
-- ------------------------------
-- V1 的 ck_audit_policy_type_action 写的是「EXCEPTION 必须有 action_json，AUTO/BLOCK 必须 NULL」。
-- 那条约束在当时是对的：那时 action_json 只装冻结动作，而"冻结"与 AUTO/BLOCK 的语义互斥
-- （一条自动过审策略不该带冻结动作）。
-- 现在动作分两档：结论动作（FROZEN 等，仍然只属于 EXCEPTION）+ 并行动作 feature_tags
-- （三种类型都能配）。AUTO/BLOCK 的动作 JSON 里只会有 feature_tags，
-- 于是「AUTO/BLOCK 的 action_json 必须 NULL」这条不再成立 —— 不放开就没法把标配上去。
--
-- 新约束（比"完全放开"更严，而不是更松）
-- --------------------------------------
--   ① EXCEPTION 仍然必须有动作（冻结不带原因的动作 JSON 是没有意义的半配状态）；
--   ② AUTO/BLOCK 的 action_json 允许为 NULL，但**一旦存在就不许含 tag 键** ——
--      有 tag 就等于"冻结动作"，而 AUTO/BLOCK 冻结订单在语义上自相矛盾
--      （判定链说"该过/该拦"，动作却把单按住）。
--      JSON_CONTAINS_PATH 是确定性的，MySQL 8.0.16+ 允许用在 CHECK 里（实测通过）。
--      代码层的第二道闸在 AuditPolicyService#validateAction（保存时拒）与
--      #parseAction（读库时对非 EXCEPTION 强制忽略 tag），三处口径一致。
--
-- 列注释同步改写：这一列已经不只是"冻结动作"了，注释留着旧口径比没有更糟
-- （下一个人读 schema 会以为 AUTO 不可能有动作）。
--
-- 幂等：DROP CHECK + ADD CHECK 模式，手工重跑一次也不会炸。
-- ============================================================================

ALTER TABLE `audit_policy`
  DROP CHECK `ck_audit_policy_type_action`;

ALTER TABLE `audit_policy`
  MODIFY COLUMN `action_json` json DEFAULT NULL COMMENT '策略动作：EXCEPTION 必填（{"tag":"FROZEN","reason":"…","reason_code":"…"}，可并列 feature_tags）；AUTO/BLOCK 可选且只允许 {"feature_tags":["CODE",…]}（不许带 tag，见 CHECK）';

ALTER TABLE `audit_policy`
  ADD CONSTRAINT `ck_audit_policy_type_action` CHECK (
    ((`type` = 'EXCEPTION') AND (`action_json` IS NOT NULL))
    OR ((`type` IN ('AUTO', 'BLOCK'))
        AND ((`action_json` IS NULL) OR (JSON_CONTAINS_PATH(`action_json`, 'one', '$.tag') = 0)))
  );
