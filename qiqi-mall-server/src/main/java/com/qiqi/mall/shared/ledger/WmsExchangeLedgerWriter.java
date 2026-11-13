package com.qiqi.mall.shared.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.shared.ledger.mapper.WmsExchangeLedgerMapper;
import com.qiqi.mall.shared.security.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WMS 下发 / 回传台账（{@code wms_exchange_ledger}）：<b>write-ahead 两阶段</b>。
 *
 * <h3>为什么是两阶段（REQ → 外呼 → ACK）</h3>
 * 外呼的副作用在数据库之外：{@code WMS 已经收到货单} 这件事回滚不掉。所以顺序必须是
 * 「先把「我要发什么」写进台账（REQ 行）→ 再外呼 → 拿到回执后写 ACK 行」。
 * 反过来（先外呼、成功才记账）在失败路径上会留下「WMS 收到了、我们库里没有任何记录」的黑洞。
 *
 * <h3>幂等键 = {deliveryId}:{direction}:{attemptNo}</h3>
 * 一次外发最多两行、两行同表 ⇒ 键里必须含 {@code direction}（REQ|ACK），否则同一 attempt 的
 * REQ 与 ACK 会互相撞键。{@code attemptNo} 按 (deliveryId, direction) 各自递增：
 * 同方向第 N 次交换 = attemptNo N。重放（同一 attempt 同一方向被写两次）→ 撞 uk_idem → 静默跳过。
 *
 * <h3>一个必须说清的局限（不是 bug，是取舍）</h3>
 * REQ/ACK 两行都在**业务事务里**（与包裹状态同生共死）：外呼抛异常 → 整笔回滚 → REQ 行也消失。
 * 也就是说它保证的是「接口看到的成功/失败与台账一致」，而<b>不是</b>「进程崩溃后仍留痕」——
 * 后者需要独立事务（{@code REQUIRES_NEW}）或落盘预写日志，代价是会留下「包裹没下发、台账却有 REQ」
 * 的孤儿行，那需要另一套对账机制来清。本轮明确选前者（证据与业务一致），
 * 并在报告里记为已知局限。
 */
@Component
public class WmsExchangeLedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(WmsExchangeLedgerWriter.class);

    public static final String DIRECTION_REQ = "REQ";
    public static final String DIRECTION_ACK = "ACK";

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_TIMEOUT = "TIMEOUT";

    private final WmsExchangeLedgerMapper mapper;
    private final ObjectMapper json;
    private final LedgerProperties props;

    public WmsExchangeLedgerWriter(WmsExchangeLedgerMapper mapper, ObjectMapper json, LedgerProperties props) {
        this.mapper = mapper;
        this.json = json;
        this.props = props;
    }

    /**
     * 阶段一（write-ahead）：外呼**之前**写 REQ 行。
     *
     * @param delivery 包裹（只取 id / 单号做报文快照）
     * @return 本次尝试号；开关关闭时返回 0（调用方照样外呼，只是不记账）
     */
    public int requestSent(DeliveryOrder delivery, Map<String, Object> payload) {
        if (!props.wmsOn()) return 0;
        long deliveryId = requireId(delivery);
        int attemptNo = mapper.nextAttemptNo(deliveryId, DIRECTION_REQ);
        insert(deliveryId, DIRECTION_REQ, attemptNo, toJson(payload, "REQ"), null, null, null);
        log.debug("WMS 台账：包裹 {} 第 {} 次下发（REQ 已落库，等待回执）", deliveryId, attemptNo);
        return attemptNo;
    }

    /** 阶段二：拿到回执后写 ACK 行（result / latency / payload 一起落） */
    public void requestAcked(DeliveryOrder delivery, int attemptNo, String result,
                             long latencyMs, Map<String, Object> responsePayload) {
        if (!props.wmsOn() || attemptNo <= 0) return;
        long deliveryId = requireId(delivery);
        insert(deliveryId, DIRECTION_ACK, attemptNo, null, toJson(responsePayload, "ACK"), result,
                (int) Math.min(Integer.MAX_VALUE, latencyMs));
        log.debug("WMS 台账：包裹 {} 第 {} 次下发回执 {}（{}ms）", deliveryId, attemptNo, result, latencyMs);
    }

    /**
     * 仓库回传（pick/outbound/transit/signed）入账：direction 仍是 ACK（这是仓库给我们的回执），
     * attemptNo 按 ACK 方向自增，因此不会与「下发回执」撞键。
     *
     * @param event       回传事件名（pick/outbound/…）
     * @param statusSeq   本次包裹状态流转后的 status_seq —— 同一个事件被重复推送时它不变，
     *                    但事件本身会被业务幂等挡在前面（不进台账），所以这里只做记录不做判重
     */
    public void callbackReceived(DeliveryOrder delivery, String event, Integer fromStatus, int toStatus) {
        if (!props.wmsOn()) return;
        long deliveryId = requireId(delivery);
        int attemptNo = mapper.nextAttemptNo(deliveryId, DIRECTION_ACK);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("deliverySn", delivery.getDeliverySn());
        payload.put("fromStatus", fromStatus);
        payload.put("toStatus", toStatus);
        insert(deliveryId, DIRECTION_ACK, attemptNo, toJson(payload, "ACK"), null, RESULT_SUCCESS, null);
        log.debug("WMS 台账：包裹 {} 收到回传 {}（{} → {}）", deliveryId, event, fromStatus, toStatus);
    }

    private static String key(long deliveryId, String direction, int attemptNo) {
        return deliveryId + ":" + direction + ":" + attemptNo;
    }

    /** 一次 insert 只解析一次操作者（ActorResolver 要看 SecurityContext，别在参数里重复调用） */
    private void insert(long deliveryId, String direction, int attemptNo, String reqPayload,
                        String respPayload, String result, Integer latencyMs) {
        ActorResolver.Actor actor = ActorResolver.resolve();
        mapper.insert(deliveryId, direction, attemptNo, reqPayload, respPayload, result, latencyMs,
                actor.type(), actor.id(), actor.name(), key(deliveryId, direction, attemptNo));
    }

    private static long requireId(DeliveryOrder delivery) {
        if (delivery == null || delivery.getId() == null) {
            throw new IllegalStateException("WMS 台账缺少包裹 id：无法生成幂等键 {deliveryId}:{direction}:{attemptNo}");
        }
        return delivery.getId();
    }

    private String toJson(Map<String, Object> payload, String what) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WMS 台账报文序列化失败（" + what + "）：" + e.getMessage(), e);
        }
    }
}
