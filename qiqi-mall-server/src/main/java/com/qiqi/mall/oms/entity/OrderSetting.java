package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("order_setting")
public class OrderSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 自动收货天数 */
    private Integer autoConfirmDays;

    /** 未付款自动关单分钟数 */
    private Integer autoCloseMinutes;
}
