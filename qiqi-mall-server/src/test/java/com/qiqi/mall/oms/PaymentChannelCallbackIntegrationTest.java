package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.service.ChannelCallbackService;
import com.qiqi.mall.oms.service.PaymentRecordService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付流水加固的两件「真缺口」的真库集成测试（台账 PAYMENT 支 + 渠道回调重放）。
 *
 * <h3>为什么必须有这个类</h3>
 * e2e 的 15-支付流水加固 此前三条用例全是 {@code test.fixme}，理由是两个缺口，不是「过期理由」：
 * <ol>
 *   <li><b>台账查询的六类型里没有 PAYMENT</b> —— 「页面上看得见支付流水」这句话根本写不出来；</li>
 *   <li><b>没有可重放的渠道回调</b> —— 模拟支付的 {@code trade_no} 是本地生成的，
 *       「第三方把同一笔通知又发了一次」这个场景造不出来，{@code uk_channel_trade} 只是注释里的声明。</li>
 * </ol>
 * 本类把这两件事钉在库里，e2e 只负责证明「人在页面上看得见」。
 *
 * <h3>四条用例各证明什么</h3>
 * <ol>
 *   <li><b>① 台账 PAYMENT 支</b>：{@code GET /admin/ledgers?type=PAYMENT&bizId=订单id} 能把该单的
 *       支付行与退款行都查出来，15 个字段逐字对得上表（bizSn=order_sn、reason=支付入账/退款入账、
 *       reasonDetail=trade_no、operator 三列照表取、refType/refId、createdAt）；
 *       并且「按 operatorType 筛选时 PAYMENT 支不参与」（与 STOCK 支同一口径）；</li>
 *   <li><b>② 重复通知被 uk_channel_trade 挡住</b>：重放端点连调两次都是 code=0 +「重复通知，已忽略」，
 *       行数与金额总和都不变；再反向证明「闸是活的」：换一个新的 trade_no 应当入账（不是一律拒绝）；
 *       同时绕开 service 直插「同 channel+trade_no、不同 idem_key」必须抛 uk_channel_trade；</li>
 *   <li><b>③ 同一张单的第二笔**不同**流水号 = 疑似重复扣款 → 必须报错而不是静默忽略</b>
 *       （把「重复通知」与「真丢钱」分开，这是本次加固最容易被合并错的两件事）；</li>
 *   <li><b>④ 权限</b>：{@code payment:write} 不在可授予 scope 里 ⇒ 任何 API Key 调重放接口都 403，
 *       只有人工 JWT 能调。</li>
 * </ol>
 *
 * <h3>事务与落库口径</h3>
 * 全部用例都**不带** {@code @Transactional}：重放走真 HTTP（服务端是另一条连接），
 * 测试线程里未提交的行它看不见。自造数据一律在 finally / {@code @AfterAll} 里按单号前缀删干净
 * （{@code payment_record} 没有外键，必须单独删 —— 这正是本类顺带记录的一条口径问题）。
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=PaymentChannelCallbackIntegrationTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentChannelCallbackIntegrationTest {

    /** 本类造的所有单号都带这个前缀：@AfterAll 按它兜底清理 */
    private static final String SN_PREFIX = "PAYCBC";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaymentRecordService paymentRecordService;
    @Autowired
    private ChannelCallbackService channelCallbackService;

    private String adminJwt;
    private Long memberId;
    private final List<Long> createdApiKeyIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();

    // ------------------------------------------------------------------ 准备 / 清理

    @BeforeAll
    void prepare() {
        adminJwt = loginAsAdmin();
        memberId = jdbc.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
        assertThat(memberId).as("测试库里应至少有一个会员（order 表有外键 fk_order_member）").isNotNull();
    }

    @AfterAll
    void cleanup() {
        // payment_record 没有外键，删订单不会带走它 —— 必须按单号自己删（这本身就是一条口径问题）
        int rows = jdbc.update("DELETE FROM payment_record WHERE order_sn LIKE ?", SN_PREFIX + "%");
        for (Long id : createdOrderIds) {
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", id);
            jdbc.update("DELETE FROM `order` WHERE id = ?", id);
        }
        for (Long id : createdApiKeyIds) {
            try {
                call(HttpMethod.DELETE, "/admin/api-keys/" + id, null, null, adminJwt);
            } catch (RuntimeException ignored) {
                // 已被用例自己删掉
            }
        }
        System.out.printf("[清理] 自造支付流水 %d 行、订单 %d 张、临时 API Key %d 个已删除%n",
                rows, createdOrderIds.size(), createdApiKeyIds.size());
    }

    // ================================================================== ① 台账 PAYMENT 支

    /**
     * ① {@code type=PAYMENT} 能把「这笔钱」查出来，且 15 个字段逐字对得上。
     *
     * <p>断言口径（与 {@code LedgerSql.PAYMENT_BRANCH} 一一对应）：
     * <pre>
     * type=PAYMENT / bizId=order_id / bizSn=order_sn（对账键，不 JOIN order）
     * fromStatus=toStatus=null（钱没有「从哪到哪」）
     * reason=支付入账|退款入账 / reasonDetail=trade_no
     * operator 三列照表取 / refType·refId = 触发单据 / createdAt 带毫秒
     * </pre>
     */
    @Test
    @Order(1)
    @DisplayName("① type=PAYMENT：支付行与退款行的 15 个字段与表逐字对得上；operatorType 筛选时本支不参与")
    void paymentBranch_exposesPayAndRefundRows() {
        String orderSn = uniqueSn("LEDGER");
        Long orderId = insertOrder(orderSn, 6);   // 6 已退款（支付+退款都发生过的一张单）
        try {
            Long payId = insertPayment(orderSn, orderId, PaymentRecord.TYPE_PAY, "PAYCBC-TRADE-" + orderSn,
                    new BigDecimal("66.00"), "MEMBER", memberId, "付款买家", null, null, null);
            Long refundId = insertPayment(orderSn, orderId, PaymentRecord.TYPE_REFUND, "PAYCBC-REF-" + orderSn,
                    new BigDecimal("66.00"), "ADMIN", 1L, "admin", "RETURN_APPLY", 9_900_001L, payId);

            JsonNode data = get("/admin/ledgers?page=1&size=50&type=PAYMENT&bizId=" + orderId).path("data");
            JsonNode records = data.path("records");
            System.out.printf("① type=PAYMENT&bizId=%d → total=%d，行=%s%n", orderId,
                    data.path("total").asInt(), records);

            assertThat(data.path("total").asInt()).as("该订单应有 2 条支付流水（1 支付 + 1 退款）").isEqualTo(2);

            // 字段口径：按 id 定位两行，逐列对撞库里那两行
            JsonNode payRow = findRow(records, payId);
            JsonNode refundRow = findRow(records, refundId);
            assertThat(payRow).as("支付行应查得出来").isNotNull();
            assertThat(refundRow).as("退款行应查得出来").isNotNull();

            for (String key : List.of("id", "type", "bizId", "bizSn", "fromStatus", "toStatus", "reason",
                    "reasonDetail", "operatorType", "operatorId", "operatorName", "refType", "refId",
                    "summary", "createdAt")) {
                assertThat(payRow.has(key)).as("PAYMENT 行必须带字段 %s：%s", key, payRow).isTrue();
            }

            // 支付行
            assertThat(payRow.path("type").asText()).isEqualTo("PAYMENT");
            assertThat(payRow.path("bizId").asLong()).as("bizId = payment_record.order_id").isEqualTo(orderId);
            assertThat(payRow.path("bizSn").asText()).as("bizSn = order_sn（对账键，不 JOIN order）").isEqualTo(orderSn);
            assertThat(payRow.path("fromStatus").isNull()).as("钱没有「从哪个状态到哪个状态」").isTrue();
            assertThat(payRow.path("toStatus").isNull()).isTrue();
            assertThat(payRow.path("reason").asText()).as("type=1 → 支付入账").isEqualTo("支付入账");
            assertThat(payRow.path("reasonDetail").asText()).as("reasonDetail 放渠道流水号").isEqualTo("PAYCBC-TRADE-" + orderSn);
            assertThat(payRow.path("operatorType").asText()).as("operator 三列照表取").isEqualTo("MEMBER");
            assertThat(payRow.path("operatorId").asLong()).isEqualTo(memberId);
            assertThat(payRow.path("operatorName").asText()).isEqualTo("付款买家");
            assertThat(payRow.path("refType").isNull()).as("支付行没有触发单据").isTrue();
            assertThat(payRow.path("createdAt").asText()).as("createdAt 是 yyyy-MM-dd HH:mm:ss").hasSize(19);

            // 退款行
            assertThat(refundRow.path("reason").asText()).as("type=2 → 退款入账").isEqualTo("退款入账");
            assertThat(refundRow.path("operatorType").asText()).isEqualTo("ADMIN");
            assertThat(refundRow.path("refType").asText()).as("触发单据类型：退货申请").isEqualTo("RETURN_APPLY");
            assertThat(refundRow.path("refId").asLong()).isEqualTo(9_900_001L);
            assertThat(refundRow.path("summary").asText())
                    .as("退款行的摘要要能读到原支付流水号（溯源看得见）").contains("原流水 #" + payId);

            // ② 按 operatorType 筛选时 PAYMENT 支整支不参与（与 STOCK 支同一口径）
            JsonNode withAdmin = get("/admin/ledgers?page=1&size=50&type=PAYMENT&operatorType=ADMIN&bizId=" + orderId)
                    .path("data");
            System.out.printf("① type=PAYMENT&operatorType=ADMIN → total=%d（应为 0：支付支不参与操作人筛选）%n",
                    withAdmin.path("total").asInt());
            assertThat(withAdmin.path("total").asInt())
                    .as("支付流水不参与 operatorType 筛选：退款行虽然是管理员点的，但它不是「管理员改的钱」")
                    .isZero();

            // ③ 不带 type 的「全链路」查询里也能看到这两行（页面默认视图）
            JsonNode allTypes = get("/admin/ledgers?page=1&size=50&bizId=" + orderId).path("data");
            long payRows = 0;
            for (JsonNode row : allTypes.path("records")) {
                if ("PAYMENT".equals(row.path("type").asText())) payRows++;
            }
            System.out.printf("① 不带 type 查全链路 → total=%d，其中 PAYMENT %d 行%n",
                    allTypes.path("total").asInt(), payRows);
            assertThat(payRows).as("全链路查询必须包含支付流水（否则页面上「这张单的钱」就断了）").isEqualTo(2);
        } finally {
            deletePayments(orderSn);
        }
    }

    // ================================================================== ② 重复通知

    /**
     * ② **渠道重复通知不会变成两笔钱** —— {@code uk_channel_trade} 是最后一道闸。
     *
     * <p>走真实的重放端点（{@code POST /admin/payments/{id}/replay-channel-callback}），
     * 它用该流水**原来的** trade_no 再走一遍渠道回调逻辑，等于「第三方把同一笔通知又发了一次」：
     * <pre>
     * ① 首次入账（真的新流水号）→ 1 行
     * ② 端点重放第 1 次 → code=0 +「重复通知，已忽略」，仍是 1 行
     * ③ 端点重放第 2 次 → 同上（重复是幂等的：重放多少次都还是 1 行）
     * ④ 反向对照：换一个**新的**流水号（另一张单）应当照常入账 —— 证明这不是「一律拒绝」
     * ⑤ 活闸证据：绕开 service 直插「同 channel+trade_no、不同 idem_key」→ 必须抛 uk_channel_trade
     *    （排掉「其实是 uk_idem 挡的」这种误判：两条唯一键同时被撞时，谁在拦必须能被指认）
     * </pre>
     * 断言落在「行数 + 金额总和」而不只是返回码：返回码对了但库里多一行，才是真事故。
     */
    @Test
    @Order(2)
    @DisplayName("② 重放渠道回调两次 → 都是 code=0「重复通知，已忽略」，行数与金额不变；uk_channel_trade 是活闸")
    void replayChannelCallback_duplicateNoticeIgnored() {
        String orderSn = uniqueSn("REPLAY");
        Long orderId = insertOrder(orderSn, 1);   // 1 已付款
        String tradeNo = "PAYCBC-REPLAY-" + orderSn;
        try {
            // ① 渠道首次通知 → 真的入账 1 行（模拟支付是本地生成流水号，这里必须走渠道流水号）
            ChannelCallbackService.Outcome first = channelCallbackService.onNotice(notice(
                    orderId, orderSn, tradeNo, PaymentRecord.TYPE_PAY, new BigDecimal("88.00")));
            Long payId = queryLong("SELECT id FROM payment_record WHERE order_sn = ? AND trade_no = ?", orderSn, tradeNo);
            System.out.printf("② ① 首次通知 → %s，流水 id=%d，行数=%d%n", first, payId, countByOrderSn(orderSn));
            assertThat(first).isEqualTo(ChannelCallbackService.Outcome.RECORDED);
            assertThat(countByOrderSn(orderSn)).isEqualTo(1);
            assertThat(sumAmount(orderSn)).isEqualByComparingTo("88.00");

            // ② 端点重放第 1 次：原 trade_no 再通知一次
            JsonNode replay1 = replayOk(payId);
            // ③ 端点重放第 2 次：还是同一笔
            JsonNode replay2 = replayOk(payId);
            System.out.printf("② ② 重放两次 → message1=%s outcome1=%s；message2=%s outcome2=%s；行数=%d，金额=%s%n",
                    replay1.path("message").asText(), replay1.path("data").path("outcome").asText(),
                    replay2.path("message").asText(), replay2.path("data").path("outcome").asText(),
                    countByOrderSn(orderSn), sumAmount(orderSn));

            for (JsonNode body : List.of(replay1, replay2)) {
                assertThat(body.path("code").asInt()).as("重复通知不是错误：code=0").isZero();
                assertThat(body.path("message").asText()).as("必须给出明确文案").isEqualTo("重复通知，已忽略");
                assertThat(body.path("data").path("outcome").asText()).isEqualTo("DUPLICATE");
                assertThat(body.path("data").path("recorded").asBoolean()).isFalse();
                assertThat(body.path("data").path("tradeNo").asText()).as("回报里带着被重放的流水号").isEqualTo(tradeNo);
            }
            assertThat(countByOrderSn(orderSn)).as("**重放不许新增流水**：仍是 1 行").isEqualTo(1);
            assertThat(sumAmount(orderSn)).as("金额总和也不许变（只看行数会漏掉「改了一行的金额」）")
                    .isEqualByComparingTo("88.00");

            // ④ 反向对照：换一张单 + 新流水号 → 必须照常入账（证明上面不是「一律拒绝」）
            String otherSn = uniqueSn("REPLAYNEW");
            Long otherOrderId = insertOrder(otherSn, 1);
            try {
                ChannelCallbackService.Outcome other = channelCallbackService.onNotice(notice(
                        otherOrderId, otherSn, "PAYCBC-OTHER-" + otherSn, PaymentRecord.TYPE_PAY, new BigDecimal("12.00")));
                System.out.printf("② ④ 反向对照：另一个流水号 → %s，行数=%d%n", other, countByOrderSn(otherSn));
                assertThat(other).as("新流水号是新的一笔钱：必须入账（否则闸会把正常通知也挡掉）")
                        .isEqualTo(ChannelCallbackService.Outcome.RECORDED);
                assertThat(countByOrderSn(otherSn)).isEqualTo(1);
            } finally {
                deletePayments(otherSn);
            }

            // ⑤ 活闸证据：绕开 service，用「同 channel+trade_no、不同 idem_key」直插
            List<Map<String, Object>> indexes = jdbc.queryForList(
                    "SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols "
                            + "FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 'payment_record' "
                            + "GROUP BY index_name, non_unique ORDER BY index_name");
            assertThat(indexes).as("payment_record 上必须有 uk_channel_trade 唯一索引 (channel, trade_no)")
                    .anySatisfy(idx -> {
                        assertThat(idx.get("index_name")).isEqualTo("uk_channel_trade");
                        assertThat(((Number) idx.get("non_unique")).intValue()).isZero();
                        assertThat(idx.get("cols")).isEqualTo("channel,trade_no");
                    });

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO payment_record (order_sn, order_id, amount, type, channel, trade_no, idem_key) "
                            + "VALUES (?, ?, 88.00, 1, 1, ?, ?)",
                    orderSn, orderId, tradeNo, orderSn + ":PAY:PROBE"))
                    .as("同一个 (channel, trade_no) 换个幂等键直插也必须被拒 —— 证明这道闸不是靠幂等键冒充的")
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("uk_channel_trade");

            System.out.printf("② ⑤ 直插同 trade_no + 新 idem_key → DuplicateKeyException(uk_channel_trade)；"
                    + "行数仍 %d%n", countByOrderSn(orderSn));
            assertThat(countByOrderSn(orderSn)).isEqualTo(1);
        } finally {
            deletePayments(orderSn);
        }
    }

    // ================================================================== ③ 第二笔不同流水号

    /**
     * ③ 同一张单、**不同**流水号的第二笔支付通知 —— 这不是重放，是「疑似重复扣款」。
     *
     * <p>设计口径（见 {@code PaymentRecordServiceImpl#recordChannelNotice}）：三种结果必须分开
     * —— 记上了 / 重复通知（忽略）/ 疑似重复扣款（报错）。把第三种也静默忽略，就是**真丢钱**：
     * 渠道那边已经扣了客户第二笔，本地台账却只有一行。
     */
    @Test
    @Order(3)
    @DisplayName("③ 同一订单第二个不同流水号 → 抛「疑似重复扣款」，行数不变（不能当重复通知吞掉）")
    void secondDifferentTradeNo_isNotSilentlyIgnored() {
        String orderSn = uniqueSn("DOUBLE");
        Long orderId = insertOrder(orderSn, 1);
        try {
            assertThat(channelCallbackService.onNotice(notice(orderId, orderSn,
                    "PAYCBC-A-" + orderSn, PaymentRecord.TYPE_PAY, new BigDecimal("30.00"))))
                    .isEqualTo(ChannelCallbackService.Outcome.RECORDED);

            assertThatThrownBy(() -> channelCallbackService.onNotice(notice(orderId, orderSn,
                    "PAYCBC-B-" + orderSn, PaymentRecord.TYPE_PAY, new BigDecimal("30.00"))))
                    .as("同一张单的第二笔扣款必须让业务失败并要求人工对账")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("疑似重复扣款");

            System.out.printf("③ 同一订单第二个流水号 → 抛疑似重复扣款；行数=%d（仍 1 行）%n", countByOrderSn(orderSn));
            assertThat(countByOrderSn(orderSn)).as("报错的同时也不许写下第二行").isEqualTo(1);
        } finally {
            deletePayments(orderSn);
        }
    }

    // ================================================================== ④ 权限

    /**
     * ④ {@code payment:write} 不可授予 ⇒ API Key 调重放接口一律 403，只有人工 JWT 能调。
     *
     * <p>注意断言的是 **403 + 明确文案**，不是「没生效」：如果这里返回 code=0，
     * 说明 AI 拿着 order:write 就能反复触发资金记账路径 —— 那比没有这个接口更糟。
     */
    @Test
    @Order(4)
    @DisplayName("④ API Key（含全部可授予 scope）调重放 → 403 payment:write；人工 JWT 才 code=0")
    void replay_requiresHumanJwt_apiKeyIs403() {
        String orderSn = uniqueSn("PERM");
        Long orderId = insertOrder(orderSn, 1);
        String tradeNo = "PAYCBC-PERM-" + orderSn;
        try {
            channelCallbackService.onNotice(notice(orderId, orderSn, tradeNo, PaymentRecord.TYPE_PAY,
                    new BigDecimal("20.00")));
            Long payId = queryLong("SELECT id FROM payment_record WHERE order_sn = ? AND trade_no = ?", orderSn, tradeNo);

            // 全权 key（把 ALL_SCOPES 里能授予的都给它 —— payment:write 不在其中）
            String scopes = "[\"order:read\",\"order:write\",\"catalog:read\",\"catalog:write\","
                    + "\"member:read\",\"member:write\",\"config:read\",\"config:write\",\"dashboard:read\","
                    + "\"ledger:read\",\"stock:read\",\"audit:read\",\"channel:read\"]";
            JsonNode keyData = call(HttpMethod.POST, "/admin/api-keys",
                    "{\"name\":\"支付重放越权测试\",\"scopes\":" + scopes + "}", null, adminJwt)
                    .getBody().path("data");
            createdApiKeyIds.add(keyData.path("id").asLong());
            String plaintext = keyData.path("key").asText();

            ResponseEntity<JsonNode> denied = call(HttpMethod.POST,
                    "/admin/payments/" + payId + "/replay-channel-callback", null, plaintext, null);
            System.out.printf("④ API Key 调重放 → HTTP %d，message=%s%n",
                    denied.getStatusCode().value(), denied.getBody() == null ? "-" : denied.getBody().path("message").asText());
            assertThat(denied.getStatusCode().value()).as("支付写操作对 API Key 一律 403").isEqualTo(403);
            // 文案沿用 ApiKeyAuthFilter 对「不可授予写 scope」的统一句式（audit:write / channel:write 同一句）：
            // 直接告诉调用方「这接口对 AI 只读」，而不是让他去权限列表里找一个根本不存在的 payment:write。
            // scope 名本身在服务端 WARN 日志里（`需要=payment:write`），排障时看得到。
            assertThat(denied.getBody().path("message").asText())
                    .as("文案要说清「只读、写操作只能人工 JWT 」，而不是让人去权限列表里找一个授不出来的 scope")
                    .contains("只读")
                    .contains("人工 JWT");

            // 人工 JWT 能调（同一个 id，仍是重复通知）
            JsonNode byJwt = replayOk(payId);
            assertThat(byJwt.path("message").asText()).isEqualTo("重复通知，已忽略");
            assertThat(countByOrderSn(orderSn)).isEqualTo(1);
        } finally {
            deletePayments(orderSn);
        }
    }

    // ================================================================== 断言 / 数据辅助

    /** 重放端点调用（人工 JWT）：断言 code=0 并返回完整 body */
    private JsonNode replayOk(Long paymentId) {
        ResponseEntity<JsonNode> resp = call(HttpMethod.POST,
                "/admin/payments/" + paymentId + "/replay-channel-callback", null, null, adminJwt);
        JsonNode body = resp.getBody();
        assertThat(body).as("重放端点必须有响应体").isNotNull();
        System.out.printf("   重放 id=%d → HTTP %d，body=%s%n", paymentId, resp.getStatusCode().value(), body);
        assertThat(body.path("code").asInt()).as("重放应成功处理（重复通知也是 code=0）：%s", body).isZero();
        return body;
    }

    private ChannelCallbackService.ChannelNotice notice(Long orderId, String orderSn, String tradeNo,
                                                        int type, BigDecimal amount) {
        return new ChannelCallbackService.ChannelNotice(1, tradeNo, orderSn, amount, type, orderId, null, null, null);
    }

    private JsonNode findRow(JsonNode records, Long id) {
        for (JsonNode row : records) {
            if (row.path("id").asLong() == id) return row;
        }
        return null;
    }

    private int countByOrderSn(String orderSn) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);
        return n == null ? 0 : n;
    }

    private BigDecimal sumAmount(String orderSn) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_record WHERE order_sn = ?", BigDecimal.class, orderSn);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private void deletePayments(String orderSn) {
        jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", orderSn);
    }

    private static String uniqueSn(String tag) {
        return SN_PREFIX + tag + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    /** 造一张订单行（order 有 fk_order_member，必须带 member_id） */
    private Long insertOrder(String orderSn, int status) {
        Long id = insertReturningId(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, receiver_name, "
                        + "receiver_phone, receiver_address, split_status) VALUES (?, ?, 66.00, ?, 0, ?, ?, ?, 0)",
                orderSn, memberId, status, "支付加固测试-收货人", "13700000011", "自动化测试地址（跑完即删）");
        createdOrderIds.add(id);
        return id;
    }

    /** 直插一条流水（造「历史/已有」的支付与退款行，避免用例依赖模拟支付的本地流水号） */
    private Long insertPayment(String orderSn, Long orderId, int type, String tradeNo, BigDecimal amount,
                               String operatorType, Long operatorId, String operatorName,
                               String refType, Long refId, Long originFlowId) {
        return insertReturningId(
                "INSERT INTO payment_record (order_sn, order_id, amount, type, channel, trade_no, ref_type, "
                        + "ref_id, origin_flow_id, operator_type, operator_id, operator_name, idem_key) "
                        + "VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?)",
                orderSn, orderId, amount, type, tradeNo, refType, refId, originFlowId,
                operatorType, operatorId, operatorName,
                type == PaymentRecord.TYPE_REFUND ? orderSn + ":REFUND:" + (refId == null ? 0 : refId) : orderSn + ":PAY");
    }

    private Long insertReturningId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("INSERT 应返回自增主键：%s", sql).isNotNull();
        return key.longValue();
    }

    private Long queryLong(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private String loginAsAdmin() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", "123456");
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login", body, JsonNode.class);
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        String token = node.path("data").path("accessToken").asText(null);
        assertThat(token).as("admin 登录失败（种子账号 admin/123456）：%s", node).isNotBlank();
        return token;
    }

    private JsonNode get(String path, String... kv) {
        StringBuilder url = new StringBuilder(path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            url.append(url.indexOf("?") >= 0 ? "&" : "?").append(kv[i]).append("=").append(kv[i + 1]);
        }
        ResponseEntity<JsonNode> resp = call(HttpMethod.GET, url.toString(), null, null, adminJwt);
        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asInt()).as("GET %s 应成功：%s", url, body).isZero();
        return body;
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String json,
                                          String apiKey, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) headers.setBearerAuth(jwt);
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        return rest.exchange(path, method, entity, JsonNode.class);
    }
}
