package com.qiqi.mall.shared.security;

/**
 * 需要确认码（confirmCode）的高危写操作的**操作标识**。
 *
 * <p>为什么单独一个类：这些字符串会写进 KV（{@code audit:code:{code}} 的 value 第一段），
 * 校验时要和「本次请求的操作」严格比对（用途不匹配必须拒），所以必须只有一处定义。
 *
 * <p>每新增一个 L3 写接口，就在这里加一个常量，并按
 * {@link ConfirmCodeService#hashIntent(String, Long, Object...)} 的约定设计「指纹组成」：
 * 凡是「执行结果会被别人改动影响的量」都必须进 extras，否则用户确认的是 A、执行成 B。
 */
public final class ConfirmOps {

    // ---------------- 订单域 ----------------
    /** 审核通过（唯一有真预览接口 split-preview 的一个）；指纹 = 订单 id + 拆单方案指纹 */
    public static final String AUDIT_PASS = "audit-pass";
    /** 审核不通过；指纹 = 订单 id + 回补明细条数 + 回补总件数 */
    public static final String AUDIT_REJECT = "audit-reject";
    /** 订单发货（已付款→已发货）；指纹 = 订单 id + 明细条数 + 当前状态 */
    public static final String SHIP = "ship";
    /** 删除订单；指纹 = 订单 id + 明细条数 + 发货单条数（级联会一起删） */
    public static final String ORDER_DELETE = "del-order";

    // ---------------- 履约域 ----------------
    /** 下发 WMS；指纹 = 包裹 id + 订单 id + 仓库 id + 明细条数 */
    public static final String DISPATCH = "dispatch";
    /** 编辑包裹（改仓/改量）；指纹 = 包裹 id + 目标仓库 id + 改后明细串 */
    public static final String DELIVERY_EDIT = "edit-devl";
    /** 拆分包裹；指纹 = 包裹 id + 新包仓库 id + 拆出明细串 + 源包剩余条数 */
    public static final String DELIVERY_SPLIT = "split";
    /** 合并包裹；指纹 = 包裹 id + 目标包裹 id + 源包明细条数 */
    public static final String DELIVERY_MERGE = "merge";
    /** 删除包裹；指纹 = 包裹 id + 并入目标 id + 明细条数 */
    public static final String DELIVERY_DELETE = "del-devl";
    /** 取消包裹（回补库存）；指纹 = 包裹 id + 订单 id + 回补明细条数 + 回补总件数 */
    public static final String DELIVERY_CANCEL = "cancel";
    /** WMS 回传；指纹 = 包裹 id + 事件名 + 当前状态 */
    public static final String WMS_CALLBACK = "wms-cb";

    // ---------------- 退货 ----------------
    /** 退货审核；指纹 = 申请 id + 结论(1/2) + 退款金额 + 回补总件数 */
    public static final String RETURN_AUDIT = "ret-audit";

    // ---------------- 商品 ----------------
    /** 删除商品；指纹 = 商品 id + 订单明细引用数 + 购物车引用数 + 下辖 SKU 数 */
    public static final String PRODUCT_DELETE = "del-prod";
    /** 删除规格；指纹 = SKU id + 订单明细引用数 + 购物车引用数 */
    public static final String SKU_DELETE = "del-sku";

    private ConfirmOps() {
    }
}
