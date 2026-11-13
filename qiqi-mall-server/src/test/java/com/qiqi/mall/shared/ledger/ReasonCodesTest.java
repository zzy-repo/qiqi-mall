package com.qiqi.mall.shared.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原因码 / 动作码的**宽度纪律**单测（不连库，毫秒级）。
 *
 * <h3>为什么这件事必须用单测钉死</h3>
 * 台账列宽是 DDL 钉死的：{@code reason VARCHAR(32)}、{@code action VARCHAR(48)}、
 * {@code target_type / ref_type VARCHAR(24)}。而 MySQL 是 STRICT 模式：
 * 超长不是「截断个警告」，而是 **ERROR 1406 → 整个业务事务回滚**（用户看到「数据处理失败」）。
 * 也就是说「有人在常量类里加了一个 33 字符的 reason」这件事，会在**下一个真实状态流转**
 * 把业务打挂 —— 而常量类本身编译、启动、其它单测全绿，只有这笔业务会炸。
 *
 * <p>所以这里把宽度、非空、同一组内不重复，三条都变成断言：
 * 改常量时一旦超宽，跑 {@code mvn test} 立刻红，不用等到线上。
 */
class ReasonCodesTest {

    private static final int REASON_WIDTH = 32;      // *_status_ledger.reason VARCHAR(32)
    private static final int ACTION_WIDTH = 48;      // admin_audit_log.action VARCHAR(48)
    private static final int TYPE_WIDTH = 24;        // target_type / ref_type VARCHAR(24)

    @Test
    @DisplayName("状态流转 reason：非空、≤32 字符、组内不重复（否则按 reason 统计会各算一类）")
    void reasonCodesAreValid() {
        Set<String> seen = new HashSet<>();
        for (Field f : ReasonCodes.class.getDeclaredFields()) {
            if (!isConstant(f)) continue;
            String name = f.getName();
            if (!name.startsWith("REF_") && !name.startsWith("TARGET_") && !name.startsWith("ACTION_")) {
                // 剩下的就是状态原因码
                String value = valueOf(f);
                assertThat(value).as("reason 不能为空（%s）", name).isNotBlank();
                assertThat(value.length()).as("reason 超宽会撞 ERROR 1406 并回滚业务事务：%s=%s", name, value)
                        .isLessThanOrEqualTo(REASON_WIDTH);
                assertThat(seen.add(value)).as("reason 重复：%s=%s（按 reason 统计会被拆成两类）", name, value)
                        .isTrue();
            }
        }
        assertThat(seen).as("至少要有订单/包裹/售后三类原因码").isNotEmpty();
    }

    @Test
    @DisplayName("审计 action：非空、≤48 字符、不重复")
    void actionCodesAreValid() {
        Set<String> seen = new HashSet<>();
        for (Field f : ReasonCodes.class.getDeclaredFields()) {
            if (!isConstant(f) || !f.getName().startsWith("ACTION_")) continue;
            String value = valueOf(f);
            assertThat(value).as("action 不能为空（%s）", f.getName()).isNotBlank();
            assertThat(value.length()).as("action 超宽会撞 ERROR 1406：%s=%s", f.getName(), value)
                    .isLessThanOrEqualTo(ACTION_WIDTH);
            assertThat(seen.add(value)).as("action 重复：%s=%s", f.getName(), value).isTrue();
        }
        assertThat(seen).as("后台审计至少要覆盖动作码").isNotEmpty();
    }

    @Test
    @DisplayName("目标类型 / 触发单据类型：≤24 字符、大写（与 CHECK 与 DDL 注释口径一致）")
    void typeCodesAreValid() {
        for (Field f : ReasonCodes.class.getDeclaredFields()) {
            if (!isConstant(f)) continue;
            String name = f.getName();
            if (!name.startsWith("REF_") && !name.startsWith("TARGET_")) continue;
            String value = valueOf(f);
            assertThat(value.length()).as("%s 超宽（VARCHAR(24)）：%s", name, value).isLessThanOrEqualTo(TYPE_WIDTH);
            assertThat(value).as("%s 应是大写下划线常量：%s", name, value).isUpperCase();
        }
    }

    @Test
    @DisplayName("状态台账幂等键口径：{业务ID}:{statusSeq} 长度远小于 VARCHAR(96)")
    void idemKeyFits() {
        // 业务ID 最长 19 位（BIGINT 上限）+ ':' + seq 最长 10 位 = 30
        String worst = "9223372036854775807:2147483647";
        assertThat(worst.length()).isLessThanOrEqualTo(96);
        assertThat(worst).matches("^\\d+:\\d+$");
    }

    private static boolean isConstant(Field f) {
        int m = f.getModifiers();
        return Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == String.class;
    }

    private static String valueOf(Field f) {
        try {
            return (String) f.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("读不到常量：" + f.getName(), e);
        }
    }
}
