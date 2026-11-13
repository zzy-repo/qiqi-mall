package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
@TableName("return_apply")
public class ReturnApply {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long memberId;

    private String returnReason;

    private String description;

    /** 状态:0待处理 1通过 2拒绝 */
    private Integer status;

    /** 退货原因字典ID */
    private Long reasonId;

    /**
     * 状态流转序号（P2 第二步）：与 {@code status} 同一条 UPDATE 自增，是售后台账幂等键
     * {@code {returnApplyId}:{statusSeq}} 的来源。只读字段（见 {@code Order#statusSeq} 的说明）。
     */
    @TableField(value = "status_seq", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    @JsonIgnore
    private Integer statusSeq;
}
