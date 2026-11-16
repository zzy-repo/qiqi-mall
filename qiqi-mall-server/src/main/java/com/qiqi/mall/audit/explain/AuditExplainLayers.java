package com.qiqi.mall.audit.explain;

import com.qiqi.mall.audit.engine.FieldRegistry;
import com.qiqi.mall.audit.engine.OpCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>「按人分层」的纯函数层</b>：把「一轮判定的逐策略结果」压成三层数据，
 * 让第一屏只说一句话，而渠道从 3 条策略涨到 30 条时第一屏长度<b>不变</b>。
 *
 * <h3>它解决的现场问题（用户原话）</h3>
 * <blockquote>这明明是直营店的单，但是把淘宝、抖音的判定都展示了一遍，渠道多了肉眼看得过来么</blockquote>
 * 病根不是「展示得难看」，而是<b>把不适用于这单的策略当成了「差一点点」</b>：
 * 一张直营店的单，淘宝策略差的条件永远是「渠道 ≠ 淘宝」——这条不是「接近」，是<b>压根不适用</b>。
 * 把它和「毛利差 2 块钱」混在一张列表里，人就得逐条读、逐条判断谁知道不知道，
 * 渠道一多必然看不完。于是这里做两件事：
 * <ol>
 *   <li><b>先把不适用的摘出去</b>（{@link #isNotApplicable}），聚合成一句话「另有 N 条不适用（渠道不符）」；</li>
 *   <li><b>再在剩下的里挑唯一一条最接近的</b>（{@link #CLOSEST_FIRST}，五级字典序）。</li>
 * </ol>
 *
 * <h3>为什么这些规则必须是硬规则（思想⑦）</h3>
 * 「哪条最接近」如果交给模型/人去做相关性判断，同样的输入今天给一个答案、明天给另一个，
 * 复核时无法解释、无法复现。所以本类的全部输出<b>只由输入常数决定</b>：
 * <ul>
 *   <li>不适用的判据是<b>字段闭集 + 操作符闭集</b>（见下），不是相似度打分；</li>
 *   <li>最接近的判据是<b>五级字典序</b>（HIT &lt; EXCLUDED &lt; MISS → missCount 升 → satisfiedCount 降
 *       → priority 升 → policyId 升），全序，且第 6 级兜底到内容的字典序；
 *   <li>排序是全序 + 渲染前不再依赖输入顺序，所以<b>打乱输入 N 次输出逐字节一致</b>
 *       （{@code AuditExplainLayersTest} T4 真的跑 20 次）。</li>
 * </ul>
 *
 * <h3>① 分类字段闭集：只有「身份类」字段能判「不适用」</h3>
 * 五个：{@code CHANNEL（渠道）} / {@code SHOP_NAME（店铺）} / {@code CUSTOMER_TAG（客户标签）} /
 * {@code RECEIVER_CITY（收货城市）} / {@code ACTIVE_TAGS（当前标记）}。理由：这五类描述
 * <b>「这单是谁的、从哪来」</b>，是这单的<b>身份</b>，不随操作变化；而金额、件数、毛利这类<b>量级字段</b>
 * 会因改价、拆单、补发而变，拿它们做分类会把「这单贵了」说成「这单不适用」——那就把一个可以谈的
 * 数字问题变成了一个身份判决。
 *
 * <p>操作符<b>按字段各自白名单</b>（见 {@link ClassificationField#mismatchOps()}）：
 * <ul>
 *   <li>{@code CHANNEL / SHOP_NAME / CUSTOMER_TAG / RECEIVER_CITY}：只认 {@link OpCode#EQ} / {@link OpCode#NE}
 *       —— 只有「等于/不等于某个渠道」才在回答「这单是不是这类单」。{@code CONTAINS / LT / GT / HAS / EXISTS…}
 *       表达的是程度或包含关系，不参与「不适用」判定（一条 {@code 客服备注 含 "改"} 的排除条件挡掉这单，
 *       不代表这单"不是这类单"）。</li>
 *   <li>{@code ACTIVE_TAGS}：认 {@link OpCode#HAS} / {@link OpCode#NOT_HAS}（期 2「读标」的口径）。
 *       理由见下面的「标的语义边界」。</li>
 * </ul>
 *
 * <h4>{@code ACTIVE_TAGS} 为什么必须进闭集（以及为什么它用 HAS/NOT_HAS）</h4>
 * 期 2 让策略可以<b>读标</b>之后，运营马上就会配 {@code ACTIVE_TAGS HAS FROZEN_RETURN_APPLY} 这类策略。
 * 若它不在闭集，这些策略对「没打这个标」的单会被算成<b>「差 1 个条件的最接近」</b>——
 * 正好是 30 条策略里最容易重新冒出来的那类噪音，而且它比渠道噪音更隐蔽（渠道不符一眼看得出，标不符看不出来）。
 * 标是这单的<b>来历</b>（被打过什么结论/特征），不随改价拆单变化，符合「身份 vs 量级」的分界。
 *
 * <p><b>标的语义边界（别混这两件事）</b>：
 * <ul>
 *   <li>{@code ACTIVE_TAGS HAS X} 未满足 ⇒ 这单<b>压根没有 X 类身份</b> ⇒ <b>不适用</b>；</li>
 *   <li>{@code ACTIVE_TAGS NOT_HAS X} 未满足 ⇒ 这单<b>确实有 X</b>，而策略只要"没有 X 的单" ⇒ 同样是<b>身份不符</b> ⇒ 不适用；</li>
 *   <li>但「<b>有 X 而数量/金额不满足</b>」是<b>真实不匹配</b>，不能算不适用 —— 那部分条件落在量级字段上
 *       （{@code ITEM_QTY_TOTAL GTE 10} 之类），量级字段本来就不在闭集里，所以这条边界是类型上自洽的，
 *       不靠人记得住。反过来说：<b>任何阈值型判断（LT/LTE/GT/GTE）永远不参与不适用判定</b>，
 *       哪怕写在 {@code ACTIVE_TAGS} 上（那种写法本身就是配错，见 {@link ClassificationField#mismatchOps()}）。</li>
 * </ul>
 *
 * <p><b>⚠️ 值缺失不判不适用</b>：{@link ScanCondition#valueMissing()} 为真时，这条条件真实含义是
 * 「<b>这条判不了</b>」（没有客户标签、没有收货城市、上下文没装配标），与「<b>渠道不是淘宝</b>」是两件事。
 * 把缺失当不符，会让「没填城市」的单在界面上被写成「渠道店铺都不匹配」，指错了排查方向。
 *
 * <h3>② 三层数据结构：第一层的类型里一开始就没有 conditions</h3>
 * <ul>
 *   <li>{@link FirstLayer}：结论 + 一句人话（≤{@value #ONELINER_MAX} 字）+ 最接近的那一条
 *       （只有策略名 + 每条差在哪的<b>人话</b>）+ 两句聚合旁注（不适用多少条 / 同样接近多少条）；</li>
 *   <li>{@link SecondLayer}：不适用总数 + 按分类字段的分布（{@code byField} 只可能有那 5 个字段）；</li>
 *   <li>{@link ThirdLayer}：完整矩阵（现状表原样保留，默认折叠）。</li>
 * </ul>
 * 第一层<b>没有</b> {@code conditions} / {@code missConditions} 这类完整矩阵字段，是刻意的类型设计：
 * 「过两天有人想往里塞完整矩阵」时，编译器会先拦住他 —— 比写十条注释都管用。
 * 想放细节只有一条路：进 {@link ThirdLayer}。
 *
 * <h3>本类的边界</h3>
 * 纯函数：无 Spring、无 Mapper、无时钟、无随机、无 IO。相同输入必然相同输出。
 * {@link PolicyScan} 是<b>引擎结果的投影</b>（由调用方从 {@code AuditVerdict} 组装），
 * 本类不读库、不改库、不碰任何实体。
 */
public final class AuditExplainLayers {

    /**
     * {@code oneLiner} 的硬上限（字符数，按 UTF-16 code unit 计）。
     * 超过就截断并补省略号 —— 宁可少说半句，也不许第一屏被一条长策略名撑破。
     */
    public static final int ONELINER_MAX = 80;

    /** 五类结论（第一层的 {@code conclusion}）。用词即界面用词，别再另起一套。 */
    public static final String CONCLUSION_HIT = "命中";
    public static final String CONCLUSION_CLOSEST = "最接近";
    public static final String CONCLUSION_EXCLUDED = "被排除";
    public static final String CONCLUSION_ALL_NOT_APPLICABLE = "全部不适用";
    public static final String CONCLUSION_NO_POLICY = "无可用策略";

    /** 全部不适用时的那一句（思想⑥的原话，别改） */
    static final String ONE_LINER_ALL_NOT_APPLICABLE = "本的渠道/店铺与全部策略都不匹配";
    /** 一条策略都没有时的那一句（T6：空输入也要有可读结论，不许空白、不许抛） */
    static final String ONE_LINER_NO_POLICY = "没有可用的审核策略，请先配置策略";

    /**
     * 分类字段闭集：{@code code → 字段定义}，<b>顺序即声明顺序</b>（也是并列时的 tie-break 顺序）。
     *
     * <p>中文名从 {@link FieldRegistry#require} 取，不在这里抄一遍：字段改名时界面文案跟着变，
     * 不会出现「引擎叫收货城市、界面叫收货地址」两套说法。字段码也用注册表的常量，
     * 万一日后注册表里删了某个字段，本类在<b>类初始化时</b>就炸（{@code require} 抛异常），
     * 而不是悄悄少统计一类。
     */
    private static final Map<String, ClassificationField> CLASSIFICATION_FIELDS = buildClassificationFields();

    /** 闭集的稳定顺序（不依赖 Map 实现，显式一份） */
    private static final List<String> CLASSIFICATION_ORDER = List.copyOf(CLASSIFICATION_FIELDS.keySet());

    /**
     * 一个分类字段：码 + 中文名 + <b>哪些操作符才允许判「不适用」</b>。
     *
     * <p>操作符白名单是<b>按字段</b>给的，不是一刀切 EQ/NE：值型身份字段（渠道/店铺/客户标签/收货城市）
     * 用 {@code EQ/NE} 表达「是不是这类单」；多值身份字段（{@code ACTIVE_TAGS}）用 {@code HAS/NOT_HAS}
     * 表达「这单有没有这类来历」—— {@link com.qiqi.mall.audit.engine.ValueType#LIST} 字段本来也不允许
     * 用 EQ/NE（见 {@code ConditionEvaluator} 的类型相容校验），两者是同一套口径。
     *
     * <p><b>白名单里永远没有 LT/LTE/GT/GTE</b>：阈值是量级判断，"不够大"不等于"不是这一类"。
     *
     * @param code        字段码
     * @param label       中文名（界面直接用）
     * @param mismatchOps 允许判「不适用」的操作符（只读集合）
     */
    record ClassificationField(String code, String label, java.util.Set<OpCode> mismatchOps) {

        ClassificationField {
            mismatchOps = mismatchOps == null ? java.util.Set.of() : java.util.Set.copyOf(mismatchOps);
        }

        /** 这条条件用的操作符是否在本字段的白名单里 */
        boolean allowsOp(OpCode op) {
            return op != null && mismatchOps.contains(op);
        }

        /** 白名单的操作符码（给测试/文档看，顺序按 {@link OpCode} 声明顺序，稳定） */
        java.util.List<String> mismatchOpCodes() {
            return java.util.Arrays.stream(OpCode.values())
                    .filter(mismatchOps::contains)
                    .map(Enum::name)
                    .toList();
        }
    }

    /**
     * 「最接近」的判据：五级字典序，越靠前越接近。
     *
     * <ol>
     *   <li>{@code outcome}：HIT(0) &lt; EXCLUDED(1) &lt; MISS(2) —— 命中的策略永远比「差一点」更接近；</li>
     *   <li>{@code missCount} 升序：差得越少越接近；</li>
     *   <li>{@code satisfiedCount} 降序：满足得越多越接近；</li>
     *   <li>{@code priority} 升序：同接近度时优先看运营配的优先级；</li>
     *   <li>{@code policyId} 升序：最终 tie-break，保证确定性；</li>
     *   <li>{@code canonicalKey} 字典序：<b>只在 1~5 级全相等时才会走到</b>（正常数据里 policyId 唯一，
     *       走不到）。它存在是为了让「重复行 / 手工构造的 null ID」这种退化输入也不依赖输入顺序 ——
     *       否则 T4（打乱 20 次一致）在退化输入上会偶发失败，而那种失败最容易被当成"测试不稳定"糊过去。</li>
     * </ol>
     *
     * <p>⚠️ 前 5 级是契约（思想⑦），第 6 级是兜底；改前 5 级的<b>顺序</b>等于改界面口径，必须同步改单测期望。
     */
    private static final Comparator<PolicyScan> CLOSEST_FIRST =
            Comparator.comparingInt((PolicyScan s) -> s.outcome().rank())
                    .thenComparingInt(PolicyScan::missCount)
                    .thenComparing(Comparator.comparingInt(PolicyScan::satisfiedCount).reversed())
                    .thenComparingInt(PolicyScan::priority)
                    .thenComparing(PolicyScan::policyId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(PolicyScan::canonicalKey);

    /**
     * 第三层（完整矩阵）的展示顺序：优先级升序 → 策略 ID 升序 → 内容字典序。
     *
     * <p>矩阵是「现状表原样保留」——<b>内容</b>一条不删（包括不适用的那些），但<b>顺序</b>必须由内容决定，
     * 不能是调用方传进来的顺序，否则同一轮判定刷新两次会看到行位置变化（人眼会以为策略变了）。
     */
    private static final Comparator<PolicyScan> MATRIX_ORDER =
            Comparator.comparingInt(PolicyScan::priority)
                    .thenComparing(PolicyScan::policyId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt((PolicyScan s) -> s.outcome().rank())
                    .thenComparing(PolicyScan::canonicalKey);

    private AuditExplainLayers() {
    }

    // ------------------------------------------------------------------ 唯一入口

    /**
     * 组装三层结构（<b>纯函数</b>：不抛业务异常、不改入参、不依赖任何全局状态）。
     *
     * @param scans 本轮逐策略的扫描结果（可空、可含 null 元素；顺序无关紧要）
     * @return 三层结构，永不为 null
     */
    public static ExplainLayers build(List<PolicyScan> scans) {
        List<PolicyScan> rows = scans == null ? List.of()
                : scans.stream().filter(Objects::nonNull).toList();

        // 第一刀：不适用的一律摘出去（它们不参与"最接近"的竞争，这正是第一屏不变长的原因）
        List<PolicyScan> notApplicable = new ArrayList<>();
        List<PolicyScan> candidates = new ArrayList<>();
        for (PolicyScan s : rows) {
            if (isNotApplicable(s)) {
                notApplicable.add(s);
            } else {
                candidates.add(s);
            }
        }
        candidates.sort(CLOSEST_FIRST);

        SecondLayer second = secondLayer(notApplicable);
        FirstLayer first = firstLayer(rows, candidates, second);

        List<MatrixRow> matrix = rows.stream().sorted(MATRIX_ORDER).map(MatrixRow::of).toList();
        return new ExplainLayers(first, second, new ThirdLayer(matrix));
    }

    // ------------------------------------------------------------------ ① 分类字段闭集 / 不适用判定

    /**
     * 这条扫描结果是不是「<b>根本不适用于这单</b>」。
     *
     * <p>判据是硬规则（思想⑦），只有一条：
     * <b>存在一条"分类字段 + EQ/NE + 有值"的未满足条件</b>。
     * 满足这一条就整条策略判不适用，不再看它差了几个量级条件 ——
     * 「渠道都不是淘宝」的策略，毛利差多少都不该出现在「最接近」里。
     *
     * <p>命中（{@link ScanOutcome#HIT}）永远算适用：它已经全条件满足，谈不上"不符"。
     * 被排除（{@link ScanOutcome#EXCLUDED}）要看它的排除条件：分类字段 EQ/NE 挡掉的（例如
     * 「店铺 ≠ 直营店 → 排除」）同样是不适用；量级/包含类条件挡掉的（「客服备注 含 改」）则是
     * 一条真实生效的闸门，必须留在候选里（它是这单被挡住的原因）。
     *
     * @param scan 一条扫描结果（null 视为适用：由 {@link #build} 过滤，不会传进来）
     */
    public static boolean isNotApplicable(PolicyScan scan) {
        if (scan == null) return false;
        if (scan.outcome() == ScanOutcome.HIT) return false;
        return classificationMismatchField(scan) != null;
    }

    /**
     * 该字段是否是「分类字段」（闭集 5 个，见类注释①）。
     * 调用方（例如组装 {@link PolicyScan} 的执行层 / 前端提示「这个字段不参与不适用判定」）不必自己维护一份。
     */
    public static boolean isClassificationField(String fieldCode) {
        return fieldCode != null && CLASSIFICATION_FIELDS.containsKey(normalizeCode(fieldCode));
    }

    /** 分类字段闭集（按声明顺序，只读）。 */
    public static List<String> classificationFields() {
        return CLASSIFICATION_ORDER;
    }

    /**
     * 该字段「能判不适用」的操作符码（按 {@link OpCode} 声明顺序）。
     * 不是分类字段返回空列表 —— 空 = 这个字段的条件只会被算成「真实不匹配」，不会被聚合成「不适用」。
     */
    public static List<String> mismatchOperators(String fieldCode) {
        ClassificationField field = CLASSIFICATION_FIELDS.get(normalizeCode(fieldCode));
        return field == null ? List.of() : field.mismatchOpCodes();
    }

    /** 分类字段的中文名；不是分类字段返回原码（不抛：这是渲染路径，不是校验路径）。 */
    public static String fieldLabel(String fieldCode) {
        String code = normalizeCode(fieldCode);
        ClassificationField field = CLASSIFICATION_FIELDS.get(code);
        return field == null ? code : field.label();
    }

    /**
     * 这条未满足条件是否构成「分类字段不匹配」。
     *
     * <p>三道闸门缺一不可：<b>分类字段</b> + <b>该字段白名单里的操作符</b> + <b>有值</b>。
     * 第二道按字段给（值型身份 EQ/NE、标 HAS/NOT_HAS，见 {@link ClassificationField#mismatchOps()}）；
     * 第三道是「缺失 ≠ 不符」：没填收货城市的单，城市条件判不了，但它并没有说"不是这个城市"。
     */
    static boolean isClassificationMismatch(ScanCondition c) {
        if (c == null) return false;
        ClassificationField field = CLASSIFICATION_FIELDS.get(normalizeCode(c.fieldCode()));
        if (field == null) return false;
        if (!field.allowsOp(OpCode.of(c.opCode()))) return false;
        return !c.valueMissing();
    }

    /**
     * 一条不适用策略「算到哪个字段头上」：闭集声明顺序里<b>第一个</b>撞上的分类字段。
     *
     * <p>为什么不是"全部字段各记一次"：那样 {@code byField} 合计会大于「不适用条数」，
     * 界面上「另有 5 条不适用（渠道 3、店铺 3）」自己就对不上账，人第一眼就会怀疑数字。
     * 所以一条策略只记一次，且记在<b>声明顺序最靠前</b>的字段上（不依赖输入顺序，保证可复现）。
     */
    static String classificationMismatchField(PolicyScan scan) {
        if (scan == null) return null;
        List<ScanCondition> conditions = scan.missingConditions();
        for (String code : CLASSIFICATION_ORDER) {
            for (ScanCondition c : conditions) {
                if (c != null && code.equals(normalizeCode(c.fieldCode())) && isClassificationMismatch(c)) {
                    return code;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ② 第一层

    private static FirstLayer firstLayer(List<PolicyScan> rows, List<PolicyScan> candidates, SecondLayer second) {
        if (rows.isEmpty()) {
            // T6：一条策略都没有也要有可读结论（不许 null、不许空白、不许抛）
            return new FirstLayer(CONCLUSION_NO_POLICY, clamp(ONE_LINER_NO_POLICY), null, "", "");
        }
        if (candidates.isEmpty()) {
            // T5：全部不适用 —— 照样给一句人话，而不是留空让界面自己想办法
            return new FirstLayer(CONCLUSION_ALL_NOT_APPLICABLE, clamp(ONE_LINER_ALL_NOT_APPLICABLE),
                    null, notApplicableNote(second, true), "");
        }

        PolicyScan best = candidates.get(0);
        int tied = tiedCount(candidates, best);
        String tieNote = tied == 0 ? "" : "另有 " + tied + " 条同样接近";
        String conclusion;
        String oneLiner;
        switch (best.outcome()) {
            case HIT -> {
                conclusion = CONCLUSION_HIT;
                oneLiner = "命中「" + best.policyName() + "」：" + firstNonBlank(best.hitCondition(), "全部条件满足");
            }
            case EXCLUDED -> {
                conclusion = CONCLUSION_EXCLUDED;
                oneLiner = "被排除策略「" + best.policyName() + "」挡掉：" + firstNonBlank(best.excludedBy(), "排除条件命中");
            }
            case MISS -> {
                conclusion = CONCLUSION_CLOSEST;
                oneLiner = "最接近「" + best.policyName() + "」，还差 " + best.missCount()
                        + " 个条件：" + firstMissingText(best);
            }
            default -> throw new IllegalStateException("未覆盖的 outcome：" + best.outcome());
        }
        return new FirstLayer(conclusion, clamp(oneLiner),
                new Closest(best.policyName(), missingTexts(best)),
                notApplicableNote(second, false), tieNote);
    }

    /**
     * 与最接近那条<b>并列</b>的条数（差同样多条件 + 满足同样多 + 同一 outcome）。
     *
     * <p>第一层仍只显示一条（界面上永远只有一个"最接近"），但必须用一句话承认"还有几条差不多"，
     * 否则运营会以为系统只找到一条、或者以为排序是随机的。
     */
    static int tiedCount(List<PolicyScan> sortedCandidates, PolicyScan best) {
        int n = 0;
        for (PolicyScan s : sortedCandidates) {
            if (s == best) continue;
            if (s == null) continue;
            if (s.outcome() == best.outcome()
                    && s.missCount() == best.missCount()
                    && s.satisfiedCount() == best.satisfiedCount()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 第一屏的「不适用」聚合句。
     *
     * <ul>
     *   <li>只有一类原因：{@code 另有 5 条不适用（渠道不符）}（思想⑥的原话）；</li>
     *   <li>多类原因：{@code 另有 5 条不适用（渠道不符 3、店铺不符 2）} —— 只给总数太模糊，
     *       但不逐条列（那是第三层的事）；</li>
     *   <li>全部都不适用：{@code 共 N 条不适用（…）}，此时没有"另有"。</li>
     * </ul>
     */
    private static String notApplicableNote(SecondLayer second, boolean all) {
        if (second.notApplicableTotal() == 0 || second.byField().isEmpty()) return "";
        StringBuilder sb = new StringBuilder(all ? "共 " : "另有 ");
        sb.append(second.notApplicableTotal()).append(" 条不适用（");
        List<FieldCount> counts = second.byField();
        if (counts.size() == 1) {
            sb.append(counts.get(0).field()).append("不符");
        } else {
            for (int i = 0; i < counts.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(counts.get(i).field()).append("不符 ").append(counts.get(i).count());
            }
        }
        return sb.append("）").toString();
    }

    // ------------------------------------------------------------------ ③ 第二层

    private static SecondLayer secondLayer(List<PolicyScan> notApplicable) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (String code : CLASSIFICATION_ORDER) counter.put(code, 0);
        for (PolicyScan s : notApplicable) {
            String code = classificationMismatchField(s);
            if (code != null) counter.merge(code, 1, Integer::sum);
        }
        // 数量降序（把最大的那撮原因顶到最前面说），同数量按闭集声明顺序 —— 两条都只看内容，不看输入顺序
        List<FieldCount> byField = CLASSIFICATION_ORDER.stream()
                .filter(code -> counter.get(code) > 0)
                .sorted(Comparator.comparingInt((String code) -> counter.get(code)).reversed()
                        .thenComparingInt(CLASSIFICATION_ORDER::indexOf))
                .map(code -> new FieldCount(fieldLabel(code), counter.get(code)))
                .toList();
        return new SecondLayer(notApplicable.size(), byField);
    }

    // ------------------------------------------------------------------ 文案小工具

    /**
     * 硬上限截断：超过 {@link #ONELINER_MAX} 字就截断并补 {@code …}（占 1 字）。
     * 这是「渠道 3→30 第一屏不变长」的最后一道保险 —— 即便某条策略名超长，第一屏也不会被撑破。
     */
    static String clamp(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() <= ONELINER_MAX) return t;
        return t.substring(0, ONELINER_MAX - 1) + "…";
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 「还差 N 个条件：<第一条差的条件的人话>」里的那一条（按 seq 再按文案，不看输入顺序）。 */
    private static String firstMissingText(PolicyScan scan) {
        List<ScanCondition> missing = orderedMissing(scan);
        if (missing.isEmpty()) return "条件明细见完整矩阵";
        return firstNonBlank(missing.get(0).text(), "条件明细见完整矩阵");
    }

    private static List<String> missingTexts(PolicyScan scan) {
        return orderedMissing(scan).stream().map(c -> firstNonBlank(c.text(), c.fieldCode())).toList();
    }

    /**
     * 未满足条件的稳定顺序：{@code seq} 升 → {@code text} 字典序。
     *
     * <p>为什么不能直接用入参顺序：同一个 {@link PolicyScan} 的 {@code missingConditions}
     * 是调用方从引擎留痕里拷来的，拼装路径一变（并发流 / 分组重新聚合）顺序就可能变；
     * 而「还差 N 个条件：<b>第一条</b>」的"第一条"必须是内容决定的，否则同一单刷新两次文案会跳。
     */
    private static List<ScanCondition> orderedMissing(PolicyScan scan) {
        return scan.missingConditions().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ScanCondition::seq)
                        .thenComparing(c -> c.text() == null ? "" : c.text()))
                .toList();
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 建闭集：<b>顺序 = 声明顺序 = 「一条策略同时撞多个分类字段时算到谁头上」的优先级</b>。
     *
     * <p>新字段一律<b>追加在末尾</b>（{@code ACTIVE_TAGS} 就是 21:40 追加的第 5 个）：已上线的界面与
     * 断言依赖这个顺序（{@code byField} 同数量时的先后、多字段不符时的归属），插在中间等于静默改口径。
     */
    private static Map<String, ClassificationField> buildClassificationFields() {
        Map<String, ClassificationField> map = new LinkedHashMap<>();
        // 值型身份：EQ / NE（「是不是这类单」）
        registerClassification(map, FieldRegistry.CHANNEL, java.util.Set.of(OpCode.EQ, OpCode.NE));
        registerClassification(map, FieldRegistry.SHOP_NAME, java.util.Set.of(OpCode.EQ, OpCode.NE));
        registerClassification(map, FieldRegistry.CUSTOMER_TAG, java.util.Set.of(OpCode.EQ, OpCode.NE));
        registerClassification(map, FieldRegistry.RECEIVER_CITY, java.util.Set.of(OpCode.EQ, OpCode.NE));
        // 多值身份（期 2 读标）：HAS / NOT_HAS（「这单有没有这类来历」）。
        // 理由与语义边界见类注释①的「ACTIVE_TAGS 为什么必须进闭集」。
        registerClassification(map, FieldRegistry.ACTIVE_TAGS, java.util.Set.of(OpCode.HAS, OpCode.NOT_HAS));
        return java.util.Collections.unmodifiableMap(map);
    }

    private static void registerClassification(Map<String, ClassificationField> map, String code,
                                               java.util.Set<OpCode> mismatchOps) {
        ClassificationField field = new ClassificationField(code, FieldRegistry.require(code).label(), mismatchOps);
        if (map.put(code, field) != null) throw new IllegalStateException("分类字段重复：" + code);
    }

    // ================================================================== 输入：扫描结果投影

    /**
     * 一条策略在本轮的扫描结果（<b>纯数据</b>，无库依赖、无实体引用）。
     *
     * <p>字段取值口径：
     * <ul>
     *   <li>{@code outcome}：命中 / 被排除策略挡掉 / 不匹配 —— 三者互斥，由引擎给出；</li>
     *   <li>{@code satisfiedCount} / {@code missCount}：满足的 MATCH 条件数 / 未满足的 MATCH 条件数；</li>
     *   <li>{@code missingConditions}：未满足条件的人话（{@link ScanCondition}）——
     *       第一层只用它取「还差的条件」，第三层原样列出；</li>
     *   <li>{@code excludedBy}：被排除时的一句话原因（例如「客服备注 含 改」）；</li>
     *   <li>{@code hitCondition}：命中时第一条满足条件的人话（第一层「命中「X」：<这里是它>」）。</li>
     * </ul>
     *
     * @param policyId          策略 ID（唯一；退化输入可为 null）
     * @param policyName        策略名（界面直接显示）
     * @param priority          同类型内优先级（升序）
     * @param outcome           本轮结论类别
     * @param satisfiedCount    满足的条件数
     * @param missCount         差的条件数
     * @param missingConditions 未满足条件（含 fieldCode + 人话 + 值是否缺失）
     * @param excludedBy        被排除的原因（人话，可空）
     * @param hitCondition      命中的第一条满足条件（人话，可空）
     */
    public record PolicyScan(
            Long policyId,
            String policyName,
            int priority,
            ScanOutcome outcome,
            int satisfiedCount,
            int missCount,
            List<ScanCondition> missingConditions,
            String excludedBy,
            String hitCondition) {

        public PolicyScan {
            policyName = policyName == null ? "" : policyName;
            outcome = outcome == null ? ScanOutcome.MISS : outcome;
            priority = Math.max(0, priority);
            satisfiedCount = Math.max(0, satisfiedCount);
            missCount = Math.max(0, missCount);
            missingConditions = missingConditions == null ? List.of()
                    : List.copyOf(missingConditions.stream().filter(Objects::nonNull).toList());
            excludedBy = excludedBy == null ? "" : excludedBy;
            hitCondition = hitCondition == null ? "" : hitCondition;
        }

        /**
         * 兜底排序键（第 6 级 tie-break）：只由内容决定，与输入顺序无关。
         * 正常数据走不到它（policyId 唯一），它是为了让退化输入也不破坏「打乱 N 次一致」。
         */
        String canonicalKey() {
            StringBuilder sb = new StringBuilder();
            sb.append(policyName).append('\u0001').append(outcome.name())
                    .append('\u0001').append(priority).append('\u0001')
                    .append(satisfiedCount).append('\u0001').append(missCount).append('\u0001')
                    .append(excludedBy).append('\u0001').append(hitCondition);
            for (ScanCondition c : orderedMissing(this)) {
                sb.append('\u0001').append(c.fieldCode()).append('\u0001').append(c.opCode())
                        .append('\u0001').append(c.seq()).append('\u0001').append(c.text());
            }
            return sb.toString();
        }
    }

    /**
     * 一条未满足条件的投影：{@code fieldCode} + 人话 + <b>值是否缺失</b>。
     *
     * <p>{@code valueMissing} 存在的唯一理由：把「判不了」和「不符」分开（见类注释①的警告）。
     * 它不参与排序，只参与 {@link #isClassificationMismatch} 的三道闸门。
     *
     * @param seq          求值顺序（决定「第一条差的条件」是哪一条）
     * @param fieldCode    字段码（{@code FieldRegistry} 的码）
     * @param opCode       操作符码（{@code OpCode} 的码）
     * @param valueMissing 实际值是否缺失（缺失 = 这条判不了，≠ 不符）
     * @param text         人话（例如「渠道 ≠ 淘宝」「毛利 −12.00 ≤ 0」）
     */
    public record ScanCondition(int seq, String fieldCode, String opCode, boolean valueMissing, String text) {

        public ScanCondition {
            fieldCode = normalizeCode(fieldCode);
            opCode = normalizeCode(opCode);
            text = text == null ? "" : text.strip();
        }

        /** 有值的不满足条件（最常见：比过了，就是不满足） */
        public static ScanCondition of(int seq, String fieldCode, String opCode, String text) {
            return new ScanCondition(seq, fieldCode, opCode, false, text);
        }

        /** <b>值缺失</b>的未满足条件：判不了，不是不符（不会被算成「不适用」） */
        public static ScanCondition ofMissingValue(int seq, String fieldCode, String opCode, String text) {
            return new ScanCondition(seq, fieldCode, opCode, true, text);
        }
    }

    /** 策略本轮结论类别；{@code rank} 越小越「接近」（第 1 级排序键）。 */
    public enum ScanOutcome {
        /** 命中：全部 MATCH 条件满足（被 BLOCK/EXCEPTION/AUTO 采用与否是上层的事） */
        HIT(0),
        /** 被排除策略挡掉：真实生效的一条闸门，比「单纯不匹配」更接近 */
        EXCLUDED(1),
        /** 不匹配：至少一条 MATCH 条件没满足 */
        MISS(2);

        private final int rank;

        ScanOutcome(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    // ================================================================== 输出：三层结构

    /**
     * 三层结构的整体（第一屏 = {@link FirstLayer}，点开 = {@link SecondLayer}，折叠 = {@link ThirdLayer}）。
     *
     * @param first  第一层：结论 + 一句人话 + 最接近那一条（<b>不含任何条件数组的完整矩阵</b>）
     * @param second 第二层：不适用聚合（总数 + 按分类字段分布）
     * @param third  第三层：完整矩阵（现状表原样保留，默认折叠）
     */
    public record ExplainLayers(FirstLayer first, SecondLayer second, ThirdLayer third) {

        /** 落 JSON / 断言用的稳定形状（键序固定：{@code layer1 → layer2 → layer3}）。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("layer1", first == null ? null : first.toMap());
            m.put("layer2", second == null ? null : second.toMap());
            m.put("layer3", third == null ? null : third.toMap());
            return m;
        }
    }

    /**
     * <b>第一层</b>：一个人一眼能读完的那一块。
     *
     * <p>⚠️ 这个记录<b>故意没有</b> {@code conditions} / {@code missingConditions} 之外的矩阵字段：
     * 想往第一屏塞「完整矩阵」的改动在这里编译不过，必须改类型 —— 而改类型就得先读完这段注释。
     * 第一层能承载的细节上限，就是「最接近那条的每条差在哪」的那几句人话。
     *
     * @param conclusion         五类结论之一（{@link #CONCLUSION_HIT} 等）
     * @param oneLiner           一句人话，硬上限 {@value #ONELINER_MAX} 字（超了截断 + 省略号）
     * @param closest            最接近的那一条；空策略 / 全部不适用时为 null
     * @param notApplicableNote  不适用聚合句（「另有 N 条不适用（渠道不符）」），无可说时为 ""
     * @param tieNote            并列提示（「另有 N 条同样接近」），无并列时为 ""
     */
    public record FirstLayer(
            String conclusion,
            String oneLiner,
            Closest closest,
            String notApplicableNote,
            String tieNote) {

        public FirstLayer {
            conclusion = conclusion == null ? "" : conclusion;
            oneLiner = clamp(oneLiner);
            notApplicableNote = notApplicableNote == null ? "" : notApplicableNote;
            tieNote = tieNote == null ? "" : tieNote;
        }

        /** 第一层可落 JSON 的形状（键序固定，便于逐字节比较） */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conclusion", conclusion);
            m.put("oneLiner", oneLiner);
            m.put("closest", closest == null ? null : closest.toMap());
            m.put("notApplicableNote", notApplicableNote);
            m.put("tieNote", tieNote);
            return m;
        }
    }

    /**
     * 「最接近的那一条」——只有策略名 + 每条差在哪的<b>人话</b>。
     * 没有 {@code conditions}（带 expected/actual/satisfied 的完整留痕），那是第三层的东西。
     *
     * @param policyName        策略名
     * @param missingConditions 每条未满足条件的人话（已按 seq/文案定序；命中时为空）
     */
    public record Closest(String policyName, List<String> missingConditions) {

        public Closest {
            policyName = policyName == null ? "" : policyName;
            missingConditions = missingConditions == null ? List.of() : List.copyOf(missingConditions);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policyName", policyName);
            m.put("missingConditions", missingConditions);
            return m;
        }
    }

    /**
     * <b>第二层</b>：不适用的聚合（<b>byField 只可能有分类字段那 5 个</b>）。
     *
     * <p>不变量：{@code byField} 的 count 之和 == {@code notApplicableTotal}
     * （一条策略只记一个字段，见 {@link #classificationMismatchField}）——
     * 界面上的两个数字必须自己算得平，人对不上账的第一反应就是不信任整页。
     *
     * @param notApplicableTotal 不适用策略条数
     * @param byField            按分类字段的分布（数量降序，同数量按闭集声明顺序）
     */
    public record SecondLayer(int notApplicableTotal, List<FieldCount> byField) {

        public SecondLayer {
            notApplicableTotal = Math.max(0, notApplicableTotal);
            byField = byField == null ? List.of() : List.copyOf(byField);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("notApplicableTotal", notApplicableTotal);
            List<Map<String, Object>> fields = new ArrayList<>();
            for (FieldCount fc : byField) fields.add(fc.toMap());
            m.put("byField", fields);
            return m;
        }
    }

    /**
     * 一个分类字段的不适用条数（{@code {field:'渠道', count:5}}）。
     * {@code field} 是<b>中文名</b>（界面直接用），不是字段码 —— 第一屏不该出现 {@code CHANNEL} 这种码。
     */
    public record FieldCount(String field, int count) {

        public FieldCount {
            field = field == null ? "" : field;
            count = Math.max(0, count);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("count", count);
            return m;
        }
    }

    /**
     * <b>第三层</b>：完整矩阵（现状表原样保留，默认折叠）。
     *
     * <p>它是「一条不删」的那张表：不适用也列、差多少条件也列、每条条件的人话和字段码都在。
     * 第一屏被压缩掉的信息没有消失，只是搬到了需要主动点开的地方。
     *
     * @param policies 全部策略行（优先级/ID 定序，与输入顺序无关）
     */
    public record ThirdLayer(List<MatrixRow> policies) {

        public ThirdLayer {
            policies = policies == null ? List.of() : List.copyOf(policies);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (MatrixRow r : policies) rows.add(r.toMap());
            m.put("policies", rows);
            return m;
        }
    }

    /**
     * 完整矩阵的一行（一个策略的现状）。
     *
     * @param policyId              策略 ID
     * @param policyName            策略名
     * @param priority              优先级
     * @param outcome               结论类别（HIT / EXCLUDED / MISS）
     * @param satisfiedCount        满足条件数
     * @param missCount             差的条件数
     * @param notApplicable         是否被判定为「不适用」（第二层统计的就是这些行）
     * @param notApplicableField    不适用的分类字段中文名（不适用时有值，否则 null）
     * @param excludedBy            被排除原因（人话，可空）
     * @param hitCondition          命中时第一条满足条件（人话，可空）
     * @param missingConditions     未满足条件明细（原样保留：字段码 + 操作符 + 人话 + 值是否缺失）
     */
    public record MatrixRow(
            Long policyId,
            String policyName,
            int priority,
            String outcome,
            int satisfiedCount,
            int missCount,
            boolean notApplicable,
            String notApplicableField,
            String excludedBy,
            String hitCondition,
            List<ScanCondition> missingConditions) {

        public MatrixRow {
            policyName = policyName == null ? "" : policyName;
            outcome = outcome == null ? "" : outcome;
            excludedBy = excludedBy == null ? "" : excludedBy;
            hitCondition = hitCondition == null ? "" : hitCondition;
            missingConditions = missingConditions == null ? List.of() : List.copyOf(missingConditions);
        }

        static MatrixRow of(PolicyScan scan) {
            String field = classificationMismatchField(scan);
            return new MatrixRow(scan.policyId(), scan.policyName(), scan.priority(),
                    scan.outcome().name(), scan.satisfiedCount(), scan.missCount(),
                    isNotApplicable(scan), field == null ? null : fieldLabel(field),
                    scan.excludedBy(), scan.hitCondition(), orderedMissing(scan));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policyId", policyId);
            m.put("policyName", policyName);
            m.put("priority", priority);
            m.put("outcome", outcome);
            m.put("satisfiedCount", satisfiedCount);
            m.put("missCount", missCount);
            m.put("notApplicable", notApplicable);
            m.put("notApplicableField", notApplicableField);
            m.put("excludedBy", excludedBy);
            m.put("hitCondition", hitCondition);
            List<Map<String, Object>> conditions = new ArrayList<>();
            for (ScanCondition c : missingConditions) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("seq", c.seq());
                cm.put("fieldCode", c.fieldCode());
                cm.put("opCode", c.opCode());
                cm.put("valueMissing", c.valueMissing());
                cm.put("text", c.text());
                conditions.add(cm);
            }
            m.put("missingConditions", conditions);
            return m;
        }
    }
}
