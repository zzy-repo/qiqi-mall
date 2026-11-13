package com.qiqi.mall.oms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.mapper.PaymentRecordMapper;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.shared.ledger.LedgerProperties;
import com.qiqi.mall.shared.security.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付流水写入（{@code payment_record}，P2 第二步：加固为「可溯源」）。
 *
 * <h3>加固后的四个问题各有答案</h3>
 * <ol>
 *   <li><b>这笔钱属于哪张订单</b> → {@code order_id}（+ 既有的 {@code order_sn}）；</li>
 *   <li><b>谁让它动的</b> → {@code operator_type/id/name}（{@code ActorResolver}）；</li>
 *   <li><b>因为哪张单据动的</b> → {@code ref_type/ref_id}（退款 = 退货申请），
 *       以及 {@code origin_flow_id}（退的是哪一笔原支付）→ 退款可一路溯到申请与原流水；</li>
 *   <li><b>同一笔钱会不会记两遍</b> → {@code idem_key}（支付 {@code {orderSn}:PAY}、
 *       退款 {@code {orderSn}:REFUND:{returnApplyId}}）+ {@code uk_idem}。</li>
 * </ol>
 *
 * <h3>幂等：只容忍「同一个幂等键」，别的唯一键冲突一律抛出</h3>
 * 这张表有**两个**唯一键：{@code uk_idem} 与 {@code uk_channel_trade(channel, trade_no)}。
 * 如果无脑用 {@code ON DUPLICATE KEY UPDATE id = id}（或 INSERT IGNORE），
 * 撞到 {@code uk_channel_trade} 时这笔流水会被**静默丢弃** —— 那是丢钱。
 * 所以这里：捕获 {@link DuplicateKeyException} 后先按 {@code idem_key} 回查，
 * 命中 ⇒ 真的是重放（已入账），返回；没命中 ⇒ 是别的唯一键撞了 ⇒ 原样抛出，让业务失败。
 *
 * <p>（{@code trade_no} 也顺手加了随机后缀：它原来只精确到毫秒，同一毫秒两笔流水会撞
 * {@code uk_channel_trade}，而那是合法业务，不该被当成重放。）
 */
@Service
public class PaymentRecordServiceImpl extends ServiceImpl<PaymentRecordMapper, PaymentRecord>
        implements PaymentRecordService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecordServiceImpl.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final LedgerProperties ledger;

    public PaymentRecordServiceImpl(LedgerProperties ledger) {
        this.ledger = ledger;
    }

    @Override
    public Long findOriginFlowId(String orderSn) {
        return orderSn == null ? null : getBaseMapper().selectOriginFlowId(orderSn);
    }

    @Override
    public void record(String orderSn, BigDecimal amount, int type, int channel,
                       Long orderId, String refType, Long refId, Long originFlowId) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderSn(orderSn);
        record.setAmount(amount == null ? BigDecimal.ZERO : amount);
        record.setType(type);
        record.setChannel(channel);
        // 随机后缀：trade_no 只精确到毫秒，同毫秒两笔合法流水会撞 uk_channel_trade(channel, trade_no)
        record.setTradeNo((type == PaymentRecord.TYPE_REFUND ? "MOCKREF" : "MOCKPAY")
                + LocalDateTime.now().format(TS) + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        record.setCreatedAt(LocalDateTime.now());
        record.setOrderId(orderId);
        record.setRefType(refType);
        record.setRefId(refId);
        record.setOriginFlowId(originFlowId);

        String idemKey = null;
        if (ledger.paymentTraceOn()) {
            ActorResolver.Actor actor = ActorResolver.resolve();
            record.setOperatorType(actor.type());
            record.setOperatorId(actor.id());
            record.setOperatorName(actor.name());
            idemKey = idemKeyOf(orderSn, type, refId);
            record.setIdemKey(idemKey);
        }

        try {
            save(record);
        } catch (DuplicateKeyException e) {
            // 只有「同一个幂等键」才算重放（钱已经记过账）。其余唯一键（uk_channel_trade）撞了是
            // 真问题 —— 抛出，宁可这次业务失败，也不能静默丢一条流水。
            if (idemKey != null && getBaseMapper().countByIdemKey(idemKey) > 0) {
                log.info("支付流水重放：幂等键 {} 已入账，本次跳过（订单 {} 金额 {} 类型 {}）",
                        idemKey, orderSn, amount, type);
                return;
            }
            throw e;
        }
    }

    /**
     * 幂等键：支付 {@code {orderSn}:PAY}；退款 {@code {orderSn}:REFUND:{returnApplyId}}。
     *
     * <p>为什么退款要带申请 id：同一张订单可以有多笔合法退款（第二次退货申请审核通过），
     * 只用 {@code {orderSn}:REFUND} 会把第二笔合法退款当成重放**吞掉**（那是真丢钱）；
     * 带上 refId 后「同一张申请被重放」才判重，「不同申请各退一笔」照常记账。
     */
    private static String idemKeyOf(String orderSn, int type, Long refId) {
        if (orderSn == null) return null;
        return type == PaymentRecord.TYPE_REFUND
                ? orderSn + ":REFUND:" + (refId == null ? 0 : refId)
                : orderSn + ":PAY";
    }
}
