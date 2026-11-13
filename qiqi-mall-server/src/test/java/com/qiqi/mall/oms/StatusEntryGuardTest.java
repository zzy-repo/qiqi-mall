package com.qiqi.mall.oms;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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

    private static OrderStatusService orderService(OrderMapper mapper) {
        OrderService orderService = mock(OrderService.class);
        when(orderService.getBaseMapper()).thenReturn(mapper);
        return new OrderStatusService(orderService);
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
    @DisplayName("① 订单：UPDATE 带 version 条件（CAS 闸门），且 status/status_seq/version 在同一条 SET 里")
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
                .as("CAS 闸门：条件里必须有 version（= 读到的 5），否则就是无条件覆盖")
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
    @DisplayName("① 退货申请（没有 version 列）：CAS 闸门是 status=读到的值，仍然写在 WHERE 里")
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
        ReturnStatusService svc = new ReturnStatusService(returnApplyService);

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

        int rows = new DeliveryStatusService(deliveryOrderService)
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
    @DisplayName("② 订单：建单初始状态白名单（只有 0/1/7 能出生）")
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
        DeliveryStatusService svc = new DeliveryStatusService(deliveryOrderService);

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

    @Test
    @DisplayName("② 退货申请：只有 0 待处理能流转到 1/2，终局不许再动")
    void returnApplyIllegalTransitionRejected() {
        ReturnApplyService returnApplyService = mock(ReturnApplyService.class);
        when(returnApplyService.getBaseMapper())
                .thenReturn(mock(com.qiqi.mall.oms.mapper.ReturnApplyMapper.class));
        ReturnStatusService svc = new ReturnStatusService(returnApplyService);

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
