// LoadGen —— 单文件 Java 21 压测器（不依赖外网 / 不用 Maven / 不用 javac）
//
// 运行方式（JDK 21 单文件源码启动）：
//   java -Xmx1g <bench>/legacy/LoadGen.java --url ... --method POST ...
//   （历史原型，仅作闭环对照保留；不要再拿它当日常工具，理由见 bench/README.md）
//
// 设计要点：
//   * java.net.http.HttpClient + 虚拟线程（Executors.newVirtualThreadPerTaskExecutor）
//   * 每个请求从 --token-file 里**原子取一个**幂等令牌（全局 AtomicLong 递增 → 天然唯一、无锁竞争）
//   * body / url 支持占位符：{{TOKEN}} 幂等令牌、{{JWT}} 当前 JWT、{{SEQ}} 全局请求序号
//   * 统计：成功/失败、TPS、p50/p95/p99/max、错误按 (HTTP状态|code|message) 分组
//   * 自己报告 CPU：ProcessCpuLoad（瞬时采样）+ 进程 CPU 时间增量算出的平均占用率 + 系统 loadavg
//     —— 用来排除「客户端自己先把 CPU 打满」这种假瓶颈
//   * 结果写 CSV（--out，long 格式 metric,value，便于 grep）+ 每秒时间序列（--out + .timeseries.csv）
//
// 退出码：0 = 跑完（哪怕有业务失败）；2 = 参数/环境错误（令牌池耗尽、文件缺失）

import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class LoadGen {

    // ---------------- 参数 ----------------
    static String url = null;
    static String method = "POST";
    static int concurrency = 1;
    static int durationSec = 10;
    static int warmupSec = 0;
    static long timeoutMs = 15000;
    static String tokenFile = null;
    static String bodyFile = null;
    static String authFile = null;
    static String skuColdFile = null;
    static String out = null;
    static String label = "";
    static boolean keepSamples = false;
    static final List<String> headers = new ArrayList<>();

    // ---------------- 全局状态 ----------------
    static final AtomicLong TOKEN_IDX = new AtomicLong(0);
    static final AtomicLong JWT_IDX = new AtomicLong(0);
    static final AtomicLong SEQ = new AtomicLong(0);
    static final AtomicBoolean tokenPoolExhausted = new AtomicBoolean(false);
    static String[] tokens = new String[0];
    static String[] jwts = new String[0];
    static long hotSku = Long.MIN_VALUE;      // 热点 SKU（--sku-hot）
    static double skuRatio = 0.8;             // 打热点的比例（--sku-ratio）
    static long[] coldSkus = new long[0];     // 冷门 SKU 池（--sku-cold-file）
    static final Map<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();
    static final LongAdder bizStockShort = new LongAdder();   // 库存不足
    static final LongAdder bizIdem = new LongAdder();         // 幂等令牌/重复提交
    static final LongAdder bizAuth = new LongAdder();         // 未登录/401
    static final LongAdder bizOther = new LongAdder();

    static final LongAdder ok = new LongAdder();
    static final LongAdder fail = new LongAdder();
    static final LongAdder httpErr = new LongAdder();   // 连接失败/超时（没拿到 HTTP 响应）
    static final Map<String, LongAdder> errGroups = new ConcurrentHashMap<>();
    static final AtomicLong[] tsOk = new AtomicLong[4096];
    static final AtomicLong[] tsFail = new AtomicLong[4096];
    static volatile long measureStartNanos = 0L;

    static HttpClient client;

    public static void main(String[] argv) throws Exception {
        parseArgs(argv);
        if (url == null || out == null) {
            System.err.println("usage: java LoadGen.java --url URL --out FILE [--method POST] [--concurrency 8] "
                    + "[--duration 30] [--warmup 10] [--token-file F] [--body-file F] [--auth-file F] "
                    + ""
                + "[--header 'K: V'] [--timeout-ms 15000] [--label name] [--samples] "
                + "[--sku-hot ID --sku-ratio 0.8 --sku-cold-file F]");
            System.exit(2);
        }
        tokens = readLines(tokenFile);
        jwts = readLines(authFile);
        String[] coldRaw = readLines(skuColdFile);
        coldSkus = new long[coldRaw.length];
        for (int i = 0; i < coldRaw.length; i++) coldSkus[i] = Long.parseLong(coldRaw[i].trim());
        if (tokens.length > 0) System.out.println("token pool=" + tokens.length + " (each taken exactly once)");
        if (jwts.length > 0) System.out.println("jwt pool=" + jwts.length + " (round-robin per request)");
        if (hotSku != Long.MIN_VALUE) {
            System.out.printf(Locale.ROOT, "sku mix: hot=%d x%.2f, cold pool=%d skus x%.2f%n",
                    hotSku, skuRatio, coldSkus.length, 1 - skuRatio);
        }
        String bodyTemplate = bodyFile == null ? null : Files.readString(Path.of(bodyFile), StandardCharsets.UTF_8);

        for (int i = 0; i < tsOk.length; i++) {
            tsOk[i] = new AtomicLong();
            tsFail[i] = new AtomicLong();
        }

        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // 走明文 h1，避免 h2c 升级协商开销
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long[] net0 = netDev();
        long cpuStartNanos = os.getProcessCpuTime();
        Instant start = Instant.now();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            Worker w = new Worker(bodyTemplate, ready, go);
            workers.add(w);
            pool.submit(w);
        }
        ready.await();

        if (warmupSec > 0) {
            System.out.printf("warmup %ds (concurrency=%d) ...%n", warmupSec, concurrency);
            measureStartNanos = System.nanoTime() + warmupSec * 1_000_000_000L;
            go.countDown();
            Thread.sleep(warmupSec * 1000L);
            // 预热阶段的记录在 worker 内部按 measureStartNanos 丢弃
        } else {
            measureStartNanos = System.nanoTime();
            go.countDown();
        }

        // 测量窗口起点：此刻再取进程 CPU 时间，把 JVM 启动 + 单文件源码编译 + 预热都排除在外
        long cpuMeasureStartNanos = os.getProcessCpuTime();
        long wallMeasureStartNanos = System.nanoTime();
        Thread.sleep(Math.max(0, (measureStartNanos - System.nanoTime()) / 1_000_000L) + durationSec * 1000L);
        long measureEndNanos = System.nanoTime();
        double elapsedSec = (measureEndNanos - measureStartNanos) / 1e9;

        // 先 stop 接新任务、让在飞请求自然跑完再收尾。
        // 之前直接 shutdownNow 会打断窗口末尾那条请求：它已被计成 fail，
        // 但 worker 在 InterruptedException 分支里 return，跳过了错误分组归并 → fail 与错误明细对不上。
        pool.shutdown();
        if (!pool.awaitTermination(25, TimeUnit.SECONDS)) {
            System.err.println("WARN: 仍有在飞请求未收尾，强制中断");
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        long cpuEndNanos = os.getProcessCpuTime();
        double cpuAvgPct = 0;
        long wallNanos = System.nanoTime() - wallMeasureStartNanos;
        if (cpuMeasureStartNanos > 0 && cpuEndNanos > 0 && wallNanos > 0) {
            // 进程 CPU 占用率（多核口径：100% = 打满 1 个核；4 核打满 = 400%）
            cpuAvgPct = 100.0 * (cpuEndNanos - cpuMeasureStartNanos) / (double) wallNanos;
        }
        double cpuNowPct = safeDouble(() -> os.getProcessCpuLoad()) * 100.0;
        double sysNowPct = safeDouble(() -> os.getCpuLoad()) * 100.0;
        double loadAvg = os.getSystemLoadAverage();
        long[] net1 = netDev();
        double netRxKBs = (net1[0] - net0[0]) / 1024.0 / Math.max(0.001, elapsedSec);
        double netTxKBs = (net1[1] - net0[1]) / 1024.0 / Math.max(0.001, elapsedSec);

        // ---- 合并延迟样本 ----
        long[] all = new long[0];
        int total = 0;
        for (Worker w : workers) total += w.n;
        all = new long[total];
        int p = 0;
        for (Worker w : workers) {
            int n = w.n;
            System.arraycopy(w.lat, 0, all, p, n);
            p += n;
        }
        Arrays.sort(all);
        long n = all.length;
        long requests = ok.sum() + fail.sum();
        double tps = elapsedSec > 0 ? requests / elapsedSec : 0;

        double p50 = pct(all, 50), p95 = pct(all, 95), p99 = pct(all, 99);
        long max = n == 0 ? 0 : all[(int) (n - 1)];
        double avg = 0;
        if (n > 0) {
            long sum = 0;
            for (long v : all) sum += v;
            avg = sum / (double) n / 1000.0;
        }

        // ---- 写 CSV ----
        StringBuilder sb = new StringBuilder("metric,value\n");
        row(sb, "label", label);
        row(sb, "url", url);
        row(sb, "method", method);
        row(sb, "concurrency", concurrency);
        row(sb, "warmup_s", warmupSec);
        row(sb, "duration_s", durationSec);
        row(sb, "elapsed_s", fmt(elapsedSec));
        row(sb, "start_iso", start.toString());
        row(sb, "end_iso", Instant.now().toString());
        row(sb, "requests", requests);
        row(sb, "ok", ok.sum());
        row(sb, "fail", fail.sum());
        row(sb, "transport_err", httpErr.sum());
        row(sb, "error_rate_pct", fmt(requests == 0 ? 0 : 100.0 * fail.sum() / requests));
        row(sb, "tps", fmt(tps));
        row(sb, "p50_ms", fmt(p50));
        row(sb, "p95_ms", fmt(p95));
        row(sb, "p99_ms", fmt(p99));
        row(sb, "max_ms", fmt(max / 1000.0));
        row(sb, "avg_ms", fmt(avg));
        row(sb, "token_pool_size", tokens.length);
        row(sb, "token_pool_exhausted", tokenPoolExhausted.get());
        row(sb, "jwt_pool_size", jwts.length);
        row(sb, "client_proc_cpu_avg_pct", fmt(cpuAvgPct));
        row(sb, "client_proc_cpu_sample_pct", fmt(cpuNowPct));
        row(sb, "machine_cpu_pct", fmt(sysNowPct));
        row(sb, "machine_loadavg_1m", fmt(loadAvg));
        row(sb, "machine_cpu_cores", Runtime.getRuntime().availableProcessors());
        row(sb, "client_net_rx_KBps", fmt(netRxKBs));
        row(sb, "client_net_tx_KBps", fmt(netTxKBs));
        row(sb, "sku_hot", hotSku == Long.MIN_VALUE ? "none" : hotSku);
        row(sb, "sku_hot_ratio", hotSku == Long.MIN_VALUE ? "-" : fmt(skuRatio));
        row(sb, "sku_cold_pool", coldSkus.length);
        row(sb, "biz_fail_stock_short", bizStockShort.sum());
        row(sb, "biz_fail_idempotent", bizIdem.sum());
        row(sb, "biz_fail_auth", bizAuth.sum());
        row(sb, "biz_fail_other", bizOther.sum());
        row(sb, "http_status_dist", statusDist());
        row(sb, "jvm_max_heap_mb", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        List<Map.Entry<String, LongAdder>> errs = new ArrayList<>(errGroups.entrySet());
        errs.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        for (Map.Entry<String, LongAdder> e : errs) {
            row(sb, "err: " + e.getKey(), e.getValue().sum());
        }
        Files.writeString(Path.of(out), sb.toString(), StandardCharsets.UTF_8);

        // ---- 每秒时间序列（便于和看门狗日志对齐）----
        Path tsPath = Path.of(out + ".timeseries.csv");
        StringBuilder tb = new StringBuilder("sec,ok,fail\n");
        int secs = (int) Math.ceil(elapsedSec);
        for (int i = 0; i < Math.min(secs + 1, tsOk.length); i++) {
            tb.append(i).append(',').append(tsOk[i].get()).append(',').append(tsFail[i].get()).append('\n');
        }
        Files.writeString(tsPath, tb.toString(), StandardCharsets.UTF_8);

        // ---- 原始样本（可选，用于复核分位数）----
        if (keepSamples) {
            StringBuilder xb = new StringBuilder("latency_us\n");
            for (long v : all) xb.append(v).append('\n');
            Files.writeString(Path.of(out + ".samples.csv"), xb.toString(), StandardCharsets.UTF_8);
        }

        // ---- 一行摘要（人直接读）----
        System.out.printf(Locale.ROOT,
                "SUMMARY ok=%d fail=%d err=%.3f%% tps=%.1f p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms "
                        + "elapsed=%.1fs client_cpu=%.1f%% machine_cpu=%.1f%% load1=%.2f csv=%s%n",
                ok.sum(), fail.sum(), (requests == 0 ? 0 : 100.0 * fail.sum() / requests), tps,
                p50, p95, p99, max / 1000.0, elapsedSec, cpuAvgPct, sysNowPct, loadAvg, out);
        if (!errs.isEmpty()) {
            System.out.println("TOP ERRORS:");
            errs.stream().limit(10).forEach(e -> System.out.printf("  %8d  %s%n", e.getValue().sum(), e.getKey()));
        }
        if (tokenPoolExhausted.get()) {
            System.err.println("WARN: 令牌池已耗尽（" + tokens.length + " 个），后续请求未发压。");
            System.exit(2);
        }
    }

    // ---------------- Worker ----------------
    static final class Worker implements Runnable {
        final String bodyTemplate;
        final CountDownLatch ready, go;
        long[] lat = new long[1 << 14];
        int n = 0;
        final Map<String, Integer> localErrs = new LinkedHashMap<>();
        int localTransportErr = 0;

        Worker(String bodyTemplate, CountDownLatch ready, CountDownLatch go) {
            this.bodyTemplate = bodyTemplate;
            this.ready = ready;
            this.go = go;
        }

        @Override
        public void run() {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                return;
            }
            long msEnd = measureStartNanos + durationSec * 1_000_000_000L;
            while (true) {
                long now = System.nanoTime();
                if (now >= msEnd) break;
                boolean measuring = now >= measureStartNanos;

                String token = null;
                if (tokens.length > 0) {
                    long i = TOKEN_IDX.getAndIncrement();
                    if (i >= tokens.length) {
                        TOKEN_IDX.set(tokens.length);
                        tokenPoolExhausted.set(true);
                        break;
                    }
                    token = tokens[(int) i];
                }
                String jwt = jwts.length == 0 ? null : jwts[(int) (JWT_IDX.getAndIncrement() % jwts.length)];
                long seq = SEQ.incrementAndGet();

                String u = url;
                String body = bodyTemplate;
                if (token != null) {
                    u = u.replace("{{TOKEN}}", token);
                    if (body != null) body = body.replace("{{TOKEN}}", token);
                }
                if (jwt != null) {
                    u = u.replace("{{JWT}}", jwt);
                    if (body != null) body = body.replace("{{JWT}}", jwt);
                }
                if (hotSku != Long.MIN_VALUE) {
                    String sku = Long.toString(pickSku());
                    u = u.replace("{{SKU}}", sku);
                    if (body != null) body = body.replace("{{SKU}}", sku);
                }
                u = u.replace("{{SEQ}}", Long.toString(seq));
                if (body != null) body = body.replace("{{SEQ}}", Long.toString(seq));

                HttpRequest.Builder rb;
                try {
                    rb = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofMillis(timeoutMs));
                } catch (RuntimeException e) {
                    record(measuring, 0, errKey("BAD_URL", e.getClass().getSimpleName()));
                    continue;
                }
                if (jwt != null) rb.header("Authorization", "Bearer " + jwt);
                for (String h : headers) {
                    int c = h.indexOf(':');
                    if (c > 0) rb.header(h.substring(0, c).trim(), h.substring(c + 1).trim());
                }
                HttpRequest.BodyPublisher pub = body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
                if (body != null && !hasContentType()) rb.header("Content-Type", "application/json");
                rb.method(method, pub);
                HttpRequest req = rb.build();

                long t0 = System.nanoTime();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    long us = (System.nanoTime() - t0) / 1000;
                    int status = resp.statusCode();
                    String b = resp.body();
                    statusCounts.computeIfAbsent(status, k -> new LongAdder()).increment();
                    int code = jsonInt(b, "\"code\"");
                    boolean success = status >= 200 && status < 300 && (code == Integer.MIN_VALUE || code == 0);
                    if (measuring) {
                        if (success) ok.increment();
                        else {
                            fail.increment();
                            String msg = jsonStr(b, "\"message\"");
                            String m = msg == null ? "" : msg;
                            if (status == 401 || m.contains("登录")) bizAuth.increment();
                            else if (m.contains("库存不足") || m.contains("库存")) bizStockShort.increment();
                            else if (m.contains("重复提交") || m.contains("令牌") || m.contains("过期")) bizIdem.increment();
                            else bizOther.increment();
                            String key = "HTTP" + status + " code=" + (code == Integer.MIN_VALUE ? "?" : code)
                                    + " msg=" + (msg == null ? "-" : trunc(msg, 60));
                            bump(key);
                        }
                        addLat(us);
                        addTs(measuring, success);
                    }
                } catch (IOException | InterruptedException e) {
                    if (measuring) {
                        fail.increment();
                        localTransportErr++;
                        record(measuring, 0, errKey("TRANSPORT", e.getClass().getSimpleName()));
                        addTs(measuring, false);
                    }
                    if (e instanceof InterruptedException) return;
                } catch (RuntimeException e) {
                    if (measuring) {
                        fail.increment();
                        record(measuring, 0, errKey("CLIENT", e.getClass().getSimpleName()));
                        addTs(measuring, false);
                    }
                }
            }
            // 归并到全局
            for (Map.Entry<String, Integer> e : localErrs.entrySet()) {
                errGroups.computeIfAbsent(e.getKey(), k -> new LongAdder()).add(e.getValue());
            }
            if (localTransportErr > 0) httpErr.add(localTransportErr);
        }

        void bump(String key) {
            localErrs.merge(key, 1, Integer::sum);
        }

        void addLat(long us) {
            if (n == lat.length) lat = Arrays.copyOf(lat, lat.length * 2);
            lat[n++] = us;
        }

        void record(boolean measuring, int status, String key) {
            if (!measuring) return;
            bump(key);
        }

        void addTs(boolean measuring, boolean success) {
            if (!measuring) return;
            long idx = (System.nanoTime() - measureStartNanos) / 1_000_000_000L;
            if (idx < 0 || idx >= tsOk.length) return;
            if (success) tsOk[(int) idx].incrementAndGet();
            else tsFail[(int) idx].incrementAndGet();
        }
    }

    /** 80/20：按 skuRatio 打热点 SKU，其余从冷门池随机 */
    static long pickSku() {
        if (coldSkus.length == 0) return hotSku;
        return java.util.concurrent.ThreadLocalRandom.current().nextDouble() < skuRatio
                ? hotSku : coldSkus[java.util.concurrent.ThreadLocalRandom.current().nextInt(coldSkus.length)];
    }

    /** /proc/net/dev 累计收发字节（除 lo 外的所有网卡） */
    static long[] netDev() {
        long rx = 0, tx = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String iface = line.substring(0, c).trim();
                if (iface.equals("lo") || iface.equals("docker0")) continue;
                String[] f = line.substring(c + 1).trim().split("\\s+");
                if (f.length < 9) continue;
                rx += Long.parseLong(f[0]);
                tx += Long.parseLong(f[8]);
            }
        } catch (Exception ignore) {
        }
        return new long[]{rx, tx};
    }

    static String statusDist() {
        List<Map.Entry<Integer, LongAdder>> l = new ArrayList<>(statusCounts.entrySet());
        l.sort(Comparator.comparingInt(Map.Entry::getKey));
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, LongAdder> e : l) {
            if (b.length() > 0) b.append(' ');
            b.append(e.getKey()).append('=').append(e.getValue().sum());
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    static boolean hasContentType() {
        for (String h : headers) if (h.toLowerCase(Locale.ROOT).startsWith("content-type")) return true;
        return false;
    }

    static String errKey(String kind, String detail) {
        return kind + " " + detail;
    }

    // ---------------- 工具 ----------------
    static String[] readLines(String f) throws IOException {
        if (f == null) return new String[0];
        List<String> lines = Files.readAllLines(Path.of(f), StandardCharsets.UTF_8);
        List<String> keep = new ArrayList<>(lines.size());
        for (String l : lines) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("#")) keep.add(t);
        }
        return keep.toArray(new String[0]);
    }

    static double pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        double rank = p / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        double v = lo == hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
        return v / 1000.0;   // us → ms
    }

    static int jsonInt(String s, String key) {
        if (s == null) return Integer.MIN_VALUE;
        int i = s.indexOf(key);
        if (i < 0) return Integer.MIN_VALUE;
        i = s.indexOf(':', i + key.length());
        if (i < 0) return Integer.MIN_VALUE;
        int j = i + 1;
        while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
        int k = j;
        while (k < s.length() && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '-')) k++;
        if (k == j) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(s.substring(j, k));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    static String jsonStr(String s, String key) {
        if (s == null) return null;
        int i = s.indexOf(key);
        if (i < 0) return null;
        i = s.indexOf('"', s.indexOf(':', i + key.length()) + 1);
        if (i < 0) return null;
        int j = s.indexOf('"', i + 1);
        if (j < 0) return null;
        return s.substring(i + 1, j);
    }

    static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static double safeDouble(java.util.function.DoubleSupplier s) {
        try {
            double v = s.getAsDouble();
            return v < 0 ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    static void row(StringBuilder sb, String k, Object v) {
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"")) s = '"' + s.replace("\"", "\"\"") + '"';
        sb.append(k).append(',').append(s).append('\n');
    }

    static void parseArgs(String[] a) {
        for (int i = 0; i < a.length; i++) {
            String k = a[i];
            switch (k) {
                case "--url" -> url = a[++i];
                case "--method" -> method = a[++i].toUpperCase(Locale.ROOT);
                case "--concurrency" -> concurrency = Integer.parseInt(a[++i]);
                case "--duration" -> durationSec = Integer.parseInt(a[++i]);
                case "--warmup" -> warmupSec = Integer.parseInt(a[++i]);
                case "--timeout-ms" -> timeoutMs = Long.parseLong(a[++i]);
                case "--token-file" -> tokenFile = a[++i];
                case "--body-file" -> bodyFile = a[++i];
                case "--auth-file" -> authFile = a[++i];
                case "--header" -> headers.add(a[++i]);
                case "--out" -> out = a[++i];
                case "--label" -> label = a[++i];
                case "--sku-hot" -> hotSku = Long.parseLong(a[++i]);
                case "--sku-ratio" -> skuRatio = Double.parseDouble(a[++i]);
                case "--sku-cold-file" -> skuColdFile = a[++i];
                case "--samples" -> keepSamples = true;
                default -> {
                    if (k.startsWith("--")) {
                        System.err.println("unknown arg: " + k);
                        System.exit(2);
                    }
                }
            }
        }
        if (concurrency < 1) concurrency = 1;
        if (durationSec < 1) durationSec = 1;
    }
}
