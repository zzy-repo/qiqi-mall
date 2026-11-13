package com.qiqi.mall.channel.spi;

import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PushResult;
import com.qiqi.mall.channel.model.ShipmentInfo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台接入 SPI —— <b>本轮唯一的扩展点</b>。
 *
 * <p>三个方法/两件事：拉单、回传发货。平台差异（URL、报文、字段名、错误码）全部关在实现类里，
 * 业务层（{@code ChannelSyncService}）只认这个接口和 {@link com.qiqi.mall.channel.model.PlatformOrder}。
 *
 * <p><b>为什么没有「推送库存」「同步退款」「对账」这些方法</b>：本轮范围明确只做接入闭环
 * （拉单 → 待审核 → 审核 → 拆单 → 出库 → 回传发货），不写策略、不做库存/退款/对账。
 * 将来要加，也应该是新增 SPI 方法或新接口，而不是把语义塞进这两个方法里。
 *
 * <h3>失败口径</h3>
 * 任何「没拿到可用数据」的情况都抛 {@link ChannelException}（带 httpStatus / rawResponse），
 * <b>不要</b>返回空列表冒充成功 —— 空列表的含义是「这段时间平台真的没有新单」，
 * 两者在 sync_log 里是 OK 与 FAIL 的区别，混在一起会让排查彻底失去依据。
 */
public interface ChannelAdapter {

    /** 平台类型：MALL / TAOBAO_MOCK / DOUYIN_MOCK（与 channel_shop.platform_type 一致） */
    String platformType();

    /**
     * 拉取 {@code since} 之后的新单（平台本地时间，含边界由平台决定）。
     *
     * @param shopCode 店铺编码（多店铺时区分；本轮每个平台一个店铺）
     * @param since    拉单水位（{@code channel_shop.watermark_at}；首次为 null 时实现类自行取默认窗口）
     * @return 平台订单（统一模型）；没有新单时返回空列表
     * @throws ChannelException 网络/HTTP/报文异常 —— 调用方会记 sync_log(FAIL) 并把原因回给后台
     */
    List<PlatformOrder> pullOrders(String shopCode, LocalDateTime since) throws ChannelException;

    /**
     * 回传发货（一个包裹一次）。
     *
     * @param platformSn 平台单号
     * @param info       承运商 / 运单号 / 发货时间
     * @return 平台结果；{@code code=DUPLICATE} 表示平台侧已存在（幂等命中，业务层记 SKIP）
     * @throws ChannelException 网络/HTTP 异常（业务层会重试一次，第二次仍失败才判 FAIL）
     */
    PushResult pushShipment(String shopCode, String platformSn, ShipmentInfo info) throws ChannelException;
}
