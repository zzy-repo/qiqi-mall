package com.qiqi.mall.audit.engine;

/**
 * 判定用的设置快照（{@code order_setting} 里与审核相关的那几列）。
 *
 * @param autoAuditEnabled  自动审核总开关（人工 run 不受它限制，定时任务受）
 * @param stockGuard        库存判据档位
 */
public record AuditSetting(boolean autoAuditEnabled, StockGuard stockGuard) {

    /** 库存判据两档（决策 D5） */
    public enum StockGuard {
        /** 默认：可用 &lt; 需求 → 降级人工 + STOCK_SHORT（绝不带着缺口自动过审） */
        STRICT,
        /** 宽松：可用 &lt; 需求仍然自动过审，但记 STOCK_SHORT 告警（扣减 SQL 仍是防超卖的最终闸门） */
        LENIENT;

        public static StockGuard of(String raw) {
            if (raw == null || raw.isBlank()) return STRICT;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // 配错一律按严格档：宁可多转人工，也不能因为一个拼写错误让缺货单自动过审
                return STRICT;
            }
        }
    }

    public static AuditSetting defaults() {
        return new AuditSetting(true, StockGuard.STRICT);
    }
}
