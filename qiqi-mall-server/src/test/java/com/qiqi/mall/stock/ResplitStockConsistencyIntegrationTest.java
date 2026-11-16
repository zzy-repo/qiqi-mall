package com.qiqi.mall.stock;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「取消包裹 → 重新拆单 → 再发货」的库存一致性：真起后端 + 真打 HTTP + 真连库。
 *
 * <h3>要钉死的那条不变量（与修法无关）</h3>
 * 一张订单从下单到签收，<b>总池库存的净变化必须恰好是 −N，而且只发生一次</b>：
 * <pre>
 * 下单扣 N            → 台账 -N  ORDER_CREATE
 * 审核通过 + 拆单       → 不动库存（既有不变量：拆单只做归属标记）
 * 取消包裹（最后一张） → 不把 N 补回总池（货仍记在这张单名下）
 * 重新审核 + 重新拆单   → 不动库存（否则就是同一件货扣两次）
 * 下发 / 出库 / 签收    → 不动库存（货在这一刻离开商家，库存早已扣过）
 * 终态                → availableStock == before − N，且 SUM(stock_ledger.delta) == −N
 * </pre>
 *
 * <h3>它修的是什么缺陷</h3>
 * 旧实现里取消包裹按包裹回补库存（{@code DeliveryFlowService#cancel} 的 {@code restoreStock}），
 * 而「包裹全被取消 → 订单退回待审核(7) → 重新审核 → 重新拆单」这条链上<b>不再扣一次</b>
 * （{@code SplitOrderService#split} 只建包裹），于是重新履约一次、货出去了，总池库存却是回补后的值
 * —— 同一个 N 被出库两次，库存虚高 N 件。表现是：账上干净（该单 {@code SUM(delta)} 恰为 0），
 * 只有把「货已签收」和「台账净额」摆在一起才看得出来。
 *
 * <h3>为什么这条用例必须自己造「真扣过库存」的订单</h3>
 * 基线只能来自真实扣减路径（{@code POST /admin/orders} 后台建单与买家下单同走
 * {@code ORDER_CREATE} 扣减）。像 {@code OptimisticLockIntegrationTest} 那样直接 INSERT 订单/包裹行，
 * 一开始就把被测对象（下单扣减）跳过去了 —— 那种数据的库存基线是假的。
 *
 * <h3>断言为什么不用 {@code sku.stock}</h3>
 * 分桶模式下 {@code sku.stock} 只是展示缓存（后台任务每 250ms 按 {@code SUM(桶)} 回写），读它会与用例赛跑。
 * 这里统一走 {@link StockStore#availableStock}（legacy = sku.stock / bucket = SUM(桶)），断言的是权威值。
 * 除了库存，还必须一起断言<b>台账</b>：库存对上但台账少一行，就是「无台账的库存变更」，
 * 那是比这个缺陷更严重的问题。
 *
 * <p>数据自建自清（订单/会员自己造，跑完全删，sku 库存与桶在 {@code @AfterAll} 还原）。
 *
 * <p>运行：{@code scripts/run-it.sh -Dtest=ResplitStockConsistencyIntegrationTest}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResplitStockConsistencyIntegrationTest {

    /** admin 口令：默认种子值；改过口令（scripts/set-admin-password.sh）时用环境变量覆盖 */
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "123456");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 库存落点（启动时定：legacy = sku.stock 就是库存 / bucket = 权威在分桶，sku.stock 是缓存）。
     * 断言与还原都走它 —— 两种模式都能跑，且断言口径不变。
     */
    @Autowired
    private com.qiqi.mall.stock.StockStore stockStore;

    private String adminJwt;
    private Long memberId;
    private Long productId;
    private Long skuId;
    private Integer skuStockBefore;

    private final List<Long> createdOrders = new ArrayList<>();

    // ------------------------------------------------------------------ 准备 / 清理

    @BeforeAll
    void prepare() {
        ResponseEntity<JsonNode> resp = rest.postForEntity("/admin/auth/login",
                jsonEntity("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"), JsonNode.class);
        adminJwt = resp.getBody() == null ? null : resp.getBody().path("data").path("accessToken").asText(null);
        assertThat(adminJwt).as("admin 登录失败：%s", resp.getBody()).isNotBlank();

        memberId = registerBuyer();

        // 挑库存最充裕的 SKU：本类会反复扣 3~4 件，别把种子规格扣成 0
        Map<String, Object> row = jdbc.queryForMap("SELECT id, product_id, stock FROM sku ORDER BY stock DESC, id LIMIT 1");
        skuId = ((Number) row.get("id")).longValue();
        productId = ((Number) row.get("product_id")).longValue();
        skuStockBefore = stockStore.availableStock(skuId);
        System.out.printf("[准备] member=%d sku=%d product=%d 权威库存=%d（模式：%s）%n",
                memberId, skuId, productId, skuStockBefore, stockStore.mode());
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrders) {
            for (String sn : jdbc.queryForList("SELECT order_sn FROM `order` WHERE id = ?", String.class, orderId)) {
                jdbc.update("DELETE FROM payment_record WHERE order_sn = ?", sn);
            }
            jdbc.update("DELETE l FROM delivery_logistics l JOIN delivery_order d ON d.id = l.delivery_id "
                    + "WHERE d.order_id = ?", orderId);
            jdbc.update("DELETE FROM return_apply WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);
        }
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        if (skuId != null && skuStockBefore != null) {
            stockStore.resetStock(skuId, skuStockBefore);   // 还原权威库存（桶 + 缓存）
        }
        // 自查看一眼：本类造过的订单一条台账都不许剩（留了就是孤儿台账，check-data-integrity ① 会报红）
        if (!createdOrders.isEmpty()) {
            String in = createdOrders.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
            long leftover = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_ledger WHERE order_id IN (" + in + ")", Long.class);
            assertThat(leftover).as("清理后本类造过的订单不许再留 stock_ledger 行").isZero();
        }
        System.out.printf("[清理] 自建订单 %d 张、会员 1 个已删除，sku=%d 权威库存还原为 %d%n",
                createdOrders.size(), skuId, skuStockBefore);
    }

    // ================================================================== 用例一：单轮「取消 → 重拆 → 发货」

    @Test
    @DisplayName("① 取消包裹 → 重新拆单 → 再发货：库存净变化 = −N，且只发生一次")
    void singleRound_cancelThenResplitThenShip_deductsExactlyOnce() {
        int n = 3;                                            // 质数：避开「2 vs 1」这类巧合蒙对断言
        int before = stockStore.availableStock(skuId);
        Long orderId = createOrder(sn("RSC-1"), n);

        // ① 下单即扣：基线必须来自真扣减
        assertThat(stockStore.availableStock(skuId)).as("下单即扣库存（%d 件）", n).isEqualTo(before - n);
        assertThat(sumDelta(orderId)).as("下单记一条 ORDER_CREATE 负数台账").isEqualTo(-n);
        assertThat(reasonCount(orderId, "ORDER_CREATE")).as("只记了一行下单扣减").isEqualTo(1);
        System.out.printf("① 建单 id=%d：库存 %d → %d，台账净额 %d%n",
                orderId, before, stockStore.availableStock(skuId), sumDelta(orderId));

        // ② 审核通过 → 拆单：只建包裹，不许动库存（既有不变量）
        Long parcel1 = auditPassAndFirstParcel(orderId);
        assertThat(orderStatus(orderId)).as("审核通过后订单应为待发货(8)").isEqualTo(8);
        assertThat(deliveryStatus(parcel1)).as("新包裹应为待下发(0)").isZero();
        assertThat(stockStore.availableStock(skuId)).as("**拆单不许扣库存**（既有不变量）").isEqualTo(before - n);
        assertThat(sumDelta(orderId)).as("拆单不写库存台账").isEqualTo(-n);
        System.out.printf("② 审核通过 + 拆单 → 包裹 %d（待下发），库存仍 %d（拆单不动库存）%n",
                parcel1, stockStore.availableStock(skuId));

        // ③ 取消这张（唯一的）包裹 → 订单退回待审核
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + parcel1 + "/cancel", null));
        assertThat(deliveryStatus(parcel1)).as("包裹应为已取消(9)").isEqualTo(9);
        assertThat(orderStatus(orderId)).as("全部包裹被取消 → 订单退回待审核(7)").isEqualTo(7);
        assertThat(splitStatus(orderId)).as("拆单标记必须归零，否则重新审核会返回那几张已取消的包裹").isZero();
        // ★ 这一条就是本缺陷的核心断言：货没有退回货架，仍记在这张单名下
        assertThat(stockStore.availableStock(skuId))
                .as("**取消最后一张包裹不回补库存**：货仍记在该订单名下（否则就是同一件货被出库两次）")
                .isEqualTo(before - n);
        assertThat(reasonCount(orderId, "DELIVERY_CANCEL"))
                .as("最后一张取消不该写 DELIVERY_CANCEL 回补台账").isZero();
        assertThat(sumDelta(orderId)).as("台账净额仍是 -N：一张订单的货还在外面").isEqualTo(-n);
        System.out.printf("③ 取消唯一包裹 %d → 包裹=9，订单=%d，拆单标记=%d，库存 %d（未回补，台账净额 %d）%n",
                parcel1, orderStatus(orderId), splitStatus(orderId), stockStore.availableStock(skuId), sumDelta(orderId));

        // ④ 重新审核通过 → 重新拆单：新包裹，仍然不动库存
        Long parcel2 = auditPassAndFirstParcel(orderId);
        assertThat(parcel2).as("必须真的重新拆出新包裹（不是把已取消的包裹原样返回）").isNotEqualTo(parcel1);
        assertThat(deliveryStatus(parcel2)).as("新包裹是待下发").isZero();
        assertThat(stockStore.availableStock(skuId))
                .as("**重新拆单不许再扣一次**：货早就扣过了，这里只是换了个包裹").isEqualTo(before - n);
        assertThat(sumDelta(orderId)).as("重新拆单不写库存台账").isEqualTo(-n);
        assertThat(reasonCount(orderId, "ORDER_RESPLIT"))
                .as("不该出现「重新拆单再扣」这类新原因码").isZero();
        System.out.printf("④ 重新审核 → 新包裹 %d（待下发），库存仍 %d，台账净额 %d%n",
                parcel2, stockStore.availableStock(skuId), sumDelta(orderId));

        // ⑤ 走完整条 WMS 链路：下发 → 拣货 → 出库 → 签收
        shipAndSign(parcel2);
        assertThat(orderStatus(orderId)).as("全部签收后订单已完成为 3").isEqualTo(3);
        assertThat(stockStore.availableStock(skuId))
                .as("**发货/出库/签收都不再动库存**（货早已扣过）").isEqualTo(before - n);

        // ⑥ 终态三件套：库存、台账净额、下单次数 —— 缺任何一条都可能假绿
        assertThat(stockStore.availableStock(skuId))
                .as("终态：取消→重拆→发货 的库存净变化必须是 -N，且只发生一次").isEqualTo(before - n);
        assertThat(sumDelta(orderId)).as("终态：台账净额 = -N（多扣/少扣都会在这里露出来）").isEqualTo(-n);
        assertThat(reasonCount(orderId, "ORDER_CREATE")).as("只下过一次单").isEqualTo(1);
        assertThat(ledgerRowCount(orderId)).as("全程只有一行库存台账（下单那一行）").isEqualTo(1);
        System.out.printf("⑥ 终态：库存 %d（期望 %d），台账净额 %d，台账行数 %d%n",
                stockStore.availableStock(skuId), before - n, sumDelta(orderId), ledgerRowCount(orderId));
    }

    // ================================================================== 用例二：两轮「取消 → 重拆」

    @Test
    @DisplayName("② 两轮「取消 → 重拆」：既不少扣（幂等键吞掉第二代）也不多补（守恒被夹）")
    void twoRounds_cancelAndResplitTwice_keepsNetAtMinusN() {
        int n = 3;
        int before = stockStore.availableStock(skuId);
        Long orderId = createOrder(sn("RSC-2"), n);
        assertThat(stockStore.availableStock(skuId)).isEqualTo(before - n);

        Long parcel1 = auditPassAndFirstParcel(orderId);

        // 第 1 轮
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + parcel1 + "/cancel", null));
        assertThat(stockStore.availableStock(skuId)).as("① 第 1 轮取消：最后一张 → 不回补").isEqualTo(before - n);
        Long parcel2 = auditPassAndFirstParcel(orderId);
        assertThat(stockStore.availableStock(skuId)).as("② 第 1 轮重拆：不再扣").isEqualTo(before - n);

        // 第 2 轮 —— 缺陷最容易在这里复活：幂等键/世代判别写错会导致「第二代不扣」或「回补被夹紧」
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + parcel2 + "/cancel", null));
        assertThat(orderStatus(orderId)).as("第 2 轮取消后订单仍要退回待审核(7)").isEqualTo(7);
        assertThat(stockStore.availableStock(skuId))
                .as("③ 第 2 轮取消：仍然不回补（**不是只对第一轮生效**）").isEqualTo(before - n);
        Long parcel3 = auditPassAndFirstParcel(orderId);
        assertThat(parcel3).as("每一轮都要真的生成新包裹").isNotIn(parcel1, parcel2);
        assertThat(stockStore.availableStock(skuId)).as("④ 第 2 轮重拆：仍然不再扣").isEqualTo(before - n);

        // 发货签收，再验终态
        shipAndSign(parcel3);
        assertThat(stockStore.availableStock(skuId))
                .as("终态：两轮取消 → 库存净变化仍是 -N").isEqualTo(before - n);
        assertThat(sumDelta(orderId)).as("终态：台账净额 = -N").isEqualTo(-n);
        assertThat(reasonCount(orderId, "DELIVERY_CANCEL")).as("两轮都是「最后一张」，都不该回补").isZero();
        assertThat(ledgerRowCount(orderId)).as("全程只有下单那一行库存台账").isEqualTo(1);
        System.out.printf("两轮终态：库存 %d（期望 %d），台账净额 %d，行数 %d，包裹 %d/3 张%n",
                stockStore.availableStock(skuId), before - n, sumDelta(orderId), ledgerRowCount(orderId),
                deliveryIds(orderId).size());
    }

    // ================================================================== 用例三：部分取消仍回补（口径边界）

    @Test
    @DisplayName("③ 部分取消仍回补、最后一张不回补：不许把「条件回补」简化成「永不回补」")
    void partialCancel_restores_butLastOneDoesNot() {
        int n = 4;
        int before = stockStore.availableStock(skuId);
        Long orderId = createOrder(sn("RSC-3"), n);
        assertThat(stockStore.availableStock(skuId)).isEqualTo(before - n);

        Long parcel1 = auditPassAndFirstParcel(orderId);
        // 一个包裹拆成两个（人工拆包，2 + 2）：库存在这一步不动
        Long parcel2 = splitParcelInHalf(parcel1);
        assertThat(activeDeliveryIds(orderId)).as("现在是两张在途包裹").hasSize(2);
        assertThat(stockStore.availableStock(skuId)).as("人工拆包不动库存").isEqualTo(before - n);

        // 取消其中一张 → 还有在途包裹 → 仍按该包裹明细回补
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + parcel2 + "/cancel", null));
        assertThat(stockStore.availableStock(skuId))
                .as("**部分取消仍回补**：订单还有在途包裹，这一包的货确实回了货架").isEqualTo(before - 2);
        assertThat(reasonCount(orderId, "DELIVERY_CANCEL")).as("回补记一行 DELIVERY_CANCEL").isEqualTo(1);
        assertThat(orderStatus(orderId)).as("还有在途包裹 → 订单不退回待审核").isEqualTo(8);

        // 再取消最后一张 → 一张不剩 → 不回补
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + parcel1 + "/cancel", null));
        assertThat(stockStore.availableStock(skuId))
                .as("**最后一张不补**：整体撤销的拆单结果，货仍记在这张单名下").isEqualTo(before - 2);
        assertThat(orderStatus(orderId)).as("全部取消 → 退回待审核(7)").isEqualTo(7);

        // 订单最终被关闭 → 按整单回补（守恒：3 已补 + 2 再补…… 这里订单量 4、已补 2 → 只补剩 2，总补 4）
        ok(exchange(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-reject", null));
        assertThat(stockStore.availableStock(skuId))
                .as("订单关闭（终止）→ 整单回补，且守恒夹紧不许多补").isEqualTo(before);
        assertThat(sumDelta(orderId)).as("终止后净额归零：这单的货全回库了").isZero();
        System.out.printf("部分取消边界：库存 %d（期望 %d），返回到 %d，台账净额 %d%n",
                stockStore.availableStock(skuId), before, stockStore.availableStock(skuId), sumDelta(orderId));
    }

    // ================================================================== 链路步骤

    /** 审核通过，并返回该订单第一张「待下发(0)」包裹的 id（必须恰好 1 张新包裹） */
    private Long auditPassAndFirstParcel(Long orderId) {
        List<Long> before = activeDeliveryIds(orderId);
        JsonNode data = ok(exchange(HttpMethod.POST, "/admin/orders/" + orderId + "/audit-pass", null));
        List<Long> active = activeDeliveryIds(orderId);
        assertThat(active).as("重新审核必须生成新包裹（不是空手而归）").isNotEmpty();
        assertThat(active.stream().filter(id -> !before.contains(id)).toList())
                .as("必须真的「新」生成包裹，而不是把已取消的那张原样返回")
                .isNotEmpty();
        Long newest = active.get(active.size() - 1);
        System.out.printf("   · audit-pass 返回 %d 张包裹，当前在途 %d 张，最新包裹 id=%d%n",
                data.size(), active.size(), newest);
        return newest;
    }

    /** 人工把一张待下发包裹按明细数量对半拆成两张（拆出量 = 原量的一半，且不动库存） */
    private Long splitParcelInHalf(Long deliveryId) {
        Integer version = jdbc.queryForObject(
                "SELECT version FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
        Long itemId = jdbc.queryForObject(
                "SELECT id FROM delivery_item WHERE delivery_id = ? ORDER BY id LIMIT 1", Long.class, deliveryId);
        int qty = jdbc.queryForObject(
                "SELECT quantity FROM delivery_item WHERE id = ?", Integer.class, itemId);
        Long warehouseId = jdbc.queryForObject(
                "SELECT warehouse_id FROM delivery_order WHERE id = ?", Long.class, deliveryId);
        int move = Math.max(1, qty / 2);
        String json = "{\"version\":" + version + ",\"newWarehouseId\":" + warehouseId
                + ",\"items\":[{\"deliveryItemId\":" + itemId + ",\"quantity\":" + move + "}]}";
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + deliveryId + "/split", json));
        List<Long> active = activeDeliveryIds(jdbc.queryForObject(
                "SELECT order_id FROM delivery_order WHERE id = ?", Long.class, deliveryId));
        Long newest = active.get(active.size() - 1);
        System.out.printf("   · 人工拆包：包裹 %d 拆出 %d 件 → 新包 %d%n", deliveryId, move, newest);
        return newest;
    }

    /** 下发 → 拣货 → 出库 → 签收 */
    private void shipAndSign(Long deliveryId) {
        ok(exchange(HttpMethod.POST, "/admin/deliveries/" + deliveryId + "/dispatch", null));
        for (String event : List.of("pick", "outbound", "signed")) {
            ok(exchange(HttpMethod.POST,
                    "/admin/deliveries/" + deliveryId + "/wms-callback?event=" + event, null));
        }
        assertThat(deliveryStatus(deliveryId)).as("签收后包裹应为 5").isEqualTo(5);
    }

    // ================================================================== 造数据

    private Long createOrder(String orderSn, int quantity) {
        String json = "{\"order\":{\"memberId\":" + memberId + ",\"orderSn\":\"" + orderSn + "\",\"status\":7,"
                + "\"payType\":1,\"receiverName\":\"重拆一致性用例\",\"receiverPhone\":\"13700000000\","
                + "\"receiverAddress\":\"自动化测试地址（跑完即删）\",\"splitStatus\":0},"
                + "\"items\":[{\"productId\":" + productId + ",\"productSkuId\":" + skuId
                + ",\"productName\":\"重拆一致性用例明细\",\"productPrice\":10.00,\"productQuantity\":"
                + quantity + "}]}";
        Long id = ok(exchange(HttpMethod.POST, "/admin/orders", json)).path("id").asLong();
        createdOrders.add(id);
        return id;
    }

    private Long registerBuyer() {
        String username = "resplit-" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
        String json = "{\"username\":\"" + username + "\",\"password\":\"123456\",\"nickname\":\"重拆一致性用例买家\"}";
        ResponseEntity<JsonNode> resp = rest.postForEntity("/portal/auth/register", jsonEntity(json), JsonNode.class);
        return ok(resp).path("memberId").asLong();
    }

    private static String sn(String prefix) {
        return prefix + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    // ------------------------------------------------------------------ 查询

    private int orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
    }

    private int splitStatus(Long orderId) {
        return jdbc.queryForObject("SELECT IFNULL(split_status, -1) FROM `order` WHERE id = ?",
                Integer.class, orderId);
    }

    private int deliveryStatus(Long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery_order WHERE id = ?", Integer.class, deliveryId);
    }

    private List<Long> deliveryIds(Long orderId) {
        return jdbc.queryForList("SELECT id FROM delivery_order WHERE order_id = ? ORDER BY id",
                Long.class, orderId);
    }

    private List<Long> activeDeliveryIds(Long orderId) {
        return jdbc.queryForList("SELECT id FROM delivery_order WHERE order_id = ? AND status <> 9 ORDER BY id",
                Long.class, orderId);
    }

    /** 该订单该规格的台账净额（= 这张单让总池发生了多少净变化） */
    private int sumDelta(Long orderId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM stock_ledger "
                + "WHERE order_id = ? AND sku_id = ?", Integer.class, orderId, skuId);
    }

    private int ledgerRowCount(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE order_id = ? AND sku_id = ?",
                Integer.class, orderId, skuId);
    }

    private int reasonCount(Long orderId, String reason) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger "
                + "WHERE order_id = ? AND sku_id = ? AND reason = ?", Integer.class, orderId, skuId, reason);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private JsonNode ok(ResponseEntity<JsonNode> resp) {
        JsonNode node = resp.getBody();
        assertThat(node).isNotNull();
        assertThat(node.path("code").asInt()).as("期望成功，实际：%s", node).isZero();
        return node.path("data");
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (adminJwt != null) headers.setBearerAuth(adminJwt);
        return rest.exchange(path, method, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (adminJwt != null) headers.setBearerAuth(adminJwt);
        return new HttpEntity<>(json, headers);
    }
}
