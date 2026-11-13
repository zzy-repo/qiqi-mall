package com.qiqi.mall.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.mapper.OrderAuditTagMapper;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.mapper.SkuMapper;
import com.qiqi.mall.member.entity.Member;
import com.qiqi.mall.member.mapper.MemberMapper;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.oms.mapper.ReturnApplyMapper;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.order.mapper.OrderItemMapper;
import com.qiqi.mall.stock.service.SkuBucketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「库里的订单事实」装配成引擎要的 {@link AuditContext}。
 *
 * <p>装配是执行层（可以查库），判定是引擎（纯函数）。分开的好处是：所有「这单的可用库存是多少」
 * 这类**容易查错**的代码集中在一处，引擎单测可以完全绕开它。
 *
 * <h3>三个关键取数口径</h3>
 * <ol>
 *   <li><b>可用库存 = SUM(sku_bucket.stock)</b>，不是 {@code sku.stock}（决策 D5）。
 *       {@code sku.stock} 只是展示缓存，允许滞后；拿它裁决会出现「页面显示 999 所以放行，
 *       实际桶里只有 5」—— QT-1006 就是钉这个的。SKU 还没有桶（历史数据）时先幂等回填一次。</li>
 *   <li><b>订单级可用量 = 瓶颈 SKU 的桶合计</b>（min）：多 SKU 单只要有一个 SKU 不够，
 *       整单就发不出去；取最小值才是「这单能不能整单发出」的答案。</li>
 *   <li><b>成本价</b>：优先下单快照 {@code order_item.cost_price}，缺失才回落到 {@code sku.cost_price}。
 *       两处都没有（=0/NULL）才算 {@code costMissing} —— 否则新加的成本列会让所有历史单瞬间变成
 *       COST_MISSING，把真正「没维护成本」的单淹掉。</li>
 * </ol>
 */
@Component
public class AuditContextLoader {

    private static final Logger log = LoggerFactory.getLogger(AuditContextLoader.class);

    private final OrderItemMapper itemMapper;
    private final MemberMapper memberMapper;
    private final SkuMapper skuMapper;
    private final ReturnApplyMapper returnApplyMapper;
    private final OrderAuditTagMapper tagMapper;
    private final SkuBucketService bucketService;

    public AuditContextLoader(OrderItemMapper itemMapper, MemberMapper memberMapper, SkuMapper skuMapper,
                              ReturnApplyMapper returnApplyMapper, OrderAuditTagMapper tagMapper,
                              SkuBucketService bucketService) {
        this.itemMapper = itemMapper;
        this.memberMapper = memberMapper;
        this.skuMapper = skuMapper;
        this.returnApplyMapper = returnApplyMapper;
        this.tagMapper = tagMapper;
        this.bucketService = bucketService;
    }

    /** 装配；{@code now} 由调用方给定（同一轮 run 内所有单共用一个「现在」） */
    public AuditContext load(Order order, LocalDateTime now) {
        List<OrderItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));

        int qtyTotal = 0;
        boolean costMissing = false;
        Map<Long, Integer> qtyBySku = new LinkedHashMap<>();
        for (OrderItem item : items) {
            int qty = item.getProductQuantity() == null ? 0 : item.getProductQuantity();
            qtyTotal += qty;
            qtyBySku.merge(item.getProductSkuId(), qty, Integer::sum);
            if (!hasCost(item.getCostPrice()) && !hasCost(costOf(item.getProductSkuId()))) {
                costMissing = true;
            }
        }
        // 没有任何明细：无货可发，按「需求 0、可用 0」处理（会被库存预检放行，
        // 但毛利/成本也自然缺失 → 由 COST_MISSING 拦住，不会静默自动过审）
        if (items.isEmpty()) costMissing = true;

        int stockAvailable = Integer.MAX_VALUE;
        int skuStockCacheSum = 0;
        Map<String, Object> stockDetail = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : qtyBySku.entrySet()) {
            Long skuId = e.getKey();
            if (skuId == null) continue;
            if (!bucketService.hasBuckets(skuId)) {
                // 历史 SKU 没有桶：幂等回填（只补没有桶的），否则 SUM=0 会把「没初始化」误判成「没库存」
                int rows = bucketService.backfillSku(skuId);
                log.info("自动审核：SKU {} 没有分桶，已按 sku.stock 幂等回填 {} 行", skuId, rows);
            }
            int sum = bucketService.sumStock(skuId);
            Sku sku = skuMapper.selectById(skuId);
            int cache = sku == null || sku.getStock() == null ? 0 : sku.getStock();
            skuStockCacheSum += cache;
            stockAvailable = Math.min(stockAvailable, sum);
            stockDetail.put(String.valueOf(skuId), Map.of("bucket_sum", sum, "sku_stock_cache", cache, "demand", e.getValue()));
        }
        if (stockAvailable == Integer.MAX_VALUE) stockAvailable = 0;

        Member member = order.getMemberId() == null ? null : memberMapper.selectById(order.getMemberId());
        var frozen = tagMapper.selectActiveFrozen(order.getId());

        return new AuditContext(
                order.getId(),
                order.getOrderSn(),
                order.getStatus(),
                order.getChannel(),
                order.getShopName(),
                order.getTotalAmount(),
                order.getGrossProfit(),
                member == null ? null : member.getCustomerTag(),
                order.getCsRemark(),
                order.getPlatformRemark(),
                order.getPlatformRemarkUpdatedAt(),
                hasPendingReturnApply(order.getId()),
                order.getAuditAddrMulti() != null && order.getAuditAddrMulti() == 1,
                stockAvailable,
                skuStockCacheSum,
                qtyBySku.size(),
                qtyTotal,
                costMissing,
                order.getPaymentTime(),
                cityOf(order.getReceiverAddress()),
                order.getReceiverAddress(),
                frozen != null,
                frozen == null ? null : frozen.getTagCode(),
                now);
    }

    /** 待处理（status=0）的退货/退款申请：异常策略的触发事实 */
    private boolean hasPendingReturnApply(Long orderId) {
        Long count = returnApplyMapper.selectCount(new LambdaQueryWrapper<ReturnApply>()
                .eq(ReturnApply::getOrderId, orderId)
                .eq(ReturnApply::getStatus, 0));
        return count != null && count > 0;
    }

    private BigDecimal costOf(Long skuId) {
        if (skuId == null) return null;
        Sku sku = skuMapper.selectById(skuId);
        return sku == null ? null : sku.getCostPrice();
    }

    private static boolean hasCost(BigDecimal cost) {
        return cost != null && cost.signum() > 0;
    }

    /**
     * 收货城市：从地址串里取「省之后、市之前」那一段。
     *
     * <p>为什么不直接存一列：历史地址是自由文本快照，没有结构化字段；判定只需要「哪个城市」，
     * 从现有文本里解析比要求运营回填新列现实。解析不出来就返回原串前 8 个字（至少不会把
     * 「北京市朝阳区…」整段塞进条件比较里）。
     */
    static String cityOf(String address) {
        if (address == null || address.isBlank()) return null;
        String s = address.trim();
        int province = s.indexOf('省');
        String rest = province >= 0 && province + 1 < s.length() ? s.substring(province + 1) : s;
        int city = rest.indexOf('市');
        if (city > 0) return rest.substring(0, city + 1);
        int space = rest.indexOf(' ');
        if (space > 0) return rest.substring(0, space);
        return rest.length() <= 8 ? rest : rest.substring(0, 8);
    }
}
