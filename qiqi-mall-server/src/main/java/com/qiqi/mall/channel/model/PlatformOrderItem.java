package com.qiqi.mall.channel.model;

import java.math.BigDecimal;

/**
 * 平台订单明细（统一模型的一部分）。
 *
 * <p><b>为什么平台 SKU 用编码字符串而不是 id</b>：各平台的主键体系互不相通，接入层唯一能对齐的
 * 就是「商家在自己系统里填的商品编码」。本轮支持两种写法（见 {@code ChannelOrderImporter#parseSkuId}）：
 * <ul>
 *   <li>{@code SKU-<id>} —— 推荐写法，直接指向本地 {@code sku.id}；</li>
 *   <li>{@code <id>} —— 纯数字，等价于 {@code SKU-<id>}（对接方懒得加前缀时也能work）。</li>
 * </ul>
 * 匹配不到 → 整单 {@code FAIL / SKU_NOT_MATCHED} 并跳过，不猜、不建脏数据。
 *
 * @param platformSkuCode 平台侧商品编码（对齐本地 sku 的桥）
 * @param productName     平台侧商品名（落库时作为 order_item.product_name 快照）
 * @param qty             数量
 * @param price           平台侧成交单价（落库时作为 order_item.product_price 快照；为空则用本地 sku 价）
 */
public record PlatformOrderItem(String platformSkuCode, String productName, int qty, BigDecimal price) {
}
