package com.qiqi.mall.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.channel.entity.ChannelOrderMap;
import com.qiqi.mall.channel.entity.ChannelShop;
import com.qiqi.mall.channel.entity.ChannelSyncLog;
import com.qiqi.mall.channel.mapper.ChannelOrderMapMapper;
import com.qiqi.mall.channel.mapper.ChannelShopMapper;
import com.qiqi.mall.channel.model.PlatformOrder;
import com.qiqi.mall.channel.model.PushResult;
import com.qiqi.mall.channel.model.ShipmentInfo;
import com.qiqi.mall.channel.spi.ChannelAdapter;
import com.qiqi.mall.channel.spi.ChannelException;
import com.qiqi.mall.oms.entity.DeliveryLogistics;
import com.qiqi.mall.oms.entity.DeliveryOrder;
import com.qiqi.mall.oms.service.DeliveryLogisticsService;
import com.qiqi.mall.oms.service.DeliveryOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入闭环的编排层：<b>拉单</b> 与 <b>回传发货</b>。
 *
 * <p>这一层只做三件事：取适配器 → 按失败口径落 sync_log → 推进店铺水位。
 * 「怎么和平台说话」在适配器里，「怎么建内部订单」在 {@link ChannelOrderImporter} 里，
 * 三者互不越界（因此换平台不用碰这里，改建单规则不用碰适配器）。
 *
 * <h3>失败口径（决定 sync_log 里是 OK / FAIL / SKIP）</h3>
 * <ul>
 *   <li>整个拉单动作（一次外呼）一行汇总：拿到平台数据 = OK（哪怕里面 0 张单）；
 *       外呼失败 = FAIL，并把异常信息写进 reason；</li>
 *   <li>单张订单：幂等命中 = SKIP/IDEMPOTENT；SKU 对不上 = FAIL/SKU_NOT_MATCHED；
 *       成功不单独记行（汇总行的 created 计数就是证据）；</li>
 *   <li>回传：每个包裹每次尝试一行。失败重试一次 —— 第二次成功就再记一行 attempt=2 OK，
 *       这样「重试了几次」不需要额外统计，翻日志就能数。</li>
 * </ul>
 */
@Service
public class ChannelSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChannelSyncService.class);

    /** 首次拉单（水位为空）的回看窗口：1 天 */
    private static final int FIRST_PULL_DAYS = 1;
    /** 回传最多尝试次数：1 次正常 + 1 次重试（需求单口径） */
    private static final int MAX_ATTEMPTS = 2;
    /** 没有物流记录时的兜底承运商（出库回传才会写 delivery_logistics） */
    private static final String DEFAULT_CARRIER = "顺丰";

    private final ChannelShopService shopService;
    private final ChannelShopMapper shopMapper;
    private final ChannelOrderMapMapper mapMapper;
    private final ChannelAdapterRegistry registry;
    private final ChannelOrderImporter importer;
    private final ChannelSyncLogWriter logWriter;
    private final DeliveryOrderService deliveryOrderService;
    private final DeliveryLogisticsService logisticsService;

    public ChannelSyncService(ChannelShopService shopService, ChannelShopMapper shopMapper,
                              ChannelOrderMapMapper mapMapper, ChannelAdapterRegistry registry,
                              ChannelOrderImporter importer, ChannelSyncLogWriter logWriter,
                              DeliveryOrderService deliveryOrderService,
                              DeliveryLogisticsService logisticsService) {
        this.shopService = shopService;
        this.shopMapper = shopMapper;
        this.mapMapper = mapMapper;
        this.registry = registry;
        this.importer = importer;
        this.logWriter = logWriter;
        this.deliveryOrderService = deliveryOrderService;
        this.logisticsService = logisticsService;
    }

    // ================================================================== 拉单

    /**
     * 拉单：平台 → 统一模型 → 待审核订单（status=7）。
     *
     * <p>水位口径见 {@code ChannelShop#watermarkAt}：存「本次拉单开始时刻」，下次拿它当 since。
     * <b>只有外呼成功才推进水位</b> —— 外呼失败还往前推水位，等于把这段时间的单永久丢掉。
     *
     * @return 汇总（给后台 toast 与 e2e 断言用）：total / created / skipped / failed / orderIds / platformSns
     */
    public Map<String, Object> pull(Long shopId) {
        ChannelShop shop = shopService.require(shopId);
        if (shop.getEnabled() == null || shop.getEnabled() != 1) {
            throw new IllegalStateException("店铺「" + shop.getShopName() + "」已停用，不能拉单");
        }
        ChannelAdapter adapter = registry.of(shop.getPlatformType());

        LocalDateTime startedAt = LocalDateTime.now();
        LocalDateTime since = shop.getWatermarkAt() != null
                ? shop.getWatermarkAt()
                : startedAt.minusDays(FIRST_PULL_DAYS);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", ChannelSyncLog.ACTION_PULL);
        request.put("platformType", shop.getPlatformType());
        request.put("shopCode", shop.getShopCode());
        request.put("since", since.toString());

        long t0 = System.nanoTime();
        List<PlatformOrder> orders;
        try {
            orders = adapter.pullOrders(shop.getShopCode(), since);
        } catch (ChannelException e) {
            long latency = elapsedMs(t0);
            String reason = "拉单失败：" + e.getMessage();
            // 外呼失败：水位原地不动（这些单下次还要拉），只记一行 FAIL
            logWriter.write(shop.getId(), ChannelSyncLog.ACTION_PULL, "SHOP", shop.getId(),
                    request, failurePayload(e), ChannelSyncLog.RESULT_FAIL, reason, 1, latency);
            // 技术细节（ConnectException / 完整 URL）只进服务端日志：运营看 reason，排障看这里
            log.warn("[channel] 拉单失败 shop={}({})：{}（技术细节：{}）",
                    shop.getShopName(), shop.getPlatformType(), e.getMessage(), technicalDetailOf(e));
            // toast 带上「哪家店」：三家店三个按钮，只说「拉单失败」等于让运营自己去猜是哪家
            throw new IllegalStateException("拉单失败：" + shop.getShopName() + " " + e.getMessage());
        }
        long latency = elapsedMs(t0);

        int created = 0;
        int skipped = 0;
        int failed = 0;
        List<Long> orderIds = new ArrayList<>();
        List<String> platformSns = new ArrayList<>();

        for (PlatformOrder platformOrder : orders) {
            platformSns.add(platformOrder.platformSn());
            try {
                ChannelOrderImporter.ImportResult result = importer.importOne(shop, platformOrder);
                created++;
                orderIds.add(result.orderId());
            } catch (ChannelOrderImporter.DuplicateOrderException e) {
                // 幂等命中：平台重推 / 水位重叠，属正常现象，但必须留证据
                skipped++;
                logWriter.write(shop.getId(), ChannelSyncLog.ACTION_PULL, "PLATFORM_ORDER", null,
                        briefOf(shop, platformOrder), null, ChannelSyncLog.RESULT_SKIP,
                        ChannelSyncLog.REASON_IDEMPOTENT, 1, 0);
            } catch (ChannelOrderImporter.SkuNotMatchedException e) {
                // 商品编码对不上：跳过该单（不猜、不建半张单），原因精确到缺哪个编码
                failed++;
                logWriter.write(shop.getId(), ChannelSyncLog.ACTION_PULL, "PLATFORM_ORDER", null,
                        briefOf(shop, platformOrder),
                        Map.of("missingSkuCodes", e.getMissingCodes()),
                        ChannelSyncLog.RESULT_FAIL, ChannelSyncLog.REASON_SKU_NOT_MATCHED, 1, 0);
                log.warn("[channel] 平台单 {} 的 SKU 匹配不到：{}", platformOrder.platformSn(), e.getMissingCodes());
            }
        }

        // 水位推进 + 上次拉单时刻（外呼已成功，即使有单失败也推进：失败单的原因已落日志）
        shopMapper.touchPull(shop.getId(), startedAt, LocalDateTime.now());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("shopId", shop.getId());
        summary.put("platformType", shop.getPlatformType());
        summary.put("since", since.toString());
        summary.put("total", orders.size());
        summary.put("created", created);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        summary.put("orderIds", orderIds);
        summary.put("platformSns", platformSns);
        logWriter.write(shop.getId(), ChannelSyncLog.ACTION_PULL, "SHOP", shop.getId(),
                request, summary, ChannelSyncLog.RESULT_OK,
                "拉回 " + orders.size() + " 单：新建 " + created + " / 跳过 " + skipped + " / 失败 " + failed,
                1, latency);
        log.info("[channel] 拉单 shop={}({})：共 {} 单 → 新建 {} / 跳过 {} / 失败 {}（{}ms）",
                shop.getShopName(), shop.getPlatformType(), orders.size(), created, skipped, failed, latency);
        return summary;
    }

    // ============================================================== 回传发货

    /**
     * 回传发货：取该订单**已出库**的包裹，逐个包裹调平台。
     *
     * <p>为什么只挑 status ≥ 已出库：没出库就没有运单号，回传出去平台也发不了货；
     * 已取消(9) 不是「出库状态」，按状态码大小排序会把它排在前面，所以显式排除。
     *
     * <p>失败重试一次（{@link #MAX_ATTEMPTS}）：第二次成功就记两行（attempt=1 FAIL、attempt=2 OK）。
     */
    public Map<String, Object> pushShipment(Long orderId) {
        ChannelOrderMap mapping = mapMapper.findByOrderId(orderId);
        if (mapping == null) {
            throw new IllegalStateException("订单 " + orderId + " 不是渠道订单（没有 platform_sn 映射），无法回传发货");
        }
        ChannelShop shop = shopService.require(mapping.getShopId());
        if (shop.getEnabled() == null || shop.getEnabled() != 1) {
            throw new IllegalStateException("店铺「" + shop.getShopName() + "」已停用，不能回传发货");
        }
        ChannelAdapter adapter = registry.of(shop.getPlatformType());

        List<DeliveryOrder> packages = deliveryOrderService.list(new LambdaQueryWrapper<DeliveryOrder>()
                        .eq(DeliveryOrder::getOrderId, orderId)).stream()
                .filter(p -> p.getStatus() != null && p.getStatus() >= DeliveryOrder.OUTBOUND
                        && p.getStatus() != DeliveryOrder.CANCELLED)
                .sorted(Comparator.comparing(DeliveryOrder::getId))
                .toList();
        if (packages.isEmpty()) {
            throw new IllegalStateException("订单 " + orderId + " 还没有已出库的包裹，无法回传发货（先拆单/下发/出库）");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        int skipCount = 0;
        for (DeliveryOrder pkg : packages) {
            DeliveryLogistics logistics = logisticsService.getOne(new LambdaQueryWrapper<DeliveryLogistics>()
                    .eq(DeliveryLogistics::getDeliveryId, pkg.getId()).last("LIMIT 1"));
            ShipmentInfo info = new ShipmentInfo(
                    mapping.getPlatformSn(),
                    logistics != null && logistics.getCompany() != null && !logistics.getCompany().isBlank()
                            ? logistics.getCompany() : DEFAULT_CARRIER,
                    logistics == null ? null : logistics.getTrackNo(),
                    LocalDateTime.now());
            Map<String, Object> result = pushOnePackage(shop, adapter, pkg, info);
            results.add(result);
            if (Boolean.TRUE.equals(result.get("ok"))) {
                if ("SKIP".equals(result.get("result"))) skipCount++; else okCount++;
            } else {
                failCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", orderId);
        summary.put("platformType", shop.getPlatformType());
        summary.put("platformSn", mapping.getPlatformSn());
        summary.put("packages", packages.size());
        summary.put("ok", okCount);
        summary.put("skipped", skipCount);
        summary.put("failed", failCount);
        summary.put("results", results);
        log.info("[channel] 回传发货 order={} 平台单 {}：{} 个包裹 → 成功 {} / 跳过 {} / 失败 {}",
                orderId, mapping.getPlatformSn(), packages.size(), okCount, skipCount, failCount);
        return summary;
    }

    /** 单个包裹：最多两次尝试，每次尝试都落一行日志 */
    private Map<String, Object> pushOnePackage(ChannelShop shop, ChannelAdapter adapter,
                                               DeliveryOrder pkg, ShipmentInfo info) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", ChannelSyncLog.ACTION_SHIP_PUSH);
        request.put("platformType", shop.getPlatformType());
        request.put("shopCode", shop.getShopCode());
        request.put("platformSn", info.platformSn());
        request.put("deliverySn", pkg.getDeliverySn());
        request.put("carrier", info.carrier());
        request.put("trackNo", info.trackNo());

        boolean ok = false;
        String lastError = null;
        String finalResult = ChannelSyncLog.RESULT_FAIL;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long t0 = System.nanoTime();
            try {
                PushResult result = adapter.pushShipment(shop.getShopCode(), info.platformSn(), info);
                long latency = elapsedMs(t0);
                if (result.duplicated()) {
                    // 平台侧已存在：不是失败，是幂等命中
                    logWriter.write(shop.getId(), ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg.getId(),
                            request, result.rawResponse(), ChannelSyncLog.RESULT_SKIP,
                            ChannelSyncLog.REASON_IDEMPOTENT, attempt, latency);
                    ok = true;
                    finalResult = ChannelSyncLog.RESULT_SKIP;
                    break;
                }
                if (result.ok()) {
                    logWriter.write(shop.getId(), ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg.getId(),
                            request, result.rawResponse(), ChannelSyncLog.RESULT_OK,
                            "回传成功（" + result.code() + "）", attempt, latency);
                    ok = true;
                    finalResult = ChannelSyncLog.RESULT_OK;
                    break;
                }
                // ok=false：平台明确拒绝（4xx/5xx 业务码）
                lastError = result.code() + " " + result.message();
                logWriter.write(shop.getId(), ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg.getId(),
                        request, result.rawResponse(), ChannelSyncLog.RESULT_FAIL, lastError, attempt, latency);
            } catch (ChannelException e) {
                long latency = elapsedMs(t0);
                lastError = e.getHttpStatus() > 0
                        ? ("HTTP " + e.getHttpStatus() + "：" + e.getMessage())
                        : e.getMessage();
                // 报文列存响应体；连接层失败（没有响应体）时存技术细节 —— 那一列就是排查用的证据
                logWriter.write(shop.getId(), ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg.getId(),
                        request, failurePayload(e), ChannelSyncLog.RESULT_FAIL, lastError, attempt, latency);
                log.warn("[channel] 回传发货外呼失败 order={} 包裹 {}：{}（技术细节：{}）",
                        pkg.getOrderId(), pkg.getDeliverySn(), e.getMessage(), technicalDetailOf(e));
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("[channel] 回传失败（第 {} 次），重试一次：{}", attempt, lastError);
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deliveryId", pkg.getId());
        row.put("deliverySn", pkg.getDeliverySn());
        row.put("trackNo", info.trackNo());
        row.put("ok", ok);
        row.put("result", finalResult);
        // 只有最终失败才把错误带出来；成功的（哪怕重试过）不挂旧错误，避免「成功了却显示报错」
        row.put("lastError", ok ? null : lastError);
        return row;
    }

    /**
     * 失败行的「报文」列：平台有响应体就存响应体；连接层失败（没有响应体）就把技术细节存进去。
     *
     * <p>这一列是排查用的证据，所以这里**必须**留得住那点技术细节 —— 人话（reason）给运营，
     * 细节（errorDetail）给排障，两者都存在同一行上，谁也不用去翻服务端日志。
     */
    private static Object failurePayload(ChannelException e) {
        if (e.getRawResponse() != null && !e.getRawResponse().isBlank()) {
            return e.getRawResponse();
        }
        if (e.getDetail() == null || e.getDetail().isBlank()) {
            return null;
        }
        return Map.of("errorDetail", e.getDetail());
    }

    /** 服务端日志里的技术细节（没有就写「无」，别打出空括号让人猜） */
    private static String technicalDetailOf(ChannelException e) {
        return e.getDetail() == null || e.getDetail().isBlank() ? "无（平台有响应体，见同步日志报文）" : e.getDetail();
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** 单张平台订单的日志摘要（不把整单塞进 payload） */
    private static Map<String, Object> briefOf(ChannelShop shop, PlatformOrder platformOrder) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("platformType", shop.getPlatformType());
        brief.put("shopCode", shop.getShopCode());
        brief.put("platformSn", platformOrder.platformSn());
        brief.put("totalAmount", platformOrder.totalAmount());
        brief.put("items", platformOrder.items().stream()
                .map(i -> i.platformSkuCode() + "x" + i.qty()).toList());
        return brief;
    }
}
