package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiqi.mall.oms.entity.PaymentRecord;

public interface PaymentRecordService extends IService<PaymentRecord> {

    /**
     * 记一条流水（P2 第二步：带上加固字段 —— 归属、触发单据、操作者、幂等键）。
     *
     * <p>为什么这些字段必须由调用方给：只有调用点知道「这笔钱是谁、为什么、因为哪张单据动的」。
     * 从 {@code order_sn} 反推是猜，猜错的台账比没有更坏。
     *
     * @param orderSn      订单号（对账键）
     * @param amount       金额
     * @param type         {@link PaymentRecord#TYPE_PAY} / {@link PaymentRecord#TYPE_REFUND}
     * @param channel      0模拟 1支付宝 2微信
     * @param orderId      关联订单 id（可空：order 被物理删后为 null）
     * @param refType      触发单据类型（退款 = {@code RETURN_APPLY}；支付 = null）
     * @param refId        触发单据 id（退款 = 退货申请 id）
     * @param originFlowId 退款指向的原支付流水 id（支付 = null）
     */
    void record(String orderSn, java.math.BigDecimal amount, int type, int channel,
                Long orderId, String refType, Long refId, Long originFlowId);

    /** 该订单号下最近一笔支付流水 id（退款溯源用；查不到返回 null） */
    Long findOriginFlowId(String orderSn);

    /**
     * 记一条**渠道回调**流水：{@code trade_no} 由渠道给（不是本地生成）。
     *
     * <p>与 {@link #record} 的唯一差别就是这一点，但恰恰是这一点让
     * {@code uk_channel_trade(channel, trade_no)} 变成可验证的事实：
     * 「第三方把同一笔通知又发了一次」时，第二次带回来的是**同一个** {@code trade_no}，
     * 于是 INSERT 必然撞那道唯一键 —— 而本地生成流水号的模拟支付永远造不出这个场景。
     *
     * <p>三种结果必须分开，不能合并成一个「没写成」：
     * <ol>
     *   <li>{@code true} —— 第一次通知，记账成功（新增 1 行）；</li>
     *   <li>{@code false} —— 该 {@code (channel, trade_no)} 已经入过账 = <b>重复通知，忽略</b>
     *       （语义是「钱已经记过了」，不是失败）；</li>
     *   <li>抛 {@link IllegalStateException} —— 同一个幂等键（＝同一张订单的同一笔钱）已入账，
     *       但渠道流水号是**新的**：说明渠道给这张单发来了第二笔真实扣款，必须人工对账，
     *       绝不能像重放那样静默吞掉（那会真丢一笔钱）。</li>
     * </ol>
     *
     * @return true = 新入账 1 行；false = 重复通知（已忽略，未新增行）
     */
    boolean recordChannelNotice(String orderSn, java.math.BigDecimal amount, int type, int channel,
                                String tradeNo, Long orderId, String refType, Long refId, Long originFlowId);
}
