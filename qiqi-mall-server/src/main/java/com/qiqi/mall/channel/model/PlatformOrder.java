package com.qiqi.mall.channel.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一模型：三个平台（MALL / TAOBAO_MOCK / DOUYIN_MOCK）的订单在接入层全部先转成它，
 * 之后的落单逻辑只有一条路径 —— 平台差异只活在各自的 {@code ChannelAdapter} 里。
 *
 * <p>字段取舍（只留「建内部订单 + 回传发货」真正需要的东西）：
 * <ul>
 *   <li>{@code platformSn}：平台单号。<b>它 + 店铺</b> 是幂等闸门（{@code channel_order_map.uk_map}），
 *       所以这个字段不许被改写、不许由业务键派生；</li>
 *   <li>{@code channel}：落到 {@code order.channel}（销售渠道），审核策略的 CHANNEL 条件用得到；</li>
 *   <li>{@code buyerName}：平台买家标识，只做展示（内部订单的买家统一挂「平台买家」账号，
 *       见 {@code ChannelOrderImporter}，因为 {@code order.member_id} 是 NOT NULL + 外键）；</li>
 *   <li>{@code paidAt}：平台支付时间，落到 {@code order.payment_time}（内部状态仍是 7 待审核）。</li>
 * </ul>
 *
 * @param platformType 平台类型（与 {@code channel_shop.platform_type} 同值）
 * @param shopCode     店铺编码（与 {@code channel_shop.shop_code} 同值，回传时带上）
 * @param platformSn   平台单号（幂等键的一半）
 * @param channel      销售渠道（落 order.channel）
 * @param buyerName    平台买家昵称（仅展示/留痕）
 * @param receiverName 收货人
 * @param receiverPhone 收货电话
 * @param receiverAddress 收货地址
 * @param totalAmount  平台订单总额（落库时以明细重算为准，两者不一致说明平台数据有问题）
 * @param paidAt       平台支付时间
 * @param items        明细（至少一条，否则整单失败）
 */
public record PlatformOrder(String platformType,
                            String shopCode,
                            String platformSn,
                            String channel,
                            String buyerName,
                            String receiverName,
                            String receiverPhone,
                            String receiverAddress,
                            BigDecimal totalAmount,
                            LocalDateTime paidAt,
                            List<PlatformOrderItem> items) {

    public PlatformOrder {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
