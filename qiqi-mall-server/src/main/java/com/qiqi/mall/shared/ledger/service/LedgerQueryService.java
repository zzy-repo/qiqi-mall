package com.qiqi.mall.shared.ledger.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.ledger.dto.LedgerRow;
import com.qiqi.mall.shared.ledger.mapper.LedgerQueryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * 台账查询的唯一业务入口：把「前端那几个参数」翻译成「查哪几支台账 + 什么条件」。
 *
 * <h3>它只做三件事，且每件都必须做对</h3>
 * <ol>
 *   <li><b>类型白名单</b>：{@code type} 空 = 全部六类；给了就必须是六类之一，
 *       拼错时<b>报错并列出可选值</b>，绝不「查不到就返回空数组」—— 空数组和「这段时间真没有记录」
 *       在页面上长得一模一样，等于让用户把「我选错类型了」误读成「系统没记录」。</li>
 *   <li><b>时间键的格式</b>：前端日期选择器只到天，会补成
 *       {@code 2026-11-13 00:00:00} / {@code 2026-11-13 23:59:59} 再传过来（见
 *       {@code admin-web/src/views/ledger/index.vue} 的 {@code rangeParams()}）。
 *       这里按 {@code LocalDateTime} 解析，另外容忍三种写法：ISO（{@code 2026-11-13T00:00:00}）、
 *       纯日期（{@code 2026-11-13} → from 取当天 00:00:00、to 取当天 23:59:59）、空串（= 不限）。</li>
 *   <li><b>区间自检</b>：{@code from > to} 直接报错。否则页面会静默返回 0 条，
 *       用户只会以为「这个区间没有台账」。</li>
 * </ol>
 *
 * <p>操作人类型同样只放行 {@code ADMIN/MEMBER/APIKEY/SYSTEM}（大小写不敏感）；
 * 注意 {@code stock_ledger} 没有 operator 列，按操作人筛选时 STOCK 那支不参与 —— 见
 * {@code LedgerSql} 的类注释。
 */
@Service
public class LedgerQueryService {

    /** 前端传的时间格式（{@code 'YYYY-MM-DD HH:mm:ss'}） */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 操作人类型白名单（与三张状态台账的 CHECK 约束、前端 {@code OPERATOR_TYPE_TEXT} 一致） */
    private static final List<String> OPERATOR_TYPES = List.of("ADMIN", "MEMBER", "APIKEY", "SYSTEM");

    private final LedgerQueryMapper ledgerQueryMapper;

    public LedgerQueryService(LedgerQueryMapper ledgerQueryMapper) {
        this.ledgerQueryMapper = ledgerQueryMapper;
    }

    /**
     * 台账分页查询。
     *
     * @param page         页码（controller 已夹在 ≥1）
     * @param size         每页条数（controller 已夹在 1..50）
     * @param type         台账类型（可空 = 全部）
     * @param bizId        业务对象 id（可空；语义见 {@link LedgerQueryMapper}）
     * @param operatorType 操作人类型（可空 = 全部）
     * @param from         起始时间原文（可空）
     * @param to           截止时间原文（可空）
     * @return MyBatis-Plus 的 Page：JSON 序列化成 {@code records/total/size/current/pages}
     */
    public Page<LedgerRow> page(long page, long size, String type, Long bizId,
                                String operatorType, String from, String to) {
        List<String> branches = branchesOf(type);
        String operator = normalizeOperatorType(operatorType);
        LocalDateTime fromTime = parseTime(from, "from", false);
        LocalDateTime toTime = parseTime(to, "to", true);
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间（from=" + from + "，to=" + to + "）");
        }
        Page<LedgerRow> result = new Page<>(page, size);
        // mapper 会把结果写回这个 Page 实例（records/total/current/size 都在它身上）
        ledgerQueryMapper.pageTimeline(result, branches, bizId, operator, fromTime, toTime);
        return result;
    }

    /**
     * 类型 → 要查的分支。
     *
     * <p>空 = 六支全查（一次查全链路）；单类型 = 只拼那一支（视图那一支上 MySQL 还会把
     * {@code biz_type} 推到 UNION 各分支、另外两支判 {@code Impossible WHERE} 不扫）。
     */
    private static List<String> branchesOf(String type) {
        if (type == null || type.isBlank()) return LedgerQueryMapper.ALL_TYPES;
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!LedgerQueryMapper.ALL_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("台账类型不支持：" + type + "（可选：" +
                    String.join(" / ", LedgerQueryMapper.ALL_TYPES) + "，留空查全部）");
        }
        return List.of(normalized);
    }

    /** 操作人类型：空 = 不过滤；否则必须是四类之一（大小写不敏感） */
    private static String normalizeOperatorType(String operatorType) {
        if (operatorType == null || operatorType.isBlank()) return null;
        String normalized = operatorType.trim().toUpperCase(Locale.ROOT);
        if (!OPERATOR_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("操作人类型不支持：" + operatorType + "（可选：" +
                    String.join(" / ", OPERATOR_TYPES) + "，留空查全部）");
        }
        return normalized;
    }

    /**
     * 时间参数解析（{@code null} = 不限）。
     *
     * @param endOfDay 纯日期（{@code 2026-11-13}）时补成当天 23:59:59 —— 这是 {@code to} 的语义
     *                 （「含这一天」），而 {@code from} 补 00:00:00
     */
    static LocalDateTime parseTime(String raw, String field, boolean endOfDay) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        try {
            if (text.length() == 10) {   // yyyy-MM-dd（手写 curl 常见）
                LocalDate date = LocalDate.parse(text);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            if (text.contains("T")) {    // ISO-8601（页面回显 / 后端响应原样回传）
                return LocalDateTime.parse(text);
            }
            return LocalDateTime.parse(text, DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间参数 " + field + " 格式不对：" + raw
                    + "（请用 'yyyy-MM-dd HH:mm:ss'，或只给日期 'yyyy-MM-dd'）");
        }
    }
}
