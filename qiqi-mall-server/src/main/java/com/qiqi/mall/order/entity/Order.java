package com.qiqi.mall.order.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 状态流转序号（P2 第二步）：{@code status_seq} 与 {@code status} 在<b>同一条</b> UPDATE 里自增
     * （见 {@code OrderStatusService#change}），是状态台账幂等键 {@code {orderId}:{statusSeq}} 的来源。
     *
     * <p><b>只读字段</b>：{@code insertStrategy / updateStrategy = NEVER} —— 本实体永远不写这一列，
     * 唯一的写者是状态服务那句 {@code SET status_seq = status_seq + 1}。若让它参与 {@code updateById}，
     * 任何一次「顺手保存订单」都会把 seq 写回旧值，幂等键立刻失去意义。
     * <p>{@code @JsonIgnore}：不给接口响应加字段（P2 的硬门槛是「行为不变」）。
     */
    @TableField(value = "status_seq", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    @JsonIgnore
    private Integer statusSeq;

    // ================================================================ 自动审核的判定上下文（P4）
    // 这些列是策略条件的输入（渠道/店铺/毛利/备注/多地址），全部可空：
    // 历史订单没有这些信息，字段取值函数对 NULL 返回「无值」，由 EXISTS/NOT_EXISTS 表达。

    /** 销售渠道（直营 / 分销 …）：CHANNEL 字段来源 */
    private String channel;

    /** 店铺名：SHOP_NAME 字段来源 */
    private String shopName;

    /** 毛利（下单时算好落库）：GROSS_PROFIT 字段来源；null = 没算过 */
    private BigDecimal grossProfit;

    /** 客服备注：CS_REMARK 字段来源（含「改」= 全局排除） */
    private String csRemark;

    /** 平台备注（买家留言同步）：证据用 */
    private String platformRemark;

    /** 平台备注最后更新时间：PLATFORM_REMARK_UPDATED 字段来源；null = 从未更新 */
    private LocalDateTime platformRemarkUpdatedAt;

    /** 备注被审核消费的时间（防止同一条备注反复触发排除） */
    private LocalDateTime remarkConsumedAt;

    /** 多地址订单标记：ADDRESS_MULTI 字段来源 */
    private Integer auditAddrMulti;
}
