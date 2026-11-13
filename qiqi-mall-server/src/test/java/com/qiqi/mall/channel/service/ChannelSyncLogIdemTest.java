package com.qiqi.mall.channel.service;

import com.qiqi.mall.channel.entity.ChannelSyncLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code channel_sync_log} 幂等闸（V2 的生成列 {@code idem_key} + {@code uk_sync_idem}）行为测试。
 *
 * <h3>要钉住的三条口径</h3>
 * <ol>
 *   <li><b>同一包裹 + 同一 attempt 重放只留一行，且不报错</b>：人工连点、调度重入、异常重放都会
 *       走到这里。写入侧拿到影响 0 行 → {@link ChannelSyncLogWriter#write} 返回 {@code false}
 *       （= 该次尝试已记过账），<b>不是异常</b>，也不是第二行；</li>
 *   <li><b>不同 attempt 各占一行</b>：attempt=1 FAIL、attempt=2 OK 是两件真事，
 *       「重试了几次」靠数据自己说明（幂等键里带 attempt，重试不能被当成重放吞掉）；</li>
 *   <li><b>PULL 行不受约束</b>：汇总行（ref_type=SHOP）与单张单的 SKIP/FAIL 明细行
 *       （ref_type=PLATFORM_ORDER、ref_id 为 NULL）连写两次就是<b>两行都在</b> ——
 *       生成列在这两类行上是 NULL，MySQL 唯一索引允许多个 NULL。这是有意的：
 *       连拉两次是两件不同的事，都要留证据。</li>
 * </ol>
 *
 * <p>跑在测试事务里（{@code @Transactional}），结束自动回滚，不污染 dev 库。
 *
 * <p>运行：{@code cd qiqi-mall-server && mvn -o test -Dtest=ChannelSyncLogIdemTest}
 */
@SpringBootTest
@Transactional
class ChannelSyncLogIdemTest {

    /** 无外键约束，随便一个不会与真实店铺撞号的 shop_id 即可 */
    private static final long SHOP = 900_001L;
    /** 同一店铺、另一个 id，验证幂等键里确实带了 shop_id */
    private static final long SHOP2 = 900_002L;

    @Autowired
    private ChannelSyncLogWriter writer;
    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ ① 同一次尝试重放

    @Test
    @DisplayName("同一包裹同一 attempt 连写两次：只有 1 行、第二次返回 false（已记过账）、不抛异常、不覆盖第一行")
    void replaySameAttemptKeepsOneRow() {
        long pkg = 990_001L;

        boolean first = writer.write(SHOP, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req-1", "resp-ok", ChannelSyncLog.RESULT_OK, "回传成功（200）", 1, 12);
        // 重放：一模一样的一次尝试（连点第二下 / 调度重入）
        boolean replay = writer.write(SHOP, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req-1", "resp-dup", ChannelSyncLog.RESULT_FAIL, "不该覆盖第一行", 1, 999);

        assertThat(first).as("第一次是真落行").isTrue();
        assertThat(replay).as("重放拿 0 行影响 → false（SKIP 口径，不是异常）").isFalse();

        List<String> reasons = jdbc.queryForList(
                "SELECT reason FROM channel_sync_log WHERE shop_id=? AND action='SHIP_PUSH' AND ref_id=?",
                String.class, SHOP, pkg);
        assertThat(reasons).as("只有一行，且是第一次那条（重放不覆写证据）")
                .containsExactly("回传成功（200）");

        String idemKey = jdbc.queryForObject(
                "SELECT idem_key FROM channel_sync_log WHERE shop_id=? AND action='SHIP_PUSH' AND ref_id=?",
                String.class, SHOP, pkg);
        assertThat(idemKey).as("生成列 = {shopId}:SHIP_PUSH:{refId}:{attempt}")
                .isEqualTo(SHOP + ":SHIP_PUSH:" + pkg + ":1");
    }

    // ------------------------------------------------------------------ ② 不同尝试分别记账

    @Test
    @DisplayName("同一包裹 attempt=1 与 attempt=2：两行都在（重试不是重放）")
    void differentAttemptsAreSeparateRows() {
        long pkg = 990_002L;

        assertThat(writer.write(SHOP, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req", "resp-500", ChannelSyncLog.RESULT_FAIL, "HTTP 500", 1, 30)).isTrue();
        assertThat(writer.write(SHOP, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req", "resp-200", ChannelSyncLog.RESULT_OK, "回传成功（200）", 2, 25)).isTrue();

        List<String> keys = jdbc.queryForList(
                "SELECT idem_key FROM channel_sync_log WHERE shop_id=? AND action='SHIP_PUSH' AND ref_id=? "
                        + "ORDER BY attempt", String.class, SHOP, pkg);
        assertThat(keys).as("两次尝试两条幂等键")
                .containsExactly(SHOP + ":SHIP_PUSH:" + pkg + ":1", SHOP + ":SHIP_PUSH:" + pkg + ":2");

        // attempt=2 重放仍然只一行
        assertThat(writer.write(SHOP, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req", "resp-200", ChannelSyncLog.RESULT_OK, "回传成功（200）", 2, 25)).isFalse();
        assertThat(countShipPush(pkg)).as("重放 attempt=2 不加行").isEqualTo(2);
    }

    // ------------------------------------------------------------------ ③ PULL 行不参与唯一约束

    @Test
    @DisplayName("PULL 店铺汇总行连写两次：两行都在（idem_key 为 NULL，NULL 不参与唯一约束）")
    void pullSummaryRowsAreNotConstrained() {
        long shop = SHOP2;

        assertThat(writer.write(shop, ChannelSyncLog.ACTION_PULL, "SHOP", shop,
                "pull-req", "sum-1", ChannelSyncLog.RESULT_OK,
                "拉回 2 单：新建 1 / 跳过 1 / 失败 0", 1, 40)).isTrue();
        assertThat(writer.write(shop, ChannelSyncLog.ACTION_PULL, "SHOP", shop,
                "pull-req", "sum-2", ChannelSyncLog.RESULT_OK,
                "拉回 2 单：新建 1 / 跳过 1 / 失败 0", 1, 41)).isTrue();

        List<Object> keys = jdbc.queryForList(
                "SELECT idem_key FROM channel_sync_log WHERE shop_id=? AND action='PULL' AND ref_type='SHOP'",
                Object.class, shop);
        assertThat(keys).as("两次拉单 → 两行，幂等键都是 NULL")
                .hasSize(2).containsOnly((Object) null);
    }

    @Test
    @DisplayName("PULL 明细行（平台单 SKIP，ref_id 为 NULL）连写两次：两行都在（同一张单被重推两次要能看见两次）")
    void pullDetailRowsAreNotConstrained() {
        long shop = SHOP2;

        for (int i = 0; i < 2; i++) {
            assertThat(writer.write(shop, ChannelSyncLog.ACTION_PULL, "PLATFORM_ORDER", null,
                    "platform-sn-P1", null, ChannelSyncLog.RESULT_SKIP,
                    ChannelSyncLog.REASON_IDEMPOTENT, 1, 0)).isTrue();
        }

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_sync_log WHERE shop_id=? AND action='PULL' "
                        + "AND ref_type='PLATFORM_ORDER' AND ref_id IS NULL", Integer.class, shop);
        assertThat(rows).as("平台重推两次 → 两条 SKIP 证据").isEqualTo(2);
    }

    // ------------------------------------------------------------------ ④ 幂等键带 shop_id

    @Test
    @DisplayName("同包裹 id、不同店铺：两条互不冲突（键里带 shop_id，多店铺不会互相吞行）")
    void idemKeyIsScopedByShop() {
        long pkg = 990_003L;

        assertThat(writer.write(SHOP, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req", "resp", ChannelSyncLog.RESULT_OK, "回传成功（200）", 1, 10)).isTrue();
        assertThat(writer.write(SHOP2, ChannelSyncLog.ACTION_SHIP_PUSH, "DELIVERY", pkg,
                "req", "resp", ChannelSyncLog.RESULT_OK, "回传成功（200）", 1, 10)).isTrue();

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_sync_log WHERE action='SHIP_PUSH' AND ref_id=? "
                        + "AND shop_id IN (?, ?)", Integer.class, pkg, SHOP, SHOP2);
        assertThat(rows).as("两家店各自的包裹 id 空间独立").isEqualTo(2);
    }

    private Integer countShipPush(long refId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_sync_log WHERE shop_id=? AND action='SHIP_PUSH' AND ref_id=?",
                Integer.class, SHOP, refId);
    }
}
