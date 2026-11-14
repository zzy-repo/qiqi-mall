import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * RedisRtt —— 用**裸 socket**量「客户端进程 → Redis」的单次命令往返（RTT），不带任何客户端库。
 *
 * <p>为什么不用 redis-cli --latency：那只能在 redis 容器里跑（回环 0.02ms，量不到容器间的那一跳），
 * 而压测里真正发生的是 <b>server 容器 → redis 容器</b>（同一 docker bridge）。
 * 这个程序编成 .class 后 docker cp 进 server 容器里跑，量的就是那条真实路径。
 *
 * <p>量的是 GETDEL（下单链路上唯一一条 Redis 命令，见 IdempotentTokenStore#consume），
 * 每个 key 只消费一次，所以先 SET 好 N 个 key 再逐个 GETDEL。
 *
 * <pre>
 *   javac -d out RedisRtt.java
 *   docker cp out qiqi-server-1:/tmp/rtt
 *   docker exec qiqi-server-1 java -cp /tmp/rtt RedisRtt redis 6379 5000
 * </pre>
 */
public final class RedisRtt {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "redis";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 5000;

        try (Socket s = new Socket(host, port)) {
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // 预热 + 建 key：先 PING 若干次，让 TCP 窗口/中断合并进入稳态
            for (int i = 0; i < 200; i++) {
                write(out, "PING");
                readReply(in);
            }
            for (int i = 0; i < n; i++) {
                write(out, "SET bench:rtt:" + i + " 1");
                readReply(in);
            }

            // 正式测量：逐条 GETDEL，nanoTime 夹住一次完整往返
            List<Long> ns = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                write(out, "GETDEL bench:rtt:" + i);
                String reply = readReply(in);
                long t1 = System.nanoTime();
                if (reply.equals("$-1")) throw new IllegalStateException("GETDEL 未命中，量到的是 miss 路径");
                ns.add(t1 - t0);
            }
            // 清理
            for (int i = 0; i < 4; i++) { /* 全部已 GETDEL 删除，无需再删 */ }

            Collections.sort(ns);
            System.out.printf("host=%s:%d  n=%d%n", host, port, n);
            System.out.printf("  p50=%.1f us  p90=%.1f us  p99=%.1f us  mean=%.1f us  min=%.1f us  max=%.1f us%n",
                    (double) ns.get(n / 2) / 1000, (double) ns.get((int) (n * 0.90)) / 1000,
                    (double) ns.get((int) (n * 0.99)) / 1000,
                    ns.stream().mapToLong(Long::longValue).average().orElse(0) / 1000,
                    (double) ns.get(0) / 1000, (double) ns.get(n - 1) / 1000);
        }
    }

    /** 发送一条命令（按空格切成数组，拼 RESP 数组） */
    private static void write(OutputStream out, String cmd) throws Exception {
        String[] parts = cmd.split(" ");
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 读**一整条** RESP 回复（含 bulk 的正文），否则多条 bulk 回复会串行错位 */
    private static String readReply(InputStream in) throws Exception {
        String line = readLine(in);
        char type = line.isEmpty() ? ' ' : line.charAt(0);
        switch (type) {
            case '+':   // 简单字符串
            case '-':   // 错误
            case ':':   // 整数
                return line;
            case '$': { // bulk：先长度，再读正文（含尾部 CRLF）
                int len = Integer.parseInt(line.substring(1));
                if (len < 0) return line;
                byte[] buf = new byte[len + 2];
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r < 0) throw new IllegalStateException("连接被关闭");
                    off += r;
                }
                return "$" + new String(buf, 0, len, StandardCharsets.UTF_8);
            }
            default:
                return line;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private RedisRtt() { }
}
