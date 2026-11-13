package com.qiqi.mall.channel.model;

import java.time.LocalDateTime;

/**
 * 回传发货的统一入参：一个包裹一次（一次外呼）。
 *
 * @param platformSn 平台单号（回传时告诉平台「是哪一单」）
 * @param carrier    承运商（取 {@code delivery_logistics.company}，没有就回落默认值）
 * @param trackNo    运单号（取 {@code delivery_logistics.track_no}；出库回传时才生成）
 * @param shippedAt  发货时间（内部事实：包裹出库的时间）
 */
public record ShipmentInfo(String platformSn, String carrier, String trackNo, LocalDateTime shippedAt) {
}
