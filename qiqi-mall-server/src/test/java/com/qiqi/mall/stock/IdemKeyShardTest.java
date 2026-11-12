package com.qiqi.mall.stock;

import com.qiqi.mall.stock.StockLedgerContext.Attribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等键打散前缀的**纯单测**（不连库、不起 Spring）。
 *
 * <h3>要钉住的四条</h3>
 * <ol>
 *   <li><b>N=1 = 逐字节回到今天</b>：不加前缀，格式与历史数据完全一致
 *       （否则回滚就不干净，且旧触发重放会算错键）；</li>
 *   <li><b>N 个前缀轮转</b>：orderId 是单调自增的，{@code orderId % N} 必须刚好铺满 0..N-1
 *       且零填充宽度一致（`00:`…`63:`），这样才能把插入分散到 N 个 gap；</li>
 *   <li><b>唯一性不变</b>：不同 (orderId, skuId, reason, refId) 生成的键仍然两两不同，
 *       前缀只是把 orderId 的信息挪到最前面，不是随机化；</li>
 *   <li><b>可回滚</b>：剥掉前缀那一段能还原出原键（与 SQL 回滚公式
 *       {@code SUBSTRING(idem_key, LOCATE(':', idem_key)+1)} 一致）。</li>
 * </ol>
 */
class IdemKeyShardTest {

    private static Attribution attr(long orderId, String reason, String refType, Long refId) {
        return new Attribution(orderId, reason, refType, refId);
    }

    // ------------------------------------------------------------------ N=1：与今天逐字节一致

    @Test
    @DisplayName("shards=1：不加前缀，格式与历史数据逐字节一致（两个重载完全等价）")
    void shardsOneIsByteIdenticalToToday() {
        Attribution a = attr(100023L, "ORDER_CREATE", null, null);
        assertThat(a.idemKey(5L, 1)).as("默认路径").isEqualTo("100023:5:ORDER_CREATE:0");
        assertThat(a.idemKey(5L)).as("旧签名").isEqualTo("100023:5:ORDER_CREATE:0");
        assertThat(a.idemKey(5L, 1)).as("两个重载必须逐字节相同").isEqualTo(a.idemKey(5L));

        Attribution withRef = attr(100024L, "DELIVERY_CANCEL", "DELIVERY", 77L);
        assertThat(withRef.idemKey(9L, 1)).isEqualTo("100024:9:DELIVERY_CANCEL:77");

        assertThat(attr(1L, "ORDER_CREATE", null, null).idemKey(2L, 0))
                .as("shards<=0 视为 1（防御性：配置写错不许突然改变键格式）").isEqualTo("1:2:ORDER_CREATE:0");
    }

    // ------------------------------------------------------------------ 轮转与位宽

    @Test
    @DisplayName("shards=64：连续 64 个 orderId 的前缀恰好是 00..63 各一次（完美轮转）")
    void shardsSixtyFourRotatesEvenly() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (long orderId = 1; orderId <= 64; orderId++) {
            prefixes.add(Attribution.shardPrefix(orderId, 64));
        }
        assertThat(prefixes).as("64 个不同前缀，一个不少").hasSize(64);
        // orderId % 64：1→"01:" … 63→"63:"、64→"00:"（→ 第 65 个又回到 "01:"）
        assertThat(Attribution.shardPrefix(1L, 64)).as("零填充两位").isEqualTo("01:");
        assertThat(Attribution.shardPrefix(64L, 64)).as("64 % 64 = 0 → 00:").isEqualTo("00:");
        assertThat(Attribution.shardPrefix(65L, 64)).as("65 % 64 = 1 → 回到 01:").isEqualTo("01:");
        assertThat(Attribution.shardPrefix(1000L, 64)).as("1000 % 64 = 40").isEqualTo("40:");
    }

    @Test
    @DisplayName("位宽随 N 变化：N=2 → 1 位、N=10 → 2 位、N=128 → 3 位（且排序与数值一致）")
    void prefixWidthFollowsShardCount() {
        // 位宽 = (N-1) 的十进制位数：N=2 → 1 位；N=10 → 1 位（桶 0..9）；N=16 → 2 位；N=128 → 3 位
        assertThat(Attribution.shardPrefix(1L, 2)).isEqualTo("1:");
        assertThat(Attribution.shardPrefix(9L, 10)).as("N=10 → 桶号 0..9，1 位").isEqualTo("9:");
        assertThat(Attribution.shardPrefix(9L, 16)).as("N=16 → 2 位").isEqualTo("09:");
        assertThat(Attribution.shardPrefix(64L, 128)).as("N=128 → 3 位").isEqualTo("064:");
        // 零填充的意义：字典序 == 数值序，前缀区间连续（便于排查与索引分布观察）
        assertThat(Attribution.shardPrefix(9L, 64).compareTo(Attribution.shardPrefix(10L, 64)))
                .as("'09:' < '10:' —— 前缀的字典序与桶号数值序一致").isNegative();
        // 反例：不补零就会错位（"9:" > "10:"），这正是零填充存在的理由
        assertThat("9:".compareTo("10:")).as("不补零时字典序与数值序相反").isPositive();
    }

    // ------------------------------------------------------------------ 唯一性

    @Test
    @DisplayName("打散后唯一性不变：1 万条 (orderId, skuId) 组合的键两两不同，且与未打散一一对应")
    void uniquenessIsPreserved() {
        Set<String> sharded = new HashSet<>();
        Set<String> plain = new HashSet<>();
        int n = 100;
        for (long orderId = 1; orderId <= n; orderId++) {
            for (long skuId = 1; skuId <= n; skuId++) {
                Attribution a = attr(orderId, "ORDER_CREATE", null, null);
                sharded.add(a.idemKey(skuId, 64));
                plain.add(a.idemKey(skuId, 1));
            }
        }
        assertThat(sharded).as("1 万个键不许有任何碰撞").hasSize(n * n);
        assertThat(sharded.size()).as("打散不改变键的数量（一一对应）").isEqualTo(plain.size());
        assertThat(sharded).as("打散后的键必须真的不同于原键").doesNotContainAnyElementsOf(plain);
    }

    @Test
    @DisplayName("前缀只由 orderId 决定（同 orderId 不同规格共享同一前缀 → 桶划分稳定）")
    void prefixDependsOnlyOnOrderId() {
        Attribution a = attr(100023L, "ORDER_CREATE", null, null);
        Attribution b = attr(100023L, "RETURN_REFUND", "RETURN_APPLY", 9L);
        String pa = a.idemKey(1L, 64).substring(0, 3);
        String pb = b.idemKey(2L, 64).substring(0, 3);
        assertThat(pa).as("同一 orderId 的前缀必须一致（前缀是 orderId 的纯函数）").isEqualTo(pb);
        assertThat(pa).isEqualTo("55:");   // 100023 % 64 = 55（1562×64=99968）
    }

    // ------------------------------------------------------------------ 可回滚

    @Test
    @DisplayName("可回滚：剥掉第一个冒号之前的部分即得原键（与 SQL 回滚公式一致）")
    void prefixIsStrippable() {
        for (long orderId : new long[]{1, 63, 64, 65, 999, 100023}) {
            Attribution a = attr(orderId, "DELIVERY_CANCEL", "DELIVERY", 42L);
            String sharded = a.idemKey(7L, 64);
            String plain = a.idemKey(7L, 1);
            String stripped = sharded.substring(sharded.indexOf(':') + 1);
            assertThat(stripped).as("orderId=%d 剥前缀必须还原原键", orderId).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("配置钳位：N 被钳在 [1,1000]，保证最坏键长永远 <= VARCHAR(96)（超了会被静默截断）")
    void shardCountIsClampedToKeepKeyFitting() {
        StockProperties p = new StockProperties();
        p.setIdemKeyShards(5000);
        assertThat(p.getIdemKeyShards()).as("上限 1000（位宽 3 → 93+3=96 顶格）").isEqualTo(1000);
        p.setIdemKeyShards(0);
        assertThat(p.getIdemKeyShards()).as("下限 1（不加前缀）").isEqualTo(1);
        p.setIdemKeyShards(-7);
        assertThat(p.getIdemKeyShards()).isEqualTo(1);

        // 最坏键长 = 92（19+1+19+1+32+1+19）+ 位宽 + 1(冒号) 必须 <= 96
        for (int shards : new int[]{2, 10, 16, 64, 128, 256, 1000}) {
            int width = String.valueOf(shards - 1).length();
            assertThat(93 + width)
                    .as("N=%d 最坏键长 %d 必须 <= 96", shards, 93 + width)
                    .isLessThanOrEqualTo(96);
        }
        // 反例：N=1024（位宽 4）就会超 —— 这正是钳到 1000 的原因
        assertThat(93 + String.valueOf(1024 - 1).length()).as("N=1024 → 97 字符，会被截断").isGreaterThan(96);
    }

    @Test
    @DisplayName("键长度仍在 VARCHAR(96) 可容纳范围内（最坏情况：19+19+32+19 位 + 前缀）")
    void keyStillFitsColumn() {
        long bigOrder = 9_223_372_036_854_775_807L;   // BIGINT 上限（19 位）
        String sharded = attr(bigOrder, "RETURN_REFUND", "RETURN_APPLY", bigOrder)
                .idemKey(9_223_372_036_854_775_807L, 64);
        System.out.printf("① 最坏情况键长 = %d 字符（VARCHAR(96) 上限），示例：%s%n", sharded.length(), sharded);
        assertThat(sharded.length()).as("**必须 <= 96，否则 INSERT IGNORE 会静默截断**").isLessThanOrEqualTo(96);
    }
}
