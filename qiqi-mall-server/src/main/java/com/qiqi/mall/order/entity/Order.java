package com.qiqi.mall.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
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

    /**
     * 状态：0待付款 1已付款 2已发货 3已完成 4已关闭 5退款中 6已退款
     *      7待审核 8待发货 9部分发货（拆单履约链，见 docs/flows/04-状态机与修复/14-订单状态图）
     */
    private Integer status;

    /** 支付方式:0未支付 1支付宝 2微信 */
    private Integer payType;

    private LocalDateTime paymentTime;

    /** 收货人姓名（快照） */
    private String receiverName;

    /** 收货电话（快照） */
    private String receiverPhone;

    /** 收货地址（快照） */
    private String receiverAddress;

    /** 拆单标记：0未拆(单仓直发) 1已拆 */
    private Integer splitStatus;

    /**
     * 乐观锁版本号（MyBatis-Plus @Version）。
     * updateById 时会生成 {@code SET version = version + 1 WHERE id = ? AND version = ?}；
     * 影响行数 0 = 期间被别人改过 → 接口回 code=409，不静默覆盖。
     */
    @Version
    @TableField("version")
    private Integer version;
}
