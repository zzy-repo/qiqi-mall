package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.entity.AuditRun;
import com.qiqi.mall.audit.mapper.AuditRunMapper;
import com.qiqi.mall.audit.mapper.AuditTrailMapper;
import com.qiqi.mall.audit.mapper.OrderAuditDecisionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final OrderAuditDecisionMapper decisionMapper;
    private final AuditRunMapper runMapper;
    private final AuditTrailMapper trailMapper;

    public AuditQueryService(OrderAuditDecisionMapper decisionMapper, AuditRunMapper runMapper,
                             AuditTrailMapper trailMapper) {
        this.decisionMapper = decisionMapper;
        this.runMapper = runMapper;
        this.trailMapper = trailMapper;
    }

    /** 人工队列（每单最新一轮），{@code result} 可为空表示不过滤 */
    public Map<String, Object> queue(String result, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);   // 与 MybatisPlusConfig.maxLimit 同口径，防 size=999999
        String filter = result == null || result.isBlank() ? null : result.trim().toUpperCase();
        List<Map<String, Object>> rows = decisionMapper.selectQueue(filter, (p - 1) * s, s);
        long total = decisionMapper.countQueue(filter);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return out;
    }

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

    /** 单张订单的审核时间线（三段按时间合并） */
    public List<Map<String, Object>> trail(Long orderId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> r : trailMapper.selectDecisions(orderId)) {
            entries.add(entry("DECISION", r.get("created_at"),
                    "第 " + r.get("round_no") + " 轮判定 → " + r.get("result"),
                    r.get("reason") == null ? "" : String.valueOf(r.get("reason")),
                    r));
        }
        for (Map<String, Object> r : trailMapper.selectStatusLedger(orderId)) {
            entries.add(entry("STATUS", r.get("created_at"),
                    "状态 " + r.get("from_status") + " → " + r.get("to_status") + "（" + r.get("reason") + "）",
                    r.get("reason_detail") == null ? "" : String.valueOf(r.get("reason_detail")),
                    r));
        }
        for (Map<String, Object> r : trailMapper.selectAdminAudit(orderId)) {
            entries.add(entry("AUDIT", r.get("created_at"),
                    String.valueOf(r.get("action")) + "：" + (r.get("summary") == null ? "" : r.get("summary")),
                    r.get("reason") == null ? "" : String.valueOf(r.get("reason")),
                    r));
        }
        entries.sort(Comparator.comparing(e -> String.valueOf(e.get("at"))));
        return entries;
    }

    private Map<String, Object> entry(String segment, Object at, String title, String detail, Map<String, Object> raw) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("segment", segment);
        e.put("at", at == null ? null : at.toString());
        e.put("title", title);
        e.put("detail", detail);
        e.put("raw", raw);
        return e;
    }
}
