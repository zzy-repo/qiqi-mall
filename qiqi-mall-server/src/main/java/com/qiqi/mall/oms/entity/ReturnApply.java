package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 操作确认码：只在审核请求的 body 里出现，**不落库**（@TableField(exist=false)），
     * 所以 PUT /admin/return-applies/{id} 可以沿用「body 带 confirmCode」的统一契约。
     */
    @TableField(exist = false)
    private String confirmCode;
}
