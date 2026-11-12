package com.qiqi.mall.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 操作确认码（confirmCode）：把「先看清将要发生什么，再执行写操作」这道闸门放在**后端**。
 *
 * <p>为什么必须在后端：MCP 的 confirm.mjs 用的是进程内 Map，任何人绕过 MCP 直接 curl
 * 写接口都没有保护。码由真正执行写操作的这一层签发并校验，才挡得住所有调用方。
 *
 * <p>统一契约（14 个 L3 高危写接口一致，L2 可逆写一律不加码）：
 * <pre>
 * ① 预演 dryRun=true（或 audit-pass 的 split-preview）→ 不落库，返回
 *      { willDo:"人话描述", confirmCode:"uuid", expiresInSec:300 }
 * ② 执行 同一地址 + body { confirmCode } → 校验 缺码/无效/已用/过期/用途不符/目标不符/指纹变化
 * </pre>
 *
 * <p>三个底层能力：
 * <ul>
 *   <li>{@link #issue}：写 {@code audit:code:{code}} → {@code op|targetId|intentHash}，TTL 5 分钟（可配）</li>
 *   <li>{@link #consume}：{@link KvStore#consume} 原子取出即删（Redis=GETDEL）→ 天然一次性</li>
 *   <li>{@link #hashIntent}：通用意图指纹（操作名 + 目标 + 若干「会被别人改动的量」）</li>
 * </ul>
 */
@Service
public class ConfirmCodeService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmCodeService.class);

    /** KV key 前缀：audit:code:{code} */
    private static final String KEY_PREFIX = "audit:code:";
    /** KV value 内部分隔符：op|targetId|intentHash */
    private static final String FIELD_SEP = "|";

    /** 少数接口的措辞沿用原有文案（audit-pass 有真预览接口，说「预览拆单方案」比说「dryRun」清楚） */
    private record OpProfile(String missingHint, String invalidHint, String targetMismatch, String intentChanged) {}

    private static final OpProfile DEFAULT_PROFILE = new OpProfile(
            "请先以 dryRun=true 预演本次操作",
            "请重新以 dryRun=true 预演",
            "确认码与操作对象不匹配，请重新预演",
            "操作内容已变化（期间数据被改动），拒绝执行，请重新预演");

    private static final OpProfile AUDIT_PASS_PROFILE = new OpProfile(
            "请先预览拆单方案",
            "请重新预览拆单方案",
            "确认码与订单不匹配，请重新预览",
            "拆单方案已变化（仓库或库存被改动），拒绝执行，请重新预览");

    private final KvStore kvStore;
    /** 确认码有效期，默认 5 分钟（application-*.yml 的 app.confirm-code.ttl-minutes 可调） */
    private final Duration ttl;

    public ConfirmCodeService(KvStore kvStore,
                              @Value("${app.confirm-code.ttl-minutes:5}") long ttlMinutes) {
        this.kvStore = kvStore;
        this.ttl = Duration.ofMinutes(ttlMinutes <= 0 ? 5 : ttlMinutes);
    }

    /** 有效期（秒）：随预演响应下发，前后端不各写一份 300 */
    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    /** dryRun 参数判定（只认字符串 true / 布尔 true，避免 "false" 被判成真） */
    public static boolean isDryRun(Boolean dryRun) {
        return Boolean.TRUE.equals(dryRun);
    }

    /**
     * 明细摘要的排序稳定化：同一组「行」不论来自哪次查询顺序，指纹都一样。
     *
     * <p>为什么单独抽出来：13 个端点的 extras 里几乎都有「若干条明细」，
     * 排序规则若分散写在各处，早晚会有人漏排 → 同一份意图算出两个指纹（预览能过、执行被拒）。
     */
    public static List<String> sortedLines(Collection<String> lines) {
        List<String> out = new ArrayList<>(lines);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ------------------------------------------------------------------ 签发 / 消费

    /** 签发确认码：{@code op + targetId + intentHash} 一起写进 KV，TTL 到点自动失效 */
    public String issue(String op, Long targetId, String intentHash) {
        String code = UUID.randomUUID().toString();
        kvStore.put(KEY_PREFIX + code, op + FIELD_SEP + targetId + FIELD_SEP + intentHash, ttl);
        log.debug("签发确认码 op={} targetId={} code={} intent={}", op, targetId, code, intentHash);
        return code;
    }

    /**
     * 消费确认码：**取出即删**，所以无论后续校验成败都不可能二次使用。
     *
     * @return null = 不存在 / 已过期 / 已用过 / 存储值损坏
     */
    public Consumed consume(String code) {
        if (code == null || code.isBlank()) return null;
        String raw = kvStore.consume(KEY_PREFIX + code.trim());
        if (raw == null) return null;
        String[] parts = raw.split("\\" + FIELD_SEP, 3);
        if (parts.length != 3) {
            log.warn("确认码存储值格式异常（应为 op|targetId|intentHash），按无效处理: {}", raw);
            return null;
        }
        try {
            return new Consumed(parts[0], Long.parseLong(parts[1]), parts[2]);
        } catch (NumberFormatException e) {
            log.warn("确认码存储值格式异常（targetId 非数字），按无效处理: {}", raw);
            return null;
        }
    }

    // ------------------------------------------------------------------ 统一预演 / 校验

    /**
     * 预演响应体：{@code {willDo, confirmCode, expiresInSec}}。
     *
     * @param extras 与 {@link #hashIntent} 完全一致的指纹组成，调用方在 execute 阶段必须用同一组值
     */
    public Map<String, Object> preflight(String willDo, String op, Long targetId, Object... extras) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("willDo", willDo);
        data.put("confirmCode", issue(op, targetId, hashIntent(op, targetId, extras)));
        data.put("expiresInSec", ttlSeconds());
        return data;
    }

    /**
     * 执行前校验（顺序即用户可见的错误顺序，全部中文）。
     *
     * @param extras 当场重新取的真实数据（不是预演时缓存的值）→ 数据被别人改过就必然对不上
     * @return null = 通过；非 null = 直接回给前端的中文原因
     */
    public String verify(String code, String op, Long targetId, Object... extras) {
        OpProfile profile = profileOf(op);
        if (code == null || code.isBlank()) {
            return "缺少确认码：" + profile.missingHint();
        }
        Consumed consumed = consume(code);
        if (consumed == null) {
            long minutes = Math.max(1, ttlSeconds() / 60);
            return "确认码无效、已使用或已过期（" + minutes + " 分钟），" + profile.invalidHint();
        }
        if (!op.equals(consumed.op())) {
            return "确认码用途不匹配（该码用于 " + consumed.op() + "，不能用于 " + op + "），请重新预演";
        }
        if (!targetId.equals(consumed.targetId())) {
            return profile.targetMismatch();
        }
        String current = hashIntent(op, targetId, extras);
        if (!current.equals(consumed.intentHash())) {
            log.info("确认码指纹不一致（拒绝执行）op={} targetId={} 预演={} 现在={}",
                    op, targetId, consumed.intentHash(), current);
            return profile.intentChanged();
        }
        return null;
    }

    private OpProfile profileOf(String op) {
        return ConfirmOps.AUDIT_PASS.equals(op) ? AUDIT_PASS_PROFILE : DEFAULT_PROFILE;
    }

    // ------------------------------------------------------------------ 指纹

    /**
     * 通用意图指纹：{@code sha256(op | targetId | canonical(extra1) | canonical(extra2) ...)}。
     *
     * <p>稳定性靠「规范化」而不是靠 JSON 库：
     * ① 分隔符与字段顺序写死在代码里（op 在前、targetId 其次、extras 按调用方给定顺序）；
     * ② Map 按键名排序后再拼（键顺序与插入顺序无关）；
     * ③ 数值归一（2 / 2L / 2.0 都算 "2"）；字符串带引号并转义分隔符，避免 "a|b" 与 ("a","b") 撞车；
     * ④ 列表/数组保持给定顺序（有语义），元素递归规范化。
     *
     * <p>所以「同一份意图」不管从哪个 JSON 库、哪种数值类型来，指纹都一样；
     * 「意图变了」（数量、包裹数、引用数、仓库……）指纹必变。
     */
    public String hashIntent(String op, Long targetId, Object... extras) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(quoted(op)).append(FIELD_SEP).append(number(targetId));
        if (extras != null) {
            for (Object extra : extras) {
                canonical.append(FIELD_SEP).append(canonicalValue(extra));
            }
        }
        String text = canonical.toString();
        log.debug("意图指纹规范化串 op={} → {}", op, text);
        return sha256Hex(text);
    }

    /**
     * 拆单方案指纹（audit-pass 专用，作为 {@link #hashIntent} 的一个 extra 传入）。
     *
     * <p>规则：{@code W={warehouseId}[I={orderItemId},S={productSkuId},Q={quantity};...]}，
     * 包裹按 warehouseId 升序、行按 orderItemId 升序 —— 与 DB 返回顺序无关；
     * 刻意**不**纳入 warehouseName / productName（改名不该让已确认的方案失效）。
     */
    public String hashPlan(List<Map<String, Object>> plan) {
        StringBuilder sb = new StringBuilder();
        if (plan == null || plan.isEmpty()) return sha256Hex("");
        List<Map<String, Object>> packages = new ArrayList<>(plan);
        packages.sort((a, b) -> number(a.get("warehouseId")).compareTo(number(b.get("warehouseId"))));
        for (Map<String, Object> pkg : packages) {
            sb.append("W=").append(number(pkg.get("warehouseId"))).append('[');
            List<Map<String, Object>> lines = linesOf(pkg.get("items"));
            lines.sort((a, b) -> number(a.get("orderItemId")).compareTo(number(b.get("orderItemId"))));
            for (Map<String, Object> line : lines) {
                sb.append("I=").append(number(line.get("orderItemId")))
                        .append(",S=").append(number(line.get("productSkuId")))
                        .append(",Q=").append(number(line.get("quantity")))
                        .append(';');
            }
            sb.append(']');
        }
        return sha256Hex(sb.toString());
    }

    private static List<Map<String, Object>> linesOf(Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            map.forEach((k, v) -> line.put(String.valueOf(k), v));
            out.add(line);
        }
        return out;
    }

    /** 递归规范化：Map 排序键、List 保序、数值归一、字符串转义 */
    private static String canonicalValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Number number) return number(number);
        if (value instanceof CharSequence text) return quoted(text.toString());
        if (value instanceof Boolean flag) return flag.toString();
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) sb.append(';');
                first = false;
                sb.append(quoted(entry.getKey())).append('=').append(canonicalValue(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object element : iterable) {
                if (!first) sb.append(';');
                first = false;
                sb.append(canonicalValue(element));
            }
            return sb.append(']').toString();
        }
        if (value instanceof Object[] array) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) sb.append(';');
                sb.append(canonicalValue(array[i]));
            }
            return sb.append(']').toString();
        }
        return quoted(String.valueOf(value));
    }

    /** 数值归一：2 / 2L / 2.0 / "2.00" 一律 "2"，避免类型差异算出不同指纹 */
    private static String number(Object value) {
        if (value == null) return "null";
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
            return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
        }
        String text = String.valueOf(value).trim();
        try {
            return number(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return quoted(text);
        }
    }

    /** 字符串统一带引号并转义分隔符：防止 "a|b" 与 ("a","b") 拼出同一个串 */
    private static String quoted(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '|', ';', '=', '{', '}', '[', ']' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用，无法签发确认码", e);
        }
    }

    /** 消费出来的确认码内容：这笔码签给哪个操作、哪个目标、哪份意图 */
    public record Consumed(String op, Long targetId, String intentHash) {}
}
