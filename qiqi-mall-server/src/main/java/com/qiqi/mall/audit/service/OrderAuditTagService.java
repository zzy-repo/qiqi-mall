package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.entity.OrderAuditTag;
import com.qiqi.mall.audit.mapper.OrderAuditTagMapper;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.security.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 审核标记（冻结 / 挂起）的唯一写入口。
 *
 * <h3>冻结为什么不是状态码（决策 J1）</h3>
 * 冻结期间订单仍是「7 待审核」——它确实还没审完，只是被按住。若引入 10=冻结：
 * <ul>
 *   <li>{@code OrderStatusService.TRANSITIONS} 里 7 的出口要改，所有按状态统计的口径都要改；</li>
 *   <li>解冻还得回到 7，而 10→7 又是一条「不是流转的流转」；</li>
 *   <li>「为什么冻的」这个信息状态码表达不了，终究还是得加一张标表。</li>
 * </ul>
 * 所以冻结只写标记 + {@code admin_audit_log}（{@code AUDIT_FREEZE} / {@code AUDIT_UNFREEZE}），
 * <b>不写 {@code order_status_ledger}</b>（7→7 会被 TRANSITIONS 拦下，而且那本来就不是状态流转）。
 *
 * <h3>并发</h3>
 * 「同一单同一标只能有一条 active」由 {@code uk_active}（虚拟列 active_tag）兜底：
 * 两个线程同时冻结，第二个 insert 会撞唯一键 —— 这里把它当成「已经冻上了」，幂等返回。
 */
@Service
public class OrderAuditTagService {

    private static final Logger log = LoggerFactory.getLogger(OrderAuditTagService.class);

    public static final String TAG_FROZEN = "FROZEN";
    public static final String TAG_STOCK_SHORT = "STOCK_SHORT";
    public static final String TAG_COST_MISSING = "COST_MISSING";

    private final OrderAuditTagMapper tagMapper;
    private final AdminAuditWriter auditWriter;

    public OrderAuditTagService(OrderAuditTagMapper tagMapper, AdminAuditWriter auditWriter) {
        this.tagMapper = tagMapper;
        this.auditWriter = auditWriter;
    }

    // ================================================================== 查询

    public List<OrderAuditTag> activeTags(Long orderId) {
        return tagMapper.selectActive(orderId);
    }

    /** 冻结判定：返回 active 的 FROZEN 类标；null = 未冻结 */
    public OrderAuditTag activeFrozen(Long orderId) {
        return tagMapper.selectActiveFrozen(orderId);
    }

    public boolean isFrozen(Long orderId) {
        return activeFrozen(orderId) != null;
    }

    // ================================================================== 打标 / 解标（幂等）

    /** 打标（已存在 active 同标则什么都不做，返回既有行） */
    @Transactional(rollbackFor = Exception.class)
    public OrderAuditTag ensureActive(Long orderId, String tagCode, String reason) {
        OrderAuditTag existing = tagMapper.selectActiveByCode(orderId, tagCode);
        if (existing != null) return existing;
        OrderAuditTag tag = new OrderAuditTag();
        tag.setOrderId(orderId);
        tag.setTagCode(tagCode);
        tag.setActive(1);
        tag.setReason(reason == null ? null : clip(reason, 255));
        try {
            tagMapper.insert(tag);
            return tag;
        } catch (DuplicateKeyException e) {
            // 并发:别人刚打了同一个标 → uk_active 拦下,当成已存在
            log.debug("订单 {} 的标 {} 已存在（并发打标）", orderId, tagCode);
            return tagMapper.selectActiveByCode(orderId, tagCode);
        }
    }

    /** 解标：返回影响行数（0 = 本来就没这个 active 标） */
    @Transactional(rollbackFor = Exception.class)
    public int release(Long orderId, String tagCode, String releasedBy) {
        return tagMapper.release(orderId, tagCode, releasedBy == null ? "system" : clip(releasedBy, 64));
    }

    // ================================================================== 冻结 / 解冻（写审计）

    /**
     * 冻结：打 FROZEN 类标 + 写审计。
     *
     * @param source 冻结来源（「自动审核：策略X」/「人工」），进审计摘要 ——
     *               事后最常问的就是「这单是谁冻的、凭什么冻的」。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderAuditTag freeze(Order order, String tagCode, String reason, String source) {
        String tag = tagCode == null || tagCode.isBlank() ? TAG_FROZEN : tagCode;
        OrderAuditTag before = tagMapper.selectActiveByCode(order.getId(), tag);
        OrderAuditTag tag0 = ensureActive(order.getId(), tag, reason);
        if (before == null) {
            auditWriter.record(new AdminAuditWriter.Event(
                    ReasonCodes.ACTION_AUDIT_FREEZE, ReasonCodes.TARGET_ORDER, order.getId(), order.getOrderSn(),
                    "冻结订单 " + order.getOrderSn() + "：" + (source == null ? "" : source + "，")
                            + "标=" + tag + (reason == null ? "" : "，原因=" + reason),
                    null, order.getId(),
                    AdminAuditWriter.snapshot("frozen", Boolean.FALSE),
                    AdminAuditWriter.snapshot("frozen", Boolean.TRUE, "tag", tag, "reason", reason)));
            log.info("订单 {} 已冻结：tag={} source={} reason={}", order.getOrderSn(), tag, source, reason);
        }
        return tag0;
    }

    /**
     * 解冻：放掉所有 FROZEN 类标 + 写审计；订单状态不动（本来也没变过）。
     *
     * @return 解除的标数（0 = 该单未冻结 → 抛异常，不让「点了没反应」蒙过去）
     */
    @Transactional(rollbackFor = Exception.class)
    public int unfreeze(Order order) {
        ActorResolver.Actor actor = ActorResolver.resolve();
        int rows = tagMapper.releaseFrozen(order.getId(), actor.name());
        if (rows == 0) {
            throw new IllegalArgumentException("订单 " + order.getOrderSn() + " 当前没有被冻结，无需解冻");
        }
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_AUDIT_UNFREEZE, ReasonCodes.TARGET_ORDER, order.getId(), order.getOrderSn(),
                "解冻订单 " + order.getOrderSn() + "（解除 " + rows + " 条冻结标，等待下一轮重新判定）",
                null, order.getId(),
                AdminAuditWriter.snapshot("frozen", Boolean.TRUE),
                AdminAuditWriter.snapshot("frozen", Boolean.FALSE, "released", rows)));
        log.info("订单 {} 已解冻（{} 条标）", order.getOrderSn(), rows);
        return rows;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
