package com.qiqi.mall.oms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 分仓库存记录：拆单时的「归属标记」依据；不在拆单时扣减（拆单口径见 {@link com.qiqi.mall.oms.service.SplitOrderService}） */
@Data
@TableName("inventory")
public class Inventory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private Long warehouseId;

    private Integer stock;
}
