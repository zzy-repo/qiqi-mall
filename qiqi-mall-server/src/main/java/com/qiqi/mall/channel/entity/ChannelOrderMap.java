package com.qiqi.mall.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台单号 → 内部订单 的映射（{@code channel_order_map}）。
 *
 * <p>{@code uk_map(shop_id, platform_sn)} 是本模块的**幂等闸门**：重复拉单只认这一张表。
 * 它的键由平台给出的事实拼成（店铺 + 平台单号），<b>不许由业务键派生</b> ——
 * 派生出来的键会在「平台改号 / 大小写 / 前后空格」这类现实差异上失守。
 *
 * <p>本表无外键（项目铁律：台账/映射表一律不建 FK），{@code shop_id} / {@code order_id} 是业务引用。
 */
@Data
@TableName("channel_order_map")
public class ChannelOrderMap {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shopId;

    /** 平台单号（MALL 直接用商城 order_sn） */
    private String platformSn;

    /** 内部订单 id */
    private Long orderId;

    private LocalDateTime createdAt;
}
