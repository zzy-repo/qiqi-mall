package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.entity.AuditTagCatalog;
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
 * <h3>冻结为什么不是状态码</h3>
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

    /** 结论标互斥时的解除人：系统（不是某个运营），且原因固定为"被新结论取代" */
    public static final String RELEASED_BY_SUPERSEDED = "SYSTEM:SUPERSEDED";

    public static final String TAG_FROZEN = "FROZEN";
    public static final String TAG_STOCK_SHORT = "STOCK_SHORT";
    public static final String TAG_COST_MISSING = "COST_MISSING";

    private final OrderAuditTagMapper tagMapper;
    private final AdminAuditWriter auditWriter;
    private final AuditFreezeReasonService reasonDict;
    /** 标目录：人工打标前必须校验「这个标码是不是目录里真有、是不是特征标」 */
    private final AuditTagCatalogService tagCatalog;

    public OrderAuditTagService(OrderAuditTagMapper tagMapper, AdminAuditWriter auditWriter,
                                AuditFreezeReasonService reasonDict, AuditTagCatalogService tagCatalog) {
        this.tagMapper = tagMapper;
        this.auditWriter = auditWriter;
        this.reasonDict = reasonDict;
        this.tagCatalog = tagCatalog;
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
        return ensureActive(orderId, tagCode, reason, null);
    }

    /**
     * 打标（带原因字典）。
     *
     * @param resolved 冻结原因的解析结果；{@code null} = 这条标与冻结原因无关
     *                 （{@code STOCK_SHORT} / {@code COST_MISSING} 这类「留人工」的挂起标——它们的
     *                 理由是 {@code downgrade_reason}，不是冻结原因，所以 {@code reason_id} 留空）
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderAuditTag ensureActive(Long orderId, String tagCode, String reason,
                                      AuditFreezeReasonService.Resolved resolved) {
        OrderAuditTag existing = tagMapper.selectActiveByCode(orderId, tagCode);
        if (existing != null) return existing;
        OrderAuditTag tag = new OrderAuditTag();
        tag.setOrderId(orderId);
        tag.setTagCode(tagCode);
        tag.setActive(1);
        tag.setReason(reason == null ? null : clip(reason, 255));
        if (resolved != null && resolved.ok()) {
            tag.setReasonId(resolved.id());
            tag.setReasonCode(resolved.code());
        }
        try {
            tagMapper.insert(tag);
            return tag;
        } catch (DuplicateKeyException e) {
            // 并发:别人刚打了同一个标 → uk_active 拦下,当成已存在
            log.debug("订单 {} 的标 {} 已存在（并发打标）", orderId, tagCode);
            return tagMapper.selectActiveByCode(orderId, tagCode);
        }
    }

    /**
     * 结论标互斥（期 1.5）：把该单身上**其他** active 的「结论」档标置为已解除（{@code active=0}）。
     *
     * <p>语义见 {@link OrderAuditTagMapper#supersedeOtherConclusionTags}：结论标描述"当前判定结论"，
     * 当前结论只有一个 ⇒ 新结论落地时旧结论必须让位。<b>不是删除</b>：行留着，
     * {@code released_at} + {@code released_by=SYSTEM:SUPERSEDED} 说明"它什么时候因为什么不再成立"。
     *
     * <p>只动「结论」档（join 标目录判 category）：特征标（客诉客户/双地址…）与"过没过"无关，
     * 一单可以既刷单又客诉，**一律不碰**。
     *
     * <p>调用方必须在**同一事务**里紧接着写新标（{@code AuditExecutor#settleConclusionTag} 就是这么做的）：
     * 否则中途失败会留下"一个结论标都没有"的空档 —— 那比"两个"更糟，因为它是沉默的。
     *
     * @param keepTagCode 本次要落的新结论标码（自己不能被自己解除）
     * @return 被取代的行数（0 = 本来就没有别的结论标，这是最常见的正常情况）
     */
    @Transactional(rollbackFor = Exception.class)
    public int supersedeOtherConclusionTags(Long orderId, String keepTagCode) {
        int rows = tagMapper.supersedeOtherConclusionTags(orderId, keepTagCode, RELEASED_BY_SUPERSEDED);
        if (rows > 0) {
            log.info("订单 {} 的旧结论标已让位给 {}（{} 条，released_by={}）",
                    orderId, keepTagCode, rows, RELEASED_BY_SUPERSEDED);
        }
        return rows;
    }

    /** 解标：返回影响行数（0 = 本来就没这个 active 标） */
    @Transactional(rollbackFor = Exception.class)
    public int release(Long orderId, String tagCode, String releasedBy) {
        return tagMapper.release(orderId, tagCode, releasedBy == null ? "system" : clip(releasedBy, 64));
    }

    // ================================================================== 人工打标（期 1）

    /**
     * 人工打一个标（特征标：客诉客户 / 双地址 / 绿旗急发…），并写审计。
     *
     * <h3>为什么这条路必须存在</h3>
     * 标目录从第一天就允许「新增自定义值」（{@link AuditTagCatalogService#createCustom}），
     * 但如果没有任何地方能把新增出来的标打到单上，那新增就是白新增 ——
     * 人工遇到新情况仍然只能写自由文本，又回到 {@code reason="1231"} 那个灾难。
     *
     * <h3>三条硬约束（缺一条这条路就会变成新的漏洞）</h3>
     * <ol>
     *   <li><b>tag_code 必须来自标目录</b>（内置或刚新增的）。自由文本不能当 code ——
     *       那正是标目录要消灭的东西；</li>
     *   <li><b>幂等</b>：同一单同一 code 已有 active 标就不产生第二行、不报错
     *       （复用 {@link #ensureActive} + {@code uk_active}）；</li>
     *   <li><b>必须写审计</b>（{@code AUDIT_TAG_ADD}）：谁在什么时候给哪张单打了什么标。
     *       没有出处的标比没有标更糟 —— 冻结那边今天刚吃过这个教训。</li>
     * </ol>
     *
     * <p>⚠️ <b>不许人工打「结论」标</b>：结论标是判定链的产物，谁判出来的、落什么码由引擎决定。
     * 人工手动打一个 {@code BLOCKED} 上去，等于伪造了一条判定结论的证据；
     * 想按住这单请用冻结（{@code POST …/freeze}），那才是有原因码、有审计、能解冻的动作。
     *
     * @param tagCode 标码（必须已在目录里且启用）
     * @param note    备注（可选；进 {@code order_audit_tag.reason} 与审计摘要）
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderAuditTag applyManualTag(Order order, String tagCode, String note) {
        String code = tagCode == null ? "" : tagCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("必须指定标码（tagCode）");
        }
        AuditTagCatalog def = tagCatalog.byCode(code);
        if (def == null) {
            throw new IllegalArgumentException("标目录里没有标码「" + code
                    + "」。先在标字典里新增一条（POST /admin/order-audit/tags），再来打标 —— "
                    + "自由文本不能当标码（那正是标目录要消灭的东西）");
        }
        if (def.getEnabled() == null || def.getEnabled() != 1) {
            throw new IllegalArgumentException("标「" + def.getName() + "」（" + code + "）已停用，不能再往上打");
        }
        if (AuditTagCatalogService.CATEGORY_CONCLUSION.equals(def.getCategory())) {
            throw new IllegalArgumentException("「" + def.getName() + "」是**结论**标，由判定链自动落，人工不能手动打。"
                    + "要按住这单请用冻结（会带原因码、留审计、可解冻）；要给单挂个说法请新增一条「特征」标");
        }

        String human = note != null && !note.isBlank() ? note.trim() : def.getName();
        OrderAuditTag before = tagMapper.selectActiveByCode(order.getId(), code);
        OrderAuditTag tag = ensureActive(order.getId(), code, human);
        if (before == null) {
            // 摘要顺序：标码 → 中文名 → 备注（机器要断言的靠前，人写的靠后；255 从尾部裁）
            String summary = "给订单 " + order.getOrderSn() + " 打标：标=" + code
                    + "，中文名=" + def.getName()
                    + (note != null && !note.isBlank() && !note.trim().equals(def.getName())
                        ? "（备注：" + note.trim() + "）" : "");
            auditWriter.record(new AdminAuditWriter.Event(
                    ReasonCodes.ACTION_AUDIT_TAG_ADD, ReasonCodes.TARGET_ORDER, order.getId(), order.getOrderSn(),
                    clip(summary, 255),
                    null, order.getId(),
                    AdminAuditWriter.snapshot("tagActive", Boolean.FALSE),
                    AdminAuditWriter.snapshot("tagActive", Boolean.TRUE, "tag", code,
                            "tagName", def.getName(), "category", def.getCategory(), "note", human)));
            log.info("订单 {} 已打标：{}（{}）", order.getOrderSn(), code, def.getName());
        }
        return tag;
    }

    // ================================================================== 冻结 / 解冻（写审计）

    /**
     * 冻结（旧签名：只有一句人话，没有原因码）。
     *
     * <p>保留它是因为调用方还在用（脚本、集成测试、以及"人工填一句备注"的旧前端）。
     * 它的 {@code reason} 会被当成**备注**，原因码按人话关键词反查；
     * 反查不到就落到「未分类（待人工归类）」—— 那是一条 {@code selectable=0} 但报表可见的兜底，
     * 不是静默丢弃。新代码请用带 {@code reasonCode} 的那个重载。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderAuditTag freeze(Order order, String tagCode, String reason, String source) {
        return freeze(order, tagCode, null, reason, source);
    }

    /**
     * 冻结：打 FROZEN 类标（带原因字典）+ 写审计。
     *
     * @param reasonCode 原因码（字典 {@code code}）：新前端下拉与策略 {@code action_json} 都给这个
     * @param note       备注（运营手打的那句人话，可为空）
     * @param source     冻结来源（「自动审核：策略X」/「人工」），进审计摘要 ——
     *                   事后最常问的就是「这单是谁冻的、凭什么冻的」。
     *
     * <h3>三样东西落在哪（别混）</h3>
     * <ul>
     *   <li>{@code reason_id} / {@code reason_code}：**键**。统计与 P1 豁免靠它；</li>
     *   <li>{@code reason} 列：**人话快照** —— 备注优先，没备注就写字典中文名
     *       （历史行是迁移前的原文，从不改写）；</li>
     *   <li>{@code admin_audit_log}：冻结那一刻的完整人话（码 + 中文名 + 备注），
     *       字典改名后仍能回答"当时这条原因叫什么"。</li>
     * </ul>
     *
     * <h3>⚠️ 审计摘要的字段顺序是契约：原因码 → 中文名 → 备注</h3>
     * {@code AdminAuditWriter} 会把 summary 裁到 255 字符（{@code LedgerText.clip}，254 + "…"，
     * 不抛异常）。若把原因码放在句尾，一句长备注就能把它挤掉 —— 于是压在这句话上的断言会随机变红，
     * 而冻结其实成功了（假故障，最难查）。规则：**机器要断言的靠前、人写的靠后**。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderAuditTag freeze(Order order, String tagCode, String reasonCode, String note, String source) {
        String tag = tagCode == null || tagCode.isBlank() ? TAG_FROZEN : tagCode;
        boolean frozenTag = tag.startsWith(TAG_FROZEN);

        // 只有冻结类标才解析原因：STOCK_SHORT / COST_MISSING 的 reason 是"留人工的原因"，不是冻结原因
        AuditFreezeReasonService.Resolved resolved = frozenTag
                ? reasonDict.resolveForWrite(reasonCode, note, AuditFreezeReasonService.CODE_UNCLASSIFIED)
                : null;

        // reason 列 = 人话快照：备注优先；没备注就写字典中文名（人查库时不必 join 也看得懂）
        String human = note != null && !note.isBlank()
                ? note.trim()
                : (resolved != null && resolved.ok() ? resolved.name() : null);

        OrderAuditTag before = tagMapper.selectActiveByCode(order.getId(), tag);
        OrderAuditTag tag0 = ensureActive(order.getId(), tag, human, resolved);
        if (before == null) {
            String summary = freezeSummary(order, tag, source, resolved, note);
            auditWriter.record(new AdminAuditWriter.Event(
                    ReasonCodes.ACTION_AUDIT_FREEZE, ReasonCodes.TARGET_ORDER, order.getId(), order.getOrderSn(),
                    summary,
                    null, order.getId(),
                    AdminAuditWriter.snapshot("frozen", Boolean.FALSE),
                    // 结构化的权威落点：不受 summary 的 255 限制，码与名字都在这儿
                    AdminAuditWriter.snapshot(
                            "frozen", Boolean.TRUE,
                            "tag", tag,
                            "reason", human,
                            "reason_code", resolved == null ? null : resolved.code(),
                            "reason_name", resolved == null ? null : resolved.name())));
            log.info("订单 {} 已冻结：tag={} source={} code={} reason={}",
                    order.getOrderSn(), tag, source,
                    resolved == null ? null : resolved.code(), human);
        }
        return tag0;
    }

    /** 摘要句：顺序固定「原因码 → 中文名 → 备注」（见 freeze 的注释，别调换） */
    private static String freezeSummary(Order order, String tag, String source,
                                        AuditFreezeReasonService.Resolved resolved, String note) {
        StringBuilder sb = new StringBuilder("冻结订单 ").append(order.getOrderSn()).append("：");
        if (source != null && !source.isBlank()) sb.append(source).append("，");
        sb.append("标=").append(tag);
        if (resolved != null) {
            sb.append("，原因码=").append(resolved.ok() ? resolved.code() : "<未知>");
            sb.append("，原因=").append(resolved.ok() ? resolved.name() : "<未知>");
        }
        // 备注放在**最后**：它最可能很长、也最不参与断言（255 从尾部裁，裁掉的只能允许是它）
        boolean noteWorthTelling = note != null && !note.isBlank()
                && (resolved == null || !resolved.ok() || !note.trim().equals(resolved.name()));
        if (noteWorthTelling) sb.append("（补充：").append(note.trim()).append("）");
        return sb.toString();
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
