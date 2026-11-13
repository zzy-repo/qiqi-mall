package com.qiqi.mall.shared.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.shared.ledger.mapper.AdminAuditLogMapper;
import com.qiqi.mall.shared.security.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 后台操作审计（{@code admin_audit_log}）的唯一写入口。
 *
 * <h3>⚠️ 红线：幂等键只能由 request_id 派生，绝不能由业务键派生</h3>
 * 审计要回答的是「<b>谁在什么时候点了什么</b>」，语义是「一次请求一条；同一个人连点两次要留两条」，
 * 因为第二次失败本身就是证据（说明他确实点了第二次、这次是因为状态已变而失败）。
 * <ul>
 *   <li>若用业务键派生（例如 {@code ORDER:12:DELETE}）：连点两次的第二次撞 {@code uk_idem} →
 *       INSERT 失败 → 整个事务回滚 → 本该留下的「失败证据」被抹掉，
 *       与「一次请求一条、连点留两条」的审计语义直接冲突；</li>
 *   <li>用 {@code request_id} 派生：两次点击是两个请求 = 两个 id，于是两条审计；
 *       同一次请求里若写了多条审计（例如删单同时删支付流水），用 {@code request_id:序号} 区分。</li>
 * </ul>
 * 注意「INSERT 失败」在这里是**真会致命**的：审计与业务同事务，唯一键冲突会让业务动作一起回滚 ——
 * 用户第一次点成功、第二次点报错，但库里两条证据一条都没有。这就是这条红线必须写进代码的原因。
 *
 * <h3>before_json / after_json 只记敏感字段</h3>
 * 不整实体 dump：价格、状态、归属这些东西才是审计要追的；把整行塞进去既看不清，又会把
 * 手机号、地址这类个人信息复制进审计表（只追加、永不删除 ⇒ 复制一份就再也去不掉）。
 * 调用方用 {@link #snapshot(Object...)} 显式挑字段。
 *
 * <h3>开关</h3>
 * {@code qiqi.ledger.enabled=false} 或 {@code audit-enabled=false} → 直接 return（一行不写）。
 */
@Component
public class AdminAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditWriter.class);

    /** 一次审计事件（before/after 只放敏感字段，见上面的说明） */
    public record Event(String action,
                        String targetType,
                        Long targetId,
                        String targetSn,
                        String summary,
                        String reason,
                        Long orderId,
                        Map<String, Object> before,
                        Map<String, Object> after) {

        /** 最常用形态：目标 + 一句人话摘要 */
        public static Event of(String action, String targetType, Long targetId, String targetSn, String summary) {
            return new Event(action, targetType, targetId, targetSn, summary, null, null, null, null);
        }
    }

    private final AdminAuditLogMapper mapper;
    private final ObjectMapper json;
    private final LedgerProperties props;

    /**
     * 同一次请求里的第几条审计。
     *
     * <p>为什么不用 MDC 存计数：MDC 是「请求生命周期」的，而这里还要覆盖「不在 HTTP 请求线程」的情况
     * （定时任务 / 内部调用）。用 {@code requestId → 计数} 的线程内小状态最省事：
     * requestId 一变（新请求 / 新的系统事件）计数自动从 1 重新开始。
     */
    private static final ThreadLocal<Seq> SEQ = ThreadLocal.withInitial(Seq::new);

    private static final class Seq {
        private String requestId;
        private int n;

        int next(String currentRequestId) {
            if (!currentRequestId.equals(requestId)) {
                requestId = currentRequestId;
                n = 0;
            }
            return ++n;
        }
    }

    public AdminAuditWriter(AdminAuditLogMapper mapper, ObjectMapper json, LedgerProperties props) {
        this.mapper = mapper;
        this.json = json;
        this.props = props;
    }

    /**
     * 写一条审计（与业务同事务；幂等键 = {@code requestId} 或 {@code requestId:n}）。
     *
     * @param event 审计事件；{@code action / targetType} 必填
     */
    public void record(Event event) {
        if (!props.auditOn() || event == null) return;
        if (event.action() == null || event.action().isBlank()) {
            throw new IllegalStateException("审计必须带 action（动作码）：没有动作的审计行读不出「他干了什么」");
        }
        if (event.targetType() == null || event.targetType().isBlank()) {
            throw new IllegalStateException("审计必须带 targetType（目标类型）");
        }
        ActorResolver.Actor actor = ActorResolver.resolve();
        if (!actor.isValid()) {
            throw new IllegalStateException("操作者解析非法（type=" + actor.type() + "）：审计说不清是谁做的就没有意义");
        }
        String requestId = requireRequestId();
        String idemKey = requestId + ":" + SEQ.get().next(requestId);

        // 摘要 / 目标单号 / 原因都是**自由文本或快照**，按列宽裁（summary 255、target_sn 64、reason 32）；
        // action / target_type 是代码常量，绝不裁 —— 超宽必须抛（见 LedgerText 的说明）
        mapper.insert(actor.type(), actor.id(), actor.name(), event.action(), event.targetType(),
                event.targetId(), LedgerText.clip(event.targetSn(), 64),
                LedgerText.clip(event.summary(), 255), LedgerText.clip(event.reason(), 32), event.orderId(),
                toJson(event.before(), event.action() + ".before"), toJson(event.after(), event.action() + ".after"),
                requestId, idemKey);
        log.debug("审计：{} {} #{} → {}（requestId={}, idemKey={}）",
                actor.type(), event.action(), event.targetId(), event.targetType(), requestId, idemKey);
    }

    /**
     * 敏感字段快照：{@code snapshot("price", old, "stock", oldStock)} → {@code {"price":..,"stock":..}}。
     *
     * <p>值传 {@code null} 时该键仍会写进去（{@code null} 也是信息：「改之前没有这个值」）。
     */
    public static Map<String, Object> snapshot(Object... kv) {
        if (kv == null || kv.length == 0) return null;
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("snapshot 必须成对传（字段名, 值）：" + kv.length + " 个参数");
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /**
     * 请求追踪号：HTTP 请求取 {@link RequestIdFilter} 写进 MDC 的那个；
     * 非 HTTP 线程（定时任务 / 内部调用）生成一次性 id —— 那种场景没有「同一请求」，每条审计天然独立。
     */
    private static String requireRequestId() {
        String id = RequestIdFilter.current();
        return id != null && !id.isBlank() ? id : "sys-" + UUID.randomUUID();
    }

    private String toJson(Map<String, Object> snapshot, String what) {
        if (snapshot == null || snapshot.isEmpty()) return null;
        try {
            return json.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // 快照序列化失败 = 审计内容不可信：抛出去让业务回滚，绝不写一条 before/after 缺失的审计
            throw new IllegalStateException("审计快照序列化失败（" + what + "）：" + e.getMessage(), e);
        }
    }
}
