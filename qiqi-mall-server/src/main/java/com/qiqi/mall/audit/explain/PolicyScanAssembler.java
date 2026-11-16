package com.qiqi.mall.audit.explain;

import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.ConditionTrace;
import com.qiqi.mall.audit.engine.Fmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>「{@code AuditVerdict} → {@link AuditExplainLayers.PolicyScan} 列表」的组装器</b>（纯函数）。
 *
 * <h3>为什么需要一个组装器，而不是让 {@link AuditExplainLayers} 直接吃 {@code AuditVerdict}</h3>
 * 两个原因，缺一不可：
 * <ol>
 *   <li><b>可测</b>：{@code AuditExplainLayers} 的 12 条单测之所以能钉死「五级字典序 / 不适用闭集」，
 *       是因为它的输入是纯数据。让它直接依赖引擎的 {@code PolicyView / PolicyMiss} 就等于把
 *       「界面口径」和「判定实现」焊在一起 —— 将来引擎加字段，界面口径的测试会跟着改，
 *       而那正是「同一份规则两处实现」的开端；</li>
 *   <li><b>可复用</b>：{@link AuditExplainLayers} 既要在<b>判定那一刻</b>用（引擎侧 {@code AuditVerdict}），
 *       也要在<b>事后回看</b>时用（读侧手里只有决策行的 JSON 列）。两条路的<b>共同形状</b>就是
 *       {@link PolicyScan}，所以两边都先汇到同一个形状上再算 —— 界面看到的「最接近那条」
 *       不会因为「这次是从库里读的」而换一条。</li>
 * </ol>
 *
 * <h3>两个入口、一份核心</h3>
 * <ul>
 *   <li>{@link #assemble(AuditVerdict, Map)}：引擎侧。{@code verdict} 的三个 {@code *Maps()} 方法
 *       产出的形状<b>正是</b>落库的 JSON（{@code matched_condition_json / excluded_by_json /
 *       miss_reason_json}），所以下面先把它折成 {@link DecisionSnapshot}，再走同一条解析路径；</li>
 *   <li>{@link #assemble(DecisionSnapshot, Map)}：读侧（{@code AuditQueryService#queue}）。
 *       {@link DecisionSnapshot#fromQueueRow} 从队列行取同样的三列 —— 两条入口共用一份解析，
 *       「判定那一刻看到的」和「第二天回看的」不可能漂移。</li>
 * </ul>
 *
 * <h3>三件必须由这里决定的判定（不在 {@link AuditExplainLayers} 里）</h3>
 * <ol>
 *   <li><b>HIT / EXCLUDED / MISS 怎么认</b>：不是读文案，而是读结构 ——
 *       <ul>
 *         <li>有命中策略（{@code matchedPolicyId/Name}）<b>且</b>{@code excludedBy} 为空 ⇒ 这条策略
 *             的全部条件都满足了 ⇒ {@link AuditExplainLayers.ScanOutcome#HIT}
 *             （MANUAL 的「预检降级」（缺货/成本缺失）也走这里：条件确实满足了，只是结论被人拦下）；</li>
 *         <li>否则看这条策略自己的条件留痕：<b>存在未满足条件 ⇒ MISS</b>；
 *             <b>全都满足却仍在「差在哪」里 ⇒ 只能是「被排除」</b>（引擎 5a 先 continue，5b 才看排除，
 *             所以「进了 {@code miss_reasons} 且一条都不差」当且仅当被排除）⇒ EXCLUDED。
 *             <p>这条推理是<b>结构性的</b>，不依赖任何文案。曾经的写法是看
 *             {@code reasons} 的第一句有没有「被排除：」前缀 —— 那是拿展示文案当判据，
 *             文案一改（或将来换语言）就静默把「被排除」认成「不满足」，而错的方向恰好是
 *             把「真实生效的闸门」降级成噪音；</li>
 *         <li>两条都不成立（没有任何留痕）⇒ 这条策略不进列表（宁可少一行，也不编一行）。</li>
 *       </ul>
 *   </li>
 *   <li><b>「值是否缺失」从哪来</b>：优先读 {@code ConditionTrace#valueMissing}（引擎求值那一刻
 *       就知道 {@code raw == null}，透传成布尔）；<b>只有</b>本期之前落库的历史行没有这个键，
 *       此时退回 {@link Fmt#NO_VALUE_TEXT} 常量比对 —— 常量只有一份，改文案不会静默失效。
 *       <p>为什么不用「{@code actual} 里有没有『无值』两个字」当主判据：那是拿展示文案当口径，
 *       改一次措辞就把「判不了」说成「不符」（「没填收货城市」被写成「城市不符」），
 *       把人引向错误的排查方向。</li>
 *   <li><b>优先级（{@code priority}）从哪来</b>：决策行里<b>没有</b>存 priority（它是策略配置，
 *       不是判定事实）。判定那一刻可以从 {@link com.qiqi.mall.audit.engine.PolicyView} 直接拿；
 *       读侧则由调用方给一份 {@code id → priority} 映射（{@code AuditQueryService} 从策略服务取）。
 *       拿不到就给 {@link #DEFAULT_PRIORITY}（与策略服务的缺省值 100 同口径），
 *       排序仍然<b>确定</b>（policyId 兜底），不会因为缺一份映射就变成随机顺序。</li>
 * </ol>
 *
 * <h3>边界</h3>
 * 纯函数：无 Spring、无 Mapper、无时钟、无 IO。输入相同 ⇒ 输出逐字段相同（含顺序）。
 */
public final class PolicyScanAssembler {

    /** 拿不到策略配置时的优先级缺省值（与 {@code AuditPolicyService#loadEnginePolicies} 的 100 同口径） */
    public static final int DEFAULT_PRIORITY = 100;

    /** 引擎「被排除」摘要行的前缀（只用于把摘要文案还原成人话，<b>不参与任何判定</b>） */
    private static final String EXCLUDED_PREFIX = "被排除：";

    private PolicyScanAssembler() {
    }

    // ================================================================== 引擎侧入口

    /** 组装（不带优先级：{@code priority} 一律取 {@link #DEFAULT_PRIORITY}）。 */
    public static List<AuditExplainLayers.PolicyScan> assemble(AuditVerdict verdict) {
        return assemble(verdict, Map.of());
    }

    /**
     * 引擎侧：{@code AuditVerdict} → 逐策略扫描结果。
     *
     * @param verdict    引擎产出（null ⇒ 空列表）
     * @param priorityById 策略 ID → 优先级（可空；缺的走 {@link #DEFAULT_PRIORITY}）
     */
    public static List<AuditExplainLayers.PolicyScan> assemble(AuditVerdict verdict,
                                                              Map<Long, Integer> priorityById) {
        if (verdict == null) return List.of();
        Long matchedId = verdict.matchedPolicy() == null ? null : verdict.matchedPolicy().id();
        String matchedName = verdict.matchedPolicy() == null ? null : verdict.matchedPolicy().name();
        return assemble(DecisionSnapshot.of(
                verdict.result() == null ? null : verdict.result().name(),
                matchedId, matchedName,
                verdict.matchedConditionMaps(),
                verdict.excludedByMaps(),
                verdict.missReasonMaps()), priorityById);
    }

    // ================================================================== 读侧入口

    /** 一次性组装（{@code layers} 用得上的一整份） */
    public static AuditExplainLayers.ExplainLayers layers(AuditVerdict verdict,
                                                          Map<Long, Integer> priorityById) {
        return AuditExplainLayers.build(assemble(verdict, priorityById));
    }

    /** 读侧：决策行（队列行）→ 三层结构；{@code row} 里没有判定留痕时返回「无可用策略」那一层，不抛 */
    public static AuditExplainLayers.ExplainLayers layersOfQueueRow(Map<String, Object> row,
                                                                    Map<Long, Integer> priorityById) {
        return AuditExplainLayers.build(assemble(DecisionSnapshot.fromQueueRow(row), priorityById));
    }

    // ================================================================== 交付给前端的 JSON（含第二层明细）

    /**
     * 三层结构 → <b>落 JSON 的形状</b>（{@code layers.layer1/2/3}），并给第二层补上明细
     * {@code layer2.items: [{policyId, policyName, field, why}]}。
     *
     * <h3>为什么明细要在这里补，而不是让前端从第三层里筛</h3>
     * 「哪些策略算不适用」是判定口径（{@link AuditExplainLayers#isNotApplicable} 的三道闸门），
     * 第三层每行上的 {@code notApplicable} 就是它的结果 —— 所以<b>不另写一份规则</b>，只做投影；
     * 但「一句为什么」要取<b>哪一条条件</b>的人话，等价于再认一遍「这条策略是撞在<b>哪个分类字段</b>上」。
     * 放到前端去认，就得在前端复刻「分类字段闭集 + 字段码↔中文名」的映射，或者在文案里找子串
     * （{@code text.includes('渠道')}）—— 后者是拿展示文案当口径：改一次措辞、换一次语言，
     * 「为什么」就静默退化成「渠道不符」甚至错配到别的字段。口径留在后端，
     * 用的还是 {@link AuditExplainLayers#fieldLabel} 那份唯一映射，比对的是<b>字段码</b>。
     *
     * <h3>为什么塞进 {@code layer2} 而不是并列一个顶层键</h3>
     * 第二层本来就是「不适用明细」这一层：{@code notApplicableTotal} 是它的<b>数</b>，
     * {@code items} 是它的<b>行</b>，两个必须同源，界面上那句「另有 N 条」与点开后的行数才对得平。
     * 顶层再开一个键，就等于允许两者被不同的代码路径分别覆盖。
     *
     * <p>形状是<b>纯增量</b>：{@code layer2} 原有的 {@code notApplicableTotal / byField} 一个字都没动
     * （{@code AuditExplainLayers} 的 12 条单测钉着它们，这里不碰）。
     */
    public static Map<String, Object> layersJson(AuditExplainLayers.ExplainLayers layers) {
        if (layers == null) return Map.of();
        Map<String, Object> json = layers.toMap();
        if (json.get("layer2") instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> layer2 = (Map<String, Object>) raw;
            List<Map<String, Object>> items = new ArrayList<>();
            for (NotApplicableItem item : notApplicableItems(layers)) items.add(item.toMap());
            layer2.put("items", items);
        }
        return json;
    }

    /**
     * 第二层的一行：<b>策略名 + 一句为什么</b>（不含完整条件表 —— 那是第三层的事）。
     *
     * @param policyId   策略 ID（退化输入可为 null）
     * @param policyName 策略名（界面直接显示）
     * @param field      撞上的分类字段中文名（「渠道」/「客户标签」…）
     * @param why        那句为什么：优先取引擎原文（「渠道 = 淘宝（实际 抖音）」），取不到退回「渠道不符」
     */
    public record NotApplicableItem(Long policyId, String policyName, String field, String why) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policyId", policyId);
            m.put("policyName", policyName == null ? "" : policyName);
            m.put("field", field == null ? "" : field);
            m.put("why", why == null ? "" : why);
            return m;
        }
    }

    /**
     * 第二层明细（顺序 = 第一层那句灰字里的字段顺序：<b>条数最多的那撮先列</b>，
     * 同组内按 policyId 升序）—— 与 {@link AuditExplainLayers.SecondLayer#byField()} 的排序同一口径，
     * 所以点开第二层看到的第一条，就是灰字里排第一的原因。
     */
    public static List<NotApplicableItem> notApplicableItems(AuditExplainLayers.ExplainLayers layers) {
        if (layers == null || layers.third() == null) return List.of();
        List<String> fieldOrder = layers.second() == null ? List.of()
                : layers.second().byField().stream().map(AuditExplainLayers.FieldCount::field).toList();
        return layers.third().policies().stream()
                .filter(AuditExplainLayers.MatrixRow::notApplicable)
                .sorted(java.util.Comparator
                        .comparingInt((AuditExplainLayers.MatrixRow r) -> {
                            int i = fieldOrder.indexOf(r.notApplicableField());
                            return i < 0 ? Integer.MAX_VALUE : i;
                        })
                        .thenComparing(AuditExplainLayers.MatrixRow::policyId,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(AuditExplainLayers.MatrixRow::policyName))
                .map(r -> new NotApplicableItem(r.policyId(), r.policyName(), r.notApplicableField(), whyOf(r)))
                .toList();
    }

    /**
     * 一条不适用策略「为什么」：取<b>撞在那个分类字段上</b>的那条条件的人话。
     *
     * <p>按<b>字段码</b>精确比对（{@link AuditExplainLayers#fieldLabel} 是唯一映射），
     * 不在文案里找子串 —— 理由见 {@link #layersJson}。{@code valueMissing} 的条件跳过：
     * 「没填」不是「不符」，它压根进不了 {@code notApplicable}（那三道闸门在
     * {@code AuditExplainLayers} 里），这里只是万一时的对称防御。
     */
    static String whyOf(AuditExplainLayers.MatrixRow row) {
        String label = row.notApplicableField() == null || row.notApplicableField().isBlank()
                ? "" : row.notApplicableField();
        for (AuditExplainLayers.ScanCondition c : row.missingConditions()) {
            if (c == null || c.valueMissing()) continue;
            if (!label.isEmpty() && label.equals(AuditExplainLayers.fieldLabel(c.fieldCode()))
                    && !c.text().isBlank()) {
                return c.text();
            }
        }
        return label.isEmpty() ? "分类字段不符" : label + "不符";
    }

    // ================================================================== 核心

    /**
     * 核心组装：一份「决策快照」→ 逐策略扫描结果（顺序由内容决定，与输入顺序无关：
     * 先命中那条，其余按 policyId 升序）。
     */
    public static List<AuditExplainLayers.PolicyScan> assemble(DecisionSnapshot snapshot,
                                                               Map<Long, Integer> priorityById) {
        if (snapshot == null) return List.of();
        Map<Long, Integer> prio = priorityById == null ? Map.of() : priorityById;
        List<AuditExplainLayers.PolicyScan> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();

        // ---- ① 命中策略（条件全满足）：excludedBy 为空才叫「命中」
        // 三列 JSON 都是「策略组 → 条件数组」（见 AuditVerdict 类注释），这里把组拆成条件；
        // 如果调用方给的已经是扁平条件数组（老调用方），下面的 conditionsOf 原样穿透。
        List<Map<String, Object>> matchedConds = conditionsOf(snapshot.matchedConditions());
        boolean hasMatched = snapshot.matchedPolicyId() != null
                || (snapshot.matchedPolicyName() != null && !snapshot.matchedPolicyName().isBlank());
        boolean excludedNotEmpty = !conditionsOf(snapshot.excludedBy()).isEmpty();
        if (hasMatched && !excludedNotEmpty) {
            List<Parsed> parsed = parse(matchedConds);
            List<AuditExplainLayers.ScanCondition> conds = parsed.stream().map(Parsed::condition).toList();
            out.add(new AuditExplainLayers.PolicyScan(
                    snapshot.matchedPolicyId(),
                    snapshot.matchedPolicyName(),
                    priorityOf(prio, snapshot.matchedPolicyId()),
                    AuditExplainLayers.ScanOutcome.HIT,
                    conds.size(),
                    0,
                    List.of(),
                    "",
                    firstText(conds)));
            if (snapshot.matchedPolicyId() != null) seen.add(snapshot.matchedPolicyId());
        }

        // ---- ② 逐条策略的「差在哪 / 被排除」
        for (Map<String, Object> miss : asList(snapshot.missReasons())) {
            if (miss == null) continue;
            Long id = toLong(first(miss, "policyId", "policy_id"));
            if (id != null && seen.contains(id)) continue;   // 同一策略不出现两行（命中那条已经更准）
            String name = str(first(miss, "policyName", "policy_name"));
            List<Parsed> conds = parse(asList(miss.get("conditions")));
            List<AuditExplainLayers.ScanCondition> failed = conds.stream()
                    .filter(p -> !p.satisfied())
                    .map(Parsed::condition)
                    .toList();
            boolean excluded = failed.isEmpty();
            out.add(new AuditExplainLayers.PolicyScan(
                    id,
                    name,
                    priorityOf(prio, id),
                    excluded ? AuditExplainLayers.ScanOutcome.EXCLUDED : AuditExplainLayers.ScanOutcome.MISS,
                    conds.size() - failed.size(),
                    failed.size(),
                    failed,
                    excluded ? excludedReason(miss) : "",
                    ""));   // hitCondition 只属于命中那条
            if (id != null) seen.add(id);
        }
        return List.copyOf(out);
    }

    /**
     * 一条条件 + 它在引擎留痕里的 {@code satisfied}（<b>直接读，不重算</b>：AND 链上"全部求值"，
     * 未求值的条件也有真值，见 {@link ConditionTrace}）。
     */
    record Parsed(AuditExplainLayers.ScanCondition condition, boolean satisfied) {
    }

    // ================================================================== 形状解析（JSON → 纯数据）

    /**
     * 「策略组 → 条件数组」摊平成条件数组。元素本身就是一条条件（没有 {@code conditions} 键）时原样保留 ——
     * 两种形状都认，免得调用方多写一层包装。
     */
    static List<Map<String, Object>> conditionsOf(List<Map<String, Object>> groups) {
        if (groups == null || groups.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            if (g == null) continue;
            Object conditions = g.get("conditions");
            if (conditions instanceof List<?>) {
                out.addAll(asList(conditions));
            } else {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * 条件数组 → {@code Parsed}（{@code valueMissing} 优先读透传布尔，历史行退回文案常量）。
     * {@code seq} 缺失时按出现顺序补（老行的 seq 可能为 null：那时展示顺序就是数组顺序）。
     */
    static List<Parsed> parse(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Parsed> out = new ArrayList<>(raw.size());
        int autoSeq = 0;
        for (Map<String, Object> c : raw) {
            if (c == null) continue;
            Long rawSeq = toLong(first(c, "seq"));
            int seq = (int) Math.max(0, rawSeq == null ? autoSeq : rawSeq);
            AuditExplainLayers.ScanCondition scan = new AuditExplainLayers.ScanCondition(
                    seq,
                    str(first(c, "fieldCode", "field")),
                    str(first(c, "opCode", "op")),
                    valueMissingOf(c),
                    str(first(c, "text", "reason")));
            out.add(new Parsed(scan, bool(c.get("satisfied"), true)));
            autoSeq++;
        }
        return out;
    }

    /**
     * 「这条条件的实际值是不是缺失」。
     *
     * <p>① 有 {@code valueMissing} 键 ⇒ 用它（本期起的新行，引擎求值时就知道）；
     * ② 没有（本期之前落库的历史行）⇒ 只能比对 {@link Fmt#NO_VALUE_TEXT} / {@link Fmt#UNASSEMBLED_TEXT}
     * —— 用<b>常量</b>而不是字面量，文案改了这里跟着改，不会静默失效。
     * ③ 两者都取不到 ⇒ {@code false}（当作"有值"）：宁可少认一次不适用，也不要把一条没证据的
     * 渠道条件说成"判不了"。
     */
    static boolean valueMissingOf(Map<String, Object> condition) {
        if (condition.containsKey("valueMissing")) return bool(condition.get("valueMissing"), false);
        String actual = str(condition.get("actual"));
        return Fmt.NO_VALUE_TEXT.equals(actual) || Fmt.UNASSEMBLED_TEXT.equals(actual);
    }

    /** 「被排除」那句人话：把引擎摘要行的前缀去掉（它已经在别处说了一次），空则退回第一条排除条件 */
    private static String excludedReason(Map<String, Object> miss) {
        List<String> reasons = anyList(miss.get("reasons")).stream()
                .map(PolicyScanAssembler::textOf)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.startsWith(EXCLUDED_PREFIX) ? s.substring(EXCLUDED_PREFIX.length()).strip() : s.strip())
                .toList();
        return String.join("；", reasons);
    }

    private static String textOf(Object value) {
        if (value instanceof Map<?, ?> m) {
            Object text = m.get("text");
            return text == null ? null : String.valueOf(text);
        }
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(List<AuditExplainLayers.ScanCondition> conds) {
        return conds.isEmpty() ? "" : conds.get(0).text();
    }

    private static int priorityOf(Map<Long, Integer> prio, Long id) {
        if (id == null) return DEFAULT_PRIORITY;
        Integer p = prio.get(id);
        return p == null ? DEFAULT_PRIORITY : Math.max(0, p);
    }

    // ---- 小工具（MyBatis 的 Map 什么类型都可能回：BigInteger / String / Boolean…

    private static Object first(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null) return v;
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) return fallback;
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "是".equals(text);
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 任意 List → 元素列表（{@code reasons} 是字符串数组，不能当成 Map 数组读） */
    private static List<Object> anyList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return new ArrayList<>(list);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    // ================================================================== 输入快照

    /**
     * 一份判定的<b>可留痕形状</b>（决策行的三列 JSON + 结论 + 命中策略）。
     *
     * <p>它存在的理由：引擎侧与读侧拿到的原料不同（一个是 record，一个是 MyBatis 的 Map），
     * 但<b>能表达的事实是同一件事</b>（决策行就是 {@code AuditVerdict} 的序列化，
     * 见 {@link AuditVerdict} 类注释的表）。把两者先收敛到这里，解析逻辑就只有一份。
     *
     * @param result            结论（{@code AUTO_PASS / MANUAL / BLOCKED / FROZEN / NO_MATCH / SKIPPED}）
     * @param matchedPolicyId   命中/最相关策略 ID（没有时 null）
     * @param matchedPolicyName 命中/最相关策略名
     * @param matchedConditions {@code matched_condition_json} 的结构（策略组 → 条件数组）
     * @param excludedBy        {@code excluded_by_json} 的结构
     * @param missReasons       {@code miss_reason_json} 的结构
     */
    public record DecisionSnapshot(
            String result,
            Long matchedPolicyId,
            String matchedPolicyName,
            List<Map<String, Object>> matchedConditions,
            List<Map<String, Object>> excludedBy,
            List<Map<String, Object>> missReasons) {

        public DecisionSnapshot {
            matchedConditions = matchedConditions == null ? List.of() : List.copyOf(matchedConditions);
            excludedBy = excludedBy == null ? List.of() : List.copyOf(excludedBy);
            missReasons = missReasons == null ? List.of() : List.copyOf(missReasons);
        }

        public static DecisionSnapshot of(String result, Long matchedPolicyId, String matchedPolicyName,
                                          List<Map<String, Object>> matchedConditions,
                                          List<Map<String, Object>> excludedBy,
                                          List<Map<String, Object>> missReasons) {
            return new DecisionSnapshot(result, matchedPolicyId, matchedPolicyName,
                    matchedConditions, excludedBy, missReasons);
        }

        /**
         * 从<b>队列行</b>取（{@code AuditQueryService#queueRow} 已把三列 JSON 解析成结构）。
         *
         * <p>键名两种都认（{@code matched_policy_id / matchedPolicyId}）：队列行目前是 snake_case
         * （MyBatis 直接写列名），但同一份「留痕」在别的接口上可能已经是驼峰 —— 认两种比"约定一种"
         * 少一类空白。三列 JSON 的结构键名沿用 {@code QueueRow} 的 {@code miss_reasons} 等
         * （它们由 {@code AuditQueryService} 从 {@code *_json} 改名而来）。
         */
        public static DecisionSnapshot fromQueueRow(Map<String, Object> row) {
            if (row == null) return new DecisionSnapshot(null, null, null, List.of(), List.of(), List.of());
            Object matchedId = first(row, "matchedPolicyId", "matched_policy_id");
            return new DecisionSnapshot(
                    str(first(row, "result")),
                    toLong(matchedId),
                    str(first(row, "matchedPolicyName", "matched_policy_name")),
                    asList(first(row, "matchedConditions", "matched_conditions", "matched_condition_json")),
                    asList(first(row, "excludedBy", "excluded_by", "excluded_by_json")),
                    asList(first(row, "missReasons", "miss_reasons", "miss_reason_json")));
        }

        /** 「逐条策略的差异组」按 policyId 建索引（给前端按策略定位用；重复的保留第一条） */
        public Map<Long, Map<String, Object>> missByPolicyId() {
            Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
            for (Map<String, Object> m : missReasons) {
                Long id = toLong(first(m, "policyId", "policy_id"));
                if (id != null) out.putIfAbsent(id, m);
            }
            return out;
        }
    }
}
