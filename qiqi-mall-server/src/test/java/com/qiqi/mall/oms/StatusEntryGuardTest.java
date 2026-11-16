package com.qiqi.mall.oms;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
import com.qiqi.mall.audit.service.OrderAuditTagService;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import com.qiqi.mall.oms.service.DeliveryStatusService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.oms.service.ReturnApplyService;
import com.qiqi.mall.oms.service.ReturnStatusService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.mapper.OrderMapper;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.ledger.StatusLedgerWriter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * **入口层**单测：状态写入收敛成单入口后，三个「不许丢」的东西还在不在。
 *
 * <p>为什么需要这一层（而不是只靠 e2e）：e2e 只能证明「被覆盖到的路径还是对的」。
 * 如果收敛时把「比较并交换」写成「无条件 setter」，只要没有并发场景就永远是绿的 ——
 * 所以这里直接对入口方法本身断言三件事：
 * <ol>
 *   <li><b>CAS 不丢</b>：更新语句<b>带条件</b>（订单/包裹是 {@code version=读到的值}，
 *       退货申请是无 version 表的 {@code status=读到的状态}），且 <b>0 行时不改内存实体、不吞异常</b>
 *       —— 由调用方的 {@code OptimisticLockGuard.requireAffected} 决定 409 与文案；</li>
 *   <li><b>同一句 UPDATE</b>：{@code status} / {@code status_seq+1} / {@code version+1} 与附带列
 *       （支付方式、拆单标记、审核意见）都在同一个 SET 里 —— 拆两条会多涨一次 version，语义就变了；</li>
 *   <li><b>非法流转 / 非法出生状态在入口就被拒</b>，不依赖调用方记得写 if。</li>
 * </ol>
 *
 * <p>纯 Mockito，不连库、不起 Spring：{@code BaseMapper.update} 的返回值就是唯一的输入。
 *
 * <p>运行：cd qiqi-mall-server && mvn -o test -Dtest=StatusEntryGuardTest
 */
class StatusEntryGuardTest {

    /**
     * 纯单测里 {@code LambdaUpdateWrapper} 需要 MyBatis-Plus 的实体-列缓存（平时由 mapper 初始化时建），
     * 没有它 {@code Order::getStatus} 会抛「can not find lambda cache」。这里手工灌一次。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, DeliveryOrder.class);
        TableInfoHelper.initTableInfo(assistant, ReturnApply.class);
    }

    /**
     * 台账写入口：本类只验证**入口层的 CAS / 单语句 UPDATE / 守卫**，所以给一个 mock ——
     * 台账真的写没写、写成什么样，由 {@code LedgerWriteIntegrationTest}（真库）负责。
     * 用 mock 而不是 null 是有意义的：它同时断言「状态更新成功时会调用台账写入」
     * （{@code verify(ledger)} 在 {@ code 台账与状态同一条 UPDATE} 那组用例里）。
     */
    private static StatusLedgerWriter ledger() {
        return mock(StatusLedgerWriter.class);
    }

    // ------------------------------------------------------------------ 夹具

    private static Order order(Integer status, Integer version) {
        Order o = new Order();
        o.setId(1001L);
        o.setStatus(status);
        o.setVersion(version);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static OrderMapper orderMapperReturning(int rows) {
        OrderMapper mapper = mock(OrderMapper.class);
        when(mapper.update(any(), any())).thenReturn(rows);
        return mapper;
    }

    /**
     * 结论标结算入口：同样给 mock —— 本类只验入口层的 CAS / 单语句 UPDATE / 守卫。
     * 「离开待审核到底结算了什么」由 {@code AuditConclusionSettlementIntegrationTest}（真库 + 真 HTTP 入口）负责。
     */
    private static OrderAuditTagService conclusionTags() {
        return mock(OrderAuditTagService.class);
    }

    private static OrderStatusService orderService(OrderMapper mapper) {
        return orderService(mapper, conclusionTags());
    }

    private static OrderStatusService orderService(OrderMapper mapper, OrderAuditTagService tags) {
        OrderService orderService = mock(OrderService.class);
        when(orderService.getBaseMapper()).thenReturn(mapper);
        return new OrderStatusService(orderService, ledger(), tags);
    }

    // ------------------------------------------------------------------ ① CAS：条件 + 影响行数

    @Test
    @DisplayName("① 订单：0 行 = 期间被别人改过 → 返回 0、内存实体不动（调用方据此抛 409）")
    void orderCasFailureDoesNotMutateEntity() {
        Order target = order(7, 5);
        OrderStatusService svc = orderService(orderMapperReturning(0));

        int rows = svc.change(target, 8, OrderStatusService.REASON_AUDIT_PASS, null);

        assertThat(rows).as("0 行必须原样返回，不能被吞成成功").isZero();
        assertThat(target.getStatus()).as("没写成功就不许把内存状态改掉（否则响应会说谎）").isEqualTo(7);
        assertThat(target.getVersion()).as("版本号同样不许动").isEqualTo(5);
    }

    @Test
    @DisplayName("① 订单：UPDATE 带 version 条件（CAS 条件），且 status/status_seq/version 在同一条 SET 里")
    @SuppressWarnings("unchecked")
    void orderUpdateIsSingleStatementWithVersionGuard() {
        Order target = order(7, 5);
        OrderMapper mapper = orderMapperReturning(1);
        OrderStatusService svc = orderService(mapper);

        assertThat(svc.change(target, 8, OrderStatusService.REASON_AUDIT_PASS, null)).isEqualTo(1);

        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<Order> wrapper = (LambdaUpdateWrapper<Order>) captor.getValue();

        assertThat(wrapper.getSqlSet())
                .as("一条 UPDATE 里同时写 status、status_seq 自增、version 自增")
                .contains("status=")
                .contains("status_seq = status_seq + 1")
                .contains("version = version + 1");
        assertThat(wrapper.getSqlSegment())
                .as("CAS 条件：条件里必须有 version（= 读到的 5），否则就是无条件覆盖")
                .contains("version")
                .doesNotContain("status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .as("条件绑定的参数里要有读到的 version=5")
                .contains(5);
        assertThat(target.getVersion()).as("成功后版本号回写，与 updateById 之后实体手里的值一致").isEqualTo(6);
        assertThat(target.getStatus()).isEqualTo(8);
    }

    @Test
    @DisplayName("① 订单：附带列进同一条 UPDATE（支付方式/支付时间不许拆成第二条语句）")
    @SuppressWarnings("unchecked")
    void orderExtraSetsStayInSameStatement() {
        Order target = order(0, 1);
        OrderMapper mapper = orderMapperReturning(1);
        OrderStatusService svc = orderService(mapper);

        svc.change(target, 7, OrderStatusService.REASON_PAY, null,
                w -> w.set(Order::getPayType, 1).set(Order::getSplitStatus, 0));

        ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertThat(((LambdaUpdateWrapper<Order>) captor.getValue()).getSqlSet())
                .contains("pay_type=").contains("split_status=").contains("status_seq = status_seq + 1");
    }

    @Test
    @DisplayName("① 退货申请（没有 version 列）：CAS 条件是 status=读到的值，仍然写在 WHERE 里")
    @SuppressWarnings("unchecked")
    void returnApplyGuardsOnStatusInWhere() {
        ReturnApply apply = new ReturnApply();
        apply.setId(77L);
        apply.setStatus(0);

        com.qiqi.mall.oms.mapper.ReturnApplyMapper mapper =
                mock(com.qiqi.mall.oms.mapper.ReturnApplyMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ReturnApplyService returnApplyService = mock(ReturnApplyService.class);
        when(returnApplyService.getBaseMapper()).thenReturn(mapper);
        ReturnStatusService svc = new ReturnStatusService(returnApplyService, ledger());

        assertThat(svc.change(apply, 1, ReturnStatusService.REASON_APPROVE, "同意",
                w -> w.set(ReturnApply::getDescription, "同意"))).isEqualTo(1);

        ArgumentCaptor<Wrapper<ReturnApply>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ReturnApply> wrapper = (LambdaUpdateWrapper<ReturnApply>) captor.getValue();
        assertThat(wrapper.getSqlSegment())
                .as("原来的 `AND status = 0` 原样保留在 WHERE（不是降级成 Java if）")
                .contains("status").contains("id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(0);
        assertThat(wrapper.getSqlSet()).contains("status_seq = status_seq + 1").contains("description=");
    }

    @Test
    @DisplayName("① 包裹：0 行同样返回 0、不动实体（确认收货那条路径由调用方抛买家视角 409）")
    void deliveryCasFailure() {
        DeliveryOrder d = new DeliveryOrder();
        d.setId(9L);
        d.setStatus(DeliveryOrder.OUTBOUND);
        d.setVersion(3);

        com.qiqi.mall.oms.mapper.DeliveryOrderMapper mapper =
                mock(com.qiqi.mall.oms.mapper.DeliveryOrderMapper.class);
        when(mapper.update(any(), any())).thenReturn(0);
        DeliveryOrderService deliveryOrderService = mock(DeliveryOrderService.class);
        when(deliveryOrderService.getBaseMapper()).thenReturn(mapper);

        int rows = new DeliveryStatusService(deliveryOrderService, ledger())
                .change(d, DeliveryOrder.SIGNED, DeliveryStatusService.REASON_CONFIRM_SIGN, null);

        assertThat(rows).isZero();
        assertThat(d.getStatus()).isEqualTo(DeliveryOrder.OUTBOUND);
        assertThat(d.getVersion()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ ② 非法流转 / 非法出生

    @Test
    @DisplayName("② 订单：现网不存在的箭头在入口被拒（0 待付款 → 1 已付款 没有任何写者）")
    void orderIllegalTransitionRejected() {
        Order target = order(0, 0);
        OrderStatusService svc = orderService(orderMapperReturning(1));

        assertThatThrownBy(() -> svc.change(target, 1, "X", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的订单状态流转");
    }

    @Test
    @DisplayName("② 订单：终局（4 已关闭 / 6 已退款）连聚合推导都不许改写")
    void orderTerminalStatesAreSealed() {
        OrderStatusService svc = orderService(orderMapperReturning(1));

        assertThatThrownBy(() -> svc.change(order(4, 1), 2, OrderStatusService.REASON_AGGREGATE, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> svc.change(order(6, 1), 3, OrderStatusService.REASON_AGGREGATE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("② 订单：聚合推导值允许重复写（9 → 9 幂等重算），不是「非法流转」")
    void orderAggregateCanRewriteSameDerivedStatus() {
        Order target = order(9, 4);
        OrderStatusService svc = orderService(orderMapperReturning(1));

        assertThat(svc.change(target, 9, OrderStatusService.REASON_AGGREGATE, null)).isEqualTo(1);
        assertThat(svc.change(target, 2, OrderStatusService.REASON_AGGREGATE, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("② 订单：建单初始状态只接受 0/1/7（别的状态不许出生）")
    void orderBirthWhitelist() {
        OrderStatusService svc = orderService(orderMapperReturning(1));

        assertThatThrownBy(() -> svc.init(order(null, 0), 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待付款");
        for (int ok : new int[]{0, 1, 7}) {
            Order o = order(null, 0);
            svc.init(o, ok);
            assertThat(o.getStatus()).isEqualTo(ok);
        }
    }

    @Test
    @DisplayName("② 包裹：待下发只能去「已下发」或「已取消」；其余箭头在入口被拒")
    void deliveryIllegalTransitionRejected() {
        DeliveryOrderService deliveryOrderService = mock(DeliveryOrderService.class);
        when(deliveryOrderService.getBaseMapper())
                .thenReturn(mock(com.qiqi.mall.oms.mapper.DeliveryOrderMapper.class));
        DeliveryStatusService svc = new DeliveryStatusService(deliveryOrderService, ledger());

        DeliveryOrder d = new DeliveryOrder();
        d.setId(5L);
        d.setStatus(DeliveryOrder.PENDING);
        d.setVersion(0);

        assertThatThrownBy(() -> svc.change(d, DeliveryOrder.SIGNED, "X", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的包裹状态流转");
        assertThatThrownBy(() -> svc.init(d, DeliveryOrder.SIGNED))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ ③ 离开待审核 ⇒ 结算结论标

    /**
     * 本期把「结论标结算」从各入口手工调用，收敛到状态写入的唯一入口。
     *
     * <p>为什么单测也要钉一次：这张映射表（7→8 / 7→4 / 7→5 / 兜底）是<b>数据口径</b>，
     * 事后查库就靠 {@code released_by} 回答「这条标为什么不成立了」。它一旦漂成 system 或空，
     * 库里的证据还在、但没人说得清原因。真库路径由
     * {@code AuditConclusionSettlementIntegrationTest}（真 HTTP 入口）覆盖。
     */
    @Test
    @DisplayName("③ 离开待审核（V9：只解不落）：7→8(人工) / 7→8(自动) / 7→4 / 兜底 各写什么 released_by、谁都不落标；只有 7→5 补退货事实标")
    void leavingAuditPendingSettlesWithFixedReleasedBy() {
        // V9（2026-11-16）：结算回到「只解不落」—— 用户 16:38 拍过板「通过与否由状态表达，不由标表达」，
        // 所以 7→8 / 7→4 这两条路上**一枚标都不许落**（V8 曾落 AUDIT_PASSED / AUDIT_REJECTED，已收回）。
        assertSettlement(8, OrderStatusService.REASON_AUDIT_PASS,
                OrderAuditTagService.RELEASED_BY_AUDIT_PASS, null);
        assertSettlement(8, ReasonCodes.AUDIT_AUTO_PASS,
                OrderAuditTagService.RELEASED_BY_SUPERSEDED, null);
        assertSettlement(4, OrderStatusService.REASON_AUDIT_REJECT,
                OrderAuditTagService.RELEASED_BY_AUDIT_REJECT, null);
        // 唯一保留：7→5 登记退货申请补一枚**事实**标（退货不是"过没过"，队列行也看不到订单状态）
        assertSettlement(5, OrderStatusService.REASON_RETURN_APPLY,
                OrderStatusService.RELEASED_BY_RETURN_APPLY, AuditTagCatalogService.CODE_RETURN_APPLIED);
        // 兜底：聚合推导把 7 推成 2（包裹事实变了）—— 也是「离开待审核」，同样要**解**，
        // 但**不落**标：那是包裹事实，不是审核结论（给它落"人工放行通过"就是伪造判定证据）。
        assertSettlement(2, OrderStatusService.REASON_AGGREGATE, "SYSTEM:STATUS_2", null);
    }

    /**
     * 一次流转同时钉两件事：① {@code releaseAllConclusionTags} 带对了 released_by；
     * ② 补落的是哪枚<b>事实</b>标（{@code null} = 这一支一枚标都不落 —— V9 之后除 7→5 外全是 null）。
     *
     * <p>为什么连"不落"也要断言：{@code null} 那一支如果哪天悄悄落了标，
     * 库里的证据会变成一条假的"人工放行通过"——数字上看不出来，业务上错得离谱。
     */
    private static void assertSettlement(int to, String reason, String expectedReleasedBy, String expectedFactCode) {
        Order target = order(7, 3);
        OrderAuditTagService tags = conclusionTags();
        OrderStatusService svc = orderService(orderMapperReturning(1), tags);

        assertThat(svc.change(target, to, reason, null)).isEqualTo(1);

        verify(tags).releaseAllConclusionTags(target.getId(), expectedReleasedBy);
        if (expectedFactCode == null) {
            verify(tags, never()).ensureActive(any(), any(), any());
        } else {
            // 顺序契约（release 在前）由真库用例验"最终只有一条 active 结论标"来兜 ——
            // 这里只断言"落的是正确的码"（用 eq 全匹配，别混用裸值与匹配器）。
            verify(tags).ensureActive(eq(target.getId()), eq(expectedFactCode), any());
        }
    }

    @Test
    @DisplayName("③ 兜底那一支（聚合推导）只解不落：不许凭空造一条审核结论")
    void derivedExitReleasesButDoesNotLandTerminalTag() {
        Order target = order(7, 3);
        OrderAuditTagService tags = conclusionTags();
        OrderStatusService svc = orderService(orderMapperReturning(1), tags);

        assertThat(svc.change(target, 9, OrderStatusService.REASON_AGGREGATE, null)).isEqualTo(1);

        verify(tags).releaseAllConclusionTags(target.getId(), "SYSTEM:STATUS_9");
        verify(tags, never()).ensureActive(any(), any(), any());
    }

    @Test
    @DisplayName("③ 状态没真的变（0 行 = 被别人抢先改过）⇒ 一条结论标都不许结算")
    void casFailureDoesNotSettleConclusionTags() {
        OrderAuditTagService tags = conclusionTags();
        OrderStatusService svc = orderService(orderMapperReturning(0), tags);

        assertThat(svc.change(order(7, 3), 8, OrderStatusService.REASON_AUDIT_PASS, null)).isZero();

        verify(tags, never()).releaseAllConclusionTags(any(), any());
        verify(tags, never()).ensureActive(any(), any(), any());
    }

    @Test
    @DisplayName("③ 聚合幂等重写 7→7 ⇒ 状态没离开待审核，结论仍然成立，不结算")
    void rewritingPendingStatusDoesNotSettleConclusionTags() {
        OrderAuditTagService tags = conclusionTags();
        OrderStatusService svc = orderService(orderMapperReturning(1), tags);

        assertThat(svc.change(order(7, 3), 7, OrderStatusService.REASON_AGGREGATE, null)).isEqualTo(1);

        verify(tags, never()).releaseAllConclusionTags(any(), any());
        verify(tags, never()).ensureActive(any(), any(), any());
    }

    @Test
    @DisplayName("③ 进入待审核（5 退款中被交回聚合 5→7）不结算：结论标由下一次判定重新落")
    void enteringAuditPendingDoesNotSettle() {
        OrderAuditTagService tags = conclusionTags();
        OrderStatusService svc = orderService(orderMapperReturning(1), tags);

        assertThat(svc.change(order(5, 3), 7, OrderStatusService.REASON_AGGREGATE, null)).isEqualTo(1);

        verify(tags, never()).releaseAllConclusionTags(any(), any());
        verify(tags, never()).ensureActive(any(), any(), any());
    }

    @Test
    @DisplayName("② 退货申请：只有 0 待处理能流转到 1/2，终局不许再动")
    void returnApplyIllegalTransitionRejected() {
        ReturnApplyService returnApplyService = mock(ReturnApplyService.class);
        when(returnApplyService.getBaseMapper())
                .thenReturn(mock(com.qiqi.mall.oms.mapper.ReturnApplyMapper.class));
        ReturnStatusService svc = new ReturnStatusService(returnApplyService, ledger());

        ReturnApply done = new ReturnApply();
        done.setId(8L);
        done.setStatus(1);

        assertThatThrownBy(() -> svc.change(done, 2, ReturnStatusService.REASON_REJECT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的退货申请状态流转");
        assertThatThrownBy(() -> svc.init(new ReturnApply(), 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
