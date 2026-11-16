package com.qiqi.mall.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 集成测试造出来的数据<b>按外键依赖倒序删干净</b>，并把「删完还剩多少」如实报出来。
 *
 * <h3>为什么不能只删不验</h3>
 * {@code order_audit_decision} / {@code stock_ledger} / {@code order_status_ledger} /
 * {@code admin_audit_log} / {@code payment_record} / {@code channel_order_map} 这几张表
 * <b>都没有指向 order 的外键</b>（V1 注释：「证据不随订单级联删除」）。所以
 * {@code DELETE FROM `order`} 之后它们会变成<b>悬空行</b>——删了订单看着干净，
 * 实际证据表里还留着一堆 order_id 指向已不存在的单。这类残留不会被 FK 挡住，
 * 只能靠「删完再数一遍」发现。{@link #residueOfOrders} / {@link #residueOfCatalog}
 * 就是那把尺子：调用方必须断言它全为 0。
 *
 * <h3>顺序（为什么不重要，但仍然写死）</h3>
 * 有外键的三处：{@code order_item → order}（CASCADE）、{@code return_apply → order}（CASCADE）、
 * {@code product →(被) order_item.product_id}（RESTRICT，正是事故里报红的那个 fk_item_product）。
 * 所以顺序是「证据表 → return_apply/return_item 子表 → order → product/member」；
 * 先删订单也顺带把 order_item 带走，product 才删得掉。
 */
public final class ItCleanup {

    /**
     * 与订单关联、但<b>没有外键</b>的证据表：必须显式删，且必须验残留。
     * 顺序即删除顺序：先把最"外围"的证据清掉，再动主表。
     */
    private static final List<String> ORDER_EVIDENCE_TABLES = List.of(
            "stock_ledger",
            "order_audit_decision",
            "order_audit_tag",
            "order_status_ledger",
            "admin_audit_log",
            "payment_record",
            "channel_order_map");

    private ItCleanup() {
    }

    /**
     * 删掉 {@code order_sn LIKE ?} 命中的全部订单及其全部痕迹。
     *
     * @return 每张表删了多少行（报告用；顺序即删除顺序）
     */
    public static Map<String, Integer> purgeOrders(JdbcTemplate jdbc, String orderSnLike) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM `order` WHERE order_sn LIKE ?", Long.class, orderSnLike);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            ORDER_EVIDENCE_TABLES.forEach(t -> deleted.put(t, 0));
            deleted.put("return_status_ledger", 0);
            deleted.put("return_apply", 0);
            deleted.put("order_item", 0);
            deleted.put("`order`", 0);
            return deleted;
        }
        // ① 无外键的证据表（不删就是悬空行）
        for (String table : ORDER_EVIDENCE_TABLES) {
            deleted.put(table, jdbc.update("DELETE FROM `" + table + "` WHERE order_id IN " + in(ids.size()),
                    ids.toArray()));
        }
        // ② 退货：return_status_ledger 无外键（同理要先删），return_apply 是 order 的 CASCADE 子表
        deleted.put("return_status_ledger", jdbc.update(
                "DELETE FROM return_status_ledger WHERE return_apply_id IN "
                        + "(SELECT id FROM return_apply WHERE order_id IN " + in(ids.size()) + ")", ids.toArray()));
        deleted.put("return_apply", jdbc.update(
                "DELETE FROM return_apply WHERE order_id IN " + in(ids.size()), ids.toArray()));
        // ③ 明细（有 CASCADE，显式删是为了让报告里的数字能看出来「明细也被清了」）
        deleted.put("order_item", jdbc.update(
                "DELETE FROM order_item WHERE order_id IN " + in(ids.size()), ids.toArray()));
        // ④ 主表
        deleted.put("`order`", jdbc.update("DELETE FROM `order` WHERE id IN " + in(ids.size()), ids.toArray()));
        return deleted;
    }

    /** 订单侧残留：全部必须为 0（键名与 {@link #purgeOrders} 对齐，便于「删了多少 / 还剩多少」对照） */
    public static Map<String, Integer> residueOfOrders(JdbcTemplate jdbc, String orderSnLike) {
        Map<String, Integer> left = new LinkedHashMap<>();
        for (String table : ORDER_EVIDENCE_TABLES) {
            left.put(table, count(jdbc, "SELECT COUNT(*) FROM `" + table + "` WHERE order_id IN "
                    + "(SELECT id FROM `order` WHERE order_sn LIKE ?)", orderSnLike));
        }
        left.put("return_status_ledger", count(jdbc,
                "SELECT COUNT(*) FROM return_status_ledger WHERE return_apply_id IN "
                        + "(SELECT r.id FROM return_apply r JOIN `order` o ON o.id = r.order_id "
                        + "WHERE o.order_sn LIKE ?)", orderSnLike));
        left.put("return_apply", count(jdbc,
                "SELECT COUNT(*) FROM return_apply WHERE order_id IN (SELECT id FROM `order` WHERE order_sn LIKE ?)",
                orderSnLike));
        left.put("order_item", count(jdbc,
                "SELECT COUNT(*) FROM order_item WHERE order_id IN (SELECT id FROM `order` WHERE order_sn LIKE ?)",
                orderSnLike));
        left.put("`order`", count(jdbc, "SELECT COUNT(*) FROM `order` WHERE order_sn LIKE ?", orderSnLike));
        return left;
    }

    /**
     * 删掉本次测试自己造的「商品 + 会员」（SKU 与桶随 product 级联删除）及其审核策略/轮次。
     *
     * @param productName     测试商品名（就是事故里撞 fk_item_product 的那个名字）
     * @param memberUsernames 测试会员名
     */
    public static Map<String, Integer> purgeCatalog(JdbcTemplate jdbc, String productName,
                                                   String... memberUsernames) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        // audit_* 三张：隔离库里只有本测试造过策略/轮次，整表清空即可
        deleted.put("audit_run", jdbc.update("DELETE FROM audit_run"));
        deleted.put("audit_policy_version", jdbc.update("DELETE FROM audit_policy_version"));
        deleted.put("audit_policy_condition", jdbc.update("DELETE FROM audit_policy_condition"));
        deleted.put("audit_policy", jdbc.update("DELETE FROM audit_policy"));
        // product → sku → sku_bucket 是级联链（fk_sku_product / fk_bucket_sku 均 CASCADE）
        deleted.put("product", jdbc.update("DELETE FROM product WHERE name = ?", productName));
        deleted.put("member", jdbc.update("DELETE FROM member WHERE username IN " + in(memberUsernames.length),
                (Object[]) memberUsernames));
        return deleted;
    }

    /** 商品/会员侧残留（含 SKU 与库存桶）：全部必须为 0 */
    public static Map<String, Integer> residueOfCatalog(JdbcTemplate jdbc, String productName,
                                                       String... memberUsernames) {
        Map<String, Integer> left = new LinkedHashMap<>();
        left.put("audit_policy", count(jdbc, "SELECT COUNT(*) FROM audit_policy", new Object[0]));
        left.put("audit_policy_condition", count(jdbc, "SELECT COUNT(*) FROM audit_policy_condition", new Object[0]));
        left.put("audit_policy_version", count(jdbc, "SELECT COUNT(*) FROM audit_policy_version", new Object[0]));
        left.put("audit_run", count(jdbc, "SELECT COUNT(*) FROM audit_run", new Object[0]));
        left.put("product", count(jdbc, "SELECT COUNT(*) FROM product WHERE name = ?", productName));
        left.put("sku", count(jdbc, "SELECT COUNT(*) FROM sku WHERE product_id IN "
                + "(SELECT id FROM product WHERE name = ?)", productName));
        left.put("sku_bucket", count(jdbc, "SELECT COUNT(*) FROM sku_bucket WHERE sku_id IN "
                + "(SELECT s.id FROM sku s JOIN product p ON p.id = s.product_id WHERE p.name = ?)", productName));
        left.put("member", count(jdbc, "SELECT COUNT(*) FROM member WHERE username IN "
                + in(memberUsernames.length), (Object[]) memberUsernames));
        return left;
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? -1 : n;
    }

    /** {@code (?,?,?)}：避免把 id 列表拼进 SQL 文本 */
    private static String in(int size) {
        return IntStream.range(0, size).mapToObj(i -> "?").collect(Collectors.joining(",", "(", ")"));
    }
}
