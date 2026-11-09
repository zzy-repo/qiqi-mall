package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
}
