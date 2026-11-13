package com.qiqi.mall.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.channel.entity.ChannelShop;
import com.qiqi.mall.channel.entity.ChannelSyncLog;
import com.qiqi.mall.channel.mapper.ChannelShopMapper;
import com.qiqi.mall.channel.mapper.ChannelSyncLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 店铺读取（+ 启动时补齐三个店铺种子）+ 同步日志分页查询。
 *
 * <p><b>为什么启动时要补种子</b>：迁移脚本与 schema 里都有 INSERT，但 e2e 分片库是
 * 「schema + seed」重建的，谁漏了那行 INSERT，后台页面就空着、拉单按钮一个都没有。
 * 这里每次启动 upsert 一次（幂等），让「三个平台店铺」成为应用不变量而不是数据脚本的运气。
 */
@Service
public class ChannelShopService {

    private static final Logger log = LoggerFactory.getLogger(ChannelShopService.class);

    /** 店铺种子：platform_type / shop_code / shop_name（与 sql/_rollback/migrate-channel.sql 同口径） */
    private static final List<String[]> SEEDS = List.of(
            new String[]{"MALL", "MALL-001", "七七商城自营"},
            new String[]{"TAOBAO_MOCK", "TB-001", "淘宝旗舰店（模拟）"},
            new String[]{"DOUYIN_MOCK", "DY-001", "抖音小店（模拟）"});

    private final ChannelShopMapper shopMapper;
    private final ChannelSyncLogMapper syncLogMapper;
    private final ChannelSyncLogWriter logWriter;

    public ChannelShopService(ChannelShopMapper shopMapper, ChannelSyncLogMapper syncLogMapper,
                              ChannelSyncLogWriter logWriter) {
        this.shopMapper = shopMapper;
        this.syncLogMapper = syncLogMapper;
        this.logWriter = logWriter;
    }

    /**
     * 启动补齐店铺种子。失败只警告不阻塞启动 ——
     * 表没建（还没跑迁移）时应用照样能起来，前台点「拉单」时才报「店铺不存在」，不会白屏。
     */
    public ApplicationRunner seedOnStartup() {
        return args -> {
            try {
                for (String[] seed : SEEDS) {
                    shopMapper.insertSeed(seed[0], seed[1], seed[2]);
                }
            } catch (Exception e) {
                log.warn("[channel] 渠道店铺种子补齐失败（是否还没跑 sql/migrate-channel.sql？）：{}", e.getMessage());
            }
        };
    }

    /** 全部店铺（按平台顺序），带今日结果计数 */
    public List<Map<String, Object>> listWithTodayCounts() {
        List<ChannelShop> shops = shopMapper.selectList(new LambdaQueryWrapper<ChannelShop>()
                .orderByAsc(ChannelShop::getId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChannelShop shop : shops) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", shop.getId());
            row.put("platformType", shop.getPlatformType());
            row.put("shopCode", shop.getShopCode());
            row.put("shopName", shop.getShopName());
            row.put("enabled", shop.getEnabled());
            row.put("watermarkAt", shop.getWatermarkAt());
            row.put("lastPullAt", shop.getLastPullAt());
            Map<String, Integer> counts = logWriter.todayCounts(shop.getId());
            row.put("todayOk", counts.get(ChannelSyncLog.RESULT_OK));
            row.put("todayFail", counts.get(ChannelSyncLog.RESULT_FAIL));
            row.put("todaySkip", counts.get(ChannelSyncLog.RESULT_SKIP));
            rows.add(row);
        }
        return rows;
    }

    /** 取店铺；不存在就抛业务异常（后台看到中文原因） */
    public ChannelShop require(Long shopId) {
        ChannelShop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new IllegalStateException("渠道店铺不存在：" + shopId);
        }
        return shop;
    }

    /**
     * 同步日志分页（页面契约：{@code page}/{@code size}，size 夹在 1..50，按时间倒序）。
     *
     * <p>过滤项全部可选：{@code shopId} / {@code action} / {@code result}。
     */
    public Page<ChannelSyncLog> pageLogs(Long shopId, String action, String result, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 50);
        LambdaQueryWrapper<ChannelSyncLog> query = new LambdaQueryWrapper<>();
        query.eq(shopId != null, ChannelSyncLog::getShopId, shopId)
                .eq(action != null && !action.isBlank(), ChannelSyncLog::getAction, action)
                .eq(result != null && !result.isBlank(), ChannelSyncLog::getResult, result)
                .orderByDesc(ChannelSyncLog::getId);
        return syncLogMapper.selectPage(new Page<>(safePage, safeSize), query);
    }
}
