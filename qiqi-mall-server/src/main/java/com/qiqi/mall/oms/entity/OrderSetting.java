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

    /** 自动审核总开关：0=定时任务不跑（人工仍可手动触发一次 run） */
    private Integer autoAuditEnabled;

    /** 库存判据档位：STRICT（默认，不足即降级人工）/ LENIENT（不足只告警） */
    private String auditStockGuard;

    /** 定时审核间隔（分钟） */
    private Integer auditRunIntervalMin;
}
