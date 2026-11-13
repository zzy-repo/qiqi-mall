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
}
