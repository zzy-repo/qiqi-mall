package com.qiqi.mall.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * <b>集成测试专用库</b>（默认 {@code qiqi_mall_it}）：建库 → 让 Flyway 建 schema → 断言「绝没连到演示库」。
 *
 * <h3>为什么需要它（2026-11-16 的「测试污染演示环境」事故）</h3>
 * <pre>
 * 曾经 {@code AuditRunIntegrationTest} / {@code AuditPendingBehaviorTest} 把 datasource 硬编码成
 * {@code qiqi_mall_test} —— 而那正是公网演示站点（admin.rose7.cn / mall.rose7.cn）用的同一个库。
 * 于是形成了一个双向污染环：
 *   ① 测试造 QT-1001..QT-1006「已付款、待审核」单，跑完<b>不清</b>；
 *   ② 演示站上点一次「商城开放接口 → 立即拉单」，/channel-api/orders 按
 *      status IN(1,7) AND pay_type IN(1,2) 把它们导成<b>渠道单</b>；
 *   ③ 这些渠道单的 order_item 指向测试商品「自动审核测试商品」；
 *   ④ 下一轮 {@code mvn test} 在 cleanOurData() 的
 *      {@code DELETE FROM product WHERE name='自动审核测试商品'} 上撞 fk_item_product → 红。
 * 表现就是「mvn 时红时绿」，被误判成「代码不稳」。
 * </pre>
 * 隔离之后：测试碰的是一个用完就重建的库，演示库里有多少可导入单都不影响 {@code mvn test}；
 * 测试也再没有任何一行 SQL 落到演示库上。
 *
 * <h3>schema 从哪来（复用项目现有约定，不另造）</h3>
 * 不写第二份 DDL，也不引用「演示库里有种子数据」：
 * <ul>
 *   <li>建库 = 本类 DROP + CREATE（空库，utf8mb4）；</li>
 *   <li>建表 = 应用启动时 Spring Boot 的 Flyway 自动迁移（{@code classpath:db/migration}，
 *       V1 = 39 表 + 1 视图 + channel_shop 3 行参考数据，V2 = channel 幂等列）。
 *       这正是 {@code scripts/init-test-db.sh} / {@code scripts/reset-e2e-db.sh} 走的「空库路径」
 *       （{@code baseline-on-migrate} 只在「库非空但没有历史表」时才 baseline，空库直接全量执行 V1），
 *       所以这里只需把库建空，剩下的交给同一份迁移文件。</li>
 *   <li>数据 = <b>测试自己造自己需要的那几行</b>（会员 / 商品 / SKU / 桶 / 订单 / 策略，以及
 *       category / product_type / return_reason 三张字典表各一行 —— 演示库里有这些字典行，
 *       空库里没有，所以必须自己插，见 {@code seedCatalogAndMembers()}）。</li>
 * </ul>
 *
 * <h3>「用完即弃」怎么体现</h3>
 * 每次 JVM 跑测试前 DROP + CREATE 一次，所以<b>上一次运行留下的任何残留都不可能影响这一次</b>
 * （连跑两遍 mvn 的第二遍是从空库起步的）。库本身留在 MySQL 里是刻意的：DROP 放在「跑之前」而不是
 * 「跑之后」，这样测试崩在半路也不会留下一个半成品库给下一次，同时不需要在测试里关 Spring 上下文去删库。
 *
 * <h3>安全阀（这几条不是装饰）</h3>
 * <ol>
 *   <li>库名必须以 {@code _it} 结尾，否则直接抛异常 —— 配错一个字母也不会 DROP 到演示库/开发库/e2e 库。</li>
 *   <li>引导前拿 MySQL 咨询锁 {@code GET_LOCK('qiqi_mall_it.bootstrap', 10)}，拿到锁的进程负责重建，
 *       拿不到就<b>跳过重建、沿用现有库</b>（说明另一个 mvn 进程正在用这个库 —— 两个进程同时跑测试本来
 *       就不该发生，但至少不许它把别人正在用的库 DROP 掉）。锁随引导连接一起活到 JVM 退出。</li>
 *   <li>{@link #assertIsolatedAndReady(JdbcTemplate)} 在每个测试类的 {@code @BeforeAll} 里再验一次
 *       「当前连接的库名 == qiqi_mall_it」+「迁移齐全（39 表 / 1 视图 / 0 条失败迁移 / channel_shop 3 行）」，
 *       防止将来有人把 datasource 又指回演示库而没人发现。</li>
 * </ol>
 *
 * <h3>怎么覆盖默认值</h3>
 * 系统属性优先，其次环境变量：{@code -Dqiqi.it.db=xxx_it} / {@code QIQI_IT_DB}，
 * 以及 {@code qiqi.it.host|port|user|password}（默认 {@code 127.0.0.1:3306 / root / qiqi123456}，
 * 与 {@code scripts/init-test-db.sh} 的默认值一致）。
 */
public final class ItDatabase {

    /** 只有以它结尾的库名才允许被本类 DROP（安全阀 ①） */
    private static final String REQUIRED_SUFFIX = "_it";

    private static final String HOST = setting("qiqi.it.host", "MYSQL_HOST", "127.0.0.1");
    private static final String PORT = setting("qiqi.it.port", "MYSQL_PORT", "3306");
    private static final String USER = setting("qiqi.it.user", "MYSQL_USER", "root");
    private static final String PASSWORD = setting("qiqi.it.password", "MYSQL_PASSWORD", "qiqi123456");
    private static final String DB_NAME = setting("qiqi.it.db", "QIQI_IT_DB", "qiqi_mall_it");

    /** 与应用 application-*.yml 同一组参数（useLocalSessionState 见 scripts/check-jdbc-session-safety.sh） */
    private static final String PARAMS = "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&useLocalSessionState=true&allowPublicKeyRetrieval=true&useSSL=false";

    /** V1 之后的结构指纹：39 张业务表 + 1 个视图。加表时这里不必改（用 ≥），但缺表一定会炸 */
    private static final int MIN_BASE_TABLES = 39;
    private static final int MIN_VIEWS = 1;
    /** V1 里 channel_shop 的 3 行参考数据：它们证明「迁移真的跑了」，而不是被 baseline 混过去 */
    private static final int EXPECTED_CHANNEL_SHOPS = 3;

    private static boolean prepared;
    /** 引导连接：留在手里既持有咨询锁（防并发重建），JVM 退出时又自动释放，不需要额外 cleanup */
    private static Connection bootConnection;

    private ItDatabase() {
    }

    /** 测试要连的 JDBC URL（给 {@code @DynamicPropertySource} 用） */
    public static String url() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + DB_NAME + "?" + PARAMS;
    }

    public static String username() {
        return USER;
    }

    public static String password() {
        return PASSWORD;
    }

    public static String dbName() {
        return DB_NAME;
    }

    /**
     * 把 {@code qiqi_mall_it} 重建为空库（每个 JVM 只做一次）。schema 由应用启动时的 Flyway 建。
     *
     * <p>必须在 Spring 上下文创建之前调用 —— 两个调用点都在 {@code @DynamicPropertySource} 里，
     * 那个阶段早于 DataSource / Flyway 的初始化。
     */
    public static synchronized void prepare() {
        if (prepared) {
            return;
        }
        if (!DB_NAME.endsWith(REQUIRED_SUFFIX)) {
            throw new IllegalStateException("拒绝操作库 `" + DB_NAME + "`：测试库名必须以 " + REQUIRED_SUFFIX
                    + " 结尾（这条限制是为了永远不可能 DROP 到演示库 qiqi_mall_test / 开发库 qiqi_mall / e2e 库）");
        }
        try {
            // 连「服务器」而不是「某个库」：库还不存在时也能连
            Connection conn = DriverManager.getConnection(
                    "jdbc:mysql://" + HOST + ":" + PORT + "/?" + PARAMS, USER, PASSWORD);
            if (!acquireBootstrapLock(conn)) {
                System.out.println("[IT DB] 另一个进程正持有引导锁（" + DB_NAME
                        + "）：跳过重建，沿用现有库（并发跑测试本就不受支持）");
                conn.close();
                prepared = true;
                return;
            }
            bootConnection = conn;   // 不关闭：锁要一直持有到本 JVM 退出
            try (Statement st = conn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + DB_NAME + "`");
                st.execute("CREATE DATABASE `" + DB_NAME + "` DEFAULT CHARACTER SET utf8mb4 "
                        + "COLLATE utf8mb4_general_ci");
            }
            System.out.println("[IT DB] 已重建空库 " + DB_NAME + "（schema 由启动时的 Flyway V1/V2 建）");
            prepared = true;
        } catch (SQLException e) {
            throw new IllegalStateException("准备集成测试库失败：" + DB_NAME + " @ " + HOST + ":" + PORT
                    + "（MySQL 容器 qiqi-mysql 起了吗？）", e);
        }
    }

    /**
     * 测试类 {@code @BeforeAll} 的第一句：确认「连的是隔离库」且「schema 齐」。
     *
     * <p>失败信息要能直接告诉人「你连错库了」—— 因为这条断言唯一的用途就是拦住这种回归。
     */
    public static void assertIsolatedAndReady(JdbcTemplate jdbc) {
        String current = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!DB_NAME.equals(current)) {
            throw new IllegalStateException("集成测试连到了 `" + current + "`，不是隔离库 `" + DB_NAME
                    + "`。测试不许碰演示库 qiqi_mall_test（那是公网 admin.rose7.cn / mall.rose7.cn 的库）——"
                    + "检查 @DynamicPropertySource 里 spring.datasource.url 是否被改回硬编码");
        }
        if (current.endsWith("qiqi_mall_test") || "qiqi_mall".equals(current)) {
            throw new IllegalStateException("危险：集成测试指向了演示库/开发库 `" + current + "`");
        }
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history'", Integer.class);
        Integer views = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE()", Integer.class);
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        Integer shops = jdbc.queryForObject("SELECT COUNT(*) FROM channel_shop", Integer.class);
        if (tables == null || tables < MIN_BASE_TABLES) {
            throw new IllegalStateException("隔离库 `" + DB_NAME + "` 结构不全：业务表 " + tables + " < "
                    + MIN_BASE_TABLES + "（Flyway 迁移没跑完？）");
        }
        if (views == null || views < MIN_VIEWS) {
            throw new IllegalStateException("隔离库 `" + DB_NAME + "` 缺视图：" + views + " < " + MIN_VIEWS);
        }
        if (failed == null || failed > 0) {
            throw new IllegalStateException("隔离库 `" + DB_NAME + "` 有失败的迁移记录 " + failed + " 条");
        }
        if (shops == null || shops != EXPECTED_CHANNEL_SHOPS) {
            throw new IllegalStateException("隔离库 `" + DB_NAME + "` 的 channel_shop 参考数据应为 "
                    + EXPECTED_CHANNEL_SHOPS + " 行，实际 " + shops);
        }
        System.out.println("[IT DB] 隔离确认：库=" + current + " 业务表=" + tables + " 视图=" + views
                + " 失败迁移=0 channel_shop=" + shops);
    }

    /** 拿咨询锁：1=拿到，0=超时（别人在引导），NULL=出错 */
    private static boolean acquireBootstrapLock(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT GET_LOCK('qiqi_mall_it.bootstrap', 10)")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private static String setting(String systemProperty, String envVar, String fallback) {
        String v = System.getProperty(systemProperty);
        if (v == null || v.isBlank()) {
            v = System.getenv(envVar);
        }
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
