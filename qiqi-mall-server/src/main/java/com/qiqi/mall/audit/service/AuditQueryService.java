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

    public AuditQueryService(OrderAuditDecisionMapper decisionMapper, AuditRunMapper runMapper,
                             AuditTrailMapper trailMapper, ObjectMapper json) {
        this.decisionMapper = decisionMapper;
        this.runMapper = runMapper;
        this.trailMapper = trailMapper;
        this.json = json;
    }

    // ================================================================ 人工队列

    /** 人工队列（每单最新一轮），{@code result} 可为空表示不过滤 */
    public Map<String, Object> queue(String result, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);   // 与 MybatisPlusConfig.maxLimit 同口径，防 size=999999
        String filter = result == null || result.isBlank() ? null : result.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = decisionMapper.selectQueue(filter, (p - 1) * s, s);
        long total = decisionMapper.countQueue(filter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows.stream().map(this::queueRow).toList());
        return out;
    }

    /**
     * 队列行整形：把四列 JSON 证据<b>解析成结构</b>再返回。
     *
     * <p>为什么不能直接把 JSON 列当字符串丢给前端：页面要判断「后端有没有留痕」，
     * 判据是 {@code Array.isArray(row.miss_reasons)}。字符串（{@code "[{...}]"}）不是数组，
     * 前端于是永远走「本地对照」降级路径 —— 数据明明在，页面却说没回传。
     * 所以解析放在这里（唯一知道这些列语义的地方），并按前端读的键名给出。
     */
    private Map<String, Object> queueRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        // 注意 remove 要在**副本**上做：四个 *_json 原始列换成同名结构，避免同一份证据
        // 既以字符串又以数组出现在同一行里（两份形状会让前端 pick() 随键名不同取到不同东西）
        out.put("miss_reasons", readJson(out.remove("miss_reason_json")));
        out.put("matched_conditions", readJson(out.remove("matched_condition_json")));
        out.put("excluded_by", readJson(out.remove("excluded_by_json")));
        out.put("evidence", readJson(out.remove("evidence_json")));
        return out;
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
