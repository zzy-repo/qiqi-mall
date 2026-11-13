package com.qiqi.mall.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道店铺（{@code channel_shop}）：一个平台一条，本轮固定三条。
 *
 * <p>{@code watermark_at} 是**拉单水位**：存「上次成功拉单的开始时刻」，下次拉单把它当作 {@code since}。
 * 存开始时刻而不是结束时刻，是为了容忍「拉单过程中新进来的单」—— 宁可重叠一次（重叠由幂等闸门吃掉），
 * 也不要漏单；漏单在接入层是不可接受的错误，重叠只是多一行 SKIP 日志。
 */
@Data
@TableName("channel_shop")
public class ChannelShop {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** MALL / TAOBAO_MOCK / DOUYIN_MOCK */
    private String platformType;

    private String shopCode;

    private String shopName;

    /** 1 启用 0 停用（停用后不允许拉单/回传） */
    private Integer enabled;

    /** 拉单水位：上次成功拉单的开始时刻 */
    private LocalDateTime watermarkAt;

    /** 上次拉单结束时刻（含失败），卡片上展示 */
    private LocalDateTime lastPullAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
