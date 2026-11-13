package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 判定上下文：<b>引擎需要的一切事实都在这里</b>，引擎因此可以是一个纯函数
 * （输入 = 上下文 + 策略 + 设置，输出 = 判定，不碰库、不读时钟、不发 SQL）。
 *
 * <p>上下文的装配（查订单明细、SUM(sku_bucket.stock)、查退货申请…）属于执行层
 * （{@code AuditContextLoader}），在事务里读一次就够 —— 同一轮里同一张单的
 * 「可用库存」不会被读到两个值，判定与证据说的是同一件事。
 *
 * <p>{@code now} 由装配方传进来（不是引擎自己 {@code LocalDateTime.now()}）：
 * 「支付距今小时数」这类派生值必须可复现，否则同一个上下文跑两次能得出两个结论，单测也就没法断言。
 *
 * @param orderId               订单 ID
 * @param orderSn               订单号（证据用）
 * @param status                订单当前状态（判定链第 1 步看它）
 * @param channel               销售渠道
 * @param shopName              店铺名
 * @param receivableAmount      应收金额（order.total_amount）
 * @param grossProfit           毛利（order.gross_profit；null = 没算过）
 * @param customerTag           客户标签（member.customer_tag）
 * @param csRemark              客服备注（含「改」= 全局排除）
 * @param platformRemark        平台备注原文（证据用）
 * @param platformRemarkUpdatedAt 平台备注最后更新时间（null = 从未更新）
 * @param hasReturnApply        该单是否有待处理退货/退款申请（异常策略用）
 * @param addressMulti          是否多地址订单
 * @param stockAvailable        SUM(sku_bucket.stock)（**不是** sku.stock 缓存）
 * @param skuStockCache         写进证据的 sku.stock 缓存值（故意可能与 stockAvailable 不一致，见 QT-1006）
 * @param itemKindCount         商品种类数
 * @param itemQtyTotal          商品总件数（= 库存预检的需求量）
 * @param costMissing           是否缺成本价（任一明细 cost_price 为 NULL/0）
 * @param payTime               支付时间（null = 未支付）
 * @param receiverCity          收货城市
 * @param receiverAddress       收货地址原文（证据用）
 * @param frozen                是否已被冻结（有 active tag=FROZEN 类）
 * @param frozenTag             冻结标码（证据用，未冻结为 null）
 * @param now                   本轮判定的「现在」
 */
public record AuditContext(
        Long orderId,
        String orderSn,
        Integer status,
        String channel,
        String shopName,
        BigDecimal receivableAmount,
        BigDecimal grossProfit,
        String customerTag,
        String csRemark,
        String platformRemark,
        LocalDateTime platformRemarkUpdatedAt,
        boolean hasReturnApply,
        boolean addressMulti,
        int stockAvailable,
        Integer skuStockCache,
        int itemKindCount,
        int itemQtyTotal,
        boolean costMissing,
        LocalDateTime payTime,
        String receiverCity,
        String receiverAddress,
        boolean frozen,
        String frozenTag,
        LocalDateTime now) {

    /** 待审核状态（判定链第 1 步）。常量放这里，避免各处再写裸 7 */
    public static final int STATUS_PENDING_AUDIT = 7;
    /** 审核通过后的状态（待发货）。执行层 {@code OrderStatusService.change(order, 8, ...)} 用它 */
    public static final int STATUS_PENDING_SHIP = 8;

    /** 便捷构造：单测里只关心判定所需的事实，其它给默认值 */
    public static AuditContext of(Long orderId, Integer status, String channel, BigDecimal amount,
                                  int stock, int qty, LocalDateTime now) {
        return new AuditContext(orderId, "SN-" + orderId, status, channel, null, amount, null, null, null,
                null, null, false, false, stock, null, 1, qty, false, null, null, null, false, null, now);
    }
}
