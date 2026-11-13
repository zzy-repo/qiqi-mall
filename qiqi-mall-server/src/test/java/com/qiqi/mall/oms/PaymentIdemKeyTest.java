package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.shared.ledger.LedgerProperties;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付幂等键 {@code payment_record.uk_idem} 的**真库**集成测试。
 *
 * <h3>为什么必须单开一个类（这是原来 e2e 的盲区）</h3>
 * 既有 e2e「支付并发冲突」场景（{@code e2e/tests/scenarios/01-买家侧/8-支付并发冲突.spec.js}）
 * 走的是**两道业务闸**，根本到不了 {@code payment_record}：
 * <pre>
 * ① 状态守卫 PortalOrderController#pay（status != 0 → code=1「当前订单状态不可支付」）
 * ② 状态 CAS  PortalOrderController#pay（WHERE version=? 影响 0 行 → 409「订单状态已变化，请刷新后重试」）
 * </pre>
 * 第二笔在调用 {@code record()} **之前**就被拒了，所以 {@code uk_idem} 一次都没被命中 ——
 * 「幂等键兜重放」一直是设计意图，不是被验事实。同一个坑对退款更明显：退货审核的重复提交
 * 同样先被状态闸门挡住。
 *
 * <h3>四条用例各证明什么</h3>
 * <ol>
 *   <li><b>①命中幂等键</b>：**绕过 controller**，直接调 {@link PaymentRecordService#record} 两次
 *       （同 orderSn 同类型、同幂等键）→ 库里只有 1 行、第二次不抛异常（静默 return，视为重放）；</li>
 *   <li><b>②幂等键与业务闸的分工</b>：走 controller 的支付接口重放 → 被**业务闸**拒（code=1），
 *       流水仍是 1 行。把「这一条是业务闸挡的、不是幂等键挡的」固化进断言注释，
 *       免得后人看见「1 行」就以为幂等键被验过了；</li>
 *   <li><b>③退款幂等键</b>：同 returnApplyId 重复退款 → 1 行，幂等键 = {@code {orderSn}:REFUND:{id}}
 *       （不同申请各退一笔则不该被吞 —— 幂等键带 refId 的全部理由）；</li>
 *   <li><b>④唯一键真在库上</b>：绕过 service 用 JdbcTemplate 直接插一条相同 idem_key →
 *       {@link DuplicateKeyException} 且报文里就是 {@code uk_idem}。证明约束是库上的事实，
 *       不是「代码里的君子协定」（顺带证明它不是撞 {@code uk_channel_trade} 的误判）。</li>
 * </ol>
 *
 * <h3>事务与落库口径</h3>
 * ①③④ 是 {@code @Transactional} 的方法级事务：真写真读真抛约束，跑完回滚，不污染 dev 库。
 * ② 必须**不**带事务：它经真 HTTP（RANDOM_PORT）打接口，服务端线程看不到测试线程里未提交的行
 * （uncommitted 的订单对另一个连接根本不存在），所以那一条自己 commit，并在 finally 里显式删干净。
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=PaymentIdemKeyTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentIdemKeyTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PaymentRecordService paymentRecordService;
    @Autowired
    private LedgerProperties ledger;
    @Autowired
    private DataSource dataSource;      // ⑤ 造「另一个已提交的写入方」：必须另开独立连接

    /** ② 造出来的会员 id（② 不带事务，得自己收尾） */
    private final List<Long> createdMembers = new ArrayList<>();

    @AfterAll
    void cleanup() {
        // ⑤ 在「另一条连接」上真提交了一行，本类事务回滚盖不到它 —— 而且测试事务在撞 uk_idem 之后
        // 会对那一行持有共享锁，所以在用例内部删会等到 lock wait timeout。
        // 因此统一放到 @AfterAll（此时各用例事务已回滚、锁已释放）按单号前缀删掉。
        int foreign = jdbc.update("DELETE FROM payment_record WHERE order_sn LIKE 'IDEMC%'");
        for (Long id : createdMembers) {
            jdbc.update("DELETE FROM member WHERE id = ?", id);
        }
        System.out.printf("[清理] ② 自建会员 %d 个已删除；⑤ 跨事务提交的流水 %d 行已删除"
                        + "（② 的订单/流水/状态台账在用例 finally 里按单号删）%n",
                createdMembers.size(), foreign);
    }

    // ================================================================== ① 命中幂等键

    /**
     * ① **幂等键真的被撞到了**：绕开 controller 的两道业务闸，直接调 service 记录两次。
     *
     * <p>这是本类存在的理由：e2e 里那个「并发支付」永远在 {@code record()} 之前就被拒，
     * 所以 {@code uk_idem} 到底会不会被撞、撞了之后是不是静默 return，此前**没人验过**。
     *
     * <p>断言：①第一次成功落 1 行；②第二次**不抛异常**（同幂等键 = 重放，静默 return）；
     * ③按 order_sn 数仍是 1 行；④那一行的 idem_key 正是 {@code {orderSn}:PAY}。
     */
    @Test
    @Order(1)
    @Transactional
    @DisplayName("① 直接调 record() 两次（同 orderSn 同类型）→ 撞 uk_idem、静默 return、payment_record 只有 1 行")
    void hitIdemKey_sameOrderSnTwice_onlyOneRow() {
        // 前提断言：幂等键只在 paymentTraceOn 时才写；开关关了这个用例就变成「什么都没验」
        assertThat(ledger.paymentTraceOn()).as("幂等键写入依赖 qiqi.ledger.payment-trace-enabled=true").isTrue();

        String orderSn = uniqueSn("IDEM");
        String idemKey = orderSn + ":PAY";

        // 第一次：真落库
        paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, null, null, null, null);
        int afterFirst = countByOrderSn(orderSn);
        System.out.printf("① 第一次 record(%s, PAY) → payment_record 行数=%d，idem_key=%s%n",
                orderSn, afterFirst, idemKeysOf(orderSn));
        assertThat(afterFirst).as("第一次必须真的写进去 1 行").isEqualTo(1);
        assertThat(idemKeysOf(orderSn)).as("幂等键口径：{orderSn}:PAY").containsExactly(idemKey);

        // 第二次：同 orderSn、同类型 → 同一个幂等键 → 撞 uk_idem → 静默 return（视为重放）
        assertThatCode(() -> paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, null, null, null, null))
                .as("同一个幂等键的重放必须**静默跳过**（不抛异常）")
                .doesNotThrowAnyException();

        int afterSecond = countByOrderSn(orderSn);
        System.out.printf("① 第二次 record(%s, PAY)（同一幂等键）→ 不抛异常，payment_record 行数=%d%n",
                orderSn, afterSecond);
        assertThat(afterSecond).as("**第二次不许再多一行流水**（幂等键兜住了重放）").isEqualTo(1);
        assertThat(countByIdemKey(idemKey)).as("该幂等键在库里仍只有 1 行").isEqualTo(1);

        // 关键 SQL（复现用）
        System.out.println("   关键 SQL：SELECT COUNT(*) FROM payment_record WHERE order_sn='" + orderSn + "' → "
                + afterSecond + "；idem_key='" + idemKey + "' → " + countByIdemKey(idemKey));
    }

    // ================================================================== ② 幂等键与业务闸的分工

    /**
     * ② **这条走的不是幂等键，是业务闸** —— 把两者的分工钉在代码里。
     *
     * <p>同一笔支付请求经 controller 重放（订单已经是已付款/待审核状态）：
     * <ul>
     *   <li>第一笔：status 0 → 状态 CAS 命中 → 订单 0→7，{@code record()} 落 1 条 {@code {orderSn}:PAY}；</li>
     *   <li>第二笔：{@code PortalOrderController#pay} 第一行守卫 {@code order.getStatus() != 0}
     *       直接命中 → {@code Result.fail("当前订单状态不可支付")}（HTTP 200 + code=1）。
     *       <b>它根本没走到 {@code paymentRecordService.record()}</b>，所以这里「流水仍是 1 行」
     *       证明的是**业务闸**有效，与 {@code uk_idem} 无关（幂等键是第 ① 条验的）。</li>
     * </ul>
     *
     * <p>为什么必须把这句话写进断言：e2e 里断言「没有第二条流水」的那两条用例，走的正是这里这条路径；
     * 如果把 ② 当成幂等键的证据，就会重演本类开头说的那个盲区。
     *
     * <p>本用例**不带事务**：真 HTTP 打接口，服务端是另一个连接，测试线程里未提交的订单它看不见。
     * 所以自造的数据全部提交，跑完在 finally 里按单号删干净（payment_record 没有外键，必须单独删）。
     */
    @Test
    @Order(2)
    @DisplayName("② controller 重放支付 → 被业务闸拒（code=1「当前订单状态不可支付」），流水仍 1 行；这条不是幂等键的功劳")
    void replayViaController_blockedByBusinessGate_notByIdemKey() {
        PortalSession buyer = registerPortalMember();
        String orderSn = uniqueSn("IDEM");
        Long orderId = insertOrder(orderSn, buyer.memberId(), "0");   // 0 待付款（无事务 → 自动提交）
        try {
            // ① 第一笔支付：正常成功
            JsonNode first = ok(post("/portal/orders/" + orderId + "/pay", buyer.jwt()));
            int statusAfterFirst = orderStatus(orderId);
            int rowsAfterFirst = countByOrderSn(orderSn);
            System.out.printf("② ① 首次支付 → code=0，订单=%d，payment_record=%d 行（idem_key=%s）%n",
                    statusAfterFirst, rowsAfterFirst, idemKeysOf(orderSn));
            assertThat(first.path("order").path("status").asInt()).as("支付后订单进入 7 待审核").isEqualTo(7);
            assertThat(rowsAfterFirst).as("第一次支付落 1 条流水").isEqualTo(1);
            assertThat(idemKeysOf(orderSn)).as("首次支付的幂等键").containsExactly(orderSn + ":PAY");

            // ② 同一请求重放：订单已不是 0 待付款 → **状态守卫先命中**，record() 一次都没被调用
            ResponseEntity<JsonNode> resp = post("/portal/orders/" + orderId + "/pay", buyer.jwt());
            JsonNode body = resp.getBody();
            System.out.printf("② ② 重放同一支付 → HTTP %d，code=%d，message=%s，payment_record=%d 行%n",
                    resp.getStatusCode().value(), body.path("code").asInt(),
                    body.path("message").asText(), countByOrderSn(orderSn));

            // 断言口径（按现状）：状态守卫是 code=1 的**可读业务失败**（HTTP 200），
            // 不是 409 —— 409 属于「读出来之后被别人改过」的 CAS 落空分支（真并发才走得到）。
            assertThat(resp.getStatusCode().value()).as("业务守卫是 HTTP 200 + code=1，不是 4xx").isEqualTo(200);
            assertThat(body.path("code").asInt()).as("被业务闸拒绝：code=1").isEqualTo(1);
            assertThat(body.path("message").asText())
                    .as("买家视角文案（PortalOrderController#pay 的状态守卫）")
                    .isEqualTo("当前订单状态不可支付");

            // ★ 这条断言证明的是**业务闸**，不是幂等键 ★
            assertThat(countByOrderSn(orderSn))
                    .as("**重放没有多记流水 —— 但拦下它的是 PortalOrderController 的状态守卫"
                            + "（status != 0 → code=1），不是 payment_record.uk_idem："
                            + "第二笔在到达 record() 之前就被拒了。幂等键的证据见用例 ①/③/④**")
                    .isEqualTo(1);
            assertThat(orderStatus(orderId)).as("订单状态也不许被重放改动").isEqualTo(7);
        } finally {
            // payment_record / order_status_ledger 没有外键，必须按单号/订单 id 单独删；
            // order 上还有 fk_order_member，所以先删订单再删会员（会员在 @AfterAll 里删）
            jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", orderSn);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        }
    }

    // ================================================================== ③ 退款幂等键

    /**
     * ③ **退款幂等键**：{@code {orderSn}:REFUND:{returnApplyId}}。
     *
     * <p>为什么退款键必须带申请 id（不是只带 orderSn）：同一张订单可以有多笔**合法**退款
     * （第二次退货申请审核通过）。只用 {@code {orderSn}:REFUND} 会把第二笔合法退款当成重放**吞掉**
     * —— 那才是真丢钱。所以这里验两件事：
     * <pre>
     * ① 同一张申请重复退款 → 只有 1 行（重放被幂等键兜住）；
     * ② 同一张订单、**另一张**申请再退一笔 → 必须有第 2 行（不同申请不算重放）。
     * </pre>
     * 走的是和 {@code ReturnApplyController} 审核通过时**同一个调用形状**
     * （type=REFUND、refType=RETURN_APPLY、refId=申请 id、originFlowId=原支付流水）。
     */
    @Test
    @Order(3)
    @Transactional
    @DisplayName("③ 同一 returnApplyId 重复退款 → 只有 1 行，idem_key={orderSn}:REFUND:{id}；不同申请照常各记一笔")
    void refundIdemKey_sameApplyOnlyOnce_otherApplyStillRecorded() {
        Long memberId = seedMemberId();
        String orderSn = uniqueSn("IDEMR");
        Long orderId = insertOrder(orderSn, memberId, "1");           // 1 已付款（退款的前置）
        Long applyId = insertReturnApply(orderId, memberId);
        Long otherApplyId = insertReturnApply(orderId, memberId);    // 第二张申请（不同申请 = 合法第二笔退款）

        // 原支付流水（退款要溯源到它）
        paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, orderId, null, null, null);
        Long originFlowId = paymentRecordService.findOriginFlowId(orderSn);
        assertThat(originFlowId).as("退款要能溯源到原支付流水").isNotNull();

        // 同一张申请退两次（第二次是重放）
        paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_REFUND, 1, orderId, ReasonCodes.REF_RETURN_APPLY, applyId, originFlowId);
        int afterFirstRefund = countByType(orderSn, PaymentRecord.TYPE_REFUND);
        assertThatCode(() -> paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_REFUND, 1, orderId, ReasonCodes.REF_RETURN_APPLY, applyId, originFlowId))
                .as("同一张申请的重放必须静默跳过（不抛异常）")
                .doesNotThrowAnyException();
        int afterReplay = countByType(orderSn, PaymentRecord.TYPE_REFUND);
        String refundIdemKey = orderSn + ":REFUND:" + applyId;
        System.out.printf("③ 同一申请 %d 退两次 → 退款流水 %d→%d 行；idem_key=%s（库里 %d 行）%n",
                applyId, afterFirstRefund, afterReplay, refundIdemKey, countByIdemKey(refundIdemKey));

        assertThat(afterFirstRefund).as("第一笔退款落 1 行").isEqualTo(1);
        assertThat(afterReplay).as("**同一申请重放不许退第二条**").isEqualTo(1);
        assertThat(idemKeysOfType(orderSn, PaymentRecord.TYPE_REFUND))
                .as("退款幂等键口径：{orderSn}:REFUND:{returnApplyId}")
                .containsExactly(refundIdemKey);

        // 反面对照：不同申请 = 合法的第二笔退款，绝不能被幂等键吞掉
        paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_REFUND, 1, orderId, ReasonCodes.REF_RETURN_APPLY, otherApplyId, originFlowId);
        System.out.printf("③ 另一张申请 %d 再退一笔 → 退款流水 %d 行（idem_key=%s）%n",
                otherApplyId, countByType(orderSn, PaymentRecord.TYPE_REFUND), idemKeysOfType(orderSn, 2));
        assertThat(countByType(orderSn, PaymentRecord.TYPE_REFUND))
                .as("**不同申请是合法第二笔退款，必须照常记（幂等键带 refId 的全部理由）**").isEqualTo(2);

        // 该订单总流水 = 1 支付 + 2 退款
        assertThat(countByOrderSn(orderSn)).isEqualTo(3);
        System.out.println("   关键 SQL：SELECT COUNT(*) FROM payment_record WHERE order_sn='" + orderSn
                + "' AND type=2 → " + countByType(orderSn, PaymentRecord.TYPE_REFUND));
    }

    // ================================================================== ④ 唯一键真在库上生效

    /**
     * ④ **uk_idem 是库上的约束，不是代码里的约定**：绕过 service，用 JdbcTemplate 直接插。
     *
     * <p>为什么非得绕过 service：走 service 只能证明「catch 到了异常并静默 return」，
     * 证明不了约束真的存在（service 里也可能是先 SELECT 后 INSERT 的假防重）。
     * 直接 SQL 插同一幂等键 → 必须抛 {@link DuplicateKeyException}，且报文里就是 {@code uk_idem}。
     *
     * <p>顺手排掉一种误判：两次插入用**不同的 channel/trade_no**，所以撞的不是
     * {@code uk_channel_trade}，只可能是 {@code uk_idem}。
     */
    @Test
    @Order(4)
    @Transactional
    @DisplayName("④ 绕过 service 直接 SQL 插相同 idem_key → DuplicateKeyException（报文含 uk_idem），证约束真在库上")
    void ukIdemExistsInDatabase_directInsertThrowsDuplicateKey() {
        // 先把索引本身读出来（information_schema 才是「库上事实」）
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols "
                        + "FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'payment_record' "
                        + "GROUP BY index_name, non_unique ORDER BY index_name");
        System.out.printf("④ information_schema.statistics(payment_record)：%s%n", indexes);
        assertThat(indexes).as("payment_record 上必须有 uk_idem 唯一索引")
                .anySatisfy(idx -> {
                    assertThat(idx.get("index_name")).isEqualTo("uk_idem");
                    assertThat(((Number) idx.get("non_unique")).intValue()).isZero();
                    assertThat(idx.get("cols")).isEqualTo("idem_key");
                });

        String orderSn = uniqueSn("IDEMU");
        String idemKey = orderSn + ":PAY";
        jdbc.update("INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                + "VALUES (?, 10.00, 1, 1, ?, ?)", orderSn, "DIRECT-A-" + orderSn, idemKey);

        // 同一幂等键、**不同** channel+trade_no → 只可能撞 uk_idem
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                        + "VALUES (?, 20.00, 1, 2, ?, ?)", orderSn, "DIRECT-B-" + orderSn, idemKey))
                .as("同一个 idem_key 直插必须被库拒绝")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_idem");

        System.out.printf("④ 直插同一 idem_key=%s → DuplicateKeyException(uk_idem)；该键在库中 %d 行%n",
                idemKey, countByIdemKey(idemKey));
        assertThat(countByIdemKey(idemKey)).as("撞了之后仍然只有 1 行（第二行根本没进去）").isEqualTo(1);
        assertThat(countByOrderSn(orderSn)).isEqualTo(1);
    }

    // ================================================================== ⑤ 真并发重放：幂等兜底不是「静默 return」

    /**
     * ⑤ **设计缺口（如实记录）**：{@code record()} 的「撞 uk_idem ⇒ 回查 ⇒ 静默 return」
     * 在**真并发**下不成立 —— 回查用的是同一事务的**快照读**，看不见对方刚刚提交的那一行。
     *
     * <p>复现时序（与真实支付事务同构：{@code pay()} 先 {@code orderService.getById}，
     * 那一刻本事务的 consistent snapshot 就定下来了）：
     * <pre>
     * ① 本事务先读一次（快照建立）
     * ② 另一个连接**真提交**同幂等键的一行（= 另一笔先到、已入账）
     * ③ 本事务再记一次 → INSERT 撞 uk_idem → service 里 countByIdemKey 是快照读 → 返回 0
     *    ⇒ 代码判定「不是重放」→ 把 DuplicateKeyException **原样抛出**
     *    （而不是它注释里承诺的「静默跳过」）
     * </pre>
     *
     * <p>为什么仍然是「可接受的缺口」：支付/退款两条线上路径都有业务闸在前面
     * （用例 ② 证明支付那条根本到不了 {@code record()}），所以今天没人能撞到这条分支。
     * 但只要出现「绕过状态闸的第二个写入方」（内部补单、对账重放、新支付入口），
     * 这条分支就会以 500 的形态出现。**这是既有实现的真实行为，此处只固化、不改业务代码。**
     *
     * <p>本用例自己提交了一行（另一个连接上），所以 finally 里也用另一个连接删掉，不让它留在库里。
     */
    @Test
    @Order(5)
    @Transactional
    @DisplayName("⑤ 真并发重放：快照挡住回查 → 抛 DuplicateKeyException 而非静默 return（设计缺口，如实固化）")
    void concurrentReplay_throwsInsteadOfSilentSkip_becauseSnapshotIsStale() throws Exception {
        String orderSn = uniqueSn("IDEMC");
        String idemKey = orderSn + ":PAY";

        // ① 事务里的第一条读 → consistent snapshot 在此刻定下来（pay() 的真实形态）
        jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);

        // ② 另一个连接真提交同幂等键的一行：模拟「另一笔已经入账」
        Long foreignId = insertOnOtherConnection(
                "INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                        + "VALUES (?, 100.00, 1, 1, ?, ?)", orderSn, "OTHER-" + orderSn, idemKey);
        int seenByThisTx = countByIdemKey(idemKey);
        int seenByFreshTx = countByIdemKeyOnOtherConnection(idemKey);
        System.out.printf("⑤ 另一个连接已提交 idem_key=%s（id=%d）；本事务快照读看到 %d 行，"
                        + "换一条新事务（新快照）看到 %d 行%n",
                idemKey, foreignId, seenByThisTx, seenByFreshTx);
        // ★ 关键事实：行**真的提交了**（新事务看得见），但**本事务看不见** —— REPEATABLE READ 的
        //   快照在本事务第一条读时就定下来了。service 里的 countByIdemKey 跑在同一个事务里，
        //   所以它同样看不见 ⇒ 「静默 return」这一步必然走不到。
        assertThat(seenByFreshTx).as("对方的行确实已提交（换新事务能看到）").isEqualTo(1);
        assertThat(seenByThisTx)
                .as("**本事务的快照早于对方提交 → 快照读看不见这一行，这正是缺口所在**").isZero();

        // ③ 本事务重放 → 撞 uk_idem → 回查走**旧快照** → 判定“不是重放” → 原样抛出
        assertThatThrownBy(() -> paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, null, null, null, null))
                .as("真并发重放：既有实现抛出 DuplicateKeyException，**不是**它注释里说的「静默跳过」")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_idem");
        System.out.println("⑤ 真并发重放 → DuplicateKeyException(uk_idem)："
                + "service 的 countByIdemKey 回查用的是本事务旧快照，看不见对方已提交的那一行"
                + "（该行由 @AfterAll 按单号前缀清理：撞键后本事务对它有共享锁，用例内删会 lock wait timeout）");
    }

    // ================================================================== 断言辅助

    /**
     * 用**另一条独立连接**（自动提交）写一行：本用例是 @Transactional 的，
     * 同一事务里的写入对方看不到、也不会真的提交，必须另开连接才能造出「已提交的第三方写入」。
     */
    private Long insertOnOtherConnection(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                assertThat(ps.executeUpdate()).as("另一个连接上这条 SQL 必须写成功：%s", sql).isEqualTo(1);
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    assertThat(rs.next()).as("另一个连接上应拿到自增主键").isTrue();
                    return rs.getLong(1);
                }
            }
        }
    }

    /** 同一条计数查询，但走**另一条新事务**（新快照）：用它证明「行确实已提交」 */
    private int countByIdemKeyOnOtherConnection(String idemKey) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM payment_record WHERE idem_key = ?")) {
                ps.setString(1, idemKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getInt(1);
                }
            }
        }
    }


    /** 该订单号的流水条数（payment_record 只有对账键 order_sn，没有外键） */
    private int countByOrderSn(String orderSn) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?",
                Integer.class, orderSn);
        return n == null ? 0 : n;
    }

    /** 该订单号某类流水条数：1 支付 / 2 退款 */
    private int countByType(String orderSn, int type) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ? AND type = ?",
                Integer.class, orderSn, type);
        return n == null ? 0 : n;
    }

    private int countByIdemKey(String idemKey) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE idem_key = ?",
                Integer.class, idemKey);
        return n == null ? 0 : n;
    }

    private List<String> idemKeysOf(String orderSn) {
        return jdbc.queryForList("SELECT idem_key FROM payment_record WHERE order_sn = ? ORDER BY id", String.class, orderSn);
    }

    private List<String> idemKeysOfType(String orderSn, int type) {
        return jdbc.queryForList("SELECT idem_key FROM payment_record WHERE order_sn = ? AND type = ? ORDER BY id",
                String.class, orderSn, type);
    }

    private int orderStatus(Long orderId) {
        Integer s = jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        return s == null ? -1 : s;
    }

    // ================================================================== 数据构造

    private static String uniqueSn(String prefix) {
        return prefix + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    /** 种子会员：只当作订单归属（不改它） */
    private Long seedMemberId() {
        Long id = jdbc.queryForObject("SELECT id FROM member ORDER BY id LIMIT 1", Long.class);
        assertThat(id).as("测试库里应至少有一个会员（种子数据）").isNotNull();
        return id;
    }

    /**
     * 直接插一张订单行（订单必须有 receiver_name；member_id 有外键 fk_order_member）。
     *
     * <p>用例 ② 是**不带事务**的（要经真 HTTP），这里的 INSERT 因此会立刻提交 ——
     * 服务端线程才看得见这张订单。
     */
    private Long insertOrder(String orderSn, Long memberId, String status) {
        return insertReturningId(
                "INSERT INTO `order` (order_sn, member_id, total_amount, status, pay_type, receiver_name, "
                        + "receiver_phone, receiver_address, split_status) VALUES (?, ?, 66.00, ?, 0, ?, ?, ?, 0)",
                orderSn, memberId, Integer.parseInt(status), "幂等键测试-收货人", "13700000000",
                "自动化测试地址（跑完即删）");
    }

    /** 一条待处理退货申请（refId 的来源；真实行，不是编的 id） */
    private Long insertReturnApply(Long orderId, Long memberId) {
        Long reasonId = jdbc.queryForObject("SELECT id FROM return_reason ORDER BY id LIMIT 1", Long.class);
        return insertReturningId(
                "INSERT INTO return_apply (order_id, member_id, return_reason, description, status, reason_id) "
                        + "VALUES (?, ?, '幂等键测试-质量问题', '跑完即删', 0, ?)",
                orderId, memberId, reasonId);
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

    /** 注册一个买家并拿到 C 端令牌（支付接口要求「本人订单」） */
    private PortalSession registerPortalMember() {
        String username = "idem-buyer-" + System.currentTimeMillis()
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"幂等键测试买家\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register",
                new HttpEntity<>(json, headers), JsonNode.class);
        JsonNode data = ok(resp);
        Long id = data.path("memberId").asLong();
        createdMembers.add(id);
        return new PortalSession(id, data.path("accessToken").asText());
    }

    // ================================================================== HTTP 辅助

    private ResponseEntity<JsonNode> post(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers), JsonNode.class);
    }

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    private record PortalSession(Long memberId, String jwt) {
    }
}
