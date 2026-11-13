package com.qiqi.mall.audit.dto;

import java.math.BigDecimal;

/**
 * 一轮 run 里某张单的结论摘要（run 接口的返回值用）。
 *
 * <p>为什么把 {@code shopName / channel / itemKindCount / grossProfit} 也带上：
 * 队列页要对齐显示「店铺 / 渠道 / 商品种类数 / 毛利」，run 返回的行与队列行给同一组事实，
 * 页面才不用为了同一行再发一次查询；这几个值在执行时**本来就在手上**（{@code order} + {@code AuditContext}），
 * 不额外查库。{@code itemKindCount} 与引擎口径一致 = 去重后的 {@code product_sku_id} 个数
 * （{@code AuditContextLoader} 的 {@code qtyBySku.size()}，不是明细行数）。
 *
 * @param orderId       订单 ID
 * @param orderSn       订单号
 * @param roundNo       该单第几轮
 * @param result        结论
 * @param matchedPolicyName 命中策略名
 * @param wouldBeAutoPolicyName 只读回填的策略名
 * @param downgradeReason 预检降级原因
 * @param reason        人话原因
 * @param shopName      店铺名（{@code order.shop_name}）
 * @param channel       渠道（{@code order.channel}）
 * @param itemKindCount 商品种类数（去重 SKU 数）
 * @param grossProfit   毛利（{@code order.gross_profit}；null = 没算过）
 */
public record DecisionRowDto(
        Long orderId,
        String orderSn,
        Integer roundNo,
        String result,
        String matchedPolicyName,
        String wouldBeAutoPolicyName,
        String downgradeReason,
        String reason,
        String shopName,
        String channel,
        Integer itemKindCount,
        BigDecimal grossProfit) {
}
