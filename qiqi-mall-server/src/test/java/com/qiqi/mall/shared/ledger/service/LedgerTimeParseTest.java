package com.qiqi.mall.shared.ledger.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 台账查询的时间参数解析（纯函数单测，不连库）。
 *
 * <p>为什么单独测这一个私有细节：前端的日期选择器只到「天」，它在 JS 里补成
 * {@code 'YYYY-MM-DD 00:00:00'} / {@code 'YYYY-MM-DD 23:59:59'} 再传；
 * 而手写 curl 的人往往只写日期。这两种都要能用，且**含义不能反**
 * （{@code to} 只给日期时必须覆盖当天整天，否则「今天」的台账一条都查不到 —— 这是最容易写错的边界）。
 */
class LedgerTimeParseTest {

    @Test
    @DisplayName("前端格式 'yyyy-MM-dd HH:mm:ss' 原样解析")
    void parsesFrontendFormat() {
        assertThat(LedgerQueryService.parseTime("2026-11-13 00:00:00", "from", false))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 0, 0, 0));
        assertThat(LedgerQueryService.parseTime("2026-11-13 23:59:59", "to", true))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 23, 59, 59));
    }

    @Test
    @DisplayName("只给日期：from 取 00:00:00，to 取 23:59:59（覆盖当天整天，方向不能反）")
    void dateOnly_expandsByEndOfDay() {
        assertThat(LedgerQueryService.parseTime("2026-11-13", "from", false))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 0, 0, 0));
        assertThat(LedgerQueryService.parseTime("2026-11-13", "to", true))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 23, 59, 59));
    }

    @Test
    @DisplayName("ISO（带 T）也能解析：页面回显后端返回的时间再查一次时不会炸")
    void isoFormat_isAccepted() {
        assertThat(LedgerQueryService.parseTime("2026-11-13T16:10:12", "from", false))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 16, 10, 12));
    }

    @Test
    @DisplayName("空值 = 不限；格式错要报中文原因（不许静默当成不限）")
    void emptyIsNull_badFormatThrows() {
        assertThat(LedgerQueryService.parseTime(null, "from", false)).isNull();
        assertThat(LedgerQueryService.parseTime("   ", "from", false)).isNull();

        assertThatThrownBy(() -> LedgerQueryService.parseTime("2026/11/13", "from", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd HH:mm:ss");
        assertThatThrownBy(() -> LedgerQueryService.parseTime("昨天", "to", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间参数 to 格式不对");
    }
}
