package com.qiqi.mall.shared.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 订单号：QQ + yyyyMMddHHmmss + 6 位随机数（并发下避免唯一键冲突） */
public final class OrderSnGenerator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private OrderSnGenerator() {
    }

    public static String next() {
        return "QQ" + LocalDateTime.now().format(TS) + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
