package com.qiqi.mall.oms.wms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * WMS 模拟器（方案 3a）：
 * - dispatch：回报受理 ACK（模拟网络延迟）
 * - randomTrackNo：出库时生成运单号
 * 回传（拣货/出库/揽收/签收）由管理端按钮或 curl 主动触发，不用定时任务，保证演示可控。
 */
@Service
public class MockWmsService {

    private static final Logger log = LoggerFactory.getLogger(MockWmsService.class);
    private static final String[] COMPANIES = {"顺丰", "中通", "圆通"};

    /** 下发到 WMS，返回受理 ACK 文本 */
    public String dispatch(String deliverySn) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(50, 150));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String ack = "WMS-ACK-" + System.currentTimeMillis();
        log.info("[MockWMS] 受理发货单 {} -> {}", deliverySn, ack);
        return ack;
    }

    /** 模拟出库时 WMS 回传的运单号 */
    public String randomTrackNo() {
        return "SF" + System.currentTimeMillis() + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    public String randomCompany() {
        return COMPANIES[ThreadLocalRandom.current().nextInt(COMPANIES.length)];
    }
}
