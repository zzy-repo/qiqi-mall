package com.qiqi.mall.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.catalog.entity.Product;
import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.catalog.service.ProductService;
import com.qiqi.mall.catalog.service.SkuService;
import com.qiqi.mall.channel.entity.ChannelShop;
import com.qiqi.mall.channel.mapper.ChannelOrderMapMapper;
import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PlatformOrderItem;
import com.qiqi.mall.member.entity.Member;
import com.qiqi.mall.member.mapper.MemberMapper;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.shared.common.OrderSnGenerator;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单张平台订单 → 内部订单（**一张单一个事务**）。
 *
 * <p>为什么按单开事务而不是整批一个事务：一张单的 SKU 对不上，不该把同批 20 张单一起回滚 ——
 * 那样「跳过该单」就变成了「跳过整批」。所以每张单 REQUIRES_NEW：成功各自提交，
 * 失败各自回滚并留一条 FAIL 日志。
 *
 * <h3>落单五步（严格按需求单顺序）</h3>
 * <ol>
 *   <li><b>先查幂等闸门</b>：{@code channel_order_map(shop_id, platform_sn)} 有 → 抛
 *       {@link DuplicateOrderException}（调用方记 SKIP / IDEMPOTENT）；</li>
 *   <li><b>SKU 匹配</b>：{@code SKU-<id>} 或纯数字 {@code <id>}；任一明细匹配不到 →
 *       {@link SkuNotMatchedException}（调用方记 FAIL / SKU_NOT_MATCHED）并跳过整单
 *       —— 不猜商品、不建半张单；</li>
 *   <li><b>建 order</b>：{@code status=7 待审核} 走 {@link OrderStatusService#init}（唯一入口）+ 出生台账；</li>
 *   <li><b>建 order_item</b>：价格 / 名称按平台快照落库（回看历史时不能因为改价而变）；</li>
 *   <li><b>写 map</b>：{@code INSERT ... ON DUPLICATE KEY UPDATE id=id}，影响 0 行说明并发路径
 *       已经把这单吃进去了 —— 抛 {@link DuplicateOrderException} 让本事务把刚建的订单回滚掉。</li>
 * </ol>
 *
 * <p><b>本模块不扣库存</b>（范围外）：平台单在商城侧早已成交，本地库存由既有的拆单/出库链路管，
 * 这里只负责把「订单事实」搬进来。
 */
@Service
public class ChannelOrderImporter {

    private static final Logger log = LoggerFactory.getLogger(ChannelOrderImporter.class);

    /**
     * 平台买家账号：{@code order.member_id} 是 NOT NULL + 外键 {@code fk_order_member}，
     * 而渠道单的买家在平台上、本地没有会员档案。所以统一挂一个「平台买家」账号。
     *
     * <p>这是**结构上的必需**，不是偷懒：要么建这个账号，要么改 order 表允许 member_id 为空
     * （那会动到会员模块的既有约束，超出本轮范围）。
     */
    static final String PLATFORM_BUYER_USERNAME = "channel_platform_buyer";
    private static final String PLATFORM_BUYER_NICKNAME = "平台买家";

    private final OrderService orderService;
    private final OrderItemService orderItemService;
    private final OrderStatusService orderStatusService;
    private final SkuService skuService;
    private final ProductService productService;
    private final ChannelOrderMapMapper mapMapper;
    private final MemberMapper memberMapper;

    /** 平台买家 id 的进程内缓存（查一次就够；被清掉也能自愈） */
    private volatile Long platformBuyerId;

    public ChannelOrderImporter(OrderService orderService, OrderItemService orderItemService,
                                OrderStatusService orderStatusService, SkuService skuService,
                                ProductService productService, ChannelOrderMapMapper mapMapper,
                                MemberMapper memberMapper) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
        this.orderStatusService = orderStatusService;
        this.skuService = skuService;
        this.productService = productService;
        this.mapMapper = mapMapper;
        this.memberMapper = memberMapper;
    }

    /** 落单结果（内部订单 id + 平台单号，供调用方组装响应与日志） */
    public record ImportResult(Long orderId, String platformSn) {
    }

    /**
     * 落一张单。
     *
     * @throws DuplicateOrderException 幂等命中（映射已存在 / 并发插入撞键）
     * @throws SkuNotMatchedException  平台商品编码对不上本地 sku（或明细为空）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ImportResult importOne(ChannelShop shop, PlatformOrder platformOrder) {
        String sn = platformOrder.platformSn();

        // ① 先查闸门（快路径；真正的兜底是第 ⑤ 步的唯一键）
        if (mapMapper.countByKey(shop.getId(), sn) > 0) {
            throw new DuplicateOrderException(sn);
        }

        // ② SKU 匹配 + 明细快照（匹配不到就整单放弃）
        List<OrderItem> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PlatformOrderItem platformItem : platformOrder.items()) {
            Long skuId = parseSkuId(platformItem.platformSkuCode());
            Sku sku = skuId == null ? null : skuService.getById(skuId);
            if (sku == null) {
                missing.add(String.valueOf(platformItem.platformSkuCode()));
                continue;
            }
            Product product = productService.getById(sku.getProductId());
            BigDecimal price = platformItem.price() != null ? platformItem.price() : sku.getPrice();
            int qty = platformItem.qty() < 1 ? 1 : platformItem.qty();

            OrderItem item = new OrderItem();
            item.setProductId(sku.getProductId());
            item.setProductSkuId(sku.getId());
            item.setProductName(platformItem.productName() != null && !platformItem.productName().isBlank()
                    ? platformItem.productName()
                    : (product == null ? "" : product.getName()));
            item.setProductPrice(price == null ? BigDecimal.ZERO : price);
            item.setProductQuantity(qty);
            items.add(item);
            total = total.add(item.getProductPrice().multiply(BigDecimal.valueOf(qty)));
        }
        if (!missing.isEmpty() || items.isEmpty()) {
            throw new SkuNotMatchedException(sn, missing);
        }

        // ③ 建订单：待审核(7) 走状态唯一入口（save 前定初值，不产生 UPDATE）
        Order order = new Order();
        order.setOrderSn(OrderSnGenerator.next());
        order.setMemberId(platformBuyerId());
        orderStatusService.init(order, 7);
        order.setPayType(0);
        order.setPaymentTime(platformOrder.paidAt());
        order.setReceiverName(blankTo(platformOrder.receiverName(), "平台收货人"));
        order.setReceiverPhone(blankTo(platformOrder.receiverPhone(), ""));
        order.setReceiverAddress(blankTo(platformOrder.receiverAddress(), ""));
        order.setTotalAmount(total);
        order.setChannel(blankTo(platformOrder.channel(), platformOrder.platformType()));
        order.setShopName(shop.getShopName());
        // 平台单号只留在备注里做「这单从哪来」的线索：真正的映射在 channel_order_map
        order.setPlatformRemark("渠道单 " + platformOrder.platformType() + "/" + sn);
        orderService.save(order);
        orderStatusService.recordBirth(order, ReasonCodes.ORDER_CREATE,
                "渠道拉单 " + platformOrder.platformType() + " " + sn);

        // ④ 明细快照
        items.forEach(item -> item.setOrderId(order.getId()));
        orderItemService.saveBatch(items);

        // ⑤ 写映射（唯一键兜底）：0 行 = 并发路径已经先进来 → 回滚本次新建的订单
        int rows = mapMapper.insertIfAbsent(shop.getId(), sn, order.getId());
        if (rows == 0) {
            throw new DuplicateOrderException(sn);
        }

        log.info("[channel] 平台单 {}（{}）→ 内部订单 {}，金额 {}，状态=7 待审核",
                sn, platformOrder.platformType(), order.getId(), total);
        return new ImportResult(order.getId(), sn);
    }

    /**
     * 平台商品编码 → 本地 sku.id：支持 {@code SKU-<id>} 与纯数字 {@code <id>}。
     *
     * <p>解析不出来返回 null（交给上层记 SKU_NOT_MATCHED），不抛异常 ——
     * 「平台填了个奇怪的编码」是业务现实，不是代码 bug。
     */
    static Long parseSkuId(String platformSkuCode) {
        if (platformSkuCode == null) return null;
        String v = platformSkuCode.trim();
        if (v.isEmpty()) return null;
        if (v.length() > 4 && v.regionMatches(true, 0, "SKU-", 0, 4)) {
            v = v.substring(4).trim();
        }
        try {
            long id = Long.parseLong(v);
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 平台买家账号 id（没有就建一个；并发建由唯一键兜住） */
    private Long platformBuyerId() {
        Long cached = platformBuyerId;
        if (cached != null) return cached;
        synchronized (this) {
            if (platformBuyerId != null) return platformBuyerId;
            Member member = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                    .eq(Member::getUsername, PLATFORM_BUYER_USERNAME).last("LIMIT 1"));
            if (member == null) {
                Member row = new Member();
                row.setUsername(PLATFORM_BUYER_USERNAME);
                row.setNickname(PLATFORM_BUYER_NICKNAME);
                row.setStatus(1);
                try {
                    memberMapper.insert(row);
                    member = row;
                    log.info("[channel] 建了「平台买家」账号 id={}（渠道单的 member_id 需要它）", row.getId());
                } catch (DuplicateKeyException e) {
                    member = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                            .eq(Member::getUsername, PLATFORM_BUYER_USERNAME).last("LIMIT 1"));
                }
            }
            if (member == null) {
                throw new IllegalStateException("无法创建/读取「平台买家」账号（channel_platform_buyer）");
            }
            platformBuyerId = member.getId();
            return platformBuyerId;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 幂等命中：这张平台单已经进过库 */
    public static class DuplicateOrderException extends RuntimeException {
        private final String platformSn;

        public DuplicateOrderException(String platformSn) {
            super("平台单 " + platformSn + " 已导入过");
            this.platformSn = platformSn;
        }

        public String getPlatformSn() {
            return platformSn;
        }
    }

    /** 平台商品编码对不上本地 SKU */
    public static class SkuNotMatchedException extends RuntimeException {
        private final String platformSn;
        private final List<String> missingCodes;

        public SkuNotMatchedException(String platformSn, List<String> missingCodes) {
            super("平台单 " + platformSn + " 的商品编码匹配不到本地 SKU：" + missingCodes);
            this.platformSn = platformSn;
            this.missingCodes = List.copyOf(missingCodes);
        }

        public String getPlatformSn() {
            return platformSn;
        }

        public List<String> getMissingCodes() {
            return missingCodes;
        }
    }
}
