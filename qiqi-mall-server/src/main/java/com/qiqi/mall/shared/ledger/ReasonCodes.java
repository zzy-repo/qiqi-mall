package com.qiqi.mall.shared.ledger;

/**
 * 台账原因码 / 动作码 / 目标类型的**唯一常量源**（不允许在业务代码里散字符串）。
 *
 * <h3>为什么必须集中</h3>
 * {@code *_status_ledger.reason} 是台账的检索维度：「这张单为什么从 7 变成 8」全靠它。
 * 散在 15 个调用点写字符串的后果是「AUDIT_PASS」「audit_pass」「AUDITPASS」三种写法并存，
 * 按 reason 统计时各算一类 —— 台账在，却回答不了「审核通过一共多少单」。
 *
 * <h3>长度纪律（与 DDL 逐字对齐）</h3>
 * <ul>
 *   <li>{@code reason} VARCHAR(32)</li>
 *   <li>{@code admin_audit_log.action} VARCHAR(48)</li>
 *   <li>{@code target_type / ref_type} VARCHAR(24)</li>
 * </ul>
 * 超长在 STRICT 模式下是**错误**（1406 Data too long），会让整个业务事务回滚 ——
 * 这不是「台账记不上就算」的事：宁可业务失败，也不能让台账少一条。
 * {@code ReasonCodesTest} 用单测把这些宽度钉死（改常量时超长立刻红）。
 *
 * <h3>与 P2 第一步的关系</h3>
 * 三个状态服务里的 {@code REASON_*} 常量在第一步就被调用点广泛引用。这里**不删它们**，
 * 而是让它们变成本类的别名（{@code public static final String REASON_PAY = ReasonCodes.ORDER_PAY;}）——
 * 调用点一个字不改，常量只有一个来源。
 */
public final class ReasonCodes {

    // ================================================================ 订单状态流转（reason）

    /** 出生：新建订单（买家下单 / 后台建单） */
    public static final String ORDER_CREATE = "ORDER_CREATE";
    /** 0 待付款 → 7 待审核（支付成功即送审） */
    public static final String ORDER_PAY = "ORDER_PAY";
    /** 7 待审核 → 8 待发货（审核通过） */
    public static final String AUDIT_PASS = "AUDIT_PASS";
    /** 7 待审核 → 4 已关闭（审核不通过） */
    public static final String AUDIT_REJECT = "AUDIT_REJECT";
    /** 8/1 → 2 已发货（直发） */
    public static final String ORDER_SHIP = "ORDER_SHIP";
    /** 2 已发货 → 3 已完成（买家确认收货，直发路径） */
    public static final String ORDER_CONFIRM = "ORDER_CONFIRM";
    /** 任一可退状态 → 5 退款中（登记退货申请） */
    public static final String RETURN_APPLY = "RETURN_APPLY";
    /** 5 退款中 → 6 已退款（退货审核通过） */
    public static final String RETURN_APPROVE = "RETURN_APPROVE";
    /** 5 退款中 → 1 已付款（退货审核拒绝，直发单） */
    public static final String RETURN_REJECT = "RETURN_REJECT";
    /** 父单状态由包裹事实聚合推导（2/3/7/8/9，含幂等重算） */
    public static final String DELIVERY_AGGREGATE = "DELIVERY_AGGREGATE";

    // ================================================================ 包裹状态流转（reason）

    /** 出生：拆单生成包裹（自动拆单 / 人工拆分） */
    public static final String DELIVERY_CREATE = "DELIVERY_CREATE";
    /** 0 待下发 → 1 已下发（下发给 WMS） */
    public static final String DELIVERY_DISPATCH = "DELIVERY_DISPATCH";
    /** 0 待下发 → 9 已取消 */
    public static final String DELIVERY_CANCEL = "DELIVERY_CANCEL";
    /** WMS 回传：拣货 */
    public static final String WMS_PICK = "WMS_PICK";
    /** WMS 回传：出库 */
    public static final String WMS_OUTBOUND = "WMS_OUTBOUND";
    /** WMS 回传：揽收（运输中） */
    public static final String WMS_TRANSIT = "WMS_TRANSIT";
    /** WMS 回传：签收 */
    public static final String WMS_SIGNED = "WMS_SIGNED";
    /** 买家确认收货把在途包裹推到已签收 */
    public static final String ORDER_CONFIRM_SIGN = "ORDER_CONFIRM_SIGN";

    // ================================================================ 售后状态流转（reason）

    /** 出生：提交退货申请（待处理） */
    public static final String RETURN_CREATE = "RETURN_CREATE";
    /** 出生：管理员直接写入历史结论（1 通过 / 2 拒绝）—— 不走流程，但仍是这一行的出生事件 */
    public static final String RETURN_IMPORT = "RETURN_IMPORT";

    // ================================================================ 后台审计动作（action）

    public static final String ACTION_SKU_PRICE_CHANGE = "SKU_PRICE_CHANGE";
    public static final String ACTION_SKU_STOCK_CHANGE = "SKU_STOCK_CHANGE";
    public static final String ACTION_ORDER_DELETE = "ORDER_DELETE";
    public static final String ACTION_ORDER_SPLIT = "ORDER_SPLIT";
    public static final String ACTION_ORDER_SHIP = "ORDER_SHIP";
    public static final String ACTION_DELIVERY_DISPATCH = "DELIVERY_DISPATCH";
    public static final String ACTION_DELIVERY_SPLIT = "DELIVERY_SPLIT";
    public static final String ACTION_DELIVERY_MERGE = "DELIVERY_MERGE";
    public static final String ACTION_DELIVERY_DELETE = "DELIVERY_DELETE";
    public static final String ACTION_DELIVERY_UPDATE = "DELIVERY_UPDATE";
    public static final String ACTION_RETURN_AUDIT = "RETURN_AUDIT";
    public static final String ACTION_RETURN_DELETE = "RETURN_DELETE";
    public static final String ACTION_WAREHOUSE_CREATE = "WAREHOUSE_CREATE";
    public static final String ACTION_WAREHOUSE_UPDATE = "WAREHOUSE_UPDATE";
    public static final String ACTION_WAREHOUSE_DELETE = "WAREHOUSE_DELETE";
    public static final String ACTION_APIKEY_CREATE = "APIKEY_CREATE";
    public static final String ACTION_APIKEY_UPDATE = "APIKEY_UPDATE";
    public static final String ACTION_APIKEY_DELETE = "APIKEY_DELETE";

    // ================================================================ 目标类型 / 触发单据类型

    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_PACKAGE = "PACKAGE";
    public static final String TARGET_RETURN = "RETURN";
    public static final String TARGET_SKU = "SKU";
    public static final String TARGET_WAREHOUSE = "WAREHOUSE";
    public static final String TARGET_APIKEY = "APIKEY";

    /** 状态台账的触发单据类型（ref_type）：发货单 */
    public static final String REF_DELIVERY = "DELIVERY";
    /** 状态台账的触发单据类型（ref_type）：退货申请 */
    public static final String REF_RETURN_APPLY = "RETURN_APPLY";
    /** 状态台账的触发单据类型（ref_type）：支付流水 */
    public static final String REF_PAYMENT = "PAYMENT";

    private ReasonCodes() {
    }
}
