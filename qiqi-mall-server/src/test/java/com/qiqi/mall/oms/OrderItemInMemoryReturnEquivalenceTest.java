package com.qiqi.mall.oms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.order.entity.OrderItem;
import com.qiqi.mall.oms.service.OrderCreateService;
import com.qiqi.mall.oms.service.OrderItemService;
import com.qiqi.mall.oms.service.OrderTransactionalCreator;
import com.qiqi.mall.stock.service.SkuBucketService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「明细那条 SELECT 根本不发」的**等价性门禁**：内存里的 {@code items} 必须与 DB 里查出来的逐字段相等。
 *
 * <h3>被改掉的那句话</h3>
 * {@code OrderTransactionalCreator#create} 原来在 {@code saveBatch} 之后还要发一条
 * {@code SELECT * FROM order_item WHERE order_id=? ORDER BY id ASC} 去回读明细，只为把明细回显给前端。
 * 现在直接返回内存里的 {@code items} —— 成立的前提是：
 * <b>MyBatis-Plus 的 {@code saveBatch} 在 {@code IdType.AUTO} 下会把自增 id 回填进传入的实体列表</b>。
 *
 * <h3>为什么这个测试能拦得住「回填不全」</h3>
 * <ol>
 *   <li><b>列由 DB 驱动，不由人写</b>：{@code SELECT *} 拿到的是库里的**全部列**，再与
 *       {@code OrderItem} 的**全部字段**做集合比对（{@code containsExactlyInAnyOrderElementsOf}）。
 *       将来给 {@code order_item} 加一列而实体没加、或实体加了字段却没回填，这里立刻红 ——
 *       {@code product_sku_id} / 数量 / 价格 / 名称快照这类"某一个字段"根本不可能被漏掉；</li>
 *   <li><b>逐字段比，不是比对象</b>：{@code BigDecimal} 连 scale 一起比
 *       （{@code 9.9} 与 {@code 9.90} 在 {@code compareTo} 下相等但在 JSON 里不同，必须抓到）；</li>
 *   <li><b>序列化成字节比</b>：同一份 Spring {@code ObjectMapper} 把「内存 items」与「DB 回查 items」
 *       各序列化一次，要求 JSON **逐字节相等** —— 这是客户端真正能观察到的东西（响应体里
 *       {@code items} 就是这个数组），比逐字段断言更接近"用户看到的"；</li>
 *   <li><b>负向自检（{@link #selfCheck_漏字段必须被这道门禁抓住()}）</b>：把真实 items 的
 *       {@code id} / {@code productName} / {@code productPrice} 分别抹掉，断言本类的比对**必须失败**。
 *       没有这一条，"测试通过"也可能只是断言没咬人。</li>
 * </ol>
 *
 * <p>数据自建自清：自建商品/规格/会员，跑完按 id 全删（订单 → 明细级联、台账 → 桶 → 规格 → 商品 → 会员），
 * 不碰任何种子数据。连的是 {@code mvn test} 的 dev 库 {@code qiqi_mall}。
 *
 * <p>运行：cd qiqi-mall-server &amp;&amp; mvn -o test -Dtest=OrderItemInMemoryReturnEquivalenceTest
 */
@SpringBootTest(properties = {
        // 与 SkuBucketIntegrationTest 完全相同的属性集合（= 复用同一份 Spring 上下文，省一次启动）
        "qiqi.stock.bucket-enabled=true",
        "qiqi.stock.ledger-insert-ignore=true",
        "qiqi.stock.bucket-count=8",
        "qiqi.stock.cache-refresh-ms=3600000",
        "qiqi.stock.cache-refresh-batch=500"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderItemInMemoryReturnEquivalenceTest {

    @Autowired
    private OrderCreateService orderCreateService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private SkuBucketService bucketService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private Long memberId;
    private Long categoryId;
    private Long typeId;

    private final List<Long> createdOrderIds = new ArrayList<>();
    private final List<Long> createdSkuIds = new ArrayList<>();
    private final List<Long> createdProductIds = new ArrayList<>();

    @BeforeAll
    void prepare() {
        categoryId = jdbc.queryForObject("SELECT id FROM category ORDER BY id LIMIT 1", Long.class);
        typeId = jdbc.queryForObject("SELECT id FROM product_type ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO member (username, password, nickname, phone, status) VALUES (?, '', ?, NULL, 1)",
                "item-eq-" + System.currentTimeMillis(), "明细等价性测试会员");
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        System.out.printf("[准备] member=%d category=%d type=%d%n", memberId, categoryId, typeId);
    }

    @AfterAll
    void cleanup() {
        for (Long orderId : createdOrderIds) {
            jdbc.update("DELETE FROM stock_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM order_status_ledger WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM `order` WHERE id = ?", orderId);   // 明细随外键级联删除
        }
        for (Long skuId : createdSkuIds) {
            jdbc.update("DELETE FROM sku WHERE id = ?", skuId);         // 桶随外键级联删除
        }
        for (Long productId : createdProductIds) {
            jdbc.update("DELETE FROM product WHERE id = ?", productId);
        }
        jdbc.update("DELETE FROM member WHERE id = ?", memberId);
        System.out.printf("[清理] 自建订单 %d 张、规格 %d 个、商品 %d 个、会员 1 个已删除%n",
                createdOrderIds.size(), createdSkuIds.size(), createdProductIds.size());
    }

    // ================================================================== 主用例

    @Test
    @DisplayName("两条明细（不同 SKU，数量 2 / 5）：内存返回的 items 与 DB 逐字段相等、JSON 逐字节相等")
    void twoItems_memoryEqualsDb() {
        Long skuA = createSku("等价性测试商品-A", "19.90", 1000);
        Long skuB = createSku("等价性测试商品-B", "0.05", 1000);

        OrderTransactionalCreator.CreatedOrder created = create(List.of(
                new OrderTransactionalCreator.ItemInput(skuA, 2),
                new OrderTransactionalCreator.ItemInput(skuB, 5)));

        assertThat(created.items()).hasSize(2);
        assertEquivalence(created.order().getId(), created.items());

        // 顺带把"人眼一看就懂"的几个关键字段单独钉一遍（失败时报错更直白）
        assertThat(created.items().get(0).getProductSkuId()).isEqualTo(skuA);
        assertThat(created.items().get(0).getProductQuantity()).isEqualTo(2);
        assertThat(created.items().get(0).getProductName()).isEqualTo("等价性测试商品-A");
        assertThat(created.items().get(0).getProductPrice()).isEqualByComparingTo("19.90");
        assertThat(created.items().get(1).getProductSkuId()).isEqualTo(skuB);
        assertThat(created.items().get(1).getProductQuantity()).isEqualTo(5);
        assertThat(created.items().get(1).getProductPrice()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("同一 SKU 连下两行：两行明细各自回填 id，顺序 = 插入顺序 = id 升序")
    void duplicateSku_twoRows_memoryEqualsDb() {
        Long sku = createSku("等价性测试商品-重复行", "7.77", 1000);

        OrderTransactionalCreator.CreatedOrder created = create(List.of(
                new OrderTransactionalCreator.ItemInput(sku, 1),
                new OrderTransactionalCreator.ItemInput(sku, 3)));

        assertThat(created.items()).hasSize(2);
        assertThat(created.items().get(0).getId()).isNotEqualTo(created.items().get(1).getId());
        assertEquivalence(created.order().getId(), created.items());
    }

    @Test
    @DisplayName("商品名 100 字（= 列宽边界，varchar(100)）：名称快照逐字节相等，不被截断")
    void productNameAtColumnLimit_memoryEqualsDb() {
        String name100 = "等价性".repeat(33) + "X";        // 33×3 + 1 = 100 字
        assertThat(name100).hasSize(100);
        Long sku = createSku(name100, "1234.56", 1000);

        OrderTransactionalCreator.CreatedOrder created = create(List.of(
                new OrderTransactionalCreator.ItemInput(sku, 1)));

        assertThat(created.items().get(0).getProductName()).isEqualTo(name100);
        assertEquivalence(created.order().getId(), created.items());
    }

    /**
     * 负向自检：证明上面那些断言**真的会咬人**。
     * 把真实 items 的某个字段抹掉之后，{@link #assertEquivalence} 必须抛 {@link AssertionError}。
     */
    @Test
    @DisplayName("自检：id / 名称快照 / 价格任一没回填，本类的等价性比对必须失败（否则等于没测）")
    void selfCheck_漏字段必须被这道门禁抓住() {
        Long sku = createSku("等价性测试商品-自检", "3.30", 1000);
        OrderTransactionalCreator.CreatedOrder created = create(List.of(
                new OrderTransactionalCreator.ItemInput(sku, 4)));
        Long orderId = created.order().getId();
        List<OrderItem> good = created.items();

        // ① 正常情况先过一遍（证明夹具本身成立）
        assertEquivalence(orderId, good);

        // ② id 没回填 → 必须红（这就是"saveBatch 回填不全"的形态）
        assertThatThrownBy(() -> assertEquivalence(orderId, List.of(copyWith(good.get(0), "id", null))))
                .as("id 缺失必须被抓住").isInstanceOf(AssertionError.class);

        // ③ 名称快照没回填 → 必须红
        assertThatThrownBy(() -> assertEquivalence(orderId, List.of(copyWith(good.get(0), "productName", ""))))
                .as("名称快照缺失必须被抓住").isInstanceOf(AssertionError.class);

        // ④ 价格 scale 不对（9.9 vs 9.90，compareTo 相等但 JSON 不同）→ 必须红
        assertThatThrownBy(() -> assertEquivalence(orderId,
                List.of(copyWith(good.get(0), "productPrice", new BigDecimal("3.3")))))
                .as("价格 scale 差异必须被抓住").isInstanceOf(AssertionError.class);

        // ⑤ 数量不对 → 必须红
        assertThatThrownBy(() -> assertEquivalence(orderId, List.of(copyWith(good.get(0), "productQuantity", 999))))
                .as("数量缺失/错误必须被抓住").isInstanceOf(AssertionError.class);
    }

    // ================================================================== 等价性断言本体

    /**
     * 内存 items vs DB 行：①列集合逐一对应 ②逐列逐值相等 ③JSON 逐字节相等。
     *
     * <p>{@code SQL} 用 {@code SELECT *}（列由库决定，不由测试写死），排序与旧代码一致（{@code id ASC}）。
     */
    private void assertEquivalence(Long orderId, List<OrderItem> inMemoryItems) {
        List<Map<String, Object>> dbRows = jdbc.queryForList(
                "SELECT * FROM order_item WHERE order_id = ? ORDER BY id ASC", orderId);

        // ---- ① 行数
        assertThat(inMemoryItems).as("内存 items 行数").hasSameSizeAs(dbRows);

        // ---- ② 列集合 ↔ 实体字段集合：多一列 = 实体漏了字段（这个测试的价值就在这里）
        Set<String> dbColumns = dbRows.isEmpty() ? Set.of() : new LinkedHashSet<>(dbRows.get(0).keySet());
        Set<String> dbFields = dbColumns.stream().map(OrderItemInMemoryReturnEquivalenceTest::snakeToCamel)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> entityFields = Arrays.stream(OrderItem.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(dbFields)
                .as("order_item 的列（转驼峰后）必须与 OrderItem 的字段一一对应：DB 列=%s 实体字段=%s",
                        dbColumns, entityFields)
                .containsExactlyInAnyOrderElementsOf(entityFields);

        // ---- ③ 逐行逐列
        for (int i = 0; i < dbRows.size(); i++) {
            Map<String, Object> row = dbRows.get(i);
            OrderItem mem = inMemoryItems.get(i);
            for (String col : dbColumns) {
                String field = snakeToCamel(col);
                assertValueEquals("第 " + (i + 1) + " 行 order_item." + col, row.get(col), readField(mem, field));
            }
        }

        // ---- ④ id 必须回填且严格升序（= 原 SQL 的 ORDER BY id ASC 顺序）
        for (int i = 0; i < inMemoryItems.size(); i++) {
            assertThat(inMemoryItems.get(i).getId())
                    .as("第 %d 行明细的自增 id 必须被 saveBatch 回填（null = 回填不全，这条改动不能做）", i + 1)
                    .isNotNull();
            if (i > 0) {
                assertThat(inMemoryItems.get(i).getId())
                        .as("内存 items 的顺序必须等于 id 升序（原来 SQL 的 ORDER BY id ASC）")
                        .isGreaterThan(inMemoryItems.get(i - 1).getId());
            }
        }

        // ---- ⑤ 客户端真正能看到的东西：响应体里的 items 数组，JSON 逐字节相等
        List<OrderItem> fromDb = orderItemService.list(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).orderByAsc(OrderItem::getId));
        assertThat(fromDb).as("DB 回查行数").hasSameSizeAs(inMemoryItems);
        assertThat(json(inMemoryItems))
                .as("内存 items 与 DB 回查 items 的 JSON 必须逐字节相等（这就是响应体里的 items）")
                .isEqualTo(json(fromDb));
    }

    private void assertValueEquals(String where, Object dbValue, Object memValue) {
        if (dbValue == null) {
            assertThat(memValue).as("%s：库里是 NULL，内存也必须是 NULL", where).isNull();
            return;
        }
        assertThat(memValue).as("%s：内存里不许是 NULL（库里=%s）", where, dbValue).isNotNull();
        if (dbValue instanceof BigDecimal db) {
            assertThat(memValue).as("%s：类型必须是 BigDecimal", where).isInstanceOf(BigDecimal.class);
            BigDecimal mem = (BigDecimal) memValue;
            assertThat(mem).as("%s：数值必须相等（%s vs %s）", where, mem, db)
                    .isEqualByComparingTo(db);
            assertThat(mem.scale()).as("%s：scale 也必须相等（9.9 与 9.90 序列化出来不一样）", where)
                    .isEqualTo(db.scale());
        } else if (dbValue instanceof Number db) {
            assertThat(((Number) memValue).longValue()).as("%s：整数必须相等", where)
                    .isEqualTo(db.longValue());
        } else {
            assertThat(memValue).as("%s：字符串/其它必须逐字符相等", where).isEqualTo(dbValue);
        }
    }

    // ================================================================== 夹具

    private OrderTransactionalCreator.CreatedOrder create(List<OrderTransactionalCreator.ItemInput> inputs) {
        OrderTransactionalCreator.CreatedOrder created = orderCreateService.createWithRetry(
                memberId, "等价性测试收货人", "13700000000", "自动化测试地址（跑完即删）", inputs, false);
        createdOrderIds.add(created.order().getId());
        return created;
    }

    private Long createSku(String productName, String price, int total) {
        jdbc.update("INSERT INTO product (name, description, category_id, type_id, publish_status) "
                + "VALUES (?, '明细等价性测试专用（跑完即删）', ?, ?, 0)", productName, categoryId, typeId);
        Long productId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdProductIds.add(productId);

        jdbc.update("INSERT INTO sku (product_id, price, stock, version) VALUES (?, ?, ?, 0)",
                productId, new BigDecimal(price), total);
        Long skuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdSkuIds.add(skuId);
        bucketService.resetTo(skuId, total);
        return skuId;
    }

    /** 浅拷贝 + 改一个字段（负向自检用） */
    private static OrderItem copyWith(OrderItem src, String fieldName, Object value) {
        try {
            OrderItem copy = new OrderItem();
            for (Field f : OrderItem.class.getDeclaredFields()) {
                f.setAccessible(true);
                f.set(copy, f.get(src));
            }
            Field target = OrderItem.class.getDeclaredField(fieldName);
            target.setAccessible(true);
            target.set(copy, value);
            return copy;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("拷贝字段失败：" + fieldName, e);
        }
    }

    private static Object readField(OrderItem item, String fieldName) {
        try {
            Field f = OrderItem.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读字段失败：" + fieldName, e);
        }
    }

    private String json(List<OrderItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 items 失败", e);
        }
    }

    /** product_sku_id → productSkuId（DB 列名 → 实体字段名） */
    private static String snakeToCamel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
