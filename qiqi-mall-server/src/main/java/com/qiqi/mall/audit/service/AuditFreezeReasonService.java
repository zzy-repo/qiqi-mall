package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.entity.AuditFreezeReason;
import com.qiqi.mall.audit.mapper.AuditFreezeReasonMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冻结原因字典（{@code audit_freeze_reason}）的读入口 + 写入侧解析。
 *
 * <h3>为什么整张表缓存在内存里</h3>
 * 字典只有十几行、几乎不变，而它要在**每一条**冻结写入路径上被解析（自动冻结、人工冻结），
 * 还要在队列的每一行上把 {@code reason_id} 翻成中文名。每行一次 SQL 是不可接受的 N+1。
 * 所以启动时整表装进两个 {@code Map}（byCode / byId），{@link #reload()} 在维护页改完字典后调一次。
 *
 * <p>⚠️ 缓存里**必须包含停用项**：历史行挂着已停用的原因，它照样要显示中文名（不是显示 ID、更不是空白）。
 * 「停用」只影响"能不能被新冻结选中"，不影响"读得出当初是哪一条"。
 *
 * <h3>写入侧解析：code 优先、其次按人话反查、最后兜底</h3>
 * 三条来路都要能落到一个具体的原因项上，不能有"解析不出来就写 NULL"的缝：
 * <ol>
 *   <li><b>给了 code</b>（新前端、策略 {@code action_json}）→ 直接用，但**必须存在且启用**，
 *       否则记 warn 并降级 —— 悄悄接受一个不存在的 code 会撞外键，悄悄接受一个停用的 code
 *       等于绕过了停用开关；</li>
 *   <li><b>只给了人话</b>（旧前端、老策略配置、脚本）→ 按 {@link #inferCodeFromText} 的关键词反查；</li>
 *   <li><b>都没命中</b> → 兜底：自动冻结落 {@link #CODE_UNSPECIFIED}（"异常策略命中但没填原因"），
 *       人工冻结落 {@link #CODE_UNCLASSIFIED}（"待人工归类"）。两条都是 {@code selectable=0}
 *       但在报表里可见 —— 兜底不等于丢弃。</li>
 * </ol>
 *
 * <h3>⚠️ 反查规则必须与迁移 V3 的回填规则逐条一致</h3>
 * {@link #inferCodeFromText} 的规则表是从 {@code V3__audit_freeze_reason.sql} 的回填 CASE 逐条抄来的。
 * 两处不一致的后果是：同一句人话，历史行归到 A、新数据归到 B，统计立刻裂开。
 * 改这里的规则 = 同时改 V3 的注释并写一个新迁移处理存量（V3 本身已不可改）。
 */
@Service
public class AuditFreezeReasonService {

    private static final Logger log = LoggerFactory.getLogger(AuditFreezeReasonService.class);

    // ---------------------------------------------------------------- 内置 code 常量
    /** 有未处理完的退款/退货申请（自动：EXCEPTION 退款申请冻结） */
    public static final String CODE_REFUND_PENDING = "REFUND_PENDING";
    /** 24 小时内平台备注被改过（自动：EXCEPTION 平台备注冻结） */
    public static final String CODE_PLATFORM_REMARK_CHANGED = "PLATFORM_REMARK_CHANGED";
    /** 风控核实中（人工） */
    public static final String CODE_RISK_REVIEW = "RISK_REVIEW";
    /** 客户要求暂缓发货（人工） */
    public static final String CODE_CUSTOMER_HOLD = "CUSTOMER_HOLD";
    /** 疑似刷单待核实（人工） */
    public static final String CODE_SUSPECTED_BRUSHING = "SUSPECTED_BRUSHING";
    /** 价格或优惠异常（人工） */
    public static final String CODE_PRICE_ANOMALY = "PRICE_OR_DISCOUNT_ANOMALY";
    /** 大额订单人工核单（人工） */
    public static final String CODE_BIG_ORDER_REVIEW = "BIG_ORDER_REVIEW";
    /** 收货信息可疑（人工） */
    public static final String CODE_ADDRESS_SUSPECT = "ADDRESS_SUSPECT";
    /** 兜底：异常策略命中但策略没配原因（自动，selectable=0） */
    public static final String CODE_UNSPECIFIED = "POLICY_FREEZE_UNSPECIFIED";
    /** 迁移归类：占位/乱填/空（selectable=0，builtin=1） */
    public static final String CODE_LEGACY = "LEGACY_PLACEHOLDER";
    /** 兜底：写了但词表没覆盖（selectable=0，builtin=1） */
    public static final String CODE_UNCLASSIFIED = "UNCLASSIFIED";

    private final AuditFreezeReasonMapper mapper;

    private volatile Map<String, AuditFreezeReason> byCode = Map.of();
    private volatile Map<Long, AuditFreezeReason> byId = Map.of();

    public AuditFreezeReasonService(AuditFreezeReasonMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void loadOnStartup() {
        try {
            reload();
        } catch (RuntimeException e) {
            // 字典读不到**不该让整个应用起不来**：冻结写入路径有兜底（code 解析失败会走 UNSPECIFIED），
            // 而"起不来"会让审核整块停摆，代价远大于"原因暂时显示不出中文名"。
            log.error("冻结原因字典加载失败（原因中文名将暂时退化为显示 code）: {}", e.getMessage(), e);
        }
    }

    /** 重新装载（维护页改完字典后调用；也可在测试里手工刷新） */
    public synchronized void reload() {
        List<AuditFreezeReason> all = mapper.selectAllOrdered();
        Map<String, AuditFreezeReason> c = new LinkedHashMap<>();
        Map<Long, AuditFreezeReason> i = new LinkedHashMap<>();
        for (AuditFreezeReason r : all) {
            c.put(r.getCode(), r);
            i.put(r.getId(), r);
        }
        this.byCode = c;
        this.byId = i;
        log.info("冻结原因字典已装载：{} 条（可选 {} 条）", all.size(), selectable().size());
    }

    // ---------------------------------------------------------------- 读

    /** 全量（含停用/兜底项）：维护页、报表口径 */
    public List<AuditFreezeReason> all() {
        return List.copyOf(byCode.values());
    }

    /** 下拉可选：enabled=1 && selectable=1 */
    public List<AuditFreezeReason> selectable() {
        return byCode.values().stream()
                .filter(r -> eq1(r.getEnabled()) && eq1(r.getSelectable()))
                .toList();
    }

    public AuditFreezeReason byCode(String code) {
        return code == null ? null : byCode.get(code);
    }

    public AuditFreezeReason byId(Long id) {
        return id == null ? null : byId.get(id);
    }

    /** id → code（队列/豁免用）；字典里没有时返回 null 而不是猜 */
    public String codeOf(Long id) {
        AuditFreezeReason r = byId(id);
        return r == null ? null : r.getCode();
    }

    /** id → 中文名（**这是页面唯一该显示的东西**，永远不要把 ID 显示给人） */
    public String nameOf(Long id) {
        AuditFreezeReason r = byId(id);
        return r == null ? null : r.getName();
    }

    // ---------------------------------------------------------------- 写入侧解析

    /**
     * 解析结果：写入 {@code order_audit_tag} 需要的三样东西。
     *
     * @param id   外键（{@code order_audit_tag.reason_id}）
     * @param code 稳定键快照（{@code order_audit_tag.reason_code}）
     * @param name 中文名（写进 {@code reason} 列作为"人话快照"，当调用方没给备注时）
     */
    public record Resolved(Long id, String code, String name) {
        public boolean ok() {
            return id != null && code != null;
        }
    }

    /**
     * 冻结写入前的解析（见类注释的三条来路）。
     *
     * @param requestedCode 调用方指定的 code（可为 null）
     * @param humanText     调用方给的人话（备注/原话，可为 null）
     * @param fallbackCode  都不命中时的兜底（自动冻结传 {@link #CODE_UNSPECIFIED}，
     *                      人工冻结传 {@link #CODE_UNCLASSIFIED}）
     */
    public Resolved resolveForWrite(String requestedCode, String humanText, String fallbackCode) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            AuditFreezeReason r = byCode(requestedCode.trim());
            if (r != null && eq1(r.getEnabled())) {
                return new Resolved(r.getId(), r.getCode(), r.getName());
            }
            // 不静默接受：不存在的 code 会撞外键；停用的 code 会绕过停用开关
            log.warn("冻结原因 code 不可用（{}），改用按人话反查/兜底：requested={}", 
                    r == null ? "不存在" : "已停用", requestedCode);
        }
        String inferred = inferCodeFromText(humanText);
        if (inferred != null) {
            AuditFreezeReason r = byCode(inferred);
            if (r != null) {
                return new Resolved(r.getId(), r.getCode(), r.getName());
            }
        }
        AuditFreezeReason fb = byCode(fallbackCode == null ? CODE_UNCLASSIFIED : fallbackCode);
        if (fb == null) {
            log.error("冻结原因兜底项 {} 在字典里不存在 —— 冻结仍会写入，但 reason_id 为空", fallbackCode);
            return new Resolved(null, null, null);
        }
        return new Resolved(fb.getId(), fb.getCode(), fb.getName());
    }

    /**
     * 人话 → code 的关键词反查（与 V3 迁移的回填 CASE **逐条同规则**，见类注释的红字）。
     *
     * <p>顺序有讲究：<b>数字规则必须排在中文关键词之前</b> —— 将来出现「1231退款」这种混合脏值，
     * 它首先是乱填，不该因为带了「退款」两个字就被归成 REFUND_PENDING。
     * 中文关键词从具体到宽泛排。
     *
     * @return 命中的 code；一个都不命中返回 null（由调用方决定兜底）
     */
    public static String inferCodeFromText(String text) {
        // ⚠️ 空/纯数字 → LEGACY 这条规则**只在迁移回填里成立**（历史行真的没填过）。
        // 写入侧的空人话表示"这次没填备注"，不等于"历史上没填"，所以这里回 null 交给调用方兜底。
        // 纯数字仍然归 LEGACY：新写入不可能合法地长成 "1231"，那一定是有人在塞占位值。
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        if (s.matches("^[0-9]+$")) return CODE_LEGACY;
        if (s.contains("退款") || s.contains("退货")) return CODE_REFUND_PENDING;
        if (s.contains("备注")) return CODE_PLATFORM_REMARK_CHANGED;
        if (s.contains("风控")) return CODE_RISK_REVIEW;
        if (s.contains("刷单")) return CODE_SUSPECTED_BRUSHING;
        if (s.contains("暂缓") || s.contains("延后") || s.contains("改期")) return CODE_CUSTOMER_HOLD;
        if (s.contains("错价") || s.contains("优惠") || s.contains("价格")) return CODE_PRICE_ANOMALY;
        if (s.contains("大额") || s.contains("核单")) return CODE_BIG_ORDER_REVIEW;
        if (s.contains("地址") || s.contains("电话")) return CODE_ADDRESS_SUSPECT;
        return null;
    }

    private static boolean eq1(Integer v) {
        return v != null && v == 1;
    }
}
