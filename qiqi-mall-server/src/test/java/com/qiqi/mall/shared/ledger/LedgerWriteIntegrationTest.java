package com.qiqi.mall.shared.ledger;

import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.DeliveryStatusService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.ReturnStatusService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.shared.ledger.mapper.DeliveryStatusLedgerMapper;
import com.qiqi.mall.shared.ledger.mapper.OrderStatusLedgerMapper;
import com.qiqi.mall.shared.ledger.mapper.ReturnStatusLedgerMapper;
import com.qiqi.mall.shared.security.ActorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 台账写入的**真库**集成测试（P2 第二步）。
 *
 * <h3>要证明的五件事</h3>
 * <ol>
 *   <li><b>三个入口各写一条台账</b>：出生（{@code from_status = NULL}）+ 每次流转一条；
 *       幂等键就是 {@code {业务ID}:{statusSeq}}，且 statusSeq 与库里的 {@code status_seq} 对得上；</li>
 *   <li><b>唯一键冲突 = 重放，静默跳过</b>：同一个 idem_key 插两次 → 仍是 1 行、不抛异常；</li>
 *   <li><b>非重复键的 SQL 错误必须抛</b>（这是 {@code ON DUPLICATE KEY UPDATE id = id} 相对
 *       {@code INSERT IGNORE} 的全部意义）：截断（1406）、NOT NULL（1048）、CHECK（3819）
 *       三种都必须抛，绝不能被「当成重放」吞掉；</li>
 *   <li><b>恒等式</b>：{@code COUNT(台账行 WHERE from_status IS NOT NULL) == status_seq}
 *       —— {@code scripts/check-data-integrity.sh} ⑥ 用的就是这条（出生行单列，不算进 seq）；</li>
 *   <li>所有断言都在**同一个事务**里跑（{@code @Transactional}），跑完自动回滚，不污染 dev 库。</li>
 * </ol>
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=LedgerWriteIntegrationTest}
 */
@SpringBootTest
@Transactional
class LedgerWriteIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private DeliveryOrderService deliveryOrderService;
    @Autowired
    private DeliveryStatusService deliveryStatusService;
    @Autowired
    private ReturnApplyService returnApplyService;
    @Autowired
    private ReturnStatusService returnStatusService;
    @Autowired
    private OrderStatusLedgerMapper orderLedgerMapper;
    @Autowired
    private DeliveryStatusLedgerMapper deliveryLedgerMapper;
    @Autowired
    private ReturnStatusLedgerMapper returnLedgerMapper;

    private Long memberId;

    @BeforeEach
    void setUp() {
        memberId = insertReturningId(
                "INSERT INTO member (username, password, nickname, status) VALUES (?, '', '台账写入测试', 1)",
                "ledger-it-" + System.nanoTime() + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    // ================================================================ ① 三个入口都写台账

    @Test
    @DisplayName("① 订单：出生 1 行 + 两次流转 2 行；幂等键 {orderId}:{0,1,2}；status_seq 与台账同步")
    void orderLedgerRows() {
        Order order = newOrder(0);
        orderStatusService.recordBirth(order, OrderStatusService.REASON_CREATE, "集成测试建单");

        Order loaded = orderService.getById(order.getId());
        assertThat(loaded.getStatusSeq()).as("出生不写 UPDATE，status_seq 仍是列默认值 0").isZero();
        assertThat(orderStatusService.change(loaded, 7, OrderStatusService.REASON_PAY, null)).isEqualTo(1);
        Order afterPay = orderService.getById(order.getId());
        assertThat(afterPay.getStatus()).isEqualTo(7);
        assertThat(afterPay.getStatusSeq()).isEqualTo(1);

        assertThat(orderStatusService.change(afterPay, 8, OrderStatusService.REASON_AUDIT_PASS, null)).isEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT from_status, to_status, reason, operator_type, operator_name, idem_key, ref_type, ref_id "
                        + "FROM order_status_ledger WHERE order_id = ? ORDER BY id", order.getId());
        assertThat(rows).as("出生 1 条 + 流转 2 条").hasSize(3);

        assertThat(rows.get(0).get("from_status")).as("出生事件没有前态").isNull();
        assertThat(rows.get(0).get("to_status")).isEqualTo(0);
        assertThat(rows.get(0).get("reason")).isEqualTo(ReasonCodes.ORDER_CREATE);
        assertThat(rows.get(0).get("idem_key")).isEqualTo(order.getId() + ":0");

        assertThat(rows.get(1).get("from_status")).isEqualTo(0);
        assertThat(rows.get(1).get("to_status")).isEqualTo(7);
        assertThat(rows.get(1).get("reason")).isEqualTo(ReasonCodes.ORDER_PAY);
        assertThat(rows.get(1).get("idem_key")).isEqualTo(order.getId() + ":1");

        assertThat(rows.get(2).get("from_status")).isEqualTo(7);
        assertThat(rows.get(2).get("to_status")).isEqualTo(8);
        assertThat(rows.get(2).get("idem_key")).isEqualTo(order.getId() + ":2");

        assertThat(rows.get(1).get("operator_type")).as("没有认证上下文 = SYSTEM（不是空台账）")
                .isEqualTo(ActorResolver.SYSTEM);
        assertThat(rows.get(1).get("operator_name")).isEqualTo(ActorResolver.SYSTEM_NAME);
        assertThat(orderService.getById(order.getId()).getStatusSeq()).isEqualTo(2);
        assertInvariantHolds(order.getId());
    }

    @Test
    @DisplayName("② 包裹：出生行带 delivery_sn 快照 + 流转行；幂等键 {deliveryId}:{seq}")
    void deliveryLedgerRows() {
        Order order = newOrder(1);
        DeliveryOrder delivery = newDelivery(order.getId());
        deliveryStatusService.recordBirth(delivery, DeliveryStatusService.REASON_CREATE, "集成测试拆单");

        DeliveryOrder loaded = deliveryOrderService.getById(delivery.getId());
        assertThat(deliveryStatusService.change(loaded, DeliveryOrder.DISPATCHED,
                DeliveryStatusService.REASON_DISPATCH, null)).isEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT delivery_sn, from_status, to_status, reason, idem_key FROM delivery_status_ledger "
                        + "WHERE delivery_id = ? ORDER BY id", delivery.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("delivery_sn")).as("已登记冗余列：包裹被物理删除后单号只能靠它")
                .isEqualTo(delivery.getDeliverySn());
        assertThat(rows.get(0).get("from_status")).isNull();
        assertThat(rows.get(1).get("from_status")).isEqualTo(DeliveryOrder.PENDING);
        assertThat(rows.get(1).get("to_status")).isEqualTo(DeliveryOrder.DISPATCHED);
        assertThat(rows.get(1).get("reason")).isEqualTo(ReasonCodes.DELIVERY_DISPATCH);
        assertThat(rows.get(1).get("idem_key")).isEqualTo(delivery.getId() + ":1");
        assertThat(deliveryOrderService.getById(delivery.getId()).getStatusSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 售后：出生行带 order_id 冗余 + 审核流转行")
    void returnLedgerRows() {
        Order order = newOrder(1);
        ReturnApply apply = newReturnApply(order.getId());
        returnStatusService.recordBirth(apply, ReturnStatusService.REASON_CREATE, "集成测试申请");

        ReturnApply loaded = returnApplyService.getById(apply.getId());
        assertThat(returnStatusService.change(loaded, 1, ReturnStatusService.REASON_APPROVE,
                "同意退款", null)).isEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id, from_status, to_status, reason, reason_detail, idem_key FROM return_status_ledger "
                        + "WHERE return_apply_id = ? ORDER BY id", apply.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("order_id")).as("已登记冗余列：删单后仍能从订单反查售后流水")
                .isEqualTo(order.getId());
        assertThat(rows.get(1).get("from_status")).isEqualTo(0);
        assertThat(rows.get(1).get("to_status")).isEqualTo(1);
        assertThat(rows.get(1).get("reason")).isEqualTo(ReasonCodes.RETURN_APPROVE);
        assertThat(rows.get(1).get("reason_detail")).isEqualTo("同意退款");
        assertThat(rows.get(1).get("idem_key")).isEqualTo(apply.getId() + ":1");
    }

    @Test
    @DisplayName("④ 对账恒等式：COUNT(台账 from_status IS NOT NULL) == status_seq（check-data-integrity ⑥ 的口径）")
    void ledgerMatchesStatusSeq() {
        Order order = newOrder(0);
        orderStatusService.recordBirth(order, OrderStatusService.REASON_CREATE, null);
        Order loaded = orderService.getById(order.getId());
        orderStatusService.change(loaded, 7, OrderStatusService.REASON_PAY, null);
        orderStatusService.change(orderService.getById(order.getId()), 8, OrderStatusService.REASON_AUDIT_PASS, null);
        assertInvariantHolds(order.getId());
    }

    @Test
    @DisplayName("④' 超长自由文本（400 字审核意见）不炸业务：reason_detail 按列宽裁到 255，业务事务照常提交")
    void longFreeTextIsClipped() {
        Order order = newOrder(1);
        ReturnApply apply = newReturnApply(order.getId());
        String longText = "退".repeat(400);          // return_apply.description 是 VARCHAR(500)，业务本来允许
        assertThat(returnStatusService.change(returnApplyService.getById(apply.getId()), 2,
                ReturnStatusService.REASON_REJECT, longText, null))
                .as("自由文本再长也不许把审核事务打回（那是新增的业务故障）").isEqualTo(1);
        String stored = jdbc.queryForObject(
                "SELECT reason_detail FROM return_status_ledger WHERE return_apply_id = ? AND from_status IS NOT NULL",
                String.class, apply.getId());
        assertThat(stored).as("裁到 255 且带省略号（原文仍在 return_apply.description）")
                .hasSize(255).endsWith("…");
    }

    // ================================================================ ② 重复键 = 重放，静默跳过

    @Test
    @DisplayName("⑤ 同一个 idem_key 插两次：只有 1 行、第二次不抛错（重放幂等）")
    void duplicateIdemKeyIsSilent() {
        Order order = newOrder(0);
        String idem = order.getId() + ":99";
        int first = orderLedgerMapper.insert(order.getId(), 7, 8, ReasonCodes.AUDIT_PASS, null,
                ActorResolver.SYSTEM, null, ActorResolver.SYSTEM_NAME, null, null, null, idem);
        int second = orderLedgerMapper.insert(order.getId(), 7, 8, ReasonCodes.AUDIT_PASS, null,
                ActorResolver.SYSTEM, null, ActorResolver.SYSTEM_NAME, null, null, null, idem);

        assertThat(countOrderLedger(order.getId())).as("重放不该记第二条").isEqualTo(1);
        System.out.printf("⑤ 重复键：第一次 affected=%d，第二次 affected=%d，台账仍 %d 行（ON DUPLICATE KEY 静默跳过）%n",
                first, second, countOrderLedger(order.getId()));
    }

    // ================================================================ ③ 非重复键错误必须抛

    @Test
    @DisplayName("⑥ 反向：reason 超长（1406 数据截断）必须抛，不能被当成重放吞掉")
    void truncationThrows() {
        Order order = newOrder(0);
        String tooLong = "X".repeat(40);        // reason 列 VARCHAR(32)
        assertThatThrownBy(() -> orderLedgerMapper.insert(order.getId(), 0, 7, tooLong, null,
                ActorResolver.SYSTEM, null, ActorResolver.SYSTEM_NAME, null, null, null, order.getId() + ":1"))
                .as("STRICT 模式下超长是错误（不是截断）：必须抛出并回滚业务事务")
                .isInstanceOf(DataAccessException.class);
        assertThat(countOrderLedger(order.getId())).as("抛错就不该留下半截台账").isZero();
        System.out.println("⑥ 截断：reason 40 字符 → 抛 DataAccessException（未被吞掉）");
    }

    @Test
    @DisplayName("⑦ 反向：operator_type = NULL（1048 NOT NULL 违规）必须抛")
    void notNullThrows() {
        Order order = newOrder(0);
        assertThatThrownBy(() -> orderLedgerMapper.insert(order.getId(), 0, 7, ReasonCodes.ORDER_PAY, null,
                null, null, ActorResolver.SYSTEM_NAME, null, null, null, order.getId() + ":1"))
                .as("operator_type 是 NOT NULL：INSERT IGNORE 会静默吞掉，这里必须抛")
                .isInstanceOf(DataAccessException.class);
        assertThat(countOrderLedger(order.getId())).isZero();
        System.out.println("⑦ NOT NULL：operator_type = null → 抛 DataAccessException（未被吞掉）");
    }

    @Test
    @DisplayName("⑧ 反向：operator_type = ROBOT（CHECK 违规 3819）必须抛")
    void checkConstraintThrows() {
        Order order = newOrder(0);
        assertThatThrownBy(() -> orderLedgerMapper.insert(order.getId(), 0, 7, ReasonCodes.ORDER_PAY, null,
                "ROBOT", null, "robot", null, null, null, order.getId() + ":1"))
                .as("ck_*_operator_type 只允许 ADMIN/MEMBER/APIKEY/SYSTEM")
                .isInstanceOf(DataAccessException.class);
        assertThat(countOrderLedger(order.getId())).isZero();
        System.out.println("⑧ CHECK：operator_type = 'ROBOT' → 抛 DataAccessException（未被吞掉）");
    }

    // ================================================================ 夹具

    /** 建一张真实订单（走唯一入口 init + save，与生产建单同一条路径） */
    private Order newOrder(int status) {
        Order order = new Order();
        order.setOrderSn("LEDGER" + System.nanoTime() + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setMemberId(memberId);
        order.setTotalAmount(new BigDecimal("12.34"));
        order.setReceiverName("台账测试收货人");
        order.setReceiverPhone("13700000000");
        order.setReceiverAddress("自动化测试地址（跑完即回滚）");
        order.setSplitStatus(0);
        orderStatusService.init(order, status);
        orderService.save(order);
        return order;
    }

    /** 建一个真实包裹（仓库取库里已有的第一个，避免外键失败） */
    private DeliveryOrder newDelivery(Long orderId) {
        DeliveryOrder delivery = new DeliveryOrder();
        delivery.setDeliverySn("DLLEDGER" + System.nanoTime() + ThreadLocalRandom.current().nextInt(1000, 9999));
        delivery.setOrderId(orderId);
        delivery.setWarehouseId(jdbc.queryForObject("SELECT id FROM warehouse ORDER BY id LIMIT 1", Long.class));
        delivery.setSource(DeliveryOrder.SOURCE_AUTO);
        delivery.setReceiverName("台账测试收货人");
        delivery.setReceiverPhone("13700000000");
        delivery.setReceiverAddress("自动化测试地址（跑完即回滚）");
        deliveryStatusService.init(delivery, DeliveryOrder.PENDING);
        deliveryOrderService.save(delivery);
        return delivery;
    }

    /** 建一张真实退货申请（状态 0 待处理） */
    private ReturnApply newReturnApply(Long orderId) {
        ReturnApply apply = new ReturnApply();
        apply.setOrderId(orderId);
        apply.setMemberId(memberId);
        apply.setReturnReason("台账测试-质量问题");
        apply.setDescription("集成测试");
        apply.setReasonId(jdbc.queryForObject("SELECT id FROM return_reason ORDER BY id LIMIT 1", Long.class));
        returnStatusService.init(apply, 0);
        returnApplyService.save(apply);
        return apply;
    }

    private void assertInvariantHolds(Long orderId) {
        Integer seq = jdbc.queryForObject("SELECT status_seq FROM `order` WHERE id = ?", Integer.class, orderId);
        Integer transitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_status_ledger WHERE order_id = ? AND from_status IS NOT NULL",
                Integer.class, orderId);
        Integer births = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_status_ledger WHERE order_id = ? AND from_status IS NULL",
                Integer.class, orderId);
        assertThat(transitions).as("恒等式：流转行数 == status_seq（出生行单列）").isEqualTo(seq);
        assertThat(births).as("新生订单恰好一条出生行").isEqualTo(1);
    }

    private int countOrderLedger(Long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM order_status_ledger WHERE order_id = ?",
                Integer.class, orderId);
        return n == null ? 0 : n;
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
}
