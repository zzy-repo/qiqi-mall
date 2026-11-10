package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付流水（对账用） */
@Data
@TableName("payment_record")
public class PaymentRecord {

    public static final int TYPE_PAY = 1;
    public static final int TYPE_REFUND = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联订单号（对账键） */
    private String orderSn;

    private BigDecimal amount;

    /** 1支付 2退款 */
    private Integer type;

    /** 0模拟 1支付宝 2微信 */
    private Integer channel;

    /** 第三方流水号（模拟为本地生成） */
    private String tradeNo;

    private LocalDateTime createdAt;
}
