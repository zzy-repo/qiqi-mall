package com.qiqi.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("return_reason")
public class ReturnReason {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 如:质量问题/不想要了 */
    private String name;
}
