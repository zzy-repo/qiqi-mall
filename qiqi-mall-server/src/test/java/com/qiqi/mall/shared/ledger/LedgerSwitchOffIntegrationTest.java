package com.qiqi.mall.shared.ledger;

import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.DeliveryStatusService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.order.entity.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开关关闭时的**等价性证明**（{@code qiqi.ledger.enabled=false}）。
 *
 * <h3>要证明什么</h3>
 * 关掉台账后，业务行为必须与 P2 第一步**逐字一致**：状态照改、{@code status_seq} 照涨、
 * {@code version} 照涨、返回值照旧 —— 只有台账一行不写。
 *
 * <p>为什么这条必须单独一个测试类：{@code @SpringBootTest(properties=…)} 需要另一个 Spring 上下文。
 * 也正因为如此，它顺带证明了「开关是启动期可配的一项，而不是运行时某种全局变量」。
 *
 * <p>验证方式：同一个流程（建单 → 支付 0→7 → 审核通过 7→8 → 拆出一个包裹 → 下发）跑一遍，
 * 断言 5 张台账表**行数增量全为 0**，而订单/包裹的状态与 status_seq 与开台账时完全一样。
 */
@SpringBootTest(properties = "qiqi.ledger.enabled=false")
@Transactional
class LedgerSwitchOffIntegrationTest {

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
    private LedgerProperties props;

    private Long memberId;

    @BeforeEach
    void setUp() {
        memberId = insertReturningId(
                "INSERT INTO member (username, password, nickname, status) VALUES (?, '', '台账开关关闭测试', 1)",
                "ledger-off-" + System.nanoTime() + ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    @Test
    @DisplayName("qiqi.ledger.enabled=false：状态/status_seq/version 行为不变，5 张台账表零写入")
    void switchOffKeepsBusinessBehaviorOnly() {
        assertThat(props.isEnabled()).as("开关确实关掉了（否则这个类什么也没证明）").isFalse();
        assertThat(props.statusOn()).isFalse();
        assertThat(props.auditOn()).isFalse();
        assertThat(props.wmsOn()).isFalse();

        long beforeOrder = count("order_status_ledger");
        long beforeDelivery = count("delivery_status_ledger");
        long beforeReturn = count("return_status_ledger");
        long beforeAudit = count("admin_audit_log");
        long beforeWms = count("wms_exchange_ledger");

        // ---- 建单（出生）----
        Order order = new Order();
        order.setOrderSn("LEDGEROFF" + System.nanoTime() + ThreadLocalRandom.current().nextInt(1000, 9999));
        order.setMemberId(memberId);
        order.setTotalAmount(new BigDecimal("9.90"));
        order.setReceiverName("开关测试收货人");
        order.setReceiverPhone("13700000000");
        order.setReceiverAddress("自动化测试地址（跑完即回滚）");
        order.setSplitStatus(0);
        orderStatusService.init(order, 0);
        orderService.save(order);
        orderStatusService.recordBirth(order, OrderStatusService.REASON_CREATE, null);   // 开关关 → 不写

        // ---- 支付 0 → 7，审核通过 7 → 8（与开台账时完全相同的一串流转）----
        Order loaded = orderService.getById(order.getId());
        assertThat(orderStatusService.change(loaded, 7, OrderStatusService.REASON_PAY, null))
                .as("状态写入照样返回影响行数（调用方据此判 409）").isEqualTo(1);
        Order afterPay = orderService.getById(order.getId());
        assertThat(afterPay.getStatus()).isEqualTo(7);
        assertThat(afterPay.getStatusSeq()).as("status_seq 照涨：它是状态写入的一部分，不属于台账").isEqualTo(1);
        assertThat(afterPay.getVersion()).isEqualTo(1);
        assertThat(orderStatusService.change(afterPay, 8, OrderStatusService.REASON_AUDIT_PASS, null)).isEqualTo(1);

        // ---- 拆一个包裹（出生）并下发 ----
        DeliveryOrder delivery = new DeliveryOrder();
        delivery.setDeliverySn("DLOFF" + System.nanoTime() + ThreadLocalRandom.current().nextInt(1000, 9999));
        delivery.setOrderId(order.getId());
        delivery.setWarehouseId(jdbc.queryForObject("SELECT id FROM warehouse ORDER BY id LIMIT 1", Long.class));
        delivery.setSource(DeliveryOrder.SOURCE_AUTO);
        delivery.setReceiverName("开关测试收货人");
        delivery.setReceiverPhone("13700000000");
        delivery.setReceiverAddress("自动化测试地址（跑完即回滚）");
        deliveryStatusService.init(delivery, DeliveryOrder.PENDING);
        deliveryOrderService.save(delivery);
        deliveryStatusService.recordBirth(delivery, DeliveryStatusService.REASON_CREATE, null);  // 开关关 → 不写
        DeliveryOrder dLoaded = deliveryOrderService.getById(delivery.getId());
        assertThat(deliveryStatusService.change(dLoaded, DeliveryOrder.DISPATCHED,
                DeliveryStatusService.REASON_DISPATCH, null)).isEqualTo(1);

        // ---- 结论：业务全对，台账一行不多 ----
        assertThat(count("order_status_ledger") - beforeOrder).as("订单台账零写入").isZero();
        assertThat(count("delivery_status_ledger") - beforeDelivery).as("包裹台账零写入").isZero();
        assertThat(count("return_status_ledger") - beforeReturn).as("售后台账零写入").isZero();
        assertThat(count("admin_audit_log") - beforeAudit).as("审计零写入").isZero();
        assertThat(count("wms_exchange_ledger") - beforeWms).as("WMS 台账零写入").isZero();

        assertThat(orderService.getById(order.getId()).getStatusSeq()).isEqualTo(2);
        assertThat(deliveryOrderService.getById(delivery.getId()).getStatusSeq()).isEqualTo(1);
        System.out.printf("开关关闭等价性：订单 %d status=%d status_seq=%d version=%d；包裹 %d status=%d status_seq=%d；"
                        + "五张台账表增量 0%n",
                order.getId(), orderService.getById(order.getId()).getStatus(),
                orderService.getById(order.getId()).getStatusSeq(), orderService.getById(order.getId()).getVersion(),
                delivery.getId(), deliveryOrderService.getById(delivery.getId()).getStatus(),
                deliveryOrderService.getById(delivery.getId()).getStatusSeq());
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
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
