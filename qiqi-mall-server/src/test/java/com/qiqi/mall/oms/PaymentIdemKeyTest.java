package com.qiqi.mall.oms;

import com.fasterxml.jackson.databind.JsonNode;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.mapper.PaymentRecordMapper;
import com.qiqi.mall.oms.service.PaymentRecordService;
import com.qiqi.mall.oms.service.impl.PaymentRecordServiceImpl;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
 * <h3>九条用例各证明什么</h3>
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
 *   <li><b>⑤缺陷本体（旧用例原样转换：从「记录现状」改成「断言新行为」）</b>：本事务先建快照
 *       （复刻 {@code pay()} 的 {@code getById}），另一条连接真提交同幂等键的一行，本事务再
 *       {@code record()} → 撞 {@code uk_idem} → <b>新事务回查看得见那一行</b> → 静默 return。
 *       旧实现在这里抛 {@link DuplicateKeyException}（同一事务的快照读看不见对方刚提交的行）。
 *       时序是构造出来的、确定的，不靠线程运气；</li>
 *   <li><b>⑥真并发重放</b>：两条线程各自开事务、各自先建快照（{@link CountDownLatch} 对齐），
 *       再一起调 {@code record()} → <b>恰好 1 行、两个调用都不抛异常</b>，并断言「恰好一方在本事务
 *       快照里看到 0 行」（它只能靠新事务回查判出重放）。跑 20 轮；</li>
 *   <li><b>⑦撞 uk_channel_trade 仍然抛</b>：把流水号钉成已知值造出「渠道流水号重复」→
 *       必须抛可读业务异常（cause 是 uk_channel_trade 的 DuplicateKeyException），
 *       <b>不许</b>被当成重放静默吞掉（那是「同一笔钱可能记两遍」的疑似场景）；</li>
 *   <li><b>⑧回查自己失败不吞原始异常</b>：新事务回查抛异常（DB 抖动）→ 抛出去的仍是原始
 *       {@link DuplicateKeyException}，回查失败挂在 suppressed 上（两个事实都留痕）；</li>
 *   <li><b>⑨幂等键为空时行为不变</b>：{@code payment-trace} 关掉 → 不写 idem_key、不参与判重，
 *       与改动前一致（本次改动只在「撞键之后」加了一道回查，不碰这条路径）。</li>
 * </ol>
 *
 * <h3>事务与落库口径</h3>
 * ①③④⑤⑦⑧ 是 {@code @Transactional} 的方法级事务：真写真读真抛约束，跑完回滚，不污染 dev 库。
 * ②⑥⑨ 必须**不**带事务：② 经真 HTTP（RANDOM_PORT）打接口，服务端线程看不到测试线程里未提交的行；
 * ⑥ 的两个写入方各自要独立事务（测试线程的事务它们根本不用）；⑨ 要真提交才看得出「写没写幂等键」。
 * 这三条自己收尾：②⑨ 在 finally 里按单号删，⑥ 每轮删，跨连接提交的残留由 {@code @AfterAll} 兜（{@code IDEMC%}）。
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
    private DataSource dataSource;      // ⑤⑦⑧ 造「另一个已提交的写入方」：必须另开独立连接
    @Autowired
    private PaymentRecordMapper paymentRecordMapper;   // ⑦⑧ 手工构造的 service 实例要注入真实 mapper
    @Autowired
    private PlatformTransactionManager txManager;      // ⑥ 每条写线程自己的事务

    /** ② 造出来的会员 id（② 不带事务，得自己收尾） */
    private final List<Long> createdMembers = new ArrayList<>();

    @AfterAll
    void cleanup() {
        // ⑤⑦⑧ 在「另一条连接」上真提交了一行，本类事务回滚盖不到它 —— 而且测试事务在撞 uk_idem 之后
        // 会对那一行持有共享锁，所以在用例内部删会等到 lock wait timeout。
        // 因此统一放到 @AfterAll（此时各用例事务已回滚、锁已释放）按单号前缀删掉。
        // （⑥ 的并发写入是真提交的，但它每轮自己在 finally 里按 orderSn 删干净。）
        int foreign = jdbc.update("DELETE FROM payment_record WHERE order_sn LIKE 'IDEMC%'");
        for (Long id : createdMembers) {
            jdbc.update("DELETE FROM member WHERE id = ?", id);
        }
        System.out.printf("[清理] ② 自建会员 %d 个已删除；⑤⑦⑧ 跨事务提交的流水 %d 行已删除"
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

    // ================================================================== ⑤ 撞键回查换新事务（缺陷的确定复现面）

    /**
     * ⑤ **本轮的缺陷本体（用例原样转换：从「记录现状」改成「断言新行为」）**：
     * 撞 {@code uk_idem} 之后，对方那一行**已经提交**，只是本事务的快照读看不见它
     * （REPEATABLE READ 的快照在 {@code pay()} 的第一条 {@code getById} 就定下来了）。
     *
     * <pre>
     * ① 本事务先读一次（快照建立 = pay() 里的 orderService.getById）
     * ② 另一个连接**真提交**同幂等键的一行（= 另一笔先到、已入账）
     * ③ 本事务 record() → INSERT 撞 uk_idem
     *    旧实现：service 里 countByIdemKey 是同一事务的快照读 → 0 → 判「不是重放」→ 抛 DuplicateKeyException
     *    新实现：本事务快照读仍是 0，但**新事务回查**看得见那一行 → 按重放静默 return
     * </pre>
     *
     * <p>为什么这条必须单独留着（不能只靠用例 ⑥ 的并发跑）：这里的时序是**构造出来的确定时序**
     * （先建快照 → 再提交 → 再撞键），不依赖线程谁先谁后；它证明的是「回查必须换一个新事务」这一条，
     * 而不是「恰好这次跑得快」。用例 ⑥ 证明的是它在真并发里成立。
     *
     * <p>行数用**另一条连接**数：本用例的测试事务快照早于那行提交，自己数永远是 0（这正是缺陷的成因）。
     */
    @Test
    @Order(5)
    @Transactional
    @DisplayName("⑤ 对方已提交的幂等行本事务看不见 → 新事务回查命中 → 静默 return（旧实现这里抛 DuplicateKeyException）")
    void replayByAnotherWriter_silentSkip_evenThoughOwnSnapshotIsStale() throws Exception {
        String orderSn = uniqueSn("IDEMC");
        String idemKey = orderSn + ":PAY";

        // ① 事务里的第一条读 → consistent snapshot 在此刻定下来（与 pay() 的 getById 同构）
        jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);

        // ② 另一个连接真提交同幂等键的一行：模拟「另一笔已经入账」
        Long foreignId = insertOnOtherConnection(
                "INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                        + "VALUES (?, 100.00, 1, 1, ?, ?)", orderSn, "OTHER-" + orderSn, idemKey);
        int seenByThisTx = countByIdemKey(idemKey);
        int seenByFreshTx = countByIdemKeyOnOtherConnection(idemKey);
        System.out.printf("⑤ 另一个连接已提交 idem_key=%s（id=%d）；本事务快照读看到 %d 行，"
                        + "新事务（新快照）看到 %d 行%n",
                idemKey, foreignId, seenByThisTx, seenByFreshTx);

        assertThat(seenByFreshTx).as("对方的行确实已提交（换新事务能看到）").isEqualTo(1);
        assertThat(seenByThisTx)
                .as("本事务快照早于对方提交 → 快照读看不见它（这就是旧实现误判「不是重放」的成因）").isZero();

        // ③ 本事务重放：撞 uk_idem → 新事务回查命中 → 静默 return（**不许**抛）
        assertThatCode(() -> paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, null, null, null, null))
                .as("撞 uk_idem 且对方那行已提交（只是本事务快照看不见）→ 必须按重放静默跳过；"
                        + "旧实现在这里抛 DuplicateKeyException = 用户看到一次失败")
                .doesNotThrowAnyException();

        // ④ 行数从新事务数（本事务的快照仍旧看不见那一行，自己数会是 0）
        assertThat(countOnOtherConnection("SELECT COUNT(*) FROM payment_record WHERE idem_key = ?", idemKey))
                .as("库里该幂等键仍恰好 1 行（没有写第 2 行）").isEqualTo(1);
        assertThat(countOnOtherConnection("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", orderSn))
                .as("该订单的流水仍 1 行").isEqualTo(1);
        System.out.println("⑤ 撞 uk_idem（对方已提交、本事务快照看不见）→ 静默 return，库里仍是 1 行："
                + "回查换了新事务才看得见那一行（该行由 @AfterAll 按单号前缀清理："
                + "撞键后本事务对它有共享锁，用例内删会 lock wait timeout）");
    }

    // ================================================================== ⑥ 真并发重放（本轮修的缺陷的回归）

    /**
     * ⑥ **真并发**：两条线程同时 {@code record()} 同一 orderSn 同一类型 → 恰好 1 行、两个调用都不抛。
     *
     * <p>旧实现在这里必挂（历史实测）：撞 {@code uk_idem} 之后的回查是**同一事务的快照读**，
     * 而 REPEATABLE READ 的快照在 {@code pay()} 的第一条 {@code getById} 就定下来了 ——
     * 对方刚提交的幂等行它看不见 → 判定「不是重放」→ {@link DuplicateKeyException} 原样抛出。
     * 也就是说「幂等跳过」只是注释里的承诺，一旦有第二个写入方（对账重放/内部补单/新支付入口），
     * 用户看到的是一次失败（HTTP 500 形态），而不是幂等跳过。
     *
     * <h3>怎么控制交错（不靠 sleep）</h3>
     * 每条线程自己开一个事务，事务里**先做一次快照读**（复刻 {@code pay()} 的 {@code getById}），
     * 然后 {@link CountDownLatch} 互相等齐（{@code snapshotsReady} 计数 2）再动手写：
     * <pre>
     * 线程A: 事务开始 → SELECT(建立快照) → latch.countDown → await(等对方也就位) ┐
     * 线程B: 事务开始 → SELECT(建立快照) → latch.countDown → await(等对方也就位) ┘
     * 两边同时 record() → 一个 INSERT 成功（未提交），另一个的 INSERT 撞 uk_idem 后**阻塞**等前者提交
     * 前者提交 → 后者拿到 DuplicateKeyException → 新事务回查（新快照）命中 → 静默 return
     * </pre>
     * 关键点：两个快照都建立在「任何一方提交之前」（latch 保证），所以落败方的快照必然是旧的 ——
     * 这不是靠时序运气，而是靠 latch 强制出来的；用例还断言了「恰好一方在本事务里看到 0 行」，
     * 若哪一轮没造出旧快照，断言会立刻说话，而不是静默变成一条什么都没验的用例。
     *
     * <p>跑 {@value #CONCURRENT_ROUNDS} 轮（真库验证：不多行、不抛异常）。
     */
    @Test
    @Order(6)
    @DisplayName("⑥ 真并发：两线程同 orderSn 同类型同时 record() → 恰好 1 行、两个调用都不抛（20 轮）")
    void concurrentRecord_exactlyOneRow_neitherCallThrows() throws Exception {
        assertThat(ledger.paymentTraceOn()).as("幂等键写入依赖 qiqi.ledger.payment-trace-enabled=true").isTrue();
        for (int round = 1; round <= CONCURRENT_ROUNDS; round++) {
            String orderSn = uniqueSn("IDEMC");
            String idemKey = orderSn + ":PAY";
            try {
                ConcurrentOutcome outcome = raceTwoWriters(orderSn);
                System.out.printf("⑥ 第 %2d 轮 orderSn=%s → 抛异常=%d 次，两方本事务快照看到 %s，"
                                + "库内该单行数=%d，幂等键行数=%d%n",
                        round, orderSn, outcome.failures().size(), outcome.snapshotCounts(),
                        countByOrderSn(orderSn), countByIdemKey(idemKey));

                // ★ 断言 1：两个调用都不许抛（旧实现这里抛 DuplicateKeyException = 用户看到失败）
                assertThat(outcome.failures())
                        .as("第 %d 轮：并发重放的两个调用都必须正常返回（撞键方应静默 return），不许抛", round)
                        .isEmpty();
                // ★ 断言 2：恰好 1 行
                assertThat(countByOrderSn(orderSn)).as("第 %d 轮：并发重放不许写出第 2 行", round).isEqualTo(1);
                assertThat(countByIdemKey(idemKey)).as("第 %d 轮：该幂等键在库里恰好 1 行", round).isEqualTo(1);
                // ★ 断言 3：这一轮确实走到了「新事务回查」—— 落败方的本事务快照看不见对方那行（0）
                assertThat(outcome.snapshotCounts())
                        .as("第 %d 轮：必须恰好一方在本事务旧快照里看到 0 行 —— 它就是靠新事务回查才判出重放的；"
                                + "两方都看到 1 说明这轮没造出旧快照，等于没验到这条分支", round)
                        .containsExactlyInAnyOrder(0, 1);
            } finally {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", orderSn);
            }
        }
        System.out.printf("⑥ 真并发 %d 轮全部通过：每轮恰好 1 行、两个调用都不抛异常%n", CONCURRENT_ROUNDS);
    }

    /** ⑥ 的轮数：真库验证「不是偶然一次」 */
    private static final int CONCURRENT_ROUNDS = 20;

    /**
     * 跑一轮真并发：两条线程各自开事务（先建快照 → latch 对齐 → record()），收集两边的结果。
     *
     * <p>用 {@link CountDownLatch} 而不是 {@code sleep}：快照对齐是**必要条件**（两个快照都必须在
     * 任何一方提交之前建立），靠 sleep 只能靠运气，靠 latch 是确定的。
     */
    private ConcurrentOutcome raceTwoWriters(String orderSn) throws Exception {
        CountDownLatch snapshotsReady = new CountDownLatch(2);
        List<Integer> ownSnapshotCounts = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        Runnable writer = () -> {
            TransactionTemplate tx = new TransactionTemplate(txManager);   // PROPAGATION_REQUIRED
            try {
                tx.executeWithoutResult(status -> {
                    try {
                        // 快照建立点：与 pay() 里 orderService.getById 同构的一条普通读（走 service/mapper，
                        // 与 record() 自己的读写同一条事务连接 —— 这里若用别的连接读，快照就不在本事务里，
                        // 整个交错控制会失效，用例会变成「什么都没验」）
                        paymentRecordService.findOriginFlowId(orderSn);
                        snapshotsReady.countDown();
                        if (!snapshotsReady.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("两个写线程没能同时就位（10s 超时）");
                        }
                        paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                                PaymentRecord.TYPE_PAY, 1, null, null, null, null);
                        // 本事务里再数一次幂等键（同一条事务连接，走 mapper 而不是 jdbc）：
                        // 先插成功的一方看得见自己那行（1）；撞键的一方快照仍旧是旧的（0）
                        // —— 它只能靠**新事务回查**认出「这是重放」
                        ownSnapshotCounts.add(paymentRecordMapper.countByIdemKey(orderSn + ":PAY"));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("并发用例被中断", ie);
                    }
                });
            } catch (Throwable t) {
                failures.add(t);
            }
        };

        Thread a = new Thread(writer, "idem-writer-A");
        Thread b = new Thread(writer, "idem-writer-B");
        a.start();
        b.start();
        a.join(TimeUnit.SECONDS.toMillis(30));
        b.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(a.isAlive()).as("写线程 A 30s 内必须结束（卡住 = 锁等待/死锁）").isFalse();
        assertThat(b.isAlive()).as("写线程 B 30s 内必须结束（卡住 = 锁等待/死锁）").isFalse();
        return new ConcurrentOutcome(List.copyOf(failures), List.copyOf(ownSnapshotCounts));
    }

    /** 一轮并发的产物：两边抛出的异常、两边在本事务里最后看到的幂等键行数 */
    private record ConcurrentOutcome(List<Throwable> failures, List<Integer> snapshotCounts) {
    }

    // ================================================================== ⑦ 撞别道唯一键：仍然抛

    /**
     * ⑥ **反向用例**：撞的是 {@code uk_channel_trade(channel, trade_no)} 而不是 {@code uk_idem} →
     * 必须抛（可读业务异常，cause 保留库的原话），<b>不许</b>被当成重放静默吞掉。
     *
     * <p>为什么这条边界必须留住：{@code uk_idem} 撞了 = 「同一笔钱又记一遍」，already booked，可以跳过；
     * {@code uk_channel_trade} 撞了 = 「同一个渠道流水号又出现一次」，那是**同一笔钱可能被重复入账**的
     * 疑似场景（钱的事），必须让人看见、必须人工对账。这两件事长得一样（都是 DuplicateKeyException），
     * 含义相反，所以判据只能是回查，不能是异常报文。
     *
     * <p>为什么得用测试专用子类：真实 {@code record()} 的流水号是随机的（随机是必需的，见
     * {@code generateTradeNo} 的注释），永远撞不到 {@code uk_channel_trade}。所以把流水号钉成已知值，
     * 再让另一条连接先提交同 {@code (channel, trade_no)} 的一行 —— 这才真造出这个唯一键冲突。
     */
    @Test
    @Order(7)
    @Transactional
    @DisplayName("⑦ 撞 uk_channel_trade（渠道流水号重复）→ 仍然抛可读业务异常，绝不当重放吞掉")
    void channelTradeCollision_stillThrows_notSwallowedAsReplay() throws Exception {
        String orderSn = uniqueSn("IDEMT");
        String tradeNo = "DUP-TRADE-" + orderSn;
        // 另一个连接先提交同 (channel=1, trade_no) 的一行：模拟「这个渠道流水号已经入过账」
        Long foreignId = insertOnOtherConnection(
                "INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                        + "VALUES (?, 100.00, 1, 1, ?, ?)",
                uniqueSn("IDEMC"), tradeNo, "OTHER-ORDER-" + tradeNo + ":PAY");
        System.out.printf("⑥ 另一个连接已提交 channel=1 trade_no=%s（id=%d）；随后用同一流水号再记一笔订单 %s%n",
                tradeNo, foreignId, orderSn);

        assertThatThrownBy(() -> boundTradeNoService(tradeNo).record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, null, null, null, null))
                .as("撞 uk_channel_trade = 疑似同一笔钱重复入账，必须抛（不许像重放那样静默 return）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("uk_channel_trade")
                .hasCauseInstanceOf(DuplicateKeyException.class);

        assertThat(countByOrderSn(orderSn)).as("异常路径不许留下半条流水").isZero();
        assertThat(countByIdemKey(orderSn + ":PAY")).as("这笔的幂等键根本没进库").isZero();
        System.out.println("⑥ 撞 uk_channel_trade → IllegalStateException(cause=DuplicateKeyException(uk_channel_trade))，"
                + "库里没有新行（既没吞掉，也没留下垃圾）");
    }

    // ================================================================== ⑧ 回查自己失败：不吞原始异常

    /**
     * ⑧ **回查自己失败时不许吞原始异常**：撞 {@code uk_idem} 之后新事务回查抛异常（DB 抖动 /
     * 连接池耗尽 / 查询超时）—— 这时既不能当成「已入账」（那是拿一笔钱赌运气），也不能把
     * 回查的失败当成新的失败原因、把原始 {@link DuplicateKeyException} 吃掉。
     * 口径：抛原始异常，回查失败挂在 {@code suppressed} 上 —— 两个事实都留在堆栈里。
     */
    @Test
    @Order(8)
    @Transactional
    @DisplayName("⑧ 撞 uk_idem 后新事务回查自己失败 → 原始 DuplicateKeyException 不被吞（回查异常挂 suppressed）")
    void lookupFailure_doesNotSwallowOriginalDuplicateKey() throws Exception {
        String orderSn = uniqueSn("IDEML");
        String idemKey = orderSn + ":PAY";

        // 复刻 pay()：本事务先读一次 → 快照定下来（此后对方提交的行，本事务看不见）
        jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_sn = ?", Integer.class, orderSn);
        insertOnOtherConnection(
                "INSERT INTO payment_record (order_sn, amount, type, channel, trade_no, idem_key) "
                        + "VALUES (?, 100.00, 1, 1, ?, ?)", uniqueSn("IDEMC"), "OTHER-" + orderSn, idemKey);
        assertThat(countByIdemKeyOnOtherConnection(idemKey))
                .as("换一条新事务能看到 → 对方的行确实已提交").isEqualTo(1);
        assertThat(countByIdemKey(idemKey))
                .as("本事务快照读看不到对方刚提交的行（所以本轮一定要走新事务回查）").isZero();

        PaymentRecordServiceImpl brokenLookup = new PaymentRecordServiceImpl(ledger, txManager) {
            @Override
            protected int countCommittedByIdemKey(String idemKey) {
                throw new IllegalStateException("回查失败（用例 ⑧ 构造：模拟 DB 抖动）");
            }
        };
        ReflectionTestUtils.setField(brokenLookup, "baseMapper", paymentRecordMapper);

        assertThatThrownBy(() -> brokenLookup.record(orderSn, new BigDecimal("100.00"),
                PaymentRecord.TYPE_PAY, 1, null, null, null, null))
                .as("回查失败不能把原始 DuplicateKeyException 换成别的、更不能静默返回")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_idem")
                .satisfies(e -> assertThat(e.getSuppressed())
                        .as("回查失败的原因必须挂成 suppressed（两个事实都留痕，排障时都看得到）")
                        .anySatisfy(s -> assertThat(s).hasMessageContaining("回查失败（用例 ⑧ 构造")));
        System.out.println("⑧ 回查失败 → 原始 DuplicateKeyException(uk_idem) 原样抛出，"
                + "回查异常在 suppressed 里（没有吞、没有换异常）");
    }

    // ================================================================== ⑨ 幂等键为空：行为不变

    /**
     * ⑧ **幂等键为空时行为保持不变**：{@code qiqi.ledger.payment-trace-enabled=false} 时
     * {@code record()} 不写 {@code idem_key}（列是 NULL）→ 没有判重能力，两次都是新行。
     *
     * <p>为什么这条值得固化：本轮改的是「撞键之后」的处理，{@code idem_key} 为空时走的另一条分支
     * （原样抛出、不改异常类型）。开关一关就是线上的一键回退路径，回退路径的行为不许被顺手改掉。
     *
     * <p>本用例必须**不带事务**（要真提交才看得出写没写幂等键），自己恢复开关、自己删数据。
     */
    @Test
    @Order(9)
    @DisplayName("⑨ 幂等键为空（payment-trace 关闭）→ record() 行为不变：不写 idem_key、不参与判重")
    void traceOff_noIdemKey_behaviorUnchanged() {
        String orderSn = uniqueSn("IDEMN");
        boolean original = ledger.isPaymentTraceEnabled();
        ledger.setPaymentTraceEnabled(false);
        try {
            paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                    PaymentRecord.TYPE_PAY, 1, null, null, null, null);
            paymentRecordService.record(orderSn, new BigDecimal("100.00"),
                    PaymentRecord.TYPE_PAY, 1, null, null, null, null);
            System.out.printf("⑨ payment-trace=false 下 record() 两次 → 行数=%d，idem_key=%s%n",
                    countByOrderSn(orderSn), idemKeysOf(orderSn));

            assertThat(ledger.paymentTraceOn()).as("开关生效值").isFalse();
            assertThat(idemKeysOf(orderSn)).as("幂等键为空 ⇒ 两行都是 NULL（没有键可判重）").containsOnlyNulls();
            assertThat(countByOrderSn(orderSn))
                    .as("没有幂等键就没有防重能力 —— 这是既有口径，本轮改动不碰这条路径").isEqualTo(2);
        } finally {
            ledger.setPaymentTraceEnabled(original);     // 开关是共享 bean，必须还原
            jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", orderSn);
        }
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
        return countOnOtherConnection("SELECT COUNT(*) FROM payment_record WHERE idem_key = ?", idemKey);
    }

    /**
     * 在**另一条自动提交的连接**上数行：本用例的事务快照早于对方提交时，
     * 「库里到底几行」只能这样数（自己数会被旧快照骗成 0）。
     */
    private int countOnOtherConnection(String sql, Object... args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
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

    /**
     * 把渠道流水号钉成给定值的 {@code record()} 实例 —— 测试专用子类。
     *
     * <p>为什么需要：生产路径的流水号是随机生成的（必须随机，否则同毫秒两笔合法流水会撞
     * {@code uk_channel_trade}），所以「撞 uk_channel_trade」这个场景用真实调用**造不出来**。
     * 覆写 {@code generateTradeNo} 把流水号钉死，才能真验「撞别道唯一键时仍然抛」（用例 ⑦）。
     * mapper 用真实的那个（同一个库、同一套约束），只是流水号不是随机的。
     */
    private PaymentRecordServiceImpl boundTradeNoService(String fixedTradeNo) {
        PaymentRecordServiceImpl svc = new PaymentRecordServiceImpl(ledger, txManager) {
            @Override
            protected String generateTradeNo(int type) {
                return fixedTradeNo;
            }
        };
        ReflectionTestUtils.setField(svc, "baseMapper", paymentRecordMapper);
        return svc;
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
