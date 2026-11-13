package com.qiqi.mall.shared.ledger;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 台账开关（{@code qiqi.ledger.*}）—— P2 第二步：把台账写入接进业务事务。
 *
 * <h3>为什么一定要开关（单变量纪律）</h3>
 * 台账写入是**新增的写库动作**，而且与业务在**同一个事务**里。上线后它只有两种可能：
 * <ol>
 *   <li>它对（多一条证据，业务照跑）—— 这时开关留着是为了<b>对比</b>：同一套 e2e，
 *       开/关各跑一次，行数差恰好等于台账写入次数，才能证明「打开台账没有改变业务行为」；</li>
 *   <li>它错（约束冲突、锁等待、事务变长到超时）—— 这时开关是**一键回退**：
 *       {@code qiqi.ledger.enabled=false} 立刻回到 P2 第一步（只有状态写入，没有台账），
 *       不用回滚代码、不用重新发版。</li>
 * </ol>
 *
 * <h3>为什么是「总开关 + 分项」而不是四个独立开关</h3>
 * 四个独立布尔能拼出 2⁴ 种组合，排障时没人能一眼说清「现在到底记了什么账」。
 * 所以口径是：{@link #enabled} 是**总闸**，分项只能在总闸打开时才生效 ——
 * 总闸一关，四项一起停，不存在「关了一半」的中间态。启动日志会把这四个**生效值**打出来
 * （见 {@link #describe()}），一眼可验。
 *
 * <p><b>默认全开</b>：台账是本轮交付物本身，默认关掉等于没交付；要关必须显式配置。
 */
@Component
@ConfigurationProperties(prefix = "qiqi.ledger")
public class LedgerProperties {

    private static final Logger log = LoggerFactory.getLogger(LedgerProperties.class);

    /** 总闸：false = 一行台账都不写（精确回到 P2 第一步的行为） */
    private boolean enabled = true;

    /** 分项：三张状态台账（order/delivery/return_status_ledger） */
    private boolean statusEnabled = true;

    /** 分项：后台操作审计（admin_audit_log） */
    private boolean auditEnabled = true;

    /** 分项：WMS 下发/回传台账（wms_exchange_ledger） */
    private boolean wmsEnabled = true;

    /** 分项：支付流水加固字段（order_id、ref_type、ref_id、origin_flow_id、operator 三列、idem_key） */
    private boolean paymentTraceEnabled = true;

    // ------------------------------------------------------------------ 生效值（总闸 & 分项）

    public boolean statusOn() {
        return enabled && statusEnabled;
    }

    public boolean auditOn() {
        return enabled && auditEnabled;
    }

    public boolean wmsOn() {
        return enabled && wmsEnabled;
    }

    public boolean paymentTraceOn() {
        return enabled && paymentTraceEnabled;
    }

    /**
     * 启动日志口径：{@code ledger.enabled=true status=true audit=true wms=true payment-trace=true}。
     *
     * <p>打的是**生效值**（已经与总闸与过），不是配置原文 —— 否则配了
     * {@code enabled=false audit-enabled=true} 时会打出「audit=true」，与实际行为相反。
     */
    public String describe() {
        return "ledger.enabled=" + enabled
                + " status=" + statusOn()
                + " audit=" + auditOn()
                + " wms=" + wmsOn()
                + " payment-trace=" + paymentTraceOn();
    }

    /**
     * 启动时打一行：**台账是开还是关，必须在启动日志里看得见**。
     *
     * <p>为什么不能只靠配置文件的默认值：出问题时的第一个问题永远是「现在到底开没开」。
     * 让它出现在启动日志里，就不用去翻 yml、不用猜是不是被环境变量覆盖了。
     */
    @PostConstruct
    public void report() {
        if (enabled) {
            log.info("[ledger] 台账写入已启用：{}", describe());
        } else {
            log.warn("[ledger] 台账写入已**关闭**（{}）：行为等同 P2 第一步，库里不会有新台账行", describe());
        }
    }

    // ------------------------------------------------------------------ getter / setter（配置绑定）

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStatusEnabled() {
        return statusEnabled;
    }

    public void setStatusEnabled(boolean statusEnabled) {
        this.statusEnabled = statusEnabled;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

    public boolean isWmsEnabled() {
        return wmsEnabled;
    }

    public void setWmsEnabled(boolean wmsEnabled) {
        this.wmsEnabled = wmsEnabled;
    }

    public boolean isPaymentTraceEnabled() {
        return paymentTraceEnabled;
    }

    public void setPaymentTraceEnabled(boolean paymentTraceEnabled) {
        this.paymentTraceEnabled = paymentTraceEnabled;
    }
}
