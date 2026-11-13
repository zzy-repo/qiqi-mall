package com.qiqi.mall.catalog.audit;

import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 「按变化选码」的判定口径单测（纯计算，不连库，毫秒级）。
 *
 * <h3>为什么这条口径必须用单测钉死</h3>
 * 这里判错的后果不是「界面难看」，而是<b>证据链造假</b>：管理端 SKU 编辑弹窗整份表单提交，
 * 旧判据（{@code price != null ? 改价 : 改库存}）会把「只改库存」记成「改价」，摘要写成
 * 「售价 ¥1999.99 → ¥1999.99」—— 一条没发生的改动进了只追加的审计表。
 * 这个错误在编译、启动、其它单测里都看不出来，只有真去看审计内容才会发现
 * （本次就是 e2e 12/11 场景把它翻出来的）。
 *
 * <p>四格全部钉住：只改价 / 只改库存 / 两个都改 / 都没改（不记）。
 */
class SkuChangeAuditTest {

    private static Sku sku(String price, Integer stock) {
        Sku s = new Sku();
        s.setId(7L);
        s.setPrice(price == null ? null : new BigDecimal(price));
        s.setStock(stock);
        return s;
    }

    @Test
    @DisplayName("只改库存（请求里同时带了没变的 price）→ SKU_STOCK_CHANGE，快照只含 stock")
    void stockOnlyChange() {
        Optional<SkuChangeAudit.Plan> plan = SkuChangeAudit.decide(
                sku("1999.99", 1000), new BigDecimal("1999.99"), 1007);

        assertThat(plan).as("库存变了，必须记一条").isPresent();
        SkuChangeAudit.Plan p = plan.orElseThrow();
        assertThat(p.action()).as("只改库存就不许记成改价").isEqualTo(ReasonCodes.ACTION_SKU_STOCK_CHANGE);
        assertThat(p.before()).as("改前快照只放实际变化的字段").containsOnlyKeys("stock");
        assertThat(p.after()).as("改后快照只放实际变化的字段").containsOnlyKeys("stock");
        assertThat(p.before().get("stock")).isEqualTo(1000);
        assertThat(p.after().get("stock")).isEqualTo(1007);
        assertThat(p.summary()).as("摘要只说变化的部分，且写清 1000 → 1007")
                .isEqualTo("修改规格库存：1000 → 1007");
        assertThat(p.summary()).as("没变的售价不许出现在摘要里（这就是旧实现往证据表里写的假话）")
                .doesNotContain("售价");
    }

    @Test
    @DisplayName("只改售价（请求里同时带了没变的 stock）→ SKU_PRICE_CHANGE，快照只含 price")
    void priceOnlyChange() {
        SkuChangeAudit.Plan p = SkuChangeAudit.decide(
                sku("1999.99", 1000), new BigDecimal("2099.00"), 1000).orElseThrow();

        assertThat(p.action()).isEqualTo(ReasonCodes.ACTION_SKU_PRICE_CHANGE);
        assertThat(p.before()).containsOnlyKeys("price");
        assertThat(p.after()).containsOnlyKeys("price");
        assertThat(p.summary()).isEqualTo("修改规格价格：售价 ¥1999.99 → ¥2099.00");
        assertThat(p.summary()).doesNotContain("库存");
    }

    @Test
    @DisplayName("售价与库存同时改 → SKU_PRICE_STOCK_CHANGE（一条记录两处改动）")
    void bothChanged() {
        SkuChangeAudit.Plan p = SkuChangeAudit.decide(
                sku("1999.99", 1000), new BigDecimal("2099.00"), 1007).orElseThrow();

        assertThat(p.action()).isEqualTo(ReasonCodes.ACTION_SKU_PRICE_STOCK_CHANGE);
        assertThat(p.before()).containsOnlyKeys("price", "stock");
        assertThat(p.after()).containsOnlyKeys("price", "stock");
        assertThat(p.summary()).isEqualTo("修改规格价格/库存：售价 ¥1999.99 → ¥2099.00，库存 1000 → 1007");
    }

    @Test
    @DisplayName("什么都没变 → 不记（一条审计都不写：没发生的事不进证据表）")
    void nothingChanged() {
        assertThat(SkuChangeAudit.decide(sku("1999.99", 1000), new BigDecimal("1999.99"), 1000))
                .as("整份表单原样提交（价格与库存都没变）不该产生审计")
                .isEmpty();
    }

    @Test
    @DisplayName("只提交库存、价格不在请求体里 → 同样按库存选码（不依赖请求体形状）")
    void stockOnlyWithoutPriceInRequest() {
        SkuChangeAudit.Plan p = SkuChangeAudit.decide(sku("1999.99", 1000), null, 1007).orElseThrow();
        assertThat(p.action()).isEqualTo(ReasonCodes.ACTION_SKU_STOCK_CHANGE);
    }

    @Test
    @DisplayName("价格按数值比：1999.9 与 1999.90 是同一个价，不算改动")
    void priceComparedByValue() {
        assertThat(SkuChangeAudit.decide(sku("1999.9", 1000), new BigDecimal("1999.90"), 1000))
                .as("只是小数位不同，不该记成一次改价")
                .isEmpty();
    }

    @Test
    @DisplayName("读不到旧实体（before=null）→ 不记（不拿猜测当证据）")
    void noBeforeSnapshot() {
        assertThat(SkuChangeAudit.decide(null, new BigDecimal("1.00"), 1)).isEmpty();
    }

    @Test
    @DisplayName("动作码宽度 ≤ 48（admin_audit_log.action VARCHAR(48)，超宽会 ERROR 1406 回滚业务）")
    void actionCodesFitColumn() {
        assertThatCode(() -> {
            assertThat(ReasonCodes.ACTION_SKU_STOCK_CHANGE.length()).isLessThanOrEqualTo(48);
            assertThat(ReasonCodes.ACTION_SKU_PRICE_CHANGE.length()).isLessThanOrEqualTo(48);
            assertThat(ReasonCodes.ACTION_SKU_PRICE_STOCK_CHANGE.length()).isLessThanOrEqualTo(48);
        }).doesNotThrowAnyException();
    }
}
