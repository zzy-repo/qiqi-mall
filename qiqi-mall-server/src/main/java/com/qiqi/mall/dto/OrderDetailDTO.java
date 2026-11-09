package com.qiqi.mall.dto;

import com.qiqi.mall.entity.Order;
import com.qiqi.mall.entity.OrderItem;
import com.qiqi.mall.entity.ReturnApply;
import lombok.Data;

import java.util.List;

/** 订单详情：订单 + 明细 + 退货申请（聚合展示） */
@Data
public class OrderDetailDTO {

    private Order order;

    private List<OrderItem> items;

    private List<ReturnApply> returnApplies;
}
