package com.qiqi.mall.oms.controller;

import com.qiqi.mall.oms.config.PaymentSandboxProperties;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.service.ChannelCallbackService;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.shared.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端「支付流水」—— 目前只有一个**沙箱**端点：重放渠道回调。
 *
 * <h3>为什么需要它</h3>
 * {@code payment_record} 有两道闸，但「第三方重复通知」这一道此前**造不出来**：
 * 模拟支付（{@code PortalOrderController#pay}）的 {@code trade_no} 是本地按时间戳生成的，
 * 没有任何入口能拿「同一个流水号」再通知一次 —— 于是 {@code uk_channel_trade} 只能是
 * 列注释里的一句声明。这个端点就是那道闸的验证入口：拿库里那条流水的**原 trade_no**
 * 重新走一遍 {@link ChannelCallbackService#onNotice}，等于模拟渠道把同一笔通知又发了一次。
 *
 * <h3>三个约束（缺一个就会变成生产事故）</h3>
 * <ol>
 *   <li><b>开关</b>：{@code qiqi.payment.sandbox-endpoints-enabled}（默认 false）。
 *       关着的时候接口返回可读失败而不是 404 —— 让在 Swagger 里看到它的人立刻知道「为什么调不通」；</li>
 *   <li><b>权限</b>：与其它写接口一致 —— {@code /admin/payments} 在 {@code ApiKeyAuthFilter.ADMIN_RULES}
 *       里登记为「读要 order:read、写要 payment:write」，而 {@code payment:write} **不在**
 *       {@code AiApiKeyService.ALL_SCOPES} 里 ⇒ 任何 API Key 调这个 POST 都 403
 *       （失败不会被静默当成「重复通知」），只有人工 JWT 能调；</li>
 *   <li><b>只记账、不推状态</b>：重放走的是渠道回调的记账路径，订单状态推进不在这里重演 ——
 *       否则「重复通知被挡住」会被状态闸抢先解释掉（详见 {@link ChannelCallbackService} 类注释）。</li>
 * </ol>
 *
 * <p><b>返回值语义</b>：{@code code=0} 表示「这次通知处理完了」，不表示「新增了一行」。
 * 到底记没记账看 {@code data.outcome}：
 * <ul>
 *   <li>{@code DUPLICATE} + message「重复通知，已忽略」—— 该 (channel, trade_no) 已入账，一行都没加；</li>
 *   <li>{@code RECORDED} + message「渠道通知已入账」—— 这次确实新增了 1 行（首次通知）。</li>
 * </ul>
 * 把「重复」写成 code=0 是**刻意的**：重复通知不是错误，是渠道的正常行为（重试/重复投递）；
 * 报错会诱导调用方去重试，而重试只会继续被挡。真正该报错的情况（缺流水号 / 疑似重复扣款）
 * 抛异常 → {@code GlobalExceptionHandler} → {@code code=1} + 可读中文。
 */
@Tag(name = "管理端-支付流水", description = "支付流水的沙箱操作（渠道回调重放；仅非生产环境开放）")
@RestController
@RequestMapping("/admin/payments")
public class AdminPaymentController {

    private static final Logger log = LoggerFactory.getLogger(AdminPaymentController.class);

    /** 重复通知的固定文案（e2e 与交付验收直接按这句断言，别改字） */
    static final String MSG_DUPLICATE = "重复通知，已忽略";
    /** 首次入账的文案 */
    static final String MSG_RECORDED = "渠道通知已入账";

    private final PaymentRecordService paymentRecordService;
    private final ChannelCallbackService channelCallbackService;
    private final PaymentSandboxProperties sandbox;

    public AdminPaymentController(PaymentRecordService paymentRecordService,
                                  ChannelCallbackService channelCallbackService,
                                  PaymentSandboxProperties sandbox) {
        this.paymentRecordService = paymentRecordService;
        this.channelCallbackService = channelCallbackService;
        this.sandbox = sandbox;
    }

    /**
     * 重放一次渠道回调：用该流水**原来的** {@code trade_no} 再通知一遍。
     *
     * <p>正常结果是「重复通知，已忽略」（因为那笔钱本来就已入账）；一行都不会新增。
     * 这正是要证明的事：{@code uk_channel_trade} 会挡住它，而不是靠「记得别插」。
     */
    @Operation(summary = "重放渠道回调（沙箱：用原 trade_no 再通知一次，验证 uk_channel_trade 挡住重复通知）")
    @PostMapping("/{id}/replay-channel-callback")
    public Result<Map<String, Object>> replayChannelCallback(@PathVariable Long id) {
        if (!sandbox.isSandboxEndpointsEnabled()) {
            log.warn("[payment] 渠道回调重放被拒：沙箱端点未开启（qiqi.payment.sandbox-endpoints-enabled=false），id={}", id);
            return Result.fail("渠道回调重放只在非生产环境开放"
                    + "（qiqi.payment.sandbox-endpoints-enabled=false）：生产环境没有这个口子");
        }

        PaymentRecord origin = paymentRecordService.getById(id);
        if (origin == null) return Result.fail("支付流水不存在：id=" + id);
        if (origin.getTradeNo() == null || origin.getTradeNo().isBlank()) {
            return Result.fail("该流水没有渠道流水号（trade_no），无法重放：id=" + id);
        }

        // 原样搬运这条流水里的「通知内容」：同一个渠道、同一个流水号、同一张订单、同一笔金额。
        // 这就是「第三方把同一笔通知又发了一次」在数据层面的样子。
        ChannelCallbackService.Outcome outcome = channelCallbackService.onNotice(
                new ChannelCallbackService.ChannelNotice(
                        origin.getChannel() == null ? 0 : origin.getChannel(),
                        origin.getTradeNo(),
                        origin.getOrderSn(),
                        origin.getAmount(),
                        origin.getType() == null ? PaymentRecord.TYPE_PAY : origin.getType(),
                        origin.getOrderId(),
                        origin.getRefType(),
                        origin.getRefId(),
                        origin.getOriginFlowId()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", origin.getId());
        data.put("orderSn", origin.getOrderSn());
        data.put("channel", origin.getChannel());
        data.put("tradeNo", origin.getTradeNo());
        data.put("type", origin.getType());
        data.put("outcome", outcome.name());
        data.put("recorded", outcome == ChannelCallbackService.Outcome.RECORDED);

        String message = outcome == ChannelCallbackService.Outcome.DUPLICATE ? MSG_DUPLICATE : MSG_RECORDED;
        // code=0 + 顶层 message 带文案：一次重复通知不是失败，调用方/验收都只需要读这一句
        return new Result<>(0, message, data);
    }
}
