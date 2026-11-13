package com.qiqi.mall.oms.entity;

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

    // ---------------------------------------------------------------- P2 第二步：加固字段（溯源用）

    /**
     * 关联订单ID（回填自 order_sn；order 被物理删后可能悬空）。
     *
     * <p>为什么有了 order_sn 还要它：{@code order_sn} 是字符串，删单后反查不到任何东西；
     * 而 {@code order_id} 至少还能和台账里的 order_id 对齐（台账故意不随订单删除）。
     */
    private Long orderId;

    /** 触发单据类型：{@code RETURN_APPLY} 表示这笔退款由某张退货申请触发 */
    private String refType;

    /** 触发单据ID：退款时 = 退货申请 id —— 「退的是哪张申请」就靠它 */
    private Long refId;

    /** 退款指向的**原支付流水** id（type=2 时应填；查不到原流水时为 null） */
    private Long originFlowId;

    /** 操作者（台账口径，见 {@code ActorResolver}） */
    private String operatorType;

    private Long operatorId;

    private String operatorName;

    /**
     * 幂等键：支付 {@code {orderSn}:PAY}、退款 {@code {orderSn}:REFUND:{returnApplyId}}。
     *
     * <p>口径与状态台账一致：**按业务事实**派生（一笔支付 / 一笔针对某张申请的退款各只该有一条），
     * 重放时撞 uk_idem → 静默跳过（钱已经记过账，不能记第二遍）。
     * 历史行为（P2 之前）的 14 行全是 NULL，{@code uk_idem} 允许多个 NULL，不受影响。
     */
    private String idemKey;
}
