package com.qiqi.mall.oms.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付沙箱端点的开关（{@code qiqi.payment.*}）。
 *
 * <h3>为什么必须有一个开关，而不是「写了就一直在」</h3>
 * {@code POST /admin/payments/{id}/replay-channel-callback} 是一个**只为验证而存在**的入口：
 * 它按库里那条流水的原 {@code trade_no} 把「第三方重复通知」再走一遍，用来证明
 * {@code payment_record.uk_channel_trade} 这道闸真的挡得住重复通知。
 *
 * <p>它对外部世界没有业务价值，却是一个「能让服务端主动去撞唯一键」的口子。生产环境留着一个
 * 可以反复触发资金记账路径的接口，等于把「钱」这条链路交给运气 —— 所以：
 * <ul>
 *   <li><b>默认 false</b>：任何没有显式打开的环境（含将来新增的 prod profile）都拿不到这个口子；</li>
 *   <li><b>dev / test 的 yml 里显式写 true</b>：e2e 与本地调试要能调它。写死在 yml 而不是靠默认值，
 *       是为了让「这个环境有没有开沙箱口子」在配置文件里一眼可见（同 {@code qiqi.ledger.*} 的口径）；</li>
 *   <li><b>启动日志打一行生效值</b>：出事时第一个问题永远是「现在到底开没开」（见 {@link #report()}）。</li>
 * </ul>
 *
 * <p>注意：关掉开关**不是**靠「注册与否」，而是控制器里显式判 {@link #isSandboxEndpointsEnabled()} 并
 * 返回可读失败 —— 这样 Swagger 里看到这个接口的人会立刻读到「为什么调不通」，而不是挠头找一个 404。
 */
@Component
@ConfigurationProperties(prefix = "qiqi.payment")
public class PaymentSandboxProperties {

    private static final Logger log = LoggerFactory.getLogger(PaymentSandboxProperties.class);

    /**
     * 是否开放支付沙箱端点（渠道回调重放）。
     *
     * <p>默认 {@code false}：生产环境（没有显式配置 yml）必须是关的。
     */
    private boolean sandboxEndpointsEnabled = false;

    public boolean isSandboxEndpointsEnabled() {
        return sandboxEndpointsEnabled;
    }

    public void setSandboxEndpointsEnabled(boolean sandboxEndpointsEnabled) {
        this.sandboxEndpointsEnabled = sandboxEndpointsEnabled;
    }

    /**
     * 启动时打一行生效值 —— **开着就必须在日志里刺眼地看得见**。
     *
     * <p>用 warn 打「开」，用 info 打「关」：翻开生产日志时，一行 WARN 比一行 INFO 难被忽略得多。
     */
    @PostConstruct
    public void report() {
        if (sandboxEndpointsEnabled) {
            log.warn("[payment] 支付沙箱端点已**开启**（qiqi.payment.sandbox-endpoints-enabled=true）："
                    + "POST /admin/payments/{id}/replay-channel-callback 可被调用。"
                    + "这个口子只允许存在于 dev / test / e2e，生产必须为 false");
        } else {
            log.info("[payment] 支付沙箱端点关闭（qiqi.payment.sandbox-endpoints-enabled=false）："
                    + "渠道回调重放接口一律拒绝");
        }
    }
}
