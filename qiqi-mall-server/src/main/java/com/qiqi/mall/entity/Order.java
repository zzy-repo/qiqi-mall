package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("`order`")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号 */
    private String orderSn;

    /** 买家ID */
    private Long memberId;

    private BigDecimal totalAmount;

    /** 状态:0待付款 1已付款 2已发货 3已完成 4已关闭 */
    private Integer status;

    /** 支付方式:0未支付 1支付宝 2微信 */
    private Integer payType;

    private LocalDateTime paymentTime;

    /** 收货人姓名（快照） */
    private String receiverName;
}
