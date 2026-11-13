package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 乐观锁版本号（MyBatis-Plus @Version）。
     * updateById 时会生成 {@code SET version = version + 1 WHERE id = ? AND version = ?}；
     * 影响行数 0 = 期间被别人改过 → 接口回 code=409，不静默覆盖。
     */
    @Version
    @TableField("version")
    private Integer version;

    /**
     * 状态流转序号（P2 第二步）：与 {@code status} 同一条 UPDATE 自增，是包裹状态台账幂等键
     * {@code {deliveryId}:{statusSeq}} 的来源。只读字段（见 {@code Order#statusSeq} 的说明）。
     */
    @TableField(value = "status_seq", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    @JsonIgnore
    private Integer statusSeq;
}
