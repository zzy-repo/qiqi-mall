package com.qiqi.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.audit.engine.PolicyView;
import com.qiqi.mall.audit.entity.AuditRun;
import com.qiqi.mall.audit.explain.AuditExplainLayers;
import com.qiqi.mall.audit.explain.PolicyScanAssembler;
import com.qiqi.mall.audit.mapper.AuditRunMapper;
import com.qiqi.mall.audit.mapper.AuditTagCatalogMapper;
import com.qiqi.mall.audit.mapper.AuditTrailMapper;
import com.qiqi.mall.audit.mapper.OrderAuditDecisionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 审核模块的只读查询：人工队列 / run 列表 / 单张订单的审核时间线。
 *
 * <p>时间线把三段证据按时间拼起来（决策 + {@code order_status_ledger} + {@code admin_audit_log}）：
 * <ul>
 *   <li>决策：怎么判的（结论、命中策略、原因原文）；</li>
 *   <li>状态台账：状态真的变了吗（7→8 的 reason = AUDIT_AUTO_PASS）；</li>
 *   <li>操作审计：谁点的（人工冻结/解冻、策略改动）。</li>
 * </ul>
 * 三段缺一段，「这单为什么现在是这个样子」就答不完整 —— 所以不是三个接口，是一个时间线。
 */
@Service
public class AuditQueryService {

    private static final TypeReference<Object> JSON_OBJECT = new TypeReference<>() {
    };

    private final OrderAuditDecisionMapper decisionMapper;
    private final AuditRunMapper runMapper;
    private final AuditTrailMapper trailMapper;
    private final ObjectMapper json;
    /** 冻结原因字典：把队列行的 {@code reason_id} 翻成中文名/原因码（整表在内存，零成本） */
    private final AuditFreezeReasonService freezeReasonService;
    /** 标目录：把 {@code tag_code} 翻成中文名 + 一句话说明（同样整表在内存） */
    private final AuditTagCatalogService tagCatalog;
    /**
     * 标目录<b>直查库</b>版：只用于对账端点取「终局结论标规则从哪一刻起生效」。
     *
     * <p>为什么不走 {@link #tagCatalog} 的内存缓存：那份副本是 per-instance 的（启动装载 + TTL 5s），
     * 而"规则生效时刻"是一个**判据**（用它区分历史单与真缺口）。判据读过期副本会算错，
     * 与下发保险踩过的坑同源（见 {@code AuditTagCatalogService} 类注释）。
     */
    private final AuditTagCatalogMapper tagCatalogMapper;
    /**
     * 策略服务：<b>只读</b>用来取「策略 ID → 优先级」。
     *
     * <p>为什么队列要读策略：{@code layers} 的第一层排序（五级字典序）第 4 级就是 priority，
     * 第三层矩阵的第一排序键也是它。而决策行里<b>没有</b>存 priority（那是策略配置，不是判定事实），
     * 所以只能现取。取不到就让组装器退回缺省值 —— 排序仍然确定（policyId 兜底），
     * 不会因为一次读失败变成随机顺序，更不该让整张队列 500。
     */
    private final AuditPolicyService policyService;

    public AuditQueryService(OrderAuditDecisionMapper decisionMapper, AuditRunMapper runMapper,
                             AuditTrailMapper trailMapper, ObjectMapper json,
                             AuditFreezeReasonService freezeReasonService,
                             AuditTagCatalogService tagCatalog,
                             AuditTagCatalogMapper tagCatalogMapper,
                             AuditPolicyService policyService) {
        this.decisionMapper = decisionMapper;
        this.runMapper = runMapper;
        this.trailMapper = trailMapper;
        this.json = json;
        this.freezeReasonService = freezeReasonService;
        this.tagCatalog = tagCatalog;
        this.tagCatalogMapper = tagCatalogMapper;
        this.policyService = policyService;
    }

    // ================================================================ 人工队列

    /**
     * 人工队列（每单最新一轮），{@code result} 可为空表示不过滤。
     *
     * <p>每行带两个**互相独立**的事实（见 {@link #queueRow} 的注释）：
     * {@code result}（引擎这一轮判了什么）与 {@code frozen} + {@code frozenReason}
     * （这单当前是否挂着 active 的冻结标）。人工冻的单不产生新决策行，只有后者能说明它冻着。
     */
    public Map<String, Object> queue(String result, int page, int size) {
        return queue(result, null, page, size);
    }

    /**
     * 人工队列（每单最新一轮），{@code result} / {@code tag} 均可为空表示不过滤。
     *
     * <p>两个筛选条件是**正交、可并列**的：
     * <ul>
     *   <li>{@code result}：引擎这一轮判了什么（全部/自动过审/留人工/阻止/冻结/不命中 —— 本期**语义不变**，期 3 才改）；</li>
     *   <li>{@code tag}：这单现在挂着哪些**标**（{@code ?tag=NO_POLICY_MATCH}）。
     *       这是期 1 新增的**平行**入口：业务人员按标找单，不需要先知道 result 码怎么拼。</li>
     * </ul>
     *
     * <p>每行带三段互相独立的事实（见 {@link #queueRow} 的注释）：
     * {@code result}（引擎这一轮判了什么）、{@code frozen} + {@code frozenReason}（这单当前是否挂着
     * active 冻结标）、{@code tags}（这单当前身上**全部** active 标，带中文名与打标时间）。
     */
    public Map<String, Object> queue(String result, String tag, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);   // 与 MybatisPlusConfig.maxLimit 同口径，防 size=999999
        String filter = result == null || result.isBlank() ? null : result.trim().toUpperCase(Locale.ROOT);
        String tagFilter = tag == null || tag.isBlank() ? null : tag.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = decisionMapper.selectQueue(filter, tagFilter, (p - 1) * s, s);
        long total = decisionMapper.countQueue(filter, tagFilter);
        Map<Long, List<ActiveTag>> tagsByOrder = tagsOf(rows);
        // 优先级映射：整页只取一次（策略配置是内存级的小表，不逐行回表）
        Map<Long, Integer> priorities = prioritiesById();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows.stream()
                // 队列行的来源恒为 order_audit_decision ⇒ hasDecision=true（这是"影子字段"以外的事实）
                .map(r -> queueRow(r, tagsByOrder.get(asLong(r.get("order_id"))), true, priorities))
                .toList());
        return out;
    }

    /**
     * 一个 active 标的**内部**视图。
     *
     * <p>比队列行里给出的 {@code tags[i]} 多一个 {@code id}：期 3 的"主标"压缩规则
     * （<b>阻断型优先 → 同类型取最新</b>）需要"最新"这个可判定的量，而 {@code id} 单调，
     * 是比 {@code created_at}（毫秒截断、同时刻多标并列）更硬的排序键。
     * <p>⚠️ {@code id} 只在服务端排序用，<b>不出现在返回体里</b> —— 它是跨库不稳定的代理键，
     * 界面上、接口上一律用 {@code tagCode}。
     */
    private record ActiveTag(long id, String code, String name, String description,
                             String category, boolean blocking, boolean visible,
                             String reason, String at) {
    }

    /**
     * 本页这些订单的 active 标（**一次批量查询**，不是逐行查、也不是 JOIN 复制行）。
     *
     * @return {@code orderId → [标]}（同一单内按 {@code id} 升序，即"越往后越新"）；
     *         没标的不出现在 map 里（调用方给空列表）
     */
    private Map<Long, List<ActiveTag>> tagsOf(List<Map<String, Object>> rows) {
        return activeTagsOf(rows.stream()
                .map(r -> asLong(r.get("order_id")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());
    }

    /** 按订单 id 批量取 active 标（队列 / 对账 / 单笔影子字段共用这一条） */
    private Map<Long, List<ActiveTag>> activeTagsOf(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, List<ActiveTag>> out = new LinkedHashMap<>();
        for (Map<String, Object> t : decisionMapper.selectActiveTagsByOrders(ids)) {
            Long orderId = asLong(t.get("order_id"));
            if (orderId == null) continue;
            String code = t.get("tag_code") == null ? null : String.valueOf(t.get("tag_code"));
            AuditTagCatalogService.TagInfo info = tagCatalog.info(code);
            // 目录里查不到的标码不是错误 —— 它允许先落库、目录后补，这里降级成
            // 「中文名=原始码 + description=null + visible=true（当作可见）」，绝不许因为一条没登记的标
            // 把整张队列打成空白。（visible 缺省 true 与 AuditQueryService 既有的 `info == null || info.visible()`
            // 同口径：没登记 ≠ 隐藏。）
            // id 只在服务端排序用（"同类型取最新"）。理论上必有（主键列），缺了降级成 0 而不是抛 ——
            // 一条排序键读不到不该把整张队列打成 500。
            Long rawId = asLong(t.get("id"));
            out.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new ActiveTag(
                    rawId == null ? 0L : rawId,
                    code,
                    tagCatalog.nameOf(code),                       // 查不到时回原始码（永不 null）
                    info == null ? null : info.description(),
                    info == null ? null : info.category(),
                    info != null && info.blocking(),
                    info == null || info.visible(),
                    t.get("reason") == null ? null : String.valueOf(t.get("reason")),
                    fmtTagTime(t.get("created_at"))));
        }
        return out;
    }

    /**
     * 内部视图 → 队列行里的 {@code tags[i]}（键名走驼峰，前端只认一份）。
     *
     * <p>⚠️ 这个形状是**显示用**的（{@code visible} 也在里面，前端可以用它再判一次）。
     * 但队列行给的 {@code tags} / {@code tagCodes} 已经按 {@code visible} 过滤过 ——
     * 见 {@link #queueRow} 的注释：{@code tags} = 可展示的标，{@code allTagCodes} = 全部 active 标。
     */
    private static Map<String, Object> tagItem(ActiveTag t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tagCode", t.code());
        item.put("name", t.name());
        item.put("description", t.description());
        item.put("category", t.category());
        item.put("blocking", t.blocking());
        item.put("visible", t.visible());
        item.put("reason", t.reason());
        item.put("at", t.at());
        return item;
    }

    /**
     * 策略 ID → 优先级（整页一份）。读失败返回空映射（{@code layers} 退回缺省优先级），
     * <b>不抛</b>：一张策略配置读不出来不该让整张人工队列打不开。
     */
    private Map<Long, Integer> prioritiesById() {
        try {
            Map<Long, Integer> out = new LinkedHashMap<>();
            for (PolicyView p : policyService.loadEnginePolicies()) {
                if (p != null && p.id() != null) out.putIfAbsent(p.id(), p.priority());
            }
            return out;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** 打标时间统一成 {@code yyyy-MM-dd HH:mm:ss}：抽屉里直接显示，不把 JDBC 的毫秒尾巴丢给页面 */
    private static final java.time.format.DateTimeFormatter TAG_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 打标时间归一（MyBatis 可能回 {@code LocalDateTime} / {@code Timestamp} / 字符串）。
     * 解析不出来就回 {@code null} 而不是抛：一条时间格式异常不该让整张队列 500。
     */
    private static String fmtTagTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof java.time.LocalDateTime ldt) return TAG_TIME.format(ldt);
        if (raw instanceof java.sql.Timestamp ts) return TAG_TIME.format(ts.toLocalDateTime());
        String text = String.valueOf(raw).trim();
        if (text.length() >= 19) {
            try {
                return TAG_TIME.format(java.time.LocalDateTime.parse(text.substring(0, 19).replace(' ', 'T')));
            } catch (java.time.format.DateTimeParseException e) {
                return text;
            }
        }
        return text;
    }

    /**
     * 队列行整形：把四列 JSON 证据<b>解析成结构</b>再返回。
     *
     * <p>为什么不能直接把 JSON 列当字符串丢给前端：页面要判断「后端有没有留痕」，
     * 判据是 {@code Array.isArray(row.miss_reasons)}。字符串（{@code "[{...}]"}）不是数组，
     * 前端于是永远走「本地对照」降级路径 —— 数据明明在，页面却说没回传。
     * 所以解析放在这里（唯一知道这些列语义的地方），并按前端读的键名给出。
     */
    private Map<String, Object> queueRow(Map<String, Object> row, List<ActiveTag> tags, boolean hasDecision,
                                        Map<Long, Integer> priorities) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        // 注意 remove 要在**副本**上做：四个 *_json 原始列换成同名结构，避免同一份证据
        // 既以字符串又以数组出现在同一行里（两份形状会让前端 pick() 随键名不同取到不同东西）
        out.put("miss_reasons", readJson(out.remove("miss_reason_json")));
        out.put("matched_conditions", readJson(out.remove("matched_condition_json")));
        out.put("excluded_by", readJson(out.remove("excluded_by_json")));
        out.put("evidence", readJson(out.remove("evidence_json")));
        // ---------------- 期 4：三层判定依据（第一屏只说一条，矩阵收进第三层） ----------------
        // **纯新增**：上面四列 JSON 与 result 一个字都没动（页面与 e2e 仍在读它们）。
        // 为什么在这里算而不是新开一个接口：抽屉的数据就是这一行 —— 另开一个接口等于让页面
        // 为同一份证据打第二次往返，两边的「由谁算」迟早漂移（见 PolicyScanAssembler 类注释）。
        // 算不出来的情况（老行没留痕 / 脏 JSON）由组装器兜底成「无可用策略」那一层，不抛、不空白。
        // 这里先拿**带类型**的三层对象、再交给组装器转 JSON，而不是一步 toMap：
        // ① 类型是这份口径的锚（第一层没有 conditions 字段不是靠自觉，是这个 record 里压根没有）；
        // ② 第二层明细（items）要和 notApplicableTotal 同源，只能从同一个对象上取。
        AuditExplainLayers.ExplainLayers layers = PolicyScanAssembler.layersOfQueueRow(out, priorities);
        out.put("layers", PolicyScanAssembler.layersJson(layers));
        // 冻结事实：**永远是 boolean**（不是 1/0、不是 null）。
        // 「这单现在冻着没有」必须是一个确定答案，前端不该去猜 null 是"没查"还是"没冻"。
        out.put("frozen", toBoolean(out.remove("frozen")));
        out.put("frozenReason", out.remove("frozen_reason"));
        out.put("frozenTagCode", out.remove("frozen_tag_code"));
        // 原因字典（P2）：id 只用来翻名字，**不回给前端**（跨库不稳定的代理键，界面上一律用 code）
        // frozenReasonName 对**历史行**同样有效：老行的 reason 还是 1231/111 这种原文，
        // 但它已经被回填了 reason_id，所以能显示「历史未填（占位）」—— 两个都在，谁也不掩盖谁。
        Long reasonId = asLong(out.remove("frozen_reason_id"));
        out.put("frozenReasonCode", freezeReasonService.codeOf(reasonId));
        out.put("frozenReasonName", freezeReasonService.nameOf(reasonId));
        List<ActiveTag> safeTags = tags == null ? List.of() : tags;

        // ---------------- 期 1：这单身上的标（带中文名 + 一句话说明 + 打标时间） ----------------
        // ⚠️ 键名走驼峰（tags / tagCodes），与 frozenReason 等既有驼峰字段一致 ——
        //    上一轮漏了 snake→camel 这一步，前端拿不到值且不报错，是最难查的那种空白。
        // ⚠️ 数据层**没有主标**：不排 is_primary、不进字典。下面推出来的 primaryTag* 是
        //    **显示层压缩的副本**，随时可以重算，不是判据（谁拿它当事实源谁就制造了第二套口径）。
        //
        // ★★ 期 3 步骤 1：`tags` / `tagCodes` 的口径变了，必须记死（别再搞混）★★
        //      tags / tagCodes = **可展示的标**（catalog.visible = 1）—— 队列行"渲染输入"就这一份，
        //                        所以 visible=0 的标（如 AUTO_PASSED）**不会**出现在页面上。
        //      allTagCodes     = **这单身上到底有什么标**（全部 active，含 visible=0）——
        //                        任何"数据判断"（统计、对账、下发保险、"这单有没有被冻结过"…）
        //                        **只能读这个**，绝不能读 tags/tagCodes（那会漏掉 visible=0 的标）。
        //    为什么不再给一个 allTags：多一份同构结构就多一处漂移；判据需要的是**码**，
        //    中文名/说明这类展示字段对非展示标没有意义（AUTO_PASSED 光一个码就说清了）。
        List<Map<String, Object>> displayTags = safeTags.stream()
                .filter(ActiveTag::visible)
                .map(AuditQueryService::tagItem)
                .toList();
        out.put("tags", displayTags);
        out.put("tagCodes", displayTags.stream().map(t -> t.get("tagCode")).toList());
        out.put("allTagCodes", safeTags.stream().map(ActiveTag::code).toList());

        // ---------------- 期 3 步骤 2：影子字段（"如果结论从标读，会是什么"） ----------------
        // ⚠️ **新增，不是替换**：`result` 一个字都没动（页签计数 / 筛选 / e2e 全依赖它）。
        //    本组字段今天的唯一消费者是「一致性对账」与排障；页面仍读 result ⇒ 零可见行为变化。
        ConclusionShadow shadow = conclusionShadow(safeTags, hasDecision);
        out.put("conclusionFromTag", shadow.fromTag());
        out.put("conclusionSource", shadow.source());
        // ★ V9：结算事实标（今天只有 RETURN_APPLIED「已申请退货」）。它回答的不是"这一轮判成了什么"
        //   （那是 result / conclusionFromTag），而是"这单进了退货流程"这条事实。
        //   两者**取值域不同、也不该相等**（result=NO_MATCH + 已申请退货完全正常），
        //   所以单开一个字段而不是硬塞进 conclusionFromTag —— 硬塞就得给它编一个最像的
        //   AuditResult，那是伪造结论，会让对账把"两边都对"算成"冲突"。
        //   ⚠️ 期 3 步骤 2.5 这里原本摆着 AUDIT_PASSED / AUDIT_REJECTED 两枚"过没过"标，
        //   已由 V9 按用户 16:38 的拍板停用（通过与否由状态表达，不由标表达）。
        out.put("conclusionSettlementFact", shadow.settlementFact());
        // 数据层事实：这单身上**全部** active 的结论标码（含 visible=0 的 AUTO_PASSED），按 id 升序
        out.put("conclusionTagCodes", shadow.tagCodes());
        // 一单多条 active 结论标（理论不该有）时为 true —— 让它在接口上"看得见"，而不是被静默压成一条
        out.put("conclusionTagMultiple", shadow.multiple());
        // 显示层压缩后的"主标"（阻断型优先 → 同类型取最新；只在**可见**标里挑）
        ActiveTag primary = primaryTag(safeTags);
        out.put("primaryTagCode", primary == null ? null : primary.code());
        out.put("primaryTagName", primary == null ? null : primary.name());
        return out;
    }

    // ============================================================ 期 3：影子字段（标 → 结论）

    /**
     * 「如果结论从标读，会是什么」的结果（期 3 起；V9 之后只剩"判定结论 + 一枚结算事实标"）。
     *
     * @param fromTag     <b>判定轴</b>：从 active 结论标反推出来的结论值（取值域与 {@code result} 完全相同：
     *                    {@code AUTO_PASS / MANUAL / BLOCKED / FROZEN / NO_MATCH}）；
     *                    {@code null} = 判定轴上没有标，或标码在已知映射之外
     * @param settlementFact <b>结算事实标</b>：离开待审核之后发生的那件值得注意的事，今天只可能是
     *                    {@code RETURN_APPLIED}（已申请退货）；{@code null} = 身上没有它。
     *                    <p>V9（2026-11-16）把它从"终局轴"缩成一枚事实标：原先那两枚
     *                    {@code AUDIT_PASSED / AUDIT_REJECTED} 回答的是"过没过"，已按用户 16:38 的拍板停用
     *                    （「通过与否由状态表达，不由标表达」）。它仍<b>不与 result 比</b>：
     *                    {@code result=NO_MATCH} + 已申请退货同时成立完全正常。
     * @param source      结论的来路：{@code TAG}（判定轴读得出）/ {@code SETTLEMENT_FACT}（只有结算事实标：
     *                    答案确定但与 result 不可比）/ {@code DECISION}
     *                    （标里没有，只能退回决策行 —— 终局单与历史单的如实回答）/ {@code NONE}
     *                    （连决策行都没有 —— 这张单**从未被判定过**）
     * @param tagCodes    这单身上全部 active 结论标码（按 id 升序）
     * @param multiple    是否一单多条 active 结论标（理论不该有；出现就说明互斥漏了某条路径）
     */
    private record ConclusionShadow(String fromTag,
                                    String settlementFact,
                                    String source,
                                    List<String> tagCodes,
                                    boolean multiple) {
    }

    /**
     * 从这一单的 active 标反推"当前结论"。<b>纯函数、不查库</b> —— 对账端点与队列行用的是同一份实现，
     * 所以"页面上看到的影子结论"与"对账报出来的不一致数"永远同源（两份实现必然漂移）。
     *
     * <h3>多条结论标时怎么取（期 3 步骤 2 的硬要求）</h3>
     * 一单理论只有一个 active 结论标（期 1.5/1.6 的互斥规则），但历史遗留可能有两条。
     * 这里**不撞 LIMIT 1、不抛异常、不静默取第一条**，而是按显示层同一条规则取一条：
     * <b>阻断型优先 → id 最大</b>（{@code blocking=1} 的结论标先说话，其次最新的那条）。
     * 同时把 {@code multiple=true} 与全部码给出去 —— 压成一条是"取哪条"的答案，
     * "一单有两条"这个事实本身不许被压掉。
     *
     * <h3>为什么从标读不出结论时，不拿 NO_MATCH 冒充</h3>
     * 没有结论标 ⇒ 标里就是"没有结论"，如实回 {@code null} + {@code source=DECISION/NONE}。
     * 拿 NO_MATCH 兜底会把"这单从没被判定过"说成"这单没有策略命中" —— 那是**伪造结论**，
     * 对账会因此把这批单算成"一致"，风险测绘当场失效。
     *
     * @param hasDecision 这张单有没有任何决策行（true ⇒ 至少是个"判定过"的单）
     */
    private ConclusionShadow conclusionShadow(List<ActiveTag> tags, boolean hasDecision) {
        List<ActiveTag> conclusionTags = new ArrayList<>();
        for (ActiveTag t : tags) {
            if (isConclusionTag(t)) conclusionTags.add(t);
        }
        List<String> codes = conclusionTags.stream().map(ActiveTag::code).toList();
        if (conclusionTags.isEmpty()) {
            return new ConclusionShadow(null, null, hasDecision ? "DECISION" : "NONE", List.of(), false);
        }
        boolean multiple = conclusionTags.size() > 1;

        // ---------------- 判定结论：这一轮引擎判成了什么（与 result 必须对得上） ----------------
        // ★ V9：结算事实标（今天只有 RETURN_APPLIED 一枚）**不进这一栏** ——
        //   它回答的是"这单进了退货流程"，与 result 是两个问题（见 ConclusionTags#isSettlementFact）。
        List<ActiveTag> verdictTags = new ArrayList<>();
        for (ActiveTag t : conclusionTags) {
            if (!ConclusionTags.isSettlementFact(t.code())) verdictTags.add(t);
        }
        String mapped = null;
        if (!verdictTags.isEmpty()) {
            // 阻断型优先 → id 最大（与 primaryTag 同一条规则，只是范围收窄到"判定结论标"）
            ActiveTag chosen = verdictTags.stream()
                    .max(java.util.Comparator.comparing(ActiveTag::blocking).thenComparingLong(ActiveTag::id))
                    .orElse(null);
            mapped = chosen == null ? null : resultOfTagCode(chosen.code());
        }

        // ---------------- 结算事实标：离开待审核之后发生的那件事（今天只有"已申请退货"） ----------------
        // 理论最多一条（同一枚码 + uk_active）；历史遗留有多条时取 id 最大的那条 ——
        // "取哪条"必须是可判定的规则，不能撞 LIMIT 1 随机取。
        ActiveTag factTag = conclusionTags.stream()
                .filter(t -> ConclusionTags.isSettlementFact(t.code()))
                .max(java.util.Comparator.comparingLong(ActiveTag::id))
                .orElse(null);
        String fact = factTag == null ? null : factTag.code();

        // 来源：有判定结论就报 TAG（它可能与 result 冲突，那正是 valueMismatch 要抓的）；
        //       只有事实标时报 SETTLEMENT_FACT（**不与 result 比**，见对账端点）；两者都没有才是 DECISION/NONE。
        String source = !verdictTags.isEmpty() ? "TAG"
                : (fact != null ? "SETTLEMENT_FACT" : (hasDecision ? "DECISION" : "NONE"));
        return new ConclusionShadow(mapped, fact, source, codes, multiple);
    }


    /**
     * 这个标算不算「结论」档：目录 {@code category='结论'} <b>外加</b> FROZEN 前缀兜底。
     *
     * <p>兜底的理由与 {@code OrderAuditTagService#isConclusionTag} 完全相同：策略可以配细分冻结码
     * （{@code FROZEN_RETURN_APPLY}…），它今天还没登记进标目录 —— 只认目录会把"冻着的单"看成"没有结论"。
     * 反过来，目录里查不到的**其它**码（还没补登记的特征标）不算结论标，与既有口径一致。
     */
    private static boolean isConclusionTag(ActiveTag t) {
        if (t.code() == null) return false;
        if (t.code().startsWith(AuditTagCatalogService.CODE_FROZEN)) return true;
        return AuditTagCatalogService.CATEGORY_CONCLUSION.equals(t.category());
    }

    /**
     * 标码 → 结论值（{@link ConclusionTags#codeOf} 的<b>反函数</b>）。
     *
     * <p>为什么放在 {@link ConclusionTags} 而不是这里：正向映射只有一处，反向映射也必须只有一处，
     * 否则加一类结论时"正向改了、反向忘改"，影子字段就会算出一个**不存在的结论**。
     *
     * @return {@code AUTO_PASS / MANUAL / BLOCKED / FROZEN / NO_MATCH}；不认识的码回 {@code null}
     *         （宁可承认"读不出"，也不猜一个最像的）
     */
    private static String resultOfTagCode(String code) {
        return ConclusionTags.resultOf(code).map(Enum::name).orElse(null);
    }

    /**
     * 显示层的"主标"：在**可见**标里按「阻断型优先 → 同类型取最新」取第一条。
     *
     * <p>⚠️ 这是**显示规则的副本**（前端 {@code displayTags} 是同一套规则），只为让"页面上第一枚标是谁"
     * 在接口上可断言、可对账。数据层仍然是并列多标 —— 没有主标列，也不许有人把它写回库。
     * <p>只用可见标：visible=0 的标既然不显示，就不可能是"页面上那枚主标"。
     */
    private static ActiveTag primaryTag(List<ActiveTag> tags) {
        return tags.stream()
                .filter(ActiveTag::visible)
                .max(java.util.Comparator.comparing(ActiveTag::blocking).thenComparingLong(ActiveTag::id))
                .orElse(null);
    }

    /** MySQL 的 EXISTS 回来是 0/1（有时是字符串），统一收敛成 boolean；缺列/空值 = false */
    /** 宽松 Long 收敛：MySQL 的 UNSIGNED 派生列会回 BigInteger，null 保持 null */
    private static Long asLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean toBoolean(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        String text = String.valueOf(raw).trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private Object readJson(Object raw) {
        if (raw == null) return null;
        String text = raw instanceof String s ? s : String.valueOf(raw);
        if (text.isBlank()) return null;
        try {
            return json.readValue(text, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            // 列里是脏 JSON：不猜内容，原样以字符串回传（至少不丢证据），并保持「不是数组」= 前端走降级
            return text;
        }
    }

    // ==================================================== 期 3：一致性对账（纯只读）

    /**
     * 单张订单的"标 → 结论"影子视图（**不依赖它在不在队列里**）。
     *
     * <p>为什么单独给一个按订单 id 的入口：队列是**决策驱动**的（{@code FROM order_audit_decision}），
     * 所以"从没被判定过"的单压根进不了队列 —— 而"标驱动结论"恰恰要回答这类单会怎样。
     * 用它才能如实证明：<b>没有任何决策行的单，结论来源是 {@code NONE}，而不是被 NO_MATCH 冒充</b>。
     *
     * @return {@code conclusionFromTag / conclusionSource / conclusionTagCodes / conclusionTagMultiple
     *         / primaryTagCode / primaryTagName / tags（可展示的）/ allTagCodes（全部）}
     */
    public Map<String, Object> conclusionShadowOf(Long orderId) {
        List<Long> ids = List.of(orderId);
        List<ActiveTag> tags = activeTagsOf(ids).getOrDefault(orderId, List.of());
        // 有没有任何决策行：复用 selectMaxRound（0 = 从没判定过），不额外造一条 count 查询
        boolean hasDecision = decisionMapper.selectMaxRound(orderId) > 0;
        ConclusionShadow shadow = conclusionShadow(tags, hasDecision);
        ActiveTag primary = primaryTag(tags);
        List<Map<String, Object>> displayTags = tags.stream()
                .filter(ActiveTag::visible).map(AuditQueryService::tagItem).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("hasDecision", hasDecision);
        out.put("conclusionFromTag", shadow.fromTag());
        out.put("conclusionSettlementFact", shadow.settlementFact());
        out.put("conclusionSource", shadow.source());
        out.put("conclusionTagCodes", shadow.tagCodes());
        out.put("conclusionTagMultiple", shadow.multiple());
        out.put("primaryTagCode", primary == null ? null : primary.code());
        out.put("primaryTagName", primary == null ? null : primary.name());
        out.put("allTagCodes", tags.stream().map(ActiveTag::code).toList());
        out.put("tags", displayTags);
        return out;
    }

    /** 对账端点的单页扫描量（队列行本身已是"每单一行"，200 一次就够快，不打爆连接池） */
    private static final int PARITY_SCAN_PAGE = 200;

    /**
     * <b>内部排障工具</b>：把「引擎这轮判了什么（{@code result}）」与「如果结论从标读会是什么」
     * 逐单比一遍，数出不一致的单数并列出前 N 条。
     *
     * <h3>⚠️ 口径迁移已暂停（V9 之后它是纯排障工具，不再进体检）</h3>
     * "结论从标读"这件事本身已被暂停：V9（2026-11-16）收回了那三枚「通过 / 不通过」标，
     * 结算回到「只解不落」。所以本端点<b>不再对任何业务方/体检承诺"零 gap"</b> ——
     * 它保留的唯一用途是：将来若重启口径迁移，先拿它量一遍风险。字段名与分桶继续可用，但：
     * <ul>
     *   <li>离开待审核的单本来就<b>没有</b>结论标（这是 V9 的设计，不是缺陷）⇒ 一律计入
     *       {@code noAuditConclusionByDesign}，<b>不算 gap</b>；</li>
     *   <li>因此不再有"终局标缺失"这一档（{@code terminalTagMissing} 已删除）、
     *       也不再有"规则生效时刻"（{@code terminalRuleSince} 已删除）——
     *       那条判据依赖一枚今天不该存在的标，留着只会天天报红，把端点自己变成假数据。</li>
     * </ul>
     *
     * <h3>还剩下的两个真缺口（都只在"待审核"这一侧）</h3>
     * <ol>
     *   <li>{@code pendingTagMissing}（<b>真缺口</b>）：还停在待审核、已经被判定过，
     *       却一条结论标都没有 ⇒ 引擎漏落了标（AUDIT_PENDING / BLOCKED / NO_POLICY_MATCH …）；</li>
     *   <li>{@code valueMismatch}（<b>真冲突</b>）：判定结论标与 {@code result} 不相等。</li>
     * </ol>
     *
     * <h3>结算事实标（已申请退货）单独计</h3>
     * {@code RETURN_APPLIED} 回答的是"这单进了退货流程"，与 {@code result} 是两个问题、<b>不比</b>，
     * 计入 {@code answeredBySettlementFact}（见 {@link ConclusionTags#isSettlementFact}）。
     *
     * <p>⚠️ 纯只读：一条写语句都没有（不写决策行、不补标、不修数据）。它要能被随手多跑几次。
     *
     * @param limit 列出的不一致明细条数上限（1~100）
     */
    public Map<String, Object> conclusionParity(int limit) {
        int sampleLimit = Math.min(Math.max(1, limit), 100);
        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Integer> byPair = new LinkedHashMap<>();

        long scanned = 0;
        long answeredByTag = 0;
        long answeredBySettlementFact = 0;
        long valueMismatch = 0;
        long noConclusionTag = 0;
        long legacyDecision = 0;
        long noAuditConclusionByDesign = 0;
        long pendingTagMissing = 0;
        long unmappedTagCode = 0;
        long multipleConclusionTags = 0;

        for (int offset = 0; ; offset += PARITY_SCAN_PAGE) {
            List<Map<String, Object>> rows = decisionMapper.selectQueue(null, null, offset, PARITY_SCAN_PAGE);
            if (rows.isEmpty()) break;
            Map<Long, List<ActiveTag>> tagsByOrder = tagsOf(rows);
            // 「每单最后一次离开待审核」那一行台账（本页一次批量查）：用它判断"是怎么离开 7 的"
            List<Long> pageOrderIds = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Long oid = asLong(row.get("order_id"));
                if (oid != null) pageOrderIds.add(oid);
            }
            Map<Long, Map<String, Object>> leaveRows = new LinkedHashMap<>();
            if (!pageOrderIds.isEmpty()) {
                for (Map<String, Object> l : trailMapper.selectLeaveAuditPendingBatch(pageOrderIds)) {
                    Long oid = asLong(l.get("order_id"));
                    if (oid != null) leaveRows.put(oid, l);
                }
            }

            for (Map<String, Object> row : rows) {
                scanned++;
                Long orderId = asLong(row.get("order_id"));
                Object resultRaw = row.get("result");
                String result = resultRaw == null ? null : String.valueOf(resultRaw);
                Integer orderStatus = row.get("order_status") == null ? null
                        : ((Number) row.get("order_status")).intValue();
                // 队列行恒有决策行 ⇒ hasDecision=true（这是队列来源决定的，不是猜的）
                ConclusionShadow shadow = conclusionShadow(tagsByOrder.getOrDefault(orderId, List.of()), true);
                if (shadow.multiple()) multipleConclusionTags++;

                String classification;
                boolean inconsistent;
                if (!shadow.tagCodes().isEmpty()) {
                    if ("SETTLEMENT_FACT".equals(shadow.source())) {
                        // 结算事实标答出来了：标在回答"这单进了退货流程"，与 result 不可比 ⇒ 不算冲突
                        answeredBySettlementFact++;
                        classification = "SETTLEMENT_FACT_TAG";
                        inconsistent = false;
                    } else if (shadow.fromTag() == null) {
                        unmappedTagCode++;
                        classification = "UNMAPPED_TAG_CODE";
                        inconsistent = true;
                    } else if (!shadow.fromTag().equals(result)) {
                        valueMismatch++;
                        classification = "VALUE_MISMATCH";
                        inconsistent = true;
                    } else {
                        answeredByTag++;
                        classification = "TAG";
                        inconsistent = false;
                    }
                } else {
                    // 标里没有结论 —— 这一档必须分清「正常」与「真缺口」
                    noConclusionTag++;
                    classification = missingConclusionBucket(leaveRows.get(orderId), orderStatus);
                    switch (classification) {
                        case "LEGACY_DECISION" -> legacyDecision++;
                        case "NO_AUDIT_CONCLUSION" -> noAuditConclusionByDesign++;
                        default -> pendingTagMissing++;
                    }
                    inconsistent = "PENDING_TAG_MISSING".equals(classification);
                }

                byPair.merge(pairLabel(result, shadow), 1, Integer::sum);
                if (inconsistent && samples.size() < sampleLimit) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("orderId", orderId);
                    sample.put("orderSn", row.get("order_sn"));
                    sample.put("result", result);
                    sample.put("conclusionFromTag", shadow.fromTag());
                    sample.put("conclusionSettlementFact", shadow.settlementFact());
                    sample.put("conclusionSource", shadow.source());
                    sample.put("activeConclusionTags", shadow.tagCodes());
                    sample.put("conclusionTagMultiple", shadow.multiple());
                    sample.put("orderStatus", orderStatus);
                    // 它是怎么离开待审核的（这一列只在台账里）—— 缺口定位全靠它
                    Map<String, Object> leave = leaveRows.get(orderId);
                    sample.put("leftAuditPendingTo", leave == null ? null : leave.get("to_status"));
                    sample.put("leftAuditPendingReason", leave == null ? null : leave.get("reason"));
                    sample.put("leftAuditPendingAt", leave == null ? null : fmtTagTime(leave.get("created_at")));
                    sample.put("classification", classification);
                    samples.add(sample);
                }
            }
            if (rows.size() < PARITY_SCAN_PAGE) break;
        }

        // 真缺口 = 该有标却没有。**离开待审核的单本来就没有结论标（V9 的设计）** ⇒ 不算缺口
        long inconsistentTotal = valueMismatch + unmappedTagCode + pendingTagMissing;
        Map<String, Object> out = new LinkedHashMap<>();
        // ── 结论行（先给数，再给明细）───────────────────────────────────────────
        out.put("scanned", scanned);                       // 扫了多少张单（= 队列里的行数，每单最新一轮）
        out.put("inconsistent", inconsistentTotal);        // 真冲突 + 真缺口（不含"正常退回决策行"）
        out.put("valueMismatch", valueMismatch);           // 判定轴两边都有结论但不相等（真冲突）
        out.put("unmappedTagCode", unmappedTagCode);       // 有结论标但码不在映射表里
        out.put("pendingTagMissing", pendingTagMissing);   // 真缺口：还在 7 被判过、却没有任何结论标（只有这一档是 gap）
        out.put("multipleConclusionTags", multipleConclusionTags);  // 一单多条 active 结论标（互斥漏了哪条路径）
        // ── 标回答了结论的单 ───────────────────────────────────────────────
        out.put("answeredByTag", answeredByTag);                     // 判定结论：标与 result 一致
        out.put("answeredBySettlementFact", answeredBySettlementFact); // 结算事实标（已申请退货）：不与 result 比
        // ── 标里没有结论的单：**离开待审核的一律算设计如此，不算 gap** ──────────────
        out.put("noConclusionTag", noConclusionTag);                 // 合计（= 下面三项之和）
        out.put("noAuditConclusionByDesign", noAuditConclusionByDesign); // 正常：已离开待审核 —— V9 结算只解不落
        out.put("legacyDecision", legacyDecision);                   // 正常：无"离开 7"台账的历史单 → 退回决策行
        // ── 端点的自身定位（别让读者把它当体检结论）────────────────────────────
        out.put("internalOnly", true);                               // V9 起：口径迁移已暂停，这是内部排障工具
        out.put("internalNote", "口径迁移已暂停（V9 收回了『通过/不通过』标）。本端点仅供排障，"
                + "不对业务承诺零 gap；离开待审核的单没有结论标是设计如此，计入 noAuditConclusionByDesign。");
        out.put("consistent", scanned - inconsistentTotal);
        out.put("consistencyRate", scanned == 0 ? 1.0
                : Math.round((scanned - inconsistentTotal) * 10000.0 / scanned) / 10000.0);
        // 从没被判定过的单：队列里永远看不到它们（队列 FROM order_audit_decision），
        // 但"标驱动结论"对它们的答案是"没有结论"。单列出来，免得这个缺口被扫描范围掩盖。
        out.put("neverJudgedOrders", decisionMapper.countOrdersWithoutAnyDecision());
        out.put("byPair", byPair);                          // { "NO_MATCH → NO_MATCH": 12, "NO_MATCH → (无结论标)": 3, … }
        out.put("samples", samples);                        // 前 N 条不一致（含缺口分类与离开待审核的原因）
        out.put("sampleLimit", sampleLimit);
        return out;
    }

    /**
     * 「标里没有结论」这一档的归因（<b>正常</b> vs <b>真缺口</b>）。
     *
     * <h3>V9 之后只剩一种真缺口</h3>
     * 结算已回到「只解不落」：单子离开待审核之后身上<b>本来就没有</b>结论标（这是设计），
     * 所以"离开了 7 却没有结论标"一律是 {@code NO_AUDIT_CONCLUSION}（正常），不再是 gap。
     * 剩下的真缺口只有一个：<b>还停在 7、已经被判定过，却一条结论标都没有</b>
     * ——那是引擎漏落了标（AUDIT_PENDING / BLOCKED / NO_POLICY_MATCH …）。
     *
     * @param leave       该单最后一次「离开待审核」的台账行（{@code null} = 台账里没有离开过 7）
     * @param orderStatus 当前订单状态
     * @return {@code PENDING_TAG_MISSING}（真缺口）/ {@code NO_AUDIT_CONCLUSION}（正常：已离开 7）/
     *         {@code LEGACY_DECISION}（正常：无离开台账、状态也不在 7 ⇒ 证明不了它"该有"结论标）
     */
    private static String missingConclusionBucket(Map<String, Object> leave, Integer orderStatus) {
        if (leave != null) {
            // 台账里有"离开 7"的记录 ⇒ 结算已经把这个单的结论标解干净了。
            // 是包裹聚合推导（DELIVERY_AGGREGATE）还是人工放行，结果都一样：**没有结论标是对的**。
            return "NO_AUDIT_CONCLUSION";
        }
        if (orderStatus != null && orderStatus == 7) {
            // 还在待审核、而且进了队列（队列 = 有决策行）⇒ 这一轮判定本该落标却没有 ⇒ 真缺口
            return "PENDING_TAG_MISSING";
        }
        // 台账里没有"离开 7"的记录、状态却不在 7：早期没有台账的旧单 / 直接造的数据。
        // 证明不了它"该有"结论标，按历史单处理（判据不知道时不许冤枉数据）。
        return "LEGACY_DECISION";
    }

    /** 配对分布里的一格：一眼看出"哪些结论在被标改写" */
    private static String pairLabel(String result, ConclusionShadow shadow) {
        if (shadow.tagCodes().isEmpty()) return result + " → (无结论标)";
        if ("SETTLEMENT_FACT".equals(shadow.source())) return result + " → 事实标:" + shadow.settlementFact();
        return result + " → " + shadow.fromTag();
    }

    // ================================================================ run 列表

    /** run 列表（倒序，最新在前） */
    public Map<String, Object> runs(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        List<AuditRun> rows = runMapper.selectPage((p - 1) * s, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", runMapper.countAll());
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return out;
    }

    // ================================================================ 时间线

    /**
     * 单张订单的审核时间线（三段按时间合并），并给出「决策 ↔ 台账行」的<b>精确对齐</b>。
     *
     * <p>对齐规则（详见 {@link AuditTrailMapper} 的类注释）：
     * <ol>
     *   <li><b>强对齐</b>：决策的 {@code run_id} 与台账行的 {@code ref_type='AUDIT_RUN'} +
     *       {@code ref_id=run_id} 相等 —— 这条台账就是那一轮推进出来的；</li>
     *   <li><b>弱对齐</b>：{@code order_status_seq} == 台账 {@code status_seq}（键尾）——
     *       序号相同，但不是本轮推进的（例：NO_MATCH 轮不推进状态，seq 仍是当前值）；</li>
     *   <li>都不满足 → 不加配对，页面显示未对齐。<b>绝不</b>按时间最接近硬配一条。</li>
     * </ol>
     *
     * <p>配对信息既写进每段 {@code raw}（前端 {@code mapTrailEntry} 只保留 raw，靠 raw 里
     * 的 {@code order_status_seq / status_seq / idem_key} 自己也能算出来），也放在条目顶层的
     * {@code aligned} 里（新的调用方可以直接读，不用再算）。
     */
    public List<Map<String, Object>> trail(Long orderId) {
        List<Map<String, Object>> decisions = trailMapper.selectDecisions(orderId);
        List<Map<String, Object>> ledgers = trailMapper.selectStatusLedger(orderId);

        // 台账按 status_seq 建索引（一个 seq 理论上只有一行；反复流转同值不会出现，uk_idem 兜住）
        Map<Long, Map<String, Object>> ledgerBySeq = new LinkedHashMap<>();
        for (Map<String, Object> l : ledgers) {
            Long seq = seqOf(l);
            if (seq != null) ledgerBySeq.putIfAbsent(seq, l);
        }

        Map<Object, List<Map<String, Object>>> ledgerPairedDecisions = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();

        for (Map<String, Object> d : decisions) {
            Long seq = seqOf(d.get("order_status_seq"));
            Map<String, Object> ledger = seq == null ? null : ledgerBySeq.get(seq);
            String by = null;
            if (ledger != null) {
                by = isRunRef(ledger, d.get("run_id")) ? "RUN_REF" : "STATUS_SEQ";
            }
            Long ledgerId = ledger == null ? null : num(ledger.get("id"));
            if (ledger != null) {
                ledgerPairedDecisions.computeIfAbsent(ledger.get("id"), k -> new ArrayList<>()).add(d);
            }
            // 决策段补对齐字段（raw 里就是前端 alignOf 读的那两个键）
            d.put("paired_ledger_row_id", ledgerId);
            d.put("paired_by", by);
            d.put("order_status_seq", seq);
            Map<String, Object> aligned = new LinkedHashMap<>();
            aligned.put("decisionId", num(d.get("id")));
            aligned.put("roundNo", num(d.get("round_no")));
            aligned.put("ledgerRowId", ledgerId);
            aligned.put("statusSeq", seq);
            aligned.put("matchedBy", by);
            aligned.put("aligned", ledgerId != null);
            // strong=同一条因果（台账由本轮回填的 run_id 写下）；false=只是「序号相同」
            aligned.put("strong", "RUN_REF".equals(by));
            entries.add(entry("DECISION", d.get("created_at"),
                    "第 " + d.get("round_no") + " 轮判定 → " + d.get("result"),
                    d.get("reason") == null ? "" : String.valueOf(d.get("reason")),
                    d, aligned));
        }

        for (Map<String, Object> l : ledgers) {
            List<Map<String, Object>> paired = ledgerPairedDecisions.get(l.get("id"));
            List<Long> decisionIds = paired == null ? List.of()
                    : paired.stream().map(x -> num(x.get("id"))).toList();
            l.put("paired_decision_ids", decisionIds);
            Map<String, Object> aligned = new LinkedHashMap<>();
            aligned.put("ledgerRowId", num(l.get("id")));
            aligned.put("statusSeq", seqOf(l));
            aligned.put("idemKey", l.get("idem_key"));
            aligned.put("decisionIds", decisionIds);
            aligned.put("aligned", !decisionIds.isEmpty());
            aligned.put("strong", !decisionIds.isEmpty() && "AUDIT_RUN".equals(String.valueOf(l.get("ref_type"))));
            entries.add(entry("STATUS", l.get("created_at"),
                    "状态 " + l.get("from_status") + " → " + l.get("to_status") + "（" + l.get("reason") + "）",
                    l.get("reason_detail") == null ? "" : String.valueOf(l.get("reason_detail")),
                    l, aligned));
        }

        for (Map<String, Object> r : trailMapper.selectAdminAudit(orderId)) {
            // 操作审计段不参与「状态对齐」（它不改状态）；aligned 给 null 免得被误读成「没对齐」
            entries.add(entry("AUDIT", r.get("created_at"),
                    String.valueOf(r.get("action")) + "：" + (r.get("summary") == null ? "" : r.get("summary")),
                    r.get("reason") == null ? "" : String.valueOf(r.get("reason")),
                    r, null));
        }

        entries.sort(Comparator.comparing(e -> String.valueOf(e.get("at"))));
        return entries;
    }

    /**
     * 台账行是不是「这一轮 run 推进出来的」：{@code ref_type='AUDIT_RUN' && ref_id=run_id}。
     * 满足它就是强对齐（同一轮的因果），否则只算「序号恰好相同」。
     */
    private static boolean isRunRef(Map<String, Object> ledger, Object runId) {
        if (runId == null) return false;
        if (!"AUDIT_RUN".equals(String.valueOf(ledger.get("ref_type")))) return false;
        Long refId = num(ledger.get("ref_id"));
        return refId != null && refId.equals(num(runId));
    }

    /** 序号归一：int / long / BigDecimal / String 都收敛成 Long（MySQL 的 UNSIGNED 派生列会回 BigInteger） */
    private static Long seqOf(Map<String, Object> row) {
        return seqOf(row.get("status_seq"));
    }

    private static Long seqOf(Object value) {
        Long n = num(value);
        return n == null ? null : n;
    }

    private static Long num(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> entry(String segment, Object at, String title, String detail,
                                     Map<String, Object> raw, Map<String, Object> aligned) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("segment", segment);
        e.put("at", at == null ? null : at.toString());
        e.put("title", title);
        e.put("detail", detail);
        e.put("raw", raw);
        e.put("aligned", aligned);
        return e;
    }
}
