package com.qiqi.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.audit.dto.ConditionDto;
import com.qiqi.mall.audit.dto.ConditionRequest;
import com.qiqi.mall.audit.dto.FieldMetaDto;
import com.qiqi.mall.audit.dto.PolicyDto;
import com.qiqi.mall.audit.dto.PolicyListDto;
import com.qiqi.mall.audit.dto.PolicyRequest;
import com.qiqi.mall.audit.engine.ActionSpec;
import com.qiqi.mall.audit.engine.ConditionKind;
import com.qiqi.mall.audit.engine.ConditionScope;
import com.qiqi.mall.audit.engine.ConditionView;
import com.qiqi.mall.audit.engine.FieldRegistry;
import com.qiqi.mall.audit.engine.OpCode;
import com.qiqi.mall.audit.engine.PolicyType;
import com.qiqi.mall.audit.engine.PolicyView;
import com.qiqi.mall.audit.engine.ValueType;
import com.qiqi.mall.audit.entity.AuditPolicy;
import com.qiqi.mall.audit.entity.AuditPolicyCondition;
import com.qiqi.mall.audit.entity.AuditPolicyVersion;
import com.qiqi.mall.audit.mapper.AuditPolicyConditionMapper;
import com.qiqi.mall.audit.mapper.AuditPolicyMapper;
import com.qiqi.mall.audit.mapper.AuditPolicyVersionMapper;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核策略的配置面：读写策略/条件、排序、版本快照、变更审计，以及给引擎装载只读视图。
 *
 * <h3>三条硬口径</h3>
 * <ol>
 *   <li><b>条件闭集在后端</b>：{@code field_code} 必须能在 {@link FieldRegistry} 里查到、
 *       {@code op_code} 必须能在 {@link OpCode} 里查到、操作符必须与字段类型相容。
 *       DDL 刻意不钉这些闭集（见迁移脚本），所以这里是唯一的守门人 —— 放进来一个拼错的字段，
 *       那条策略就永远不会命中，而且没人看得出原因。</li>
 *   <li><b>每次改动都留证 + 留快照</b>：{@code audit_policy_version} 存整份策略+条件，
 *       {@code admin_audit_log} 存「谁在什么时候改了哪条」。策略改一次就可能让几千单的判定结果变样，
 *       没有版本快照时「上周为什么这单没自动过」永远查不出来。</li>
 *   <li><b>全局排除不跟着策略走</b>：{@code scope=ALL_AUTO} 的行 {@code policy_id} 为 NULL，
 *       只有请求显式带了 {@code globalExclusions} 才整组替换（null = 不动）。</li>
 * </ol>
 */
@Service
public class AuditPolicyService {

    private static final Logger log = LoggerFactory.getLogger(AuditPolicyService.class);

    private final AuditPolicyMapper policyMapper;
    private final AuditPolicyConditionMapper conditionMapper;
    private final AuditPolicyVersionMapper versionMapper;
    private final AdminAuditWriter auditWriter;
    private final ObjectMapper json;

    public AuditPolicyService(AuditPolicyMapper policyMapper,
                              AuditPolicyConditionMapper conditionMapper,
                              AuditPolicyVersionMapper versionMapper,
                              AdminAuditWriter auditWriter,
                              ObjectMapper json) {
        this.policyMapper = policyMapper;
        this.conditionMapper = conditionMapper;
        this.versionMapper = versionMapper;
        this.auditWriter = auditWriter;
        this.json = json;
    }

    // ================================================================== 读

    /** 策略页：策略 + 全局排除 + 字段/操作符元数据 */
    public PolicyListDto list() {
        List<PolicyDto> policies = policyMapper.selectAllOrdered().stream()
                .map(p -> toDto(p, conditionMapper.selectByPolicy(p.getId())))
                .toList();
        List<ConditionDto> globals = conditionMapper.selectGlobalExclusions().stream().map(this::toDto).toList();
        List<FieldMetaDto> fields = FieldRegistry.all().stream()
                .map(f -> new FieldMetaDto(f.code(), f.label(), f.type().name(), allowedOps(f.type())))
                .toList();
        List<String> ops = Arrays.stream(OpCode.values()).map(Enum::name).toList();
        return new PolicyListDto(policies, globals, fields, ops);
    }

    /** 单条策略（不存在抛 404 语义的异常，由 GlobalExceptionHandler 转成中文失败） */
    public PolicyDto get(Long id) {
        AuditPolicy policy = requirePolicy(id);
        return toDto(policy, conditionMapper.selectByPolicy(id));
    }

    /**
     * 给引擎装载策略视图（**只读**）。
     *
     * <p>全局排除行挂到每条策略的 conditions 上（DB 里它们没有归属策略），引擎按 scope 汇总并去重 ——
     * 这样「停用某条策略」不会让全局排除消失，也不需要在引擎里加第二个入参。
     */
    public List<PolicyView> loadEnginePolicies() {
        List<AuditPolicyCondition> globals = conditionMapper.selectGlobalExclusions();
        List<ConditionView> globalViews = globals.stream().map(this::toConditionView).toList();
        List<PolicyView> result = new ArrayList<>();
        for (AuditPolicy policy : policyMapper.selectAllOrdered()) {
            List<ConditionView> conditions = new ArrayList<>(
                    conditionMapper.selectByPolicy(policy.getId()).stream().map(this::toConditionView).toList());
            conditions.addAll(globalViews);
            result.add(new PolicyView(policy.getId(), PolicyType.valueOf(policy.getType()),
                    policy.getPriority() == null ? 100 : policy.getPriority(), policy.getName(),
                    policy.getVersionNo() == null ? 1 : policy.getVersionNo(),
                    policy.getEnabled() != null && policy.getEnabled() == 1,
                    parseAction(policy.getActionJson(), policy.getName()),
                    List.copyOf(conditions)));
        }
        return result;
    }

    // ================================================================== 写

    @Transactional(rollbackFor = Exception.class)
    public PolicyDto create(PolicyRequest req) {
        PolicyType type = requireType(req.type());
        String name = requireName(req.name());
        if (existsName(type, name, null)) {
            throw new IllegalArgumentException("同类型下已有同名策略：" + name);
        }
        validateAction(type, req);

        AuditPolicy policy = new AuditPolicy();
        policy.setType(type.name());
        policy.setPriority(req.priority() == null ? 100 : req.priority());
        policy.setName(name);
        policy.setEnabled(req.enabled() == null || req.enabled() ? 1 : 0);
        policy.setVersionNo(1);
        policy.setActionJson(type == PolicyType.EXCEPTION ? actionJson(req) : null);
        policyMapper.insert(policy);

        int seq = 0;
        for (ConditionRequest c : nullSafe(req.conditions())) {
            insertCondition(policy.getId(), seq++, c, ConditionScope.POLICY);
        }
        if (req.globalExclusions() != null) {
            replaceGlobalExclusions(req.globalExclusions());
        }
        snapshot(policy);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_POLICY_CREATE, ReasonCodes.TARGET_POLICY, policy.getId(), name,
                "新建审核策略「" + name + "」（type=" + type + "，priority=" + policy.getPriority() + "）",
                null, null, null,
                AdminAuditWriter.snapshot("type", type.name(), "priority", policy.getPriority(),
                        "enabled", policy.getEnabled())));
        log.info("新建审核策略 {} #{} type={} priority={}", name, policy.getId(), type, policy.getPriority());
        return toDto(policy, conditionMapper.selectByPolicy(policy.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public PolicyDto update(Long id, PolicyRequest req) {
        AuditPolicy before = requirePolicy(id);
        PolicyType type = req.type() == null ? PolicyType.valueOf(before.getType()) : requireType(req.type());
        String name = req.name() == null ? before.getName() : requireName(req.name());
        if (existsName(type, name, id)) {
            throw new IllegalArgumentException("同类型下已有同名策略：" + name);
        }
        validateAction(type, req);

        AuditPolicy update = new AuditPolicy();
        update.setId(id);
        update.setType(type.name());
        update.setName(name);
        update.setPriority(req.priority() == null ? before.getPriority() : req.priority());
        update.setEnabled(req.enabled() == null ? before.getEnabled() : (req.enabled() ? 1 : 0));
        // 版本号 +1：改完条件后，历史决策仍然指得到「当时是哪一版」
        update.setVersionNo((before.getVersionNo() == null ? 1 : before.getVersionNo()) + 1);
        update.setActionJson(type == PolicyType.EXCEPTION ? actionJson(req) : null);
        policyMapper.updateById(update);

        conditionMapper.deleteByPolicy(id);
        int seq = 0;
        for (ConditionRequest c : nullSafe(req.conditions())) {
            insertCondition(id, seq++, c, ConditionScope.POLICY);
        }
        if (req.globalExclusions() != null) {
            replaceGlobalExclusions(req.globalExclusions());
        }
        AuditPolicy after = policyMapper.selectById(id);
        snapshot(after);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_POLICY_UPDATE, ReasonCodes.TARGET_POLICY, id, name,
                "修改审核策略「" + name + "」（version " + before.getVersionNo() + " → " + after.getVersionNo() + "）",
                null, null,
                AdminAuditWriter.snapshot("type", before.getType(), "priority", before.getPriority(),
                        "enabled", before.getEnabled()),
                AdminAuditWriter.snapshot("type", after.getType(), "priority", after.getPriority(),
                        "enabled", after.getEnabled(),
                        "conditions", nullSafe(req.conditions()).size())));
        log.info("修改审核策略 {} #{} version={}", name, id, after.getVersionNo());
        return toDto(after, conditionMapper.selectByPolicy(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AuditPolicy policy = requirePolicy(id);
        // 条件与版本快照随策略级联删除（DDL ON DELETE CASCADE）。
        // 历史决策行**不**级联：它们只快照了策略名与版本号，删策略不影响「当时按哪条判的」这段事实。
        policyMapper.deleteById(id);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_POLICY_DELETE, ReasonCodes.TARGET_POLICY, id, policy.getName(),
                "删除审核策略「" + policy.getName() + "」（type=" + policy.getType() + "）",
                null, null,
                AdminAuditWriter.snapshot("name", policy.getName(), "type", policy.getType(),
                        "priority", policy.getPriority(), "versionNo", policy.getVersionNo()),
                AdminAuditWriter.snapshot("deleted", Boolean.TRUE)));
        log.info("删除审核策略 {} #{}", policy.getName(), id);
    }

    /**
     * 上移 / 下移：只与**同类型**的相邻策略换位（跨类型换位没有意义 —— 执行顺序由判定链定，
     * 一个 AUTO 无论 priority 多小都不可能排到 BLOCK 前面）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PolicyDto move(Long id, String direction) {
        AuditPolicy current = requirePolicy(id);
        boolean up = "UP".equalsIgnoreCase(direction) || "上移".equals(direction);
        boolean down = "DOWN".equalsIgnoreCase(direction) || "下移".equals(direction);
        if (!up && !down) {
            throw new IllegalArgumentException("direction 只支持 UP / DOWN，收到：" + direction);
        }
        AuditPolicy neighbour = up
                ? policyMapper.selectPrev(current.getType(), id, current.getPriority())
                : policyMapper.selectNext(current.getType(), id, current.getPriority());
        if (neighbour == null) {
            throw new IllegalArgumentException("已经是该类型的第一条 / 最后一条，无法继续" + (up ? "上移" : "下移"));
        }
        int curPriority = current.getPriority() == null ? 100 : current.getPriority();
        int nbPriority = neighbour.getPriority() == null ? 100 : neighbour.getPriority();

        AuditPolicy a = new AuditPolicy();
        a.setId(current.getId());
        AuditPolicy b = new AuditPolicy();
        b.setId(neighbour.getId());
        if (curPriority == nbPriority) {
            // priority 相同：顺序本来靠 id 兜底，交换 priority 无法生效 → 直接把当前这条挪到邻居外侧
            a.setPriority(up ? nbPriority - 1 : nbPriority + 1);
        } else {
            a.setPriority(nbPriority);
            b.setPriority(curPriority);
        }
        policyMapper.updateById(a);
        if (b.getPriority() != null) policyMapper.updateById(b);

        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_POLICY_PRIORITY, ReasonCodes.TARGET_POLICY, id, current.getName(),
                "审核策略「" + current.getName() + "」" + (up ? "上移" : "下移") + "（与「" + neighbour.getName() + "」换位）",
                null, null,
                AdminAuditWriter.snapshot("priority", curPriority),
                AdminAuditWriter.snapshot("priority", a.getPriority())));
        return get(id);
    }

    // ================================================================== 内部

    private void replaceGlobalExclusions(List<ConditionRequest> requests) {
        for (ConditionRequest c : requests) {
            if (c.kind() == null || !"EXCLUDE".equalsIgnoreCase(c.kind())) {
                throw new IllegalArgumentException("全局排除（scope=ALL_AUTO）只能是 EXCLUDE 条件");
            }
        }
        conditionMapper.deleteGlobalExclusions();
        int seq = 0;
        for (ConditionRequest c : requests) {
            insertCondition(null, seq++, c, ConditionScope.ALL_AUTO);
        }
    }

    private void insertCondition(Long policyId, int seq, ConditionRequest req, ConditionScope requiredScope) {
        ConditionScope scope = req.scope() == null ? requiredScope : parseScope(req.scope());
        if (scope != requiredScope) {
            throw new IllegalArgumentException("条件的 scope 与所在集合不匹配：期望 " + requiredScope + "，收到 " + scope);
        }
        ConditionKind kind = parseKind(req.kind());
        if (requiredScope == ConditionScope.ALL_AUTO && kind != ConditionKind.EXCLUDE) {
            throw new IllegalArgumentException("全局排除只能是 EXCLUDE 条件（ALL_AUTO 的 MATCH 没有语义）");
        }
        FieldRegistry.FieldDef def = FieldRegistry.require(req.fieldCode());
        OpCode op = OpCode.of(req.opCode());
        if (op == null) {
            throw new IllegalArgumentException("未知操作符「" + req.opCode() + "」，可用：" + Arrays.toString(OpCode.values()));
        }
        if (def.type() == ValueType.JSON) {
            throw new IllegalArgumentException("字段 " + def.code() + " 暂不支持配置");
        }
        // 用求值器「空跑」一次做类型相容与值完整性校验：与判定时完全同一套规则，不会出现
        // 「保存时通过、判定时抛异常」的两套口径
        AuditPolicyCondition probeRow = new AuditPolicyCondition();
        probeRow.setSeq(seq);
        probeRow.setKind(kind.name());
        probeRow.setScope(scope.name());
        probeRow.setFieldCode(def.code());
        probeRow.setOpCode(op.name());
        probeRow.setValueType(def.type().name());
        probeRow.setValueNum(req.valueNum());
        probeRow.setValueText(req.valueText());
        com.qiqi.mall.audit.engine.ConditionEvaluator.evaluate(blankContext(), toConditionView(probeRow));

        AuditPolicyCondition row = new AuditPolicyCondition();
        row.setPolicyId(policyId);
        row.setSeq(seq);
        row.setKind(kind.name());
        row.setScope(scope.name());
        row.setFieldCode(def.code());
        row.setOpCode(op.name());
        row.setValueType(def.type().name());
        row.setValueNum(def.type() == ValueType.TEXT ? null : req.valueNum());
        row.setValueText(def.type() == ValueType.TEXT ? req.valueText() : null);
        row.setValueJson(null);
        conditionMapper.insert(row);
    }

    /** 校验用的空上下文：所有字段都是「无值」，只用来触发类型/值完整性检查 */
    private static com.qiqi.mall.audit.engine.AuditContext blankContext() {
        return new com.qiqi.mall.audit.engine.AuditContext(0L, "PROBE", 7, null, null, null, null, null, null,
                null, null, false, false, 0, 0, 0, 0, false, null, null, null, false, null, LocalDateTime.now());
    }

    private void validateAction(PolicyType type, PolicyRequest req) {
        if (type == PolicyType.EXCEPTION) {
            String tag = req.actionTag() == null || req.actionTag().isBlank()
                    ? ActionSpec.DEFAULT_TAG : req.actionTag().trim();
            if (tag.length() > 32) {
                throw new IllegalArgumentException("冻结标码不能超过 32 字符（order_audit_tag.tag_code）");
            }
        } else if ((req.actionTag() != null && !req.actionTag().isBlank())
                || (req.actionReason() != null && !req.actionReason().isBlank())) {
            throw new IllegalArgumentException(type + " 策略不能带冻结动作（只有 EXCEPTION 会冻结订单）");
        }
    }

    private String actionJson(PolicyRequest req) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("tag", req.actionTag() == null || req.actionTag().isBlank()
                ? ActionSpec.DEFAULT_TAG : req.actionTag().trim());
        action.put("reason", req.actionReason() == null || req.actionReason().isBlank()
                ? "异常策略命中" : req.actionReason().trim());
        try {
            return json.writeValueAsString(action);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("冻结动作序列化失败：" + e.getMessage(), e);
        }
    }

    private ActionSpec parseAction(String actionJson, String policyName) {
        if (actionJson == null || actionJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = json.readTree(actionJson);
            String tag = node.path("tag").asText(ActionSpec.DEFAULT_TAG);
            String reason = node.path("reason").asText("异常策略命中：" + policyName);
            return new ActionSpec(tag, reason);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("策略 " + policyName + " 的 action_json 不是合法 JSON：" + e.getMessage(), e);
        }
    }

    private void snapshot(AuditPolicy policy) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("policy", policy);
        snap.put("conditions", conditionMapper.selectByPolicy(policy.getId()));
        snap.put("global_exclusions", false);   // 全局排除不属于任何策略版本，单独维护
        AuditPolicyVersion version = new AuditPolicyVersion();
        version.setPolicyId(policy.getId());
        version.setVersionNo(policy.getVersionNo() == null ? 1 : policy.getVersionNo());
        try {
            version.setSnapshotJson(json.writeValueAsString(snap));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("策略快照序列化失败：" + e.getMessage(), e);
        }
        // 同一版本号重复写（并发保存）会撞 uk_policy_version → 直接抛，让调用方重试：
        // 静默覆盖快照等于丢掉「这一版到底长什么样」的证据
        versionMapper.insert(version);
    }

    private AuditPolicy requirePolicy(Long id) {
        AuditPolicy policy = id == null ? null : policyMapper.selectById(id);
        if (policy == null) {
            throw new IllegalArgumentException("审核策略不存在：" + id);
        }
        return policy;
    }

    private boolean existsName(PolicyType type, String name, Long excludeId) {
        return policyMapper.selectAllOrdered().stream()
                .anyMatch(p -> p.getType().equals(type.name()) && p.getName().equals(name)
                        && !p.getId().equals(excludeId));
    }

    private static PolicyType requireType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("策略类型不能为空（AUTO / BLOCK / EXCEPTION）");
        }
        try {
            return PolicyType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知策略类型「" + raw + "」，可用：AUTO / BLOCK / EXCEPTION");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("策略名不能为空");
        }
        if (name.trim().length() > 64) {
            throw new IllegalArgumentException("策略名不能超过 64 字符");
        }
        return name.trim();
    }

    private static ConditionKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return ConditionKind.MATCH;
        try {
            return ConditionKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知条件种类「" + raw + "」，可用：MATCH / EXCLUDE");
        }
    }

    private static ConditionScope parseScope(String raw) {
        try {
            return ConditionScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知条件作用域「" + raw + "」，可用：POLICY / ALL_AUTO");
        }
    }

    private static String allowedOps(ValueType type) {
        return switch (type) {
            case NUM -> "EQ,NE,LT,LTE,GT,GTE,EXISTS,NOT_EXISTS";
            case BOOL -> "EQ,NE,EXISTS,NOT_EXISTS";
            case TEXT -> "EQ,NE,CONTAINS,NOT_CONTAINS,EXISTS,NOT_EXISTS";
            case JSON -> "";
        };
    }

    private static List<ConditionRequest> nullSafe(List<ConditionRequest> list) {
        return list == null ? List.of() : list;
    }

    private PolicyDto toDto(AuditPolicy policy, List<AuditPolicyCondition> conditions) {
        ActionSpec action = parseAction(policy.getActionJson(), policy.getName());
        return new PolicyDto(policy.getId(), policy.getType(), policy.getPriority(), policy.getName(),
                policy.getEnabled() != null && policy.getEnabled() == 1, policy.getVersionNo(),
                action == null ? null : action.tagCode(), action == null ? null : action.reason(),
                conditions.stream().map(this::toDto).toList());
    }

    private ConditionDto toDto(AuditPolicyCondition c) {
        FieldRegistry.FieldDef def = FieldRegistry.find(c.getFieldCode());
        return new ConditionDto(c.getId(), c.getSeq(), c.getKind(), c.getScope(), c.getFieldCode(),
                def == null ? c.getFieldCode() : def.label(), c.getOpCode(), c.getValueType(),
                c.getValueNum(), c.getValueText(), c.getValueJson());
    }

    private ConditionView toConditionView(AuditPolicyCondition c) {
        return new ConditionView(c.getId(), c.getSeq(), ConditionKind.valueOf(c.getKind()),
                ConditionScope.valueOf(c.getScope()), c.getFieldCode(), c.getOpCode(),
                c.getValueType() == null ? ValueType.TEXT : ValueType.valueOf(c.getValueType()),
                c.getValueNum(), c.getValueText(), c.getValueJson());
    }
}
