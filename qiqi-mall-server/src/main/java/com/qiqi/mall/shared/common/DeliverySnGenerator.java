package com.qiqi.mall.shared.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 发货单号：DL + yyyyMMddHHmmss + 6 位随机数（与 OrderSnGenerator 同风格，并发下不撞唯一键） */
public final class DeliverySnGenerator {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private DeliverySnGenerator() {
    }

    public static String next() {
        return "DL" + LocalDateTime.now().format(TS) + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
