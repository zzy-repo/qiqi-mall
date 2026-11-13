package com.qiqi.mall.shared.storage;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 图片上传：校验 → 重写文件名 → PutObject → 返回可直连的 URL。
 *
 * 走 MinIO Java SDK 的 S3 兼容协议，dev 连本机 MinIO、test 连阿里云 OSS（见 app.minio.*）。
 * 写入键 = objectKeyPrefix + objectName（test 下 objectKeyPrefix=qiqi-mall/，与 OSS 历史图同目录）；
 * 返回地址 = publicBaseUrl(留空) + "/" + publicPathPrefix(默认 bucket) + "/" + objectName
 * ⇒ dev/test 都是相对路径 /qiqi-mall/products/2026/11/uuid.png，历史数据不用改。
 */
@Service
public class MinioStorageService {

    /** 白名单 content-type → 扩展名 */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    /** 单文件上限 5MB */
    public static final long MAX_SIZE = 5L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public boolean isAllowed(String contentType) {
        return contentType != null && ALLOWED.containsKey(contentType.toLowerCase());
    }

    /**
     * @param objectName 形如 products/2026/11/uuid.jpg
     * @return 浏览器可直接访问的 URL
     */
    public String upload(String objectName, InputStream stream, long size, String contentType) throws Exception {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.getBucket())
                // OSS 里历史图都在 qiqi-mall/ 前缀下，写入键要带上同一前缀（dev 前缀为空）
                .object(properties.objectKey(objectName))
                .stream(stream, size, -1)
                .contentType(contentType)
                .build());
        return properties.publicUrl(objectName);
    }

    public String extensionOf(String contentType) {
        return ALLOWED.getOrDefault(contentType == null ? "" : contentType.toLowerCase(), "bin");
    }

    /** 供 controller 返回统一结构 */
    public Map<String, String> result(String url, String objectName) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("objectName", objectName);
        return body;
    }

    // ==================================================================================
    // 按 URL 拉取图片（POST /admin/upload/image-from-url）
    //
    // 为什么要有：MCP 的入参是**模型产出的 JSON 文本**，两张半成品都不行 ——
    //   ① 5MB 图塞 base64 → 6~7MB 文本进上下文，模型产不出来；
    //   ② 传服务器本地路径 → MCP 变成「任意本地文件读取 + 外发对象存储」的通道。
    // 所以让后端自己去拉 URL：入参只有一个字符串，落地仍走同一套 bucket/前缀/年-月归档。
    //
    // ⚠️ 这是本方案唯一的高风险项（SSRF）：后端会替调用方发起外发请求，
    //    若不加限制，AI 就能借它去打内网 ECS / 数据库 / 云元数据。下面 7 条是硬约束，改代码前先读完：
    //
    //  ① 只允许 https —— http / file / ftp / gopher / jar / data 等一律在第一步拒绝（见 requireHttps）；
    //  ② 解析出的**每个** IP 都要过黑名单 —— 回环 / 私有 / 链路本地 / CGNAT（本机跑在 Docker + Tailscale 里，
    //     100.64/10 与 172.16/12 必须禁，否则能打内网 ECS、后端、数据库），见 isBlockedAddress；
    //     IPv6 侧另封 NAT64（64:ff9b::/96、64:ff9b:1::/48）、6to4（2002::/16）、Teredo（2001::/32）
    //     —— 这几段能把 IPv4 私有地址嵌进 v6 地址里，是 IPv4 黑名单的旁路，见 BLOCKED_IPV6；
    //  ③ 不跟随重定向 —— 3xx 直接拒（HttpClient.Redirect.NEVER 的等价物：这里手写 HTTP，压根不发第二次请求）；
    //  ④ 超时与体积：连接 5s、读取 10s（整体读预算，不是单次 read），正文上限 5MB，超了立刻中断；
    //  ⑤ MIME 嗅探不看 Content-Type —— 读前 16 字节判真实类型，只收 jpg/png/webp（与现有白名单同一张表）；
    //  ⑥ 防 DNS 重绑定 —— 先解析一次拿 IP，**后面就用这个 IP 连**（不交给 HTTP 客户端再解析一次），
    //     TLS 的 SNI 与证书主机名校验仍按原域名做（见 openTls）。
    //  ⑦ 端口只允许 443 —— https 默认端口之外一律拒（见 fetchFromUrl 的端口校验）。目标 IP 虽然是公网的，
    //     但「对任意端口发一次 GET」就等于给调用方一个端口探测 / 请求放大器（:22 探活、打内网服务的
    //     非标端口、拿本机当跳板做扫描），所以只留 443，URL 不带端口（getPort() == -1）按 443 放行。
    //
    // 代价与取舍：为了做到 ⑥，这里没有用 java.net.http.HttpClient，而是「自己连 IP + 手写一行 HTTP/1.1 请求」。
    //    多出的代码只为三件事：IP 钉死、绝不跟随重定向、正文边读边掐。改这里时别顺手换回 HttpClient。
    // ==================================================================================

    /** 连接超时：5s */
    public static final int CONNECT_TIMEOUT_MS = 5_000;
    /** ⑦ 唯一允许的端口：https 默认 443（其它端口一律拒，见 fetchFromUrl） */
    public static final int ALLOWED_HTTPS_PORT = 443;
    /** 读取总预算：10s（整个正文下载的墙钟上限，不是单次 read 的 soTimeout） */
    public static final int READ_TIMEOUT_MS = 10_000;
    /** 响应头字节上限（防御畸形/超长响应头把内存吃光） */
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    /** 单个 URL 上限（正常图片地址远小于它） */
    private static final int MAX_URL_LENGTH = 2048;

    private static final SSLSocketFactory TLS_SOCKET_FACTORY = (SSLSocketFactory) SSLSocketFactory.getDefault();

    /** 一个网段：网络地址字节 + 前缀长度 */
    private record Cidr(byte[] network, int prefixBits) {}

    private static Cidr cidr(String literal, int prefixBits) {
        try {
            // 只喂 IP 字面量，getByName 不会触发 DNS
            return new Cidr(InetAddress.getByName(literal).getAddress(), prefixBits);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("内置网段字面量写错了：" + literal, e);
        }
    }

    /**
     * IPv4 黑名单。含需求点名的 6 段，外加 0.0.0.0/8（本机/无效）与两段 IETF 保留段。
     * 172.16/12 与 100.64/10 在 Docker + Tailscale 环境下就是内网本身，禁掉才有意义。
     */
    private static final List<Cidr> BLOCKED_IPV4 = List.of(
            cidr("127.0.0.0", 8),      // 回环
            cidr("10.0.0.0", 8),       // 私有
            cidr("172.16.0.0", 12),    // 私有（Docker 默认 bridge / compose 常落在这里）
            cidr("192.168.0.0", 16),   // 私有
            cidr("169.254.0.0", 16),   // 链路本地（云元数据 169.254.169.254 就在这段）
            cidr("100.64.0.0", 10),    // CGNAT / Tailscale 100.x
            cidr("0.0.0.0", 8),        // 本机 / 无效
            cidr("198.18.0.0", 15),    // 基准测试保留
            cidr("192.0.0.0", 24));    // IETF 协议保留

    /**
     * IPv6 黑名单：回环、未指定、唯一本地（fc00::/7）、链路本地（fe80::/10），
     * 外加四段「能把 IPv4 地址嵌进去的转换前缀」，以及 RFC 4291 已废弃的 IPv4-compatible 前缀 {@code ::/96}。
     *
     * 为什么后四段必须整段禁，而不是把里面的 IPv4 逐条映射出来再判：
     *   64:ff9b::/96 是 NAT64 well-known prefix，最后 32 位就是 IPv4 —— 64:ff9b::7f00:1 = 127.0.0.1、
     *   64:ff9b::a9fe:a9fe = 169.254.169.254（云元数据）。在纯 IPv6 / NAT64 环境里，内核会把它真的
     *   路由成那条 IPv4 连接，等于绕开上面整张 IPv4 黑名单。2002::/16（6to4）与 2001::/32（Teredo）
     *   同样在地址里内嵌 IPv4（6to4 取第 17~48 位、Teredo 取最后 32 位取反）。
     *   ⇒ 这几段能把 IPv4 私有地址嵌进去，所以整段禁止比逐条映射更安全：
     *     逐条映射要先枚举所有转换/隧道编码方式，漏一种就是一个洞；整段禁则连「新变种」一起挡掉。
     *     代价是公网用户真的用 IPv6 转换前缀访问时会一并被拒 —— 图片源不该依赖 NAT64/6to4，可接受。
     */
    private static final List<Cidr> BLOCKED_IPV6 = List.of(
            cidr("::1", 128),            // 回环
            cidr("::", 128),             // 未指定
            cidr("fc00::", 7),           // 唯一本地（IPv6 私有段，等价 10/8 + 172.16/12 + 192.168/16）
            cidr("fe80::", 10),          // 链路本地（IPv6 的 169.254/16）
            cidr("64:ff9b::", 96),       // NAT64 well-known prefix（末 32 位 = 任意 IPv4，能映射回 127.0.0.1 / 169.254.169.254）
            cidr("64:ff9b:1::", 48),     // NAT64 local-use（同上，RFC 8215 的本地专用前缀）
            cidr("2002::", 16),          // 6to4（第 17~48 位内嵌 IPv4）
            cidr("2001::", 32),          // Teredo（末 32 位是取反的 IPv4）
            cidr("::", 96));             // IPv4-compatible（RFC 4291 废弃格式）：末 32 位是 IPv4，::127.0.0.1 即 127.0.0.1

    /** 拉回来的图片（已过 SSRF 校验、已嗅探出真实类型、已在 5MB 以内） */
    public record FetchedImage(byte[] data, String contentType) {
        public long size() { return data.length; }
    }

    /** 拉取失败：status 直接当 HTTP 状态码用，message 是给人/模型看的中文原因（绝不回 500） */
    public static class ImageFetchRejected extends Exception {
        private final int status;

        public ImageFetchRejected(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    /** 目标地址是否落在内网/回环/链路本地等禁用网段（IPv4-mapped IPv6 会先摊平成 IPv4 再判） */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) return true;
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            byte[] mapped = ipv4Mapped(raw);
            if (mapped != null) return inAny(mapped, BLOCKED_IPV4);
            return inAny(raw, BLOCKED_IPV6);
        }
        return inAny(raw, BLOCKED_IPV4);
    }

    /** ::ffff:a.b.c.d → 4 字节；不是 mapped 地址返回 null */
    private static byte[] ipv4Mapped(byte[] v6) {
        for (int i = 0; i < 10; i++) if (v6[i] != 0) return null;
        if ((v6[10] & 0xFF) != 0xFF || (v6[11] & 0xFF) != 0xFF) return null;
        return new byte[] {v6[12], v6[13], v6[14], v6[15]};
    }

    private static boolean inAny(byte[] addr, List<Cidr> nets) {
        for (Cidr net : nets) if (inCidr(addr, net)) return true;
        return false;
    }

    private static boolean inCidr(byte[] addr, Cidr cidr) {
        byte[] net = cidr.network();
        if (addr.length != net.length) return false;
        int bits = cidr.prefixBits();
        int fullBytes = bits / 8;
        for (int i = 0; i < fullBytes; i++) if (addr[i] != net[i]) return false;
        int rest = bits % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (addr[fullBytes] & mask) == (net[fullBytes] & mask);
    }

    /**
     * 前 16 字节判真实图片类型（**不看 Content-Type**，服务端说是 image/png 也不算数）。
     * @return image/jpeg | image/png | image/webp | image/gif | null（不是图片）
     */
    public static String sniffImageType(byte[] head) {
        if (head == null) return null;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && (head[4] & 0xFF) == 0x0D && (head[5] & 0xFF) == 0x0A && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        if (head.length >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return "image/gif";
        }
        return null;
    }

    /**
     * 按 URL 拉一张图片（SSRF 全套防护见本段开头的 6 条）。
     *
     * @throws ImageFetchRejected 任何不可拉取的情形（400 / 403 / 413 / 415 / 502 / 504 + 中文原因）
     */
    public FetchedImage fetchFromUrl(String rawUrl) throws ImageFetchRejected {
        URI uri = parseHttpsUrl(rawUrl);

        // ⑦ 端口只允许 443：URL 不带端口时 getPort() 返回 -1，当作 443 放行；
        //    :8443 / :22 / :6379 这类一律在解析域名之前就拒（连 DNS 都不做，更谈不上发请求）。
        int port = uri.getPort() == -1 ? ALLOWED_HTTPS_PORT : uri.getPort();
        if (port != ALLOWED_HTTPS_PORT) {
            throw new ImageFetchRejected(400, "只允许 https 默认端口 443，收到 :" + port + "，已拒绝（本接口不用于探测其它端口）");
        }

        String host = uri.getHost();

        // ②+⑥ 先解析一次，逐个校验，然后把第一个可用 IP 钉死下来
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ImageFetchRejected(400, "域名解析失败：找不到主机 " + host + "，请确认这个图片地址真实可访问");
        }
        if (resolved.length == 0) {
            throw new ImageFetchRejected(400, "域名解析失败：主机 " + host + " 没有任何可用 IP");
        }
        for (InetAddress address : resolved) {
            if (isBlockedAddress(address)) {
                throw new ImageFetchRejected(403, "目标地址（" + host + " → " + address.getHostAddress()
                        + "）属于内网 / 回环 / 链路本地 / CGNAT 网段，或落在 IPv6 转换前缀（NAT64 / 6to4 / Teredo）里"
                        + "（这类地址能把 IPv4 私有地址嵌进去），已拒绝：本接口不允许访问本机与内网资源");
            }
        }
        InetAddress pinned = resolved[0];

        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
        // 端口已钉死 443（⑦），Host 头不再需要端口后缀；IPv6 字面量由 URI.getHost() 自带方括号，正是 Host 头要的写法
        String hostHeader = host;

        long deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L;

        try (Socket plain = new Socket()) {
            // ④ 连接超时 5s
            plain.connect(new InetSocketAddress(pinned, port), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(READ_TIMEOUT_MS);
            SSLSocket tls = openTls(plain, host, port);

            // ③ 手写一次 HTTP/1.1 GET：只要一次响应，天然不跟随重定向
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: qiqi-mall-server/1.0 (image-from-url)\r\n"
                    + "Accept: image/jpeg,image/png,image/webp,image/*;q=0.8\r\n"
                    + "Accept-Encoding: identity\r\n"   // 不要压缩，省得体积上限被压缩流绕过
                    + "Connection: close\r\n\r\n";
            OutputStream out = tls.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            Fetched response = readResponse(tls.getInputStream(), host, deadline);

            // ⑤ 嗅探真实类型（Content-Type 只用来在错误文案里说明「服务端自称什么」）
            String sniffed = sniffImageType(response.body());
            if (sniffed == null) {
                throw new ImageFetchRejected(415, "目标地址返回的不是图片：前 16 字节既不是 jpg 也不是 png/webp/gif"
                        + "（服务端自称 Content-Type=" + response.serverContentType() + "，很可能是网页/JSON/错误页）");
            }
            if (!ALLOWED.containsKey(sniffed)) {
                throw new ImageFetchRejected(415, "该格式不在上传白名单内（嗅探到 " + sniffed
                        + "），与现有 /admin/upload/image 一致，只接受 jpg / png / webp");
            }
            return new FetchedImage(response.body(), sniffed);
        } catch (SocketTimeoutException e) {
            throw new ImageFetchRejected(504, "拉取图片超时（连接 " + CONNECT_TIMEOUT_MS / 1000
                    + "s、读取 " + READ_TIMEOUT_MS / 1000 + "s），目标站点太慢或不可达");
        } catch (ImageFetchRejected e) {
            throw e;
        } catch (IOException e) {
            throw new ImageFetchRejected(502, "拉取图片失败（网络/TLS 层）：" + e.getMessage()
                    + "；请确认目标地址是公开可访问的 https 图片");
        }
    }

    /**
     * ① 只允许 https，且必须是「干净的」绝对地址。
     * file / ftp / gopher / jar / data / dict 等协议在这里一次拒掉，不给下游任何解析机会。
     */
    private static URI parseHttpsUrl(String rawUrl) throws ImageFetchRejected {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ImageFetchRejected(400, "请提供图片地址（JSON 字段名 url）");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new ImageFetchRejected(400, "图片地址太长（超过 " + MAX_URL_LENGTH + " 字符），请给一个正常的图片 URL");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new ImageFetchRejected(400, "图片地址格式不合法：" + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            throw new ImageFetchRejected(400, "只支持 https 图片地址，"
                    + (scheme.isEmpty() ? "这个地址没有协议前缀" : "收到的协议是 " + scheme + "://")
                    + "；http / file / ftp / gopher 等一律拒绝（http 也能用的话，内网与元数据地址就防不住）");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ImageFetchRejected(400, "图片地址不允许带用户名/密码（user:pass@host 这种写法会被拒绝）");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ImageFetchRejected(400, "图片地址里没有可用的域名（形如 https://example.com/a.png）");
        }
        return uri;
    }

    /**
     * ⑥ IP 已钉死的前提下建立 TLS：SNI 用原域名，证书主机名校验也按原域名做（不是对 IP 校验证书）。
     * createSocket(socket, host, port, true) 的 host 参数决定 SNI 与 endpoint identification 的目标。
     */
    private static SSLSocket openTls(Socket plain, String host, int port) throws IOException {
        SSLSocket tls = (SSLSocket) TLS_SOCKET_FACTORY.createSocket(plain, host, port, true);
        SSLParameters params = tls.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");   // 主机名不匹配就 handshake 失败，绝不放行
        tls.setSSLParameters(params);
        tls.setSoTimeout(READ_TIMEOUT_MS);
        tls.startHandshake();
        return tls;
    }

    /** 解析后的响应（正文已在 5MB 以内，serverContentType 只用于错误文案） */
    private record Fetched(byte[] body, String serverContentType) {}

    /**
     * 读一次 HTTP 响应：状态行 + 头 + 正文（支持 Content-Length 与 chunked，两者都受 5MB 与 10s 约束）。
     * 3xx 一律拒（③），非 200 一律拒。
     */
    private Fetched readResponse(InputStream rawIn, String host, long deadline)
            throws IOException, ImageFetchRejected {
        BufferedInputStream in = new BufferedInputStream(rawIn, 8192);

        String statusLine = readLine(in, host, deadline);
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
            throw new ImageFetchRejected(502, "目标地址返回的不是 HTTP 响应（" + host + "），无法作为图片源");
        }
        int status = parseStatus(statusLine);

        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = statusLine.length();
        while (true) {
            String line = readLine(in, host, deadline);
            if (line == null) break;
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new ImageFetchRejected(502, "目标地址响应头超过 " + MAX_HEADER_BYTES + " 字节，已中止（疑似异常站点）");
            }
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }

        if (status >= 300 && status < 400) {
            throw new ImageFetchRejected(400, "目标地址返回重定向（HTTP " + status + " → "
                    + headers.getOrDefault("location", "(无 Location)") + "），本接口不跟随重定向（防止用跳转绕过内网校验），"
                    + "请直接给图片的最终 https 地址");
        }
        if (status != 200) {
            throw new ImageFetchRejected(400, "目标地址返回 HTTP " + status + "（不是 200），拉不到图片，请确认链接可匿名访问");
        }

        String encoding = headers.getOrDefault("content-encoding", "").trim().toLowerCase(Locale.ROOT);
        InputStream body = in;
        if (!encoding.isEmpty() && !"identity".equals(encoding)) {
            if (encoding.contains("gzip")) {
                body = new GZIPInputStream(in);
            } else {
                throw new ImageFetchRejected(400, "目标地址使用了不支持的压缩编码（" + encoding + "），请换一个图片地址");
            }
        }

        long declared = parseLong(headers.get("content-length"), -1L);
        if (declared > MAX_SIZE) {
            throw new ImageFetchRejected(413, "远程图片声明体积 " + mib(declared) + "，超过 5MB 上限，已拒绝");
        }
        boolean chunked = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked");
        byte[] data = chunked ? readChunked(body, deadline) : readFixed(body, declared, deadline);

        return new Fetched(data, headers.getOrDefault("content-type", "(未声明)"));
    }

    /** 定长（或读到 EOF）读取，边读边掐 5MB / 10s */
    private byte[] readFixed(InputStream in, long declared, long deadline) throws IOException, ImageFetchRejected {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
        byte[] chunk = new byte[8192];
        long total = 0;
        while (declared < 0 || total < declared) {
            checkDeadline(deadline);
            int n = in.read(chunk);
            if (n < 0) break;
            total += n;
            if (total > MAX_SIZE) {
                throw new ImageFetchRejected(413, "远程内容超过 5MB 上限，已中断下载（与现有 /admin/upload/image 的上限一致）");
            }
            buffer.write(chunk, 0, n);
        }
        if (total == 0) {
            throw new ImageFetchRejected(400, "目标地址返回了空内容（0 字节），不是图片");
        }
        return buffer.toByteArray();
    }

    /** chunked 读取（没有 Content-Length 的站点就是这种），同样受 5MB / 10s 约束 */
    private byte[] readChunked(InputStream in, long deadline) throws IOException, ImageFetchRejected {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
        byte[] chunk = new byte[8192];
        long total = 0;
        while (true) {
            checkDeadline(deadline);
            String sizeLine = readLine(in, "chunked", deadline);
            if (sizeLine == null) break;
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new ImageFetchRejected(502, "目标地址的分块传输格式不合法（chunk 大小=\"" + hex + "\"），已中止");
            }
            if (size == 0) break;
            long remaining = size;
            while (remaining > 0) {
                checkDeadline(deadline);
                int n = in.read(chunk, 0, (int) Math.min(chunk.length, remaining));
                if (n < 0) {
                    throw new ImageFetchRejected(502, "目标地址的分块内容提前结束，已中止");
                }
                total += n;
                if (total > MAX_SIZE) {
                    throw new ImageFetchRejected(413, "远程内容超过 5MB 上限，已中断下载（与现有 /admin/upload/image 的上限一致）");
                }
                buffer.write(chunk, 0, n);
                remaining -= n;
            }
            readLine(in, "chunked", deadline);   // 每个 chunk 后面的空行
        }
        if (total == 0) {
            throw new ImageFetchRejected(400, "目标地址返回了空内容（0 字节），不是图片");
        }
        return buffer.toByteArray();
    }

    /** ④ 总读取预算：sockets 的 soTimeout 只防「单次 read 卡住」，这里再防「慢慢喂」 */
    private static void checkDeadline(long deadline) throws ImageFetchRejected {
        if (System.nanoTime() > deadline) {
            throw new ImageFetchRejected(504, "拉取图片超时（读取总预算 " + READ_TIMEOUT_MS / 1000 + "s 已用完），已中断");
        }
    }

    /** 读一行（\r\n 或 \n 结尾）；超长直接判异常站点 */
    private static String readLine(InputStream in, String who, long deadline) throws IOException, ImageFetchRejected {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > MAX_HEADER_BYTES) {
                throw new ImageFetchRejected(502, "目标地址的响应行超过 " + MAX_HEADER_BYTES + " 字节（" + who + "），已中止");
            }
            if ((sb.length() & 0x3FF) == 0) checkDeadline(deadline);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int parseStatus(String statusLine) throws ImageFetchRejected {
        String[] parts = statusLine.split(" +");
        if (parts.length < 2) {
            throw new ImageFetchRejected(502, "目标地址的 HTTP 状态行不合法：" + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new ImageFetchRejected(502, "目标地址的 HTTP 状态码不合法：" + parts[1]);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }
}
