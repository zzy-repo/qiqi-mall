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
    /** 冻结原因字典：校验 action_json 里的 reason_code 是不是真的存在 */
    private final AuditFreezeReasonService freezeReasonService;
    /** 标目录：校验 {@code ACTIVE_TAGS} 的条件只引用「特征」标（结论标在保存时拒掉，见 {@link #validateTagReference}） */
    private final AuditTagCatalogService tagCatalogService;

    public AuditPolicyService(AuditPolicyMapper policyMapper,
                              AuditPolicyConditionMapper conditionMapper,
                              AuditPolicyVersionMapper versionMapper,
                              AdminAuditWriter auditWriter,
                              ObjectMapper json,
                              AuditFreezeReasonService freezeReasonService,
                              AuditTagCatalogService tagCatalogService) {
        this.policyMapper = policyMapper;
        this.conditionMapper = conditionMapper;
        this.versionMapper = versionMapper;
        this.auditWriter = auditWriter;
        this.json = json;
        this.freezeReasonService = freezeReasonService;
        this.tagCatalogService = tagCatalogService;
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
        // 期 2 步骤 1 口径①：ACTIVE_TAGS 只许引用「特征」标（结论标在这里被拒掉）。
        // 放在求值探针之前 —— 结论标不是"求值会失败"，而是"这条策略根本不该存在"，报错要直说原因。
        validateTagReference(def, req.valueText());
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
        // 值落在哪一列由字段类型决定（DDL 的 ck_audit_policy_condition_value 钉住这个对应关系）：
        // TEXT / LIST → value_text；NUM / BOOL → value_num。写错列会被 CHECK 拒掉，
        // 但那时报的是一句 MySQL 的约束错误，没人看得懂，所以这里先把口径摆正。
        row.setValueNum(storeNum(def.type()) ? req.valueNum() : null);
        row.setValueText(storeText(def.type()) ? req.valueText() : null);
        row.setValueJson(null);
        conditionMapper.insert(row);
    }

    /** 该类型的条件值存在 {@code value_num} 上 */
    private static boolean storeNum(ValueType type) {
        return type == ValueType.NUM || type == ValueType.BOOL;
    }

    /** 该类型的条件值存在 {@code value_text} 上（多值字段的条件值是"一个元素码"，也在这一列） */
    private static boolean storeText(ValueType type) {
        return type == ValueType.TEXT || type == ValueType.LIST;
    }

    /**
     * 读标条件的守门（期 2 步骤 1 口径①）：{@code ACTIVE_TAGS} 只允许引用 {@code category='特征'} 的标。
     *
     * <h3>为什么结论标必须在这里拒绝，而不是在判定时忽略</h3>
     * 标是在<b>判定之后</b>才落库的。所以「策略 A 打标 → 策略 B 读标」只能等下一轮 ——
     * 这是<b>轮间传送带</b>，不是链内数据流。引用结论标（BLOCKED / FROZEN / STOCK_SHORT /
     * NO_POLICY_MATCH…）等于拿<b>上一轮判定的照片</b>做这一轮的决定：
     * <ul>
     *   <li>语义上是自相矛盾：这一轮的结论还没出来，条件却在读"这一轮的结论"；</li>
     *   <li>实际后果是策略之间出现<b>隐性依赖</b>：谁先跑、上一轮谁命中，悄悄改变了这一轮的结果，
     *       而证据里只看到「读标条件不满足/满足」，看不出它是上一轮的残留；</li>
     *   <li>它会随「上一轮有没有跑」而变 —— 同一份数据跑两次能得到两种结论，
     *       正好是「不在流程开头一次算全」忌讳的另一面（拿旧结果当输入）。</li>
     * </ul>
     * 判「结论档」的依据是标目录（{@code audit_tag_catalog.category}），而不是代码里枚举几个码：
     * 将来加一类结论标只改字典，不用回来改这段校验。另外 {@code FROZEN} 前缀也算结论档 ——
     * 冻结类标是判定的产物，且按前缀识别（{@code FROZEN_RETURN_APPLY} 这种细分未必登记在目录里）。
     *
     * <p>目录里<b>查不到</b>的标码不拒（那条纪律见 {@code AuditTagCatalog}：标码允许先落库、目录后补），
     * 只有<b>明确登记为结论</b>的才拒 —— 否则期 1.6 的特征标还没登记就配不了策略。
     */
    private void validateTagReference(FieldRegistry.FieldDef def, String rawCode) {
        if (!FieldRegistry.ACTIVE_TAGS.equals(def.code())) return;
        if (rawCode == null || rawCode.isBlank()) return;   // 值完整性由求值探针报（必须配标码）
        String code = rawCode.trim();
        // 前缀判定大小写不敏感：冻结类的识别口径与判定链第 2 步一致（那边等价于 LIKE 'FROZEN%' 的 ci 比较），
        // 两端用同一把尺子，才不会出现"配置放过、运行期按冻结处理"的错位
        if (code.regionMatches(true, 0, "FROZEN", 0, "FROZEN".length())) {
            throw new IllegalArgumentException("「" + def.label() + "」只能引用「特征」类的标，不能引用冻结标「"
                    + code + "」：冻结是判定链第 2 步的产物（结论档），引用它等于拿上一轮的照片做这一轮的决定");
        }
        if (tagCatalogService.isConclusion(code)) {
            String name = tagCatalogService.nameOf(code);
            throw new IllegalArgumentException("「" + def.label() + "」只能引用「特征」类的标，不能引用结论标「"
                    + code + "」（" + name + "）：标是判定**之后**才落库的，引用结论标等于拿上一轮的照片做这一轮的决定"
                    + " —— 特征标（客诉客户 / 双地址 / 平台旗帜…）才是可以跨策略传递的那一类");
        }
    }

    /**
     * 校验用的空上下文：所有字段都是「无值」，只用来触发类型/值完整性检查。
     *
     * <p>⚠️ {@code activeTags} 必须传<b>空集合</b>（{@code List.of()}），不是 {@code null}：
     * null 表示"没装配"，求值器对它会抛异常（口径③），那样一条合法的读标条件在保存时就被打回来了。
     */
    private static com.qiqi.mall.audit.engine.AuditContext blankContext() {
        return new com.qiqi.mall.audit.engine.AuditContext(0L, "PROBE", 7, null, null, null, null, null, null,
                null, null, false, false, 0, 0, 0, 0, false, null, null, null, false, null, List.of(),
                LocalDateTime.now());
    }

    private void validateAction(PolicyType type, PolicyRequest req) {
        if (type == PolicyType.EXCEPTION) {
            String tag = req.actionTag() == null || req.actionTag().isBlank()
                    ? ActionSpec.DEFAULT_TAG : req.actionTag().trim();
            if (tag.length() > 32) {
                throw new IllegalArgumentException("冻结标码不能超过 32 字符（order_audit_tag.tag_code）");
            }
            // 原因码必须是字典里存在的启用项：写错一个字母就 400，而不是等到真冻结时
            // 才在库里留下一条指不准原因的记录（那时没人会回头改配置）
            String code = req.actionReasonCode();
            if (code != null && !code.isBlank()) {
                var reason = freezeReasonService.byCode(code.trim());
                if (reason == null) {
                    throw new IllegalArgumentException("冻结原因码不存在：" + code.trim()
                            + "（可用 GET /admin/order-audit/freeze-reasons 取列表）");
                }
                if (reason.getEnabled() == null || reason.getEnabled() != 1) {
                    throw new IllegalArgumentException("冻结原因码已停用：" + code.trim());
                }
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
        // 原因码是稳定键（统计与 P1 豁免都靠它）；没配就留 null，执行时按人话反查 ——
        // 不在这里"猜一个"写死，否则策略里会留下一份可能与字典改版失配的副本
        if (req.actionReasonCode() != null && !req.actionReasonCode().isBlank()) {
            action.put("reason_code", req.actionReasonCode().trim());
        }
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
            String reasonCode = node.path("reason_code").asText(null);   // 老配置没有这个键 → null
            return new ActionSpec(tag, reason, reasonCode);
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
            // 多值字段：集合语义的两个操作符（CONTAINS 是子串语义，在这里是错的，不给）
            case LIST -> "HAS,NOT_HAS,EXISTS,NOT_EXISTS";
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
                action == null ? null : action.reasonCode(),
                conditions.stream().map(this::toDto).toList(),
                // ⑥ 顺手补：实体上有 updated_at（ON UPDATE 自动刷），策略页那一列不用再显示「—」
                policy.getUpdatedAt());
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
