package com.qiqi.mall.oms.service;

import com.qiqi.mall.oms.entity.PaymentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 渠道回调处理：**「第三方把同一笔支付通知又发了一次」这件事的唯一去处**。
 *
 * <h3>它为什么存在（而不是直接在 controller 里调 service）</h3>
 * 真实系统里，钱是被渠道推回来的，不是被本地算出来的：
 * <pre>
 *   渠道 POST 通知 {channel, trade_no, order_sn, amount, ...}
 *     → 本类 onNotice()：按 (channel, trade_no) 判断这是不是一笔**已经入过账**的通知
 *         ├─ 新通知 → 记账 1 行（payment_record）
 *         └─ 重复通知 → 一行都不加，返回 DUPLICATE（「钱已经记过了」，不是失败）
 * </pre>
 * 把这段逻辑收在这一个类里，是为了让「重放」有东西可复用：
 * {@code POST /admin/payments/{id}/replay-channel-callback} 就是把库里那条流水的原
 * {@code trade_no} 重新构造成一份 {@link ChannelNotice} 再走一遍 {@link #onNotice} ——
 * <b>走的是同一条代码路径，不是测试专用分支</b>。测试专门分支证明不了生产路径。
 *
 * <h3>本类只负责「记账」，不负责「推进订单状态」</h3>
 * 支付请求侧（{@code PortalOrderController#pay}）的状态推进有自己的业务闸
 * （{@code status != 0 → 「当前订单状态不可支付」} + 乐观锁 CAS）。如果回调这里也去推状态，
 * 那么「重复通知被挡住」就会被**状态闸**抢先解释掉，{@code uk_channel_trade} 反而永远走不到
 * （这正是 {@code PaymentIdemKeyTest} ② 记录的那个盲区）。所以这里刻意只做记账：
 * 让「同一笔钱不会被记两次」由 payment_record 自己的两道唯一键来回答。
 *
 * <h3>三种结果，一个都不许被吞</h3>
 * <ul>
 *   <li>{@link Outcome#RECORDED} —— 第一次通知，新增 1 行；</li>
 *   <li>{@link Outcome#DUPLICATE} —— 同一个 {@code (channel, trade_no)} 已入账 = 重复通知，忽略；</li>
 *   <li>抛异常 —— 缺流水号 / 同一张单来了**不同**流水号的第二笔扣款（疑似重复扣款，要人工对账）。</li>
 * </ul>
 * 「重复」与「第二笔真实扣款」必须分开：前者该安静，后者该尖叫。合并成一个「没写成」，
 * 等于把丢钱和防重记成同一件事。
 */
@Service
public class ChannelCallbackService {

    private static final Logger log = LoggerFactory.getLogger(ChannelCallbackService.class);

    private final PaymentRecordService paymentRecordService;

    public ChannelCallbackService(PaymentRecordService paymentRecordService) {
        this.paymentRecordService = paymentRecordService;
    }

    /** 一次渠道通知（字段与 payment_record 的加固列一一对应，重放时原样搬运） */
    public record ChannelNotice(int channel,
                                String tradeNo,
                                String orderSn,
                                BigDecimal amount,
                                int type,
                                Long orderId,
                                String refType,
                                Long refId,
                                Long originFlowId) {
    }

    /** 处理结果：只有一个「记了」和一个「重复，忽略」—— 失败走异常，不用枚举兜 */
    public enum Outcome {
        /** 首次通知：新增 1 行流水 */
        RECORDED,
        /** 重复通知：该 (channel, trade_no) 已入账，未新增任何行 */
        DUPLICATE
    }

    /**
     * 处理一次渠道通知（幂等：同一个 {@code (channel, trade_no)} 只会入账一次）。
     *
     * @return {@link Outcome#RECORDED} 或 {@link Outcome#DUPLICATE}
     * @throws IllegalArgumentException 通知缺字段（渠道流水号 / 订单号为空）
     * @throws IllegalStateException    同一张订单已入账、但渠道流水号是新的（疑似重复扣款）
     */
    public Outcome onNotice(ChannelNotice notice) {
        if (notice == null) throw new IllegalArgumentException("渠道通知不能为空");
        if (notice.orderSn() == null || notice.orderSn().isBlank()) {
            throw new IllegalArgumentException("渠道通知缺少订单号（order_sn），无法入账");
        }
        if (notice.type() != PaymentRecord.TYPE_PAY && notice.type() != PaymentRecord.TYPE_REFUND) {
            throw new IllegalArgumentException("渠道通知的类型不合法：" + notice.type() + "（1支付 2退款）");
        }
        boolean recorded = paymentRecordService.recordChannelNotice(
                notice.orderSn(), notice.amount(), notice.type(), notice.channel(), notice.tradeNo(),
                notice.orderId(), notice.refType(), notice.refId(), notice.originFlowId());
        log.info("渠道通知处理完成：channel={} trade_no={} order_sn={} 结果={}",
                notice.channel(), notice.tradeNo(), notice.orderSn(),
                recorded ? Outcome.RECORDED : Outcome.DUPLICATE);
        return recorded ? Outcome.RECORDED : Outcome.DUPLICATE;
    }
}
