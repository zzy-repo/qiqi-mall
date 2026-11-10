package com.qiqi.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiqi.mall.entity.PaymentRecord;

public interface PaymentRecordService extends IService<PaymentRecord> {

    /** 记一条流水，tradeNo 自动生成（模拟渠道） */
    void record(String orderSn, java.math.BigDecimal amount, int type, int channel);
}
