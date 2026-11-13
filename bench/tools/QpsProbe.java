import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * QpsProbe —— 「这台 MySQL 的裸语句吞吐」探针（**与业务代码完全无关**）
 *
 * 为什么需要它：雪崩实验第三步要证明的不变式是
 *     ok/s ≈ QPS_cap ÷ (每单语句数)
 * 如果我既改「每单语句数」、又用改后的吞吐当证据，那是自证（被解释量与解释量一起动）。
 * 所以 QPS_cap 必须用**业务无关的方式**独立量出来，并且在改动前后各量一次、证明它没变。
 *
 * 用法（单文件源码，Java 21 直接跑，不依赖任何构建）：
 *   java -cp <mysql-connector.jar> QpsProbe.java <jdbcUrl> <user> <pass> <conns> <seconds> [sql]
 * 例：
 *   java -cp ~/.m2/.../mysql-connector-j-8.3.0.jar QpsProbe.java \
 *        "jdbc:mysql://127.0.0.1:3306/qiqi_mall_test" root "$PW" 10 20 "SELECT 1"
 *
 * 口径：
 *   · 固定连接数（闭环保压）：每条连接上「发一句→等回包→再发」，所以 QPS = Σ 每条连接的完成数 / 秒
 *   · 语句可换（默认 SELECT 1）；用来区分「纯解析+执行」与「带写事务」的差别
 *   · 刻意**不加**任何业务表/索引条件：它量的是「MySQL 在这台机器上每秒能跑多少条语句」
 *   · 输出一行机器可读： conns=N seconds=T qps=... p50_us=... p99_us=...
 */
public class QpsProbe {
    public static void main(String[] a) throws Exception {
        String url = a[0], user = a[1], pass = a[2];
        int conns = Integer.parseInt(a[3]);
        int seconds = Integer.parseInt(a[4]);
        String sql = a.length > 5 ? a[5] : "SELECT 1";

        ExecutorService pool = Executors.newFixedThreadPool(conns);
        AtomicLong total = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(conns);
        long[] latBuf = new long[1 << 20];           // 记录最多 100 万次延迟（微秒），够算分位
        AtomicInteger li = new AtomicInteger();

        for (int i = 0; i < conns; i++) {
            pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(url, user, pass);
                     Statement st = c.createStatement()) {
                    st.setFetchSize(1);
                    start.await();
                    long end = System.nanoTime() + seconds * 1_000_000_000L;
                    while (System.nanoTime() < end) {
                        long t0 = System.nanoTime();
                        try (ResultSet rs = st.executeQuery(sql)) {
                            while (rs.next()) { rs.getInt(1); }
                        }
                        long dt = (System.nanoTime() - t0) / 1000;
                        total.incrementAndGet();
                        int k = li.getAndIncrement();
                        if (k < latBuf.length) latBuf[k] = dt;
                    }
                } catch (Exception e) {
                    System.err.println("probe err: " + e);
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        start.countDown();
        done.await();
        pool.shutdownNow();

        int n = Math.min(li.get(), latBuf.length);
        long[] lat = java.util.Arrays.copyOf(latBuf, n);
        java.util.Arrays.sort(lat);
        long p50 = n > 0 ? lat[(int) (n * 0.50)] : 0;
        long p99 = n > 0 ? lat[Math.min(n - 1, (int) (n * 0.99))] : 0;
        System.out.printf("conns=%d seconds=%d qps=%.1f p50_us=%d p99_us=%d sql=%s%n",
                conns, seconds, total.get() / (double) seconds, p50, p99, sql.replace(' ', '_'));
    }
}
