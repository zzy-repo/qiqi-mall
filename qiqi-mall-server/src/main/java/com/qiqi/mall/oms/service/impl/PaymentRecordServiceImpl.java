package com.qiqi.mall.oms.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.oms.entity.PaymentRecord;
import com.qiqi.mall.oms.mapper.PaymentRecordMapper;
import com.qiqi.mall.oms.service.PaymentRecordService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class PaymentRecordServiceImpl extends ServiceImpl<PaymentRecordMapper, PaymentRecord>
        implements PaymentRecordService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    @Override
    public void record(String orderSn, BigDecimal amount, int type, int channel) {
        PaymentRecord record = new PaymentRecord();
        record.setOrderSn(orderSn);
        record.setAmount(amount == null ? BigDecimal.ZERO : amount);
        record.setType(type);
        record.setChannel(channel);
        record.setTradeNo((type == PaymentRecord.TYPE_REFUND ? "MOCKREF" : "MOCKPAY") + LocalDateTime.now().format(TS));
        record.setCreatedAt(LocalDateTime.now());
        save(record);
    }
}
