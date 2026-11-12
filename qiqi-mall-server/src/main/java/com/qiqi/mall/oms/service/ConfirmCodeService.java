package com.qiqi.mall.oms.service;

import com.qiqi.mall.shared.security.KvStore;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 操作确认码（confirmCode）：把「先看清方案，再执行写操作」这道闸门从 MCP 层下沉到后端。
 *
 * <p>为什么要在后端：MCP 的 confirm.mjs 用的是进程内 Map，绕过 MCP 直接 curl
 * {@code POST /admin/orders/{id}/audit-pass} 完全没有保护。码必须由**真正执行写操作的那一层**签发并校验，
 * 才能挡住任何直调后端的调用方（curl / 脚本 / 别的前端 / 未来的工具）。
 *
 * <p>三个方法就是全部契约：
 * <ul>
 *   <li>{@link #issue(Long, List)}：预览时签发 —— 算方案指纹 + 生成 code + 写入 KV（TTL 5 分钟，见 app.confirm-code.ttl-minutes）</li>
 *   <li>{@link #consume(String)}：执行时消费 —— {@code KvStore.consume} 原子取出即删（Redis = GETDEL），天然一次性</li>
 *   <li>{@link #hashPlan(List)}：稳定指纹 —— 同一份方案必须算出同一个 hash，方案变了 hash 必变</li>
 * </ul>
 *
 * <p>存储口径（与 CaptchaStore 一致）：key={@code audit:code:{code}}，value={@code orderId|planHash}
 * （纯字符串，不塞 JSON —— 存在 KV 里的东西越简单，跨档位（memory/redis）行为越可预测）。
 */
@Service
public class ConfirmCodeService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmCodeService.class);

    /** KV key 前缀：audit:code:{code} */
    private static final String KEY_PREFIX = "audit:code:";
    /** value 里 orderId 与 planHash 的分隔符 */
    private static final String FIELD_SEP = "|";

    private final KvStore kvStore;
    /** 确认码有效期，默认 5 分钟（application-*.yml 的 app.confirm-code.ttl-minutes 可调） */
    private final Duration ttl;

    public ConfirmCodeService(KvStore kvStore,
                              @Value("${app.confirm-code.ttl-minutes:5}") long ttlMinutes) {
        this.kvStore = kvStore;
        this.ttl = Duration.ofMinutes(ttlMinutes <= 0 ? 5 : ttlMinutes);
    }

    /** 有效期（秒），随预览响应一起下发给调用方，避免前后端各写一份 300 */
    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    /**
     * 签发：预览拆单方案时调用。
     *
     * @return 36 位（带横线）的 UUID 确认码
     */
    public String issue(Long orderId, List<Map<String, Object>> plan) {
        String planHash = hashPlan(plan);
        String code = UUID.randomUUID().toString();
        kvStore.put(KEY_PREFIX + code, orderId + FIELD_SEP + planHash, ttl);
        log.debug("签发确认码 orderId={} code={} planHash={}", orderId, code, planHash);
        return code;
    }

    /**
     * 消费：执行写操作时调用。**取出即删**（Redis 档为 GETDEL，原子），因此无论后续校验成败，
     * 这个码都不可能被第二次使用。
     *
     * @return {@code {orderId, planHash}}；null 表示不存在 / 已过期 / 已被用过
     */
    public Consumed consume(String code) {
        if (code == null || code.isBlank()) return null;
        String raw = kvStore.consume(KEY_PREFIX + code.trim());
        if (raw == null) return null;
        int sep = raw.indexOf(FIELD_SEP);
        if (sep <= 0) {
            log.warn("确认码存储值格式异常（缺分隔符），按无效处理: {}", raw);
            return null;
        }
        try {
            return new Consumed(Long.parseLong(raw.substring(0, sep)), raw.substring(sep + 1));
        } catch (NumberFormatException e) {
            log.warn("确认码存储值格式异常（orderId 非数字），按无效处理: {}", raw);
            return null;
        }
    }

    /**
     * 方案指纹（sha256）。
     *
     * <p>**不依赖 JSON 库的键顺序**：这里手拼规范化字符串，规则固定为
     * <pre>
     * W={warehouseId}[I={orderItemId},S={productSkuId},Q={quantity};...]
     * </pre>
     * ① 包裹按 warehouseId 升序、行内明细按 orderItemId 升序 —— 方案元素顺序变了（DB 返回顺序不同）不影响指纹；
     * ② 字段顺序写死在代码里，不用 LinkedHashMap 迭代/Jackson 序列化，避免「换个 JSON 库 hash 就变」；
     * ③ 数值统一归一（{@code 2} 与 {@code 2.0} 视为同一个数），避免类型差异导致假变化。
     *
     * <p>刻意**不**纳入指纹：warehouseName / productName —— 那是展示文案，仓库改名不该让用户已确认的方案失效；
     * 而「从哪个仓发、发哪些 SKU、各多少件」已经由 warehouseId / productSkuId / quantity 完全覆盖。
     */
    public String hashPlan(List<Map<String, Object>> plan) {
        String canonical = canonicalize(plan);
        log.debug("确认码方案规范化串: {}", canonical);
        return sha256Hex(canonical);
    }

    /** 规范化序列化（见 {@link #hashPlan} 的三条规则） */
    private String canonicalize(List<Map<String, Object>> plan) {
        StringBuilder sb = new StringBuilder();
        if (plan == null || plan.isEmpty()) return "";
        List<Map<String, Object>> packages = new ArrayList<>(plan);
        packages.sort(Comparator.comparing(pkg -> cell(pkg.get("warehouseId"))));
        for (Map<String, Object> pkg : packages) {
            sb.append("W=").append(cell(pkg.get("warehouseId"))).append('[');
            List<Map<String, Object>> lines = linesOf(pkg.get("items"));
            lines.sort(Comparator.comparing(line -> cell(line.get("orderItemId"))));
            for (Map<String, Object> line : lines) {
                sb.append("I=").append(cell(line.get("orderItemId")))
                        .append(",S=").append(cell(line.get("productSkuId")))
                        .append(",Q=").append(cell(line.get("quantity")))
                        .append(';');
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** items 是 List<Map>；类型不对时按空处理（指纹只会因此变化 → 拒执行，安全侧） */
    private static List<Map<String, Object>> linesOf(Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) continue;
            Map<String, Object> line = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> line.put(String.valueOf(k), v));
            out.add(line);
        }
        return out;
    }

    /** 规范化单元格：数值归一,其它按字符串,缺字段固定为 null 字样 */
    private static String cell(Object value) {
        if (value == null) return "null";
        if (value instanceof Number number) {
            // 2 / 2L / 2.0 全部归一为 "2"，避免数值类型差异算出不同指纹
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里；仍显式抛出，避免返回可被伪造的弱指纹
            throw new IllegalStateException("SHA-256 不可用，无法签发确认码", e);
        }
    }

    /** 消费出来的确认码内容：这笔码是给哪个订单、哪个方案的 */
    public record Consumed(Long orderId, String planHash) {}
}
