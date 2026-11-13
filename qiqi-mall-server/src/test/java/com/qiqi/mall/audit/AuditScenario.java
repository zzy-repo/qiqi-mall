package com.qiqi.mall.audit;

import com.qiqi.mall.audit.engine.ActionSpec;
import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditSetting;
import com.qiqi.mall.audit.engine.ConditionScope;
import com.qiqi.mall.audit.engine.ConditionView;
import com.qiqi.mall.audit.engine.PolicyType;
import com.qiqi.mall.audit.engine.PolicyView;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 六笔单的判定场景 —— <b>严格对齐用户原表</b>（引擎单测与真库集成测试共用同一份事实）。
 *
 * <h3>策略集（3 AUTO + 2 BLOCK + 2 EXCEPTION + 1 条全局排除）</h3>
 * <pre>
 * AUTO      直营-小单    prio 100  CUSTOMER_TAG EQ 直营门店 & RECEIVABLE_AMOUNT LT 300
 * AUTO      淘宝-标准单  prio 200  CHANNEL EQ 淘宝 & RECEIVABLE_AMOUNT LT 500 & ITEM_KIND_COUNT LTE 5
 * AUTO      抖音-标准单  prio 300  CHANNEL EQ 抖音 & RECEIVABLE_AMOUNT LT 500 & ITEM_KIND_COUNT LTE 5
 * BLOCK     毛利不为正    prio 10   GROSS_PROFIT LTE 0
 * BLOCK     双地址       prio 20   ADDRESS_MULTI EQ 是
 * EXCEPTION 退款申请冻结  prio 10   HAS_RETURN_APPLY EQ 是            → tag FROZEN
 * EXCEPTION 平台备注冻结  prio 20   PLATFORM_REMARK_UPDATED LTE 24     → tag FROZEN
 * ALL_AUTO  全局排除      —        CS_REMARK CONTAINS 改
 * </pre>
 *
 * <h3>为什么「异常策略」是两条而不是一条</h3>
 * 设计里那条异常是「有退款申请 <b>或</b> 平台备注被改（近 24 小时）」。行模型的条件是
 * <b>AND</b> 语义（{@code audit_policy_condition} 没有 group/OR 列，DDL 也没给），
 * 一条策略里放两个条件会变成「两者同时成立」——那是另一个（更窄的）规则。
 * 所以「或」用**两条同参数的 EXCEPTION 策略**表达：任一命中就冻结，与一条 OR 策略完全等价，
 * 而且台账里能看出「这次是被哪一条冻的」。六笔单只走退款那条。
 *
 * <h3>六笔单（用户原表）</h3>
 * <pre>
 * QT-1001 淘宝 268 元 2 件 毛利 62          → AUTO_PASS（淘宝-标准单）
 * QT-1002 淘宝 268 元 2 件 客服备注「改地址」 → MANUAL（淘宝-标准单 满足但被全局排除）
 * QT-1003 抖音 1580 元 8 件                 → NO_MATCH（抖音-标准单：应收 1580 &lt; 500 不成立）
 * QT-1004 直营 199 元 毛利 −12              → BLOCKED（毛利不为正；would_be=直营-小单）
 * QT-1005 淘宝 320 元 已有退款申请           → FROZEN →（处理申请 + 解冻）→ 二轮 AUTO_PASS
 * QT-1006 淘宝 420 元 6 件 6 个样           → NO_MATCH（淘宝-标准单：种类数 6 ≤ 5 不成立）
 * </pre>
 */
final class AuditScenario {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 19, 0);

    static final String POLICY_AUTO_DIRECT = "直营-小单";
    static final String POLICY_AUTO_TAOBAO = "淘宝-标准单";
    static final String POLICY_AUTO_DOUYIN = "抖音-标准单";
    static final String POLICY_BLOCK_PROFIT = "毛利不为正";
    static final String POLICY_BLOCK_ADDRESS = "双地址";
    static final String POLICY_EXCEPTION_RETURN = "退款申请冻结";
    static final String POLICY_EXCEPTION_PLATFORM_REMARK = "平台备注冻结";

    private AuditScenario() {
    }

    /** 策略集（引擎视角） */
    static List<PolicyView> policies() {
        PolicyView autoDirect = new PolicyView(1L, PolicyType.AUTO, 100, POLICY_AUTO_DIRECT, 1, true, null,
                List.of(ConditionView.matchText("CUSTOMER_TAG", "EQ", "直营门店"),
                        ConditionView.match("RECEIVABLE_AMOUNT", "LT", new BigDecimal("300"))));
        PolicyView autoTaobao = new PolicyView(2L, PolicyType.AUTO, 200, POLICY_AUTO_TAOBAO, 1, true, null,
                List.of(ConditionView.matchText("CHANNEL", "EQ", "淘宝"),
                        ConditionView.match("RECEIVABLE_AMOUNT", "LT", new BigDecimal("500")),
                        ConditionView.match("ITEM_KIND_COUNT", "LTE", new BigDecimal("5"))));
        PolicyView autoDouyin = new PolicyView(3L, PolicyType.AUTO, 300, POLICY_AUTO_DOUYIN, 1, true, null,
                List.of(ConditionView.matchText("CHANNEL", "EQ", "抖音"),
                        ConditionView.match("RECEIVABLE_AMOUNT", "LT", new BigDecimal("500")),
                        ConditionView.match("ITEM_KIND_COUNT", "LTE", new BigDecimal("5"))));
        PolicyView blockProfit = new PolicyView(4L, PolicyType.BLOCK, 10, POLICY_BLOCK_PROFIT, 1, true, null,
                List.of(ConditionView.match("GROSS_PROFIT", "LTE", new BigDecimal("0"))));
        PolicyView blockAddress = new PolicyView(5L, PolicyType.BLOCK, 20, POLICY_BLOCK_ADDRESS, 1, true, null,
                List.of(ConditionView.matchBool("ADDRESS_MULTI", "EQ", true)));
        PolicyView exceptionReturn = new PolicyView(6L, PolicyType.EXCEPTION, 10, POLICY_EXCEPTION_RETURN, 1, true,
                ActionSpec.freeze("存在待处理退款申请"),
                List.of(ConditionView.matchBool("HAS_RETURN_APPLY", "EQ", true)));
        // 设计里「有退款申请 或 平台备注被改」的「或」= 第二条 EXCEPTION（见类注释）
        PolicyView exceptionPlatformRemark = new PolicyView(7L, PolicyType.EXCEPTION, 20, POLICY_EXCEPTION_PLATFORM_REMARK, 1,
                true, ActionSpec.freeze("平台备注 24 小时内被修改过"),
                List.of(ConditionView.match("PLATFORM_REMARK_UPDATED", "LTE", new BigDecimal("24"))));
        // 全局排除：装载位置在最后一条策略上，语义上不属于任何策略（DB 里 policy_id 为 NULL）
        PolicyView withGlobal = new PolicyView(7L, PolicyType.EXCEPTION, 20, POLICY_EXCEPTION_PLATFORM_REMARK, 1,
                true, ActionSpec.freeze("平台备注 24 小时内被修改过"),
                concat(exceptionPlatformRemark.conditions(),
                        List.of(ConditionView.exclude("CS_REMARK", "CONTAINS", "改", ConditionScope.ALL_AUTO))));
        return List.of(autoDirect, autoTaobao, autoDouyin, blockProfit, blockAddress, exceptionReturn, withGlobal);
    }

    private static List<ConditionView> concat(List<ConditionView> a, List<ConditionView> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    static AuditSetting strict() {
        return new AuditSetting(true, AuditSetting.StockGuard.STRICT);
    }

    static AuditSetting lenient() {
        return new AuditSetting(true, AuditSetting.StockGuard.LENIENT);
    }

    /** 引擎单测用：只跑判定，不关心库存（库存预检单独有用例） */
    static AuditEngine engine() {
        return new AuditEngine();
    }

    // ------------------------------------------------------------------ 六笔单

    /** QT-1001：淘宝 268 元 2 件 毛利 62 → AUTO_PASS（淘宝-标准单） */
    static AuditContext qt1001() {
        return builder(1001L, "QT-1001", 7, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(2).itemKinds(1)
                .stock(100, 100).costMissing(false)
                .build();
    }

    /** QT-1002：同 QT-1001，但客服备注含「改地址」→ 被全局排除 → MANUAL */
    static AuditContext qt1002() {
        return builder(1002L, "QT-1002", 7, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(2).itemKinds(1)
                .csRemark("客户改地址：朝阳区xx路2号").stock(100, 100).costMissing(false)
                .build();
    }

    /** QT-1003：抖音 1580 元 8 件 → 三条 AUTO 都不命中 → NO_MATCH（差额：1580 ≥ 500） */
    static AuditContext qt1003() {
        return builder(1003L, "QT-1003", 7, "抖音", "1580.00")
                .grossProfit("300.00").itemQty(8).itemKinds(2)
                .stock(100, 100).costMissing(false)
                .build();
    }

    /** QT-1004：直营门店 199 元，毛利 −12 → BLOCKED（毛利不为正），would_be=直营-小单 */
    static AuditContext qt1004() {
        return builder(1004L, "QT-1004", 7, "直营", "199.00")
                .customerTag("直营门店").grossProfit("-12.00").itemQty(1).itemKinds(1)
                .stock(100, 100).costMissing(false)
                .build();
    }

    /** QT-1005 第一轮：淘宝 320 元，已有待处理退款申请 → FROZEN */
    static AuditContext qt1005Round1() {
        return builder(1005L, "QT-1005", 7, "淘宝", "320.00")
                .grossProfit("80.00").itemQty(2).itemKinds(1)
                .hasReturnApply(true).stock(100, 100).costMissing(false)
                .build();
    }

    /**
     * QT-1005 第二轮：退款申请已处理（拒绝）+ 人工解冻 → AUTO_PASS（淘宝-标准单）。
     *
     * <p>两个事实缺一不可：解冻只解除「暂停」，不消除异常；申请还在就会再冻一次 ——
     * 所以运营的正确动作是「处理申请 → 解冻 → 重跑」。
     */
    static AuditContext qt1005Round2() {
        return builder(1005L, "QT-1005", 7, "淘宝", "320.00")
                .grossProfit("80.00").itemQty(2).itemKinds(1)
                .hasReturnApply(false).stock(100, 100).costMissing(false)
                .build();
    }

    /** QT-1006：淘宝 420 元 6 件 6 个样 → NO_MATCH（种类数 6 ≤ 5 不成立；金额 420 &lt; 500 是满足的） */
    static AuditContext qt1006() {
        return builder(1006L, "QT-1006", 7, "淘宝", "420.00")
                .grossProfit("100.00").itemQty(6).itemKinds(6)
                .stock(100, 100).costMissing(false)
                .build();
    }

    // ------------------------------------------------------------------ 补充场景（链上每一步各一条）

    /** 状态已不是 7 → SKIPPED（第 1 步） */
    static AuditContext qt1001Shipped() {
        return builder(1001L, "QT-1001", 8, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(2).itemKinds(1).stock(100, 100).build();
    }

    /** 已冻结 → SKIPPED（第 2 步） */
    static AuditContext qt1005Frozen() {
        return builder(1005L, "QT-1005", 7, "淘宝", "320.00")
                .grossProfit("80.00").itemQty(2).itemKinds(1).hasReturnApply(true).stock(100, 100)
                .frozen(true, "FROZEN").build();
    }

    /** 毛利没算过 + 成本价缺失 → COST_MISSING（风险③） */
    static AuditContext qtCostMissing() {
        return builder(1101L, "QT-1101", 7, "淘宝", "268.00")
                .grossProfit(null).itemQty(2).itemKinds(1).stock(100, 100).costMissing(true).build();
    }

    /** AUTO 满足（淘宝-标准单）但桶只有 5 &lt; 需求 8 → STRICT 降级 STOCK_SHORT（引擎预检保留） */
    static AuditContext qtStockShort() {
        return builder(1102L, "QT-1102", 7, "淘宝", "268.00")
                .grossProfit("62.00").itemQty(8).itemKinds(1).stock(5, 999).costMissing(false).build();
    }

    // ------------------------------------------------------------------ builder

    static Builder builder(Long id, String sn, Integer status, String channel, String amount) {
        return new Builder(id, sn, status, channel, new BigDecimal(amount));
    }

    static final class Builder {
        private final Long id;
        private final String sn;
        private final Integer status;
        private final String channel;
        private final BigDecimal amount;
        private BigDecimal grossProfit;
        private String customerTag;
        private String csRemark;
        private String platformRemark;
        private LocalDateTime platformRemarkUpdatedAt;
        private boolean hasReturnApply;
        private boolean addressMulti;
        private int stockAvailable;
        private Integer skuStockCache;
        private int itemKinds = 1;
        private int itemQty = 1;
        private boolean costMissing;
        private LocalDateTime payTime;
        private String city = "北京市";
        private boolean frozen;
        private String frozenTag;

        private Builder(Long id, String sn, Integer status, String channel, BigDecimal amount) {
            this.id = id;
            this.sn = sn;
            this.status = status;
            this.channel = channel;
            this.amount = amount;
        }

        Builder grossProfit(String v) {
            this.grossProfit = v == null ? null : new BigDecimal(v);
            return this;
        }

        Builder customerTag(String v) {
            this.customerTag = v;
            return this;
        }

        Builder csRemark(String v) {
            this.csRemark = v;
            return this;
        }

        Builder platformRemark(String v, LocalDateTime at) {
            this.platformRemark = v;
            this.platformRemarkUpdatedAt = at;
            return this;
        }

        Builder hasReturnApply(boolean v) {
            this.hasReturnApply = v;
            return this;
        }

        Builder addressMulti(boolean v) {
            this.addressMulti = v;
            return this;
        }

        Builder stock(int bucketSum, Integer skuCache) {
            this.stockAvailable = bucketSum;
            this.skuStockCache = skuCache;
            return this;
        }

        Builder itemKinds(int v) {
            this.itemKinds = v;
            return this;
        }

        Builder itemQty(int v) {
            this.itemQty = v;
            return this;
        }

        Builder costMissing(boolean v) {
            this.costMissing = v;
            return this;
        }

        Builder payTime(LocalDateTime v) {
            this.payTime = v;
            return this;
        }

        Builder city(String v) {
            this.city = v;
            return this;
        }

        Builder frozen(boolean v, String tag) {
            this.frozen = v;
            this.frozenTag = tag;
            return this;
        }

        AuditContext build() {
            return new AuditContext(id, sn, status, channel, "官方旗舰店", amount, grossProfit, customerTag,
                    csRemark, platformRemark, platformRemarkUpdatedAt, hasReturnApply, addressMulti,
                    stockAvailable, skuStockCache, itemKinds, itemQty, costMissing, payTime, city,
                    "北京市朝阳区xx路1号", frozen, frozenTag, NOW);
        }
    }
}
