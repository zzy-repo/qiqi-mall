package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 发货单（拆单后的新聚合根） */
@Data
@TableName("delivery_order")
public class DeliveryOrder {

    /** 状态：0待下发 1已下发 2已拣货 3已出库 4运输中 5已签收 9已取消 */
    public static final int PENDING = 0;
    public static final int DISPATCHED = 1;
    public static final int PICKED = 2;
    public static final int OUTBOUND = 3;
    public static final int IN_TRANSIT = 4;
    public static final int SIGNED = 5;
    public static final int CANCELLED = 9;

    /** 来源：0自动拆单 1人工调整（改仓/改量/拆分/合并/删除） */
    public static final int SOURCE_AUTO = 0;
    public static final int SOURCE_MANUAL = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发货单号（DL 开头） */
    private String deliverySn;

    private Long orderId;

    private Long warehouseId;

    private Integer status;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    /** 来源：0自动拆单 1人工调整（见 SOURCE_AUTO / SOURCE_MANUAL） */
    private Integer source;

    /** 预留：命中的拆单规则ID，层次二规则引擎启用后才写入 */
    private Long splitRuleId;

    private LocalDateTime createdAt;
}
