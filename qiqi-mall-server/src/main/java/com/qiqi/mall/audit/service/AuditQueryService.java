package com.qiqi.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.audit.entity.AuditRun;
import com.qiqi.mall.audit.mapper.AuditRunMapper;
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

    public AuditQueryService(OrderAuditDecisionMapper decisionMapper, AuditRunMapper runMapper,
                             AuditTrailMapper trailMapper, ObjectMapper json,
                             AuditFreezeReasonService freezeReasonService,
                             AuditTagCatalogService tagCatalog) {
        this.decisionMapper = decisionMapper;
        this.runMapper = runMapper;
        this.trailMapper = trailMapper;
        this.json = json;
        this.freezeReasonService = freezeReasonService;
        this.tagCatalog = tagCatalog;
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
        Map<Long, List<Map<String, Object>>> tagsByOrder = tagsOf(rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows.stream()
                .map(r -> queueRow(r, tagsByOrder.get(asLong(r.get("order_id")))))
                .toList());
        return out;
    }

    /**
     * 本页这些订单的 active 标（**一次批量查询**，不是逐行查、也不是 JOIN 复制行）。
     *
     * @return {@code orderId → [标]}；没标的不出现在 map 里（调用方给空列表）
     */
    private Map<Long, List<Map<String, Object>>> tagsOf(List<Map<String, Object>> rows) {
        List<Long> ids = rows.stream()
                .map(r -> asLong(r.get("order_id")))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> t : decisionMapper.selectActiveTagsByOrders(ids)) {
            Long orderId = asLong(t.get("order_id"));
            if (orderId == null) continue;
            String code = t.get("tag_code") == null ? null : String.valueOf(t.get("tag_code"));
            AuditTagCatalogService.TagInfo info = tagCatalog.info(code);
            // 形状固定（键名与其它队列行统一走驼峰，前端只认一份）：目录里查不到的标码不是错误
            // —— 它允许先落库、目录后补，这里降级成「中文名=原始码 + description=null」，
            // 绝不许因为一条没登记的标把整张队列打成空白。
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tagCode", code);
            item.put("name", tagCatalog.nameOf(code));            // 查不到时回原始码（永不 null）
            item.put("description", info == null ? null : info.description());
            item.put("category", info == null ? null : info.category());
            item.put("blocking", info != null && info.blocking());
            item.put("visible", info == null || info.visible());
            item.put("reason", t.get("reason") == null ? null : String.valueOf(t.get("reason")));
            item.put("at", fmtTagTime(t.get("created_at")));
            out.computeIfAbsent(orderId, k -> new ArrayList<>()).add(item);
        }
        return out;
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
    private Map<String, Object> queueRow(Map<String, Object> row, List<Map<String, Object>> tags) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        // 注意 remove 要在**副本**上做：四个 *_json 原始列换成同名结构，避免同一份证据
        // 既以字符串又以数组出现在同一行里（两份形状会让前端 pick() 随键名不同取到不同东西）
        out.put("miss_reasons", readJson(out.remove("miss_reason_json")));
        out.put("matched_conditions", readJson(out.remove("matched_condition_json")));
        out.put("excluded_by", readJson(out.remove("excluded_by_json")));
        out.put("evidence", readJson(out.remove("evidence_json")));
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
        // 期 1：这单身上的**全部** active 标（带中文名 + 一句话说明 + 打标时间）。
        // ⚠️ 键名走驼峰（tags / tagCodes），与 frozenReason 等既有驼峰字段一致 ——
        //    上一轮漏了 snake→camel 这一步，前端拿不到值且不报错，是最难查的那种空白。
        // ⚠️ 这里给的是**数据层的并列多标**，不排"主标"：显示层要压缩（阻断优先、同类型取最新）
        //    的规则写在前端，不进数据、不进字典 —— 否则就有了第二套口径。
        List<Map<String, Object>> safeTags = tags == null ? List.of() : tags;
        out.put("tags", safeTags);
        out.put("tagCodes", safeTags.stream().map(t -> t.get("tagCode")).toList());
        return out;
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
