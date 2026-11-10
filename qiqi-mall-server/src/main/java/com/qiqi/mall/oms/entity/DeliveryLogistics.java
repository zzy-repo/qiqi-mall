package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 物流信息（WMS/TMS 回传，与发货单 1:1） */
@Data
@TableName("delivery_logistics")
public class DeliveryLogistics {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;

    private String company;

    private String trackNo;

    private LocalDateTime createdAt;
}
