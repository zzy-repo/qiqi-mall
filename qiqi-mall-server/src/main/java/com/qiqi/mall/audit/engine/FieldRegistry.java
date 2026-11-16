package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 字段注册表：15 个可配字段的<b>唯一来源</b>（code / 中文名 / 值类型 / 取值函数）。
 * 第 15 个是期 2 的 {@link #ACTIVE_TAGS}（「当前标记」，多值），前 14 个是期 1 的标量事实。
 *
 * <h3>为什么要有它</h3>
 * 策略条件是**行模型**（{@code audit_policy_condition} 一行一条），{@code field_code} 是一列字符串。
 * DDL 上刻意<b>不</b>给 field_code 加 CHECK 闭集 —— 闭集如果钉在 DDL 里，加一个字段就要跑一次
 * 结构性迁移，而且 CHECK 违规会回滚整个业务事务（配错一条条件不该让写库挂掉）。
 * 于是闭集住在这里：校验在**保存策略**时做（给出「可用字段清单」的人话报错），
 * 取值在**判定时**做（{@link #require(String)} 拿不到就抛，绝不当成「条件不满足」静默放行 ——
 * 那样一个拼错的字段名会让策略永久失效却没人知道）。
 *
 * <h3>两个时间字段为什么是「距今小时数」（NUM）</h3>
 * 配置的人想表达的是「付款超过 24 小时」「平台备注 2 小时内被改过」，不是某个绝对时刻。
 * 值域若给绝对时间（TEXT 时间戳），判断就得跨类型比较，还会把时区问题带进判定。
 * 统一换算成 NUM 小时后，条件就是普通的 {@code PAY_TIME GTE 24}，可读、可测、可复现
 * （{@code now} 由上下文给定，引擎不读时钟）。
 *
 * <h3>缺失值口径</h3>
 * 字段取不到值（从未更新过平台备注 / 未支付 / 没有客户标签）时取值函数返回 {@code null}，
 * 于是 {@code EXISTS / NOT_EXISTS} 才有意义；此时任何带值操作符一律判为「不满足」并给出
 * 「实际 无值」的文案 —— 绝不把 null 当 0 参与比较（null 当 0 会让「毛利缺失」看起来像「毛利 0」，
 * 正好放过最该拦的那类单，所以缺失一律判「不满足」，交给 COST_MISSING 转人工）。
 *
 * <h3>{@link #ACTIVE_TAGS}（读标）的三条硬口径 —— 改这个字段之前先读完</h3>
 * <ol>
 *   <li><b>只许引用 {@code category='特征'} 的标，结论标在保存策略时直接拒掉</b>
 *       （守门在 {@code AuditPolicyService}，不是引擎）。理由：标是在<b>判定之后</b>才落库的，
 *       「策略 A 打标 → 策略 B 读标」永远只能等下一轮 —— 这是<b>轮间传送带</b>，不是链内数据流。
 *       引用结论标（BLOCKED / FROZEN / NO_POLICY_MATCH…）= 拿上一轮的照片做这一轮的决定，
 *       正是「不在流程开头一次算全」的忌讳；而且会让策略之间出现隐性依赖，谁先跑谁后跑变成结果的一部分。</li>
 *   <li><b>不许用 {@code CONTAINS} 匹配逗号串</b>：那是子串语义，{@code FROZEN} 会命中
 *       {@code FROZEN_RETURN_APPLY}。所以这个字段是 {@link ValueType#LIST} + {@code HAS/NOT_HAS}
 *       （集合包含元素），求值是元素相等，不是字符串包含。</li>
 *   <li><b>空集合不算"有值"</b>：集合为空时 {@code HAS} 判 false、{@code NOT_HAS} 判 true；
 *       但<b>字段缺失</b>（装配方没传 {@code AuditContext.activeTags}，即 null）与"空集合"
 *       必须区分开 —— 前者是<b>不可评估</b>（引擎抛异常），不是 false。
 *       把"没装配"当成 false，一条配好的读标策略会永远不命中且毫无痕迹。</li>
 * </ol>
 */
public final class FieldRegistry {

    /** 一个字段的定义 */
    public record FieldDef(String code, String label, ValueType type, Function<AuditContext, Object> getter) {
    }

    public static final String CHANNEL = "CHANNEL";
    public static final String SHOP_NAME = "SHOP_NAME";
    public static final String RECEIVABLE_AMOUNT = "RECEIVABLE_AMOUNT";
    public static final String ITEM_KIND_COUNT = "ITEM_KIND_COUNT";
    public static final String ITEM_QTY_TOTAL = "ITEM_QTY_TOTAL";
    public static final String GROSS_PROFIT = "GROSS_PROFIT";
    public static final String CUSTOMER_TAG = "CUSTOMER_TAG";
    public static final String CS_REMARK = "CS_REMARK";
    public static final String PLATFORM_REMARK_UPDATED = "PLATFORM_REMARK_UPDATED";
    public static final String HAS_RETURN_APPLY = "HAS_RETURN_APPLY";
    public static final String ADDRESS_MULTI = "ADDRESS_MULTI";
    public static final String STOCK_AVAILABLE = "STOCK_AVAILABLE";
    public static final String PAY_TIME = "PAY_TIME";
    public static final String RECEIVER_CITY = "RECEIVER_CITY";
    /**
     * 该单当前 active 的标码集合（期 2 步骤 1「读标」）。值类型 {@link ValueType#LIST}，
     * 只能用 {@link OpCode#HAS} / {@link OpCode#NOT_HAS}。
     */
    public static final String ACTIVE_TAGS = "ACTIVE_TAGS";

    private static final Map<String, FieldDef> FIELDS = build();

    private static Map<String, FieldDef> build() {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        register(map, new FieldDef(CHANNEL, "渠道", ValueType.TEXT, AuditContext::channel));
        register(map, new FieldDef(SHOP_NAME, "店铺", ValueType.TEXT, AuditContext::shopName));
        register(map, new FieldDef(RECEIVABLE_AMOUNT, "应收金额", ValueType.NUM, AuditContext::receivableAmount));
        register(map, new FieldDef(ITEM_KIND_COUNT, "商品种类数", ValueType.NUM, AuditContext::itemKindCount));
        register(map, new FieldDef(ITEM_QTY_TOTAL, "商品总件数", ValueType.NUM, AuditContext::itemQtyTotal));
        register(map, new FieldDef(GROSS_PROFIT, "毛利", ValueType.NUM, AuditContext::grossProfit));
        register(map, new FieldDef(CUSTOMER_TAG, "客户标签", ValueType.TEXT, AuditContext::customerTag));
        register(map, new FieldDef(CS_REMARK, "客服备注", ValueType.TEXT, AuditContext::csRemark));
        register(map, new FieldDef(PLATFORM_REMARK_UPDATED, "平台备注更新距今(小时)", ValueType.NUM,
                ctx -> hoursSince(ctx.platformRemarkUpdatedAt(), ctx.now())));
        register(map, new FieldDef(HAS_RETURN_APPLY, "有退款申请", ValueType.BOOL, AuditContext::hasReturnApply));
        register(map, new FieldDef(ADDRESS_MULTI, "多地址", ValueType.BOOL, AuditContext::addressMulti));
        register(map, new FieldDef(STOCK_AVAILABLE, "可用库存(SUM桶)", ValueType.NUM, AuditContext::stockAvailable));
        register(map, new FieldDef(PAY_TIME, "支付距今(小时)", ValueType.NUM,
                ctx -> hoursSince(ctx.payTime(), ctx.now())));
        register(map, new FieldDef(RECEIVER_CITY, "收货城市", ValueType.TEXT, AuditContext::receiverCity));
        // ------------------------------------------------------------------ 期 2 步骤 1：读标
        // 标从「引擎私有开关」升级成业务接口层之后，策略必须能**引用**标：
        //   第一个检测点命中 → 打特征标；第二个检测点读标 → 决定动作。
        // 这条 register 就是"读标"的全部引擎侧入口；打标（期 1.6）不在这里。
        register(map, new FieldDef(ACTIVE_TAGS, "当前标记", ValueType.LIST, AuditContext::activeTags));
        return Map.copyOf(map);
    }

    private static void register(Map<String, FieldDef> map, FieldDef def) {
        FieldDef prev = map.put(def.code(), def);
        if (prev != null) throw new IllegalStateException("字段码重复：" + def.code());
    }

    private FieldRegistry() {
    }

    /** 全部字段（按注册顺序，前端下拉直接照抄这个顺序） */
    public static java.util.List<FieldDef> all() {
        return java.util.List.copyOf(FIELDS.values());
    }

    /** 取字段定义；不认识返回 null（保存策略时用：返回 null 就报「可用字段」清单） */
    public static FieldDef find(String code) {
        return code == null ? null : FIELDS.get(code.trim().toUpperCase());
    }

    /** 取字段定义；不认识直接抛（判定时用：静默当「条件不满足」等于让策略悄悄失效） */
    public static FieldDef require(String code) {
        FieldDef def = find(code);
        if (def == null) {
            throw new IllegalArgumentException("未知字段码「" + code + "」，可用字段：" + FIELDS.keySet());
        }
        return def;
    }

    /** 取字段当前值（返回 null = 这个单没有这个事实） */
    public static Object value(AuditContext ctx, String code) {
        return require(code).getter().apply(ctx);
    }

    private static BigDecimal hoursSince(LocalDateTime then, LocalDateTime now) {
        if (then == null || now == null) return null;
        long minutes = Duration.between(then, now).toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
