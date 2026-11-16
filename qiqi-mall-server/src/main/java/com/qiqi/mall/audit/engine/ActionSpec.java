package com.qiqi.mall.audit.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 策略命中的动作（{@code audit_policy.action_json} 解析结果）。
 *
 * <p>冻结<b>不是新状态码</b>：订单还停在 7 待审核，只是多了一条 active 的 {@code order_audit_tag}
 * （完整理由见 {@link com.qiqi.mall.audit.service.OrderAuditTagService} 的类注释）。
 *
 * <h3>两种动作：结论动作 + 并行动作（期 2 步骤 2）</h3>
 * <ul>
 *   <li><b>结论动作</b>（{@code tagCode} / {@code reason} / {@code reasonCode}）：FROZEN 类，
 *       <b>只有 EXCEPTION 能配</b>。它落到的是"这单被冻了"这条结论标，并且要带冻结原因字典；</li>
 *   <li><b>并行动作</b>（{@code featureTags}）：打「特征」标。<b>三种策略类型都能配</b>，
 *       与结论<b>并行</b> —— 一次命中可以既冻结又打标，也可以只打标（AUTO / BLOCK）。
 *       这就是「标是策略之间的传送带」的另一半：策略 A 打标 → 下一轮策略 B 读标
 *       （读标见 {@link ValueType#LIST} + {@link FieldRegistry#ACTIVE_TAGS}，期 2 步骤 1 已做）。</li>
 * </ul>
 * 两者为什么不是一个字段：结论动作会改"这单当前结论是什么"（参与结论标互斥），
 * 特征标与"过没过"无关（一单可以既刷单又客诉，打了标照样自动过审）。
 * 混在一起的话，"只打标不冻结"就只能靠约定俗成的空 {@code tagCode} 表达，而那正是出错的地方。
 *
 * <h3>{@code reasonCode} 是后加的，{@code reason} 没有被取代</h3>
 * <ul>
 *   <li>{@code reasonCode}：冻结原因字典的稳定键（{@code audit_freeze_reason.code}）——
 *       统计与 P1 的「不再以此原因冻结该单」都以它为键，所以策略里**应当**配它；</li>
 *   <li>{@code reason}：人话（会写进 {@code order_audit_tag.reason} 作为"冻结那一刻的快照"，
 *       也进审计摘要）。老策略只配了它、没配 code，所以它必须留着 ——
 *       这种策略在执行时按人话关键词反查出 code（见 {@code AuditExecutor}），不会因为没升级配置就丢原因。</li>
 * </ul>
 * ⚠️ {@code reason} 同时是特征标的<b>备注</b>（策略配了就用它，没配则由执行层拼
 * 「策略「X」命中：&lt;第一条条件人话&gt;」）。{@code reasonCode} 则**只属于结论动作**：
 * 特征标的 {@code reason_id} / {@code reason_code} 必须留空（那是
 * {@code audit_freeze_reason} 的外键，稳定键已经是 {@code tag_code} 本身）。
 *
 * @param tagCode     标码（FROZEN / FROZEN_RETURN_APPLY …；判定链第 2 步按 {@code FROZEN} 前缀识别）
 * @param reason      note 人话（冻结时写进 tag.reason 与 admin_audit_log.summary；也是特征标的备注），可为 null
 * @param reasonCode  冻结原因的稳定键（{@code audit_freeze_reason.code}），可为 null（老配置）
 * @param featureTags 命中时要打的「特征」标码（并行动作，可为空集合，永不为 null）
 */
public record ActionSpec(String tagCode, String reason, String reasonCode, List<String> featureTags) {

    public static final String DEFAULT_TAG = "FROZEN";

    /**
     * 归一化：{@code null} → 空集合（**不是** null），去空白、去重、保序。
     *
     * <p>为什么在记录里就归一：执行层每个命中单都要遍历它，若这里可能是 null，
     * 每个调用点都得写一次判空 —— 漏一处就是 NPE 打断整轮审核（整轮一个事务，代价是整批回滚）。
     */
    public ActionSpec {
        featureTags = normalize(featureTags);
    }

    /**
     * 兼容构造：只有结论动作、没有特征标（老配置 / 单测造数）。
     *
     * <p>加这个构造器的理由与 {@code PolicyRequest} 的先例相同：record 加一个组件就换了构造器元数，
     * 而 {@code ActionSpec.freeze(...)} / {@code new ActionSpec(tag, reason, code)} 在判定链、
     * 执行层和 {@code AuditScenario} 里都有点位。<b>为加一个字段去改十几处调用，
     * 换来的只是"没有人用得上旧签名"，而风险是改错一处就编译不过、或者更糟：编译过但语义变了。</b>
     */
    public ActionSpec(String tagCode, String reason, String reasonCode) {
        this(tagCode, reason, reasonCode, List.of());
    }

    /** 只有人话（老配置 / 测试造数）：code 留空，执行时按人话反查 */
    public static ActionSpec freeze(String reason) {
        return new ActionSpec(DEFAULT_TAG, reason, null, List.of());
    }

    /** 人话 + 稳定键（新配置：策略页下拉选的就是 code） */
    public static ActionSpec freeze(String reason, String reasonCode) {
        return new ActionSpec(DEFAULT_TAG, reason, reasonCode, List.of());
    }

    /** 冻结 + 打特征标（一次命中既冻结又打标） */
    public static ActionSpec freeze(String reason, String reasonCode, List<String> featureTags) {
        return new ActionSpec(DEFAULT_TAG, reason, reasonCode, featureTags);
    }

    /** 只打标、不冻结（AUTO / BLOCK 的并行动作：{@code tagCode} 为 null，判定链不会把它当冻结） */
    public static ActionSpec onlyFeatureTags(String... codes) {
        return new ActionSpec(null, null, null, codes == null ? List.of() : List.of(codes));
    }

    /** 这次命中要不要打特征标 */
    public boolean hasFeatureTags() {
        return !featureTags.isEmpty();
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        Set<String> ordered = new LinkedHashSet<>();
        for (String code : raw) {
            if (code == null) continue;
            String trimmed = code.trim();
            if (!trimmed.isEmpty()) ordered.add(trimmed);
        }
        return ordered.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(ordered));
    }
}
