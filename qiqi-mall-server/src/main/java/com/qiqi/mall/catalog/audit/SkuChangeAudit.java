package com.qiqi.mall.catalog.audit;

import com.qiqi.mall.catalog.entity.Sku;
import com.qiqi.mall.shared.ledger.ReasonCodes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 「这次 SKU 保存到底改了什么」的判定 —— 审计动作码 / 摘要 / 快照都从这里派生。
 *
 * <h3>为什么不能按请求体选码</h3>
 * 以前的判据是 {@code sku.getPrice() != null ? 改价 : 改库存}。而管理端的 SKU 编辑弹窗是
 * <b>整份表单提交</b>（{@code views/product/skus.vue} 的 form 里永远有 price），于是：
 * <ul>
 *   <li>{@code SKU_STOCK_CHANGE} 分支事实上<b>不可达</b>；</li>
 *   <li>只改库存的动作被记成「改价」，摘要还写成「售价 ¥1999.99 → ¥1999.99」——</li>
 * </ul>
 * 台账是只追加证据表：写进去一条「没发生的改动」不是措辞问题，是<b>证据链造假</b>。
 * 所以判据换成「拿旧实体跟新值逐字段比」：
 * <ol>
 *   <li>只 price 变 → {@link ReasonCodes#ACTION_SKU_PRICE_CHANGE}</li>
 *   <li>只 stock 变 → {@link ReasonCodes#ACTION_SKU_STOCK_CHANGE}</li>
 *   <li>两个都变 → {@link ReasonCodes#ACTION_SKU_PRICE_STOCK_CHANGE}（一条记录两处改动）</li>
 *   <li>都没变 → {@link Optional#empty()}：<b>不写审计</b>（没发生的事不进证据表）</li>
 * </ol>
 *
 * <p>快照只放<b>实际变化的字段</b>，摘要也只描述变化的部分 —— 否则读审计的人还得自己比对
 * before/after 才知道到底动了哪个字段。
 *
 * <p>纯计算、无 Spring、无库：判定口径可以被单测逐格钉死（见 {@code SkuChangeAuditTest}）。
 */
public final class SkuChangeAudit {

    /**
     * 一次「该记的审计」。
     *
     * @param action    动作码（按变化字段组合选）
     * @param summary   一句人话摘要，只描述变化的部分
     * @param before    改前快照（只含变化字段）
     * @param after     改后快照（只含变化字段）
     */
    public record Plan(String action, String summary,
                       Map<String, Object> before, Map<String, Object> after) {
    }

    private SkuChangeAudit() {
    }

    /**
     * 判定这次保存要不要记审计、记哪一条。
     *
     * @param before  改之前的实体（必须是从库里读出来的那一份；{@code null} = 没读旧值，不记）
     * @param price   请求里的售价；{@code null} = 本次没提交售价
     * @param stock   请求里的库存；{@code null} = 本次没提交库存
     * @return 需要记的审计；{@link Optional#empty()} = 两个敏感字段都没变（或不认识旧值）
     */
    public static Optional<Plan> decide(Sku before, BigDecimal price, Integer stock) {
        if (before == null) return Optional.empty();

        boolean priceChanged = price != null && !samePrice(before.getPrice(), price);
        boolean stockChanged = stock != null && !sameStock(before.getStock(), stock);
        if (!priceChanged && !stockChanged) return Optional.empty();

        Map<String, Object> beforeSnap = new LinkedHashMap<>();
        Map<String, Object> afterSnap = new LinkedHashMap<>();
        StringBuilder summary = new StringBuilder();
        String action;

        if (priceChanged && stockChanged) {
            action = ReasonCodes.ACTION_SKU_PRICE_STOCK_CHANGE;
            beforeSnap.put("price", before.getPrice());
            beforeSnap.put("stock", before.getStock());
            afterSnap.put("price", price);
            afterSnap.put("stock", stock);
            summary.append("修改规格价格/库存：售价 ").append(money(before.getPrice())).append(" → ").append(money(price))
                    .append("，库存 ").append(before.getStock()).append(" → ").append(stock);
        } else if (priceChanged) {
            action = ReasonCodes.ACTION_SKU_PRICE_CHANGE;
            beforeSnap.put("price", before.getPrice());
            afterSnap.put("price", price);
            summary.append("修改规格价格：售价 ").append(money(before.getPrice())).append(" → ").append(money(price));
        } else {
            action = ReasonCodes.ACTION_SKU_STOCK_CHANGE;
            beforeSnap.put("stock", before.getStock());
            afterSnap.put("stock", stock);
            summary.append("修改规格库存：").append(before.getStock()).append(" → ").append(stock);
        }
        return Optional.of(new Plan(action, summary.toString(), beforeSnap, afterSnap));
    }

    /** 售价比较按数值（{@code 1999.9} 与 {@code 1999.90} 是同一个价，不算改动） */
    private static boolean samePrice(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    private static boolean sameStock(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 摘要里的金额形态：{@code ¥1999.99}（{@code null} 记作「空」，不假装是 0） */
    private static String money(BigDecimal value) {
        return value == null ? "空" : "¥" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
