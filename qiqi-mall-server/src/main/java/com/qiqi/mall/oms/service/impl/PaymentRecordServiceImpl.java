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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 命中 ⇒ 真的是重放（已入账），返回；没命中 ⇒ 是别的唯一键撞了 ⇒ 抛出可读的业务异常，让业务失败。
 *
 * <h3>回查必须跨两道「视野」，否则承诺在真并发下不成立</h3>
 * 一次回查不够，原因见 {@link #idemKeyAlreadyBooked}：
 * <ol>
 *   <li>本事务内的读（含「本事务自己刚插、还没提交」的那一行）—— 同事务里 record() 调两次走这条；</li>
 *   <li><b>REQUIRES_NEW 新事务</b>的读（另一条连接 = 新快照）—— 只有它看得见「别的写入方刚提交」的幂等行。
 *       缺了它就会把真重放判成「不是重放」：{@code pay()} 的事务在第一条 {@code getById} 时就把
 *       consistent snapshot 定下来了，对方之后提交的行，本事务的快照读**永远**看不见
 *       （{@code PaymentIdemKeyTest} 用例 ⑤ 就是这个场景的回归：本事务快照读=0、新事务=1）。</li>
 * </ol>
 * 这两道都在**不改全局隔离级别**的前提下成立：新事务 = 新快照，普通快照读不加锁，
 * 所以也不会和「撞键失败的那次 INSERT 留在本事务里的唯一索引共享锁」抢锁。
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

    /**
     * 「撞键之后回查」专用的新事务模板（{@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}）。
     *
     * <p>为什么不用 {@code @Transactional(REQUIRES_NEW)} 注解：{@code record()} 是同类内部调用，
     * 注解式事务靠代理生效，自调用不会开新事务 —— 那个注解在这里会**静默失效**，
     * 代码看起来「已经修了」，行为却没变。用 TransactionTemplate 是显式的，绕不过去。
     */
    private final TransactionTemplate newTransaction;

    public PaymentRecordServiceImpl(LedgerProperties ledger, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        record.setTradeNo(generateTradeNo(type));
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
            if (idemKey == null) {
                // 幂等键没写（qiqi.ledger.payment-trace-enabled=false）：uk_idem 上多个 NULL 互不冲突，
                // 所以撞的必然是别道的唯一键。行为与改动前逐字一致：原样抛出，不改异常类型。
                throw e;
            }
            if (idemKeyAlreadyBooked(idemKey, e)) {
                log.info("支付流水重放：幂等键 {} 已入账，本次跳过（订单 {} 金额 {} 类型 {}）",
                        idemKey, orderSn, amount, type);
                return;
            }
            // 撞的不是幂等键（这张表上另一道唯一键就是 uk_channel_trade(channel, trade_no)）：
            // 那是「同一笔钱可能被记了两遍」的疑似场景，绝不能像重放一样静默吞掉 ——
            // 包成可读的业务异常抛出，原始 DuplicateKeyException 挂在 cause 上（排障要看得到库说的话）。
            throw new IllegalStateException("支付流水未入账：订单 " + orderSn
                    + " 本次写入撞了唯一键，但不是幂等键（" + idemKey + " 在库里没有已提交的行），"
                    + "只可能是 uk_channel_trade(channel=" + channel + ", trade_no=" + record.getTradeNo() + ") 撞了"
                    + " —— 疑似同一笔钱重复入账，需人工对账", e);
        }
    }

    /**
     * 撞键之后：这个幂等键到底「已入账」了没有？—— 两道回查，缺一不可。
     *
     * <ol>
     *   <li><b>本事务内</b>（{@code SELECT COUNT(*) ... idem_key=?}，走本事务快照）：
     *       看得见「本事务自己刚插、还没提交」的那一行 —— 同一个事务里 {@code record()} 被调两次
     *       就是这一种（{@code PaymentIdemKeyTest} 用例 ①/③），漏了它会把同事务重放判成撞别键；</li>
     *   <li><b>REQUIRES_NEW 新事务</b>（{@link #countCommittedByIdemKey}，另一条连接 = 新快照）：
     *       看得见「别的写入方刚提交」的那一行。REPEATABLE READ 下本事务的快照在
     *       {@code pay()} 第一条 {@code getById} 时就定下来了，对方之后提交的幂等行对快照读**不可见**，
     *       所以只靠第 ① 道会把真重放判成「不是重放」，把它原样抛出去（用户看到失败，
     *       而设计承诺的是幂等跳过）。用例 ⑤ 就是这条的回归。</li>
     * </ol>
     *
     * <p>为什么不是 {@code SELECT ... FOR UPDATE} / 改隔离级别：撞键失败的那次 INSERT 已经在本事务里
     * 对该唯一索引记录留下了**共享锁**，再在同一事务里加锁读等于跟自己抢锁（还可能升级成死锁）；
     * 新事务里的普通快照读不加锁、不阻塞、也不用为一次判重去改全局隔离级别。
     *
     * @param original 原始 {@link DuplicateKeyException}：回查自己失败时**不能把它吞掉**，
     *                 挂到它身上再抛（见下），否则「DB 抖动」会被伪装成「撞了别的唯一键」
     */
    private boolean idemKeyAlreadyBooked(String idemKey, DuplicateKeyException original) {
        if (getBaseMapper().countByIdemKey(idemKey) > 0) {
            return true;
        }
        try {
            return countCommittedByIdemKey(idemKey) > 0;
        } catch (RuntimeException lookupFailure) {
            // 回查本身失败（连接池耗尽 / DB 抖动 / 查询超时）：既不能当「已入账」（那是拿一笔钱赌运气），
            // 也不能把回查的失败当成新的失败原因、把原始 DuplicateKeyException 吃掉 ——
            // 原样抛出原始异常，把回查失败挂成 suppressed，两个事实都留在堆栈里。
            original.addSuppressed(lookupFailure);
            throw original;
        }
    }

    /**
     * 在**新事务**里数这个幂等键的行数 —— 换一条连接，因此拿到的是**新快照**，
     * 看得见别的写入方在本事务快照之后提交的行。
     *
     * <p>{@code protected} 的原因：{@code PaymentIdemKeyTest} 要能覆写它来模拟「回查自己失败」
     * （用例 ⑧），验证原始 {@link DuplicateKeyException} 不会被吞掉。
     */
    protected int countCommittedByIdemKey(String idemKey) {
        Integer n = newTransaction.execute(status -> getBaseMapper().countByIdemKey(idemKey));
        return n == null ? 0 : n;
    }

    /**
     * 本地生成渠道流水号（模拟支付用；渠道回调走 {@link #recordChannelNotice}，流水号由渠道给）。
     *
     * <p>随机后缀是必需的：{@code trade_no} 原来只精确到毫秒，同毫秒两笔合法流水会撞
     * {@code uk_channel_trade}，而那是合法业务、不该被当成重放。
     *
     * <p>{@code protected} 的原因：生产路径永远随机、不可预测，所以「撞 uk_channel_trade」这个
     * 场景没法用真实调用造出来。{@code PaymentIdemKeyTest} 覆写它把流水号钉成已知值，
     * 才能验「撞别道唯一键时**仍然抛**、不被当成重放吞掉」（用例 ⑦）。
     */
    protected String generateTradeNo(int type) {
        return (type == PaymentRecord.TYPE_REFUND ? "MOCKREF" : "MOCKPAY")
                + LocalDateTime.now().format(TS) + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
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

    @Override
    public boolean recordChannelNotice(String orderSn, BigDecimal amount, int type, int channel,
                                       String tradeNo, Long orderId, String refType, Long refId,
                                       Long originFlowId) {
        if (tradeNo == null || tradeNo.isBlank()) {
            // 渠道回调没有流水号 = 无法判断「是不是同一笔」→ 直接拒绝，不记一条「无身份证」的钱
            throw new IllegalArgumentException("渠道回调缺少渠道流水号（trade_no），无法入账");
        }
        PaymentRecord record = new PaymentRecord();
        record.setOrderSn(orderSn);
        record.setAmount(amount == null ? BigDecimal.ZERO : amount);
        record.setType(type);
        record.setChannel(channel);
        // ★ 与 record() 的唯一差别：流水号由**渠道**给，本地不改写、不加随机后缀。
        //   改写了就永远撞不到 uk_channel_trade，「重复通知」这个场景也就永远造不出来。
        record.setTradeNo(tradeNo);
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
            return true;
        } catch (DuplicateKeyException e) {
            // 撞了唯一键 → 先问「是不是同一笔渠道流水」：是 → 第三方重复通知，钱已经记过，忽略。
            if (getBaseMapper().countByChannelTrade(channel, tradeNo) > 0) {
                log.info("渠道重复通知：channel={} trade_no={} 已入账（订单 {} 金额 {} 类型 {}），本次忽略",
                        channel, tradeNo, orderSn, amount, type);
                return false;
            }
            // 不是同一笔流水号，但同一幂等键已入账 = 同一张订单又来了一笔**新的**扣款通知。
            // 这不是重放，是「疑似重复扣款」，必须让业务失败并留下可查的错误，而不是静默吞掉。
            if (idemKey != null && getBaseMapper().countByIdemKey(idemKey) > 0) {
                throw new IllegalStateException("订单 " + orderSn + " 已有入账流水（幂等键 " + idemKey
                        + "），但本次渠道流水号 " + tradeNo + " 是新的 —— 疑似重复扣款，需人工对账");
            }
            throw e;
        }
    }
}
