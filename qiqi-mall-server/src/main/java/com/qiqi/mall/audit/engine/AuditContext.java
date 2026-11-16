package com.qiqi.mall.audit.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
 * @param activeTags            该单当前 active 的**全部**标码（期 2 步骤 1：策略的「读标」事实源）；
 *                              {@code null} = 没装配（不可评估，见下）
 * @param now                   本轮判定的「现在」
 *
 * <h3>{@code activeTags} 的三条口径（期 2 步骤 1，写进类型里而不是散在调用点）</h3>
 * <ol>
 *   <li><b>它装的是"这一轮判定开始时该单身上挂着的标"</b>，也就是上一轮落下来的标。
 *       标是在判定<b>之后</b>才落库的，所以「策略 A 打标 → 策略 B 读标」不可能发生在同一轮里，
 *       只能等下一轮 —— 这是<b>轮间传送带</b>，不是链内数据流。也正因如此，策略侧只许引用
 *       {@code category='特征'} 的标（结论标是上一轮判定的照片，拿它做决定就是"在流程开头一次算全"）。</li>
 *   <li><b>空集合 ≠ 没装配</b>：{@code List.of()} 表示"这个单一个标都没有"（可评估：HAS 判 false、
 *       NOT_HAS 判 true）；{@code null} 表示装配方<b>没把这个组件传进来</b>（代码缺陷）——
 *       引擎对它的处理是<b>抛异常</b>，不是静默判 false（静默判 false 会让一条配好的读标策略
 *       永远不命中，而且没有任何痕迹）。</li>
 *   <li><b>冻结判定不从它推导</b>：{@code frozen / frozenTag} 仍是独立组件，由装配方按
 *       「{@code tag_code} 以 {@code FROZEN} 开头」从同一批 active 标里挑出来 ——
 *       判定链第 2 步的冻结短路因此与 {@code activeTags} 的引入无关（改动前后是同一批行、同一个"第一条"）。</li>
 * </ol>
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
        List<String> activeTags,
        LocalDateTime now) {

    /** 待审核状态（判定链第 1 步）。常量放这里，避免各处再写裸 7 */
    public static final int STATUS_PENDING_AUDIT = 7;
    /** 审核通过后的状态（待发货）。执行层 {@code OrderStatusService.change(order, 8, ...)} 用它 */
    public static final int STATUS_PENDING_SHIP = 8;

    /** 出厂即只读：避免装配方后面又把手里的 List 改掉，让「上下文是快照」这条口径漏气 */
    public AuditContext {
        activeTags = activeTags == null ? null : List.copyOf(activeTags);
    }

    /** 该单挂着的标码集合；**没装配时回 {@code null}**（调用方必须区分 null 与空集合，见类注释） */
    public List<String> activeTags() {
        return activeTags;
    }

    /** 便捷构造：单测里只关心判定所需的事实，其它给默认值 */
    public static AuditContext of(Long orderId, Integer status, String channel, BigDecimal amount,
                                  int stock, int qty, LocalDateTime now) {
        return new AuditContext(orderId, "SN-" + orderId, status, channel, null, amount, null, null, null,
                null, null, false, false, stock, null, 1, qty, false, null, null, null, false, null,
                // 装配过的空集合（不是 null）：便捷构造出来的是"一个标都没有的单"，不是"没装标"
                List.of(), now);
    }
}
