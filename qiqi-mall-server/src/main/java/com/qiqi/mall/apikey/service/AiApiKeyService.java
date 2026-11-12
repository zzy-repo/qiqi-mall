package com.qiqi.mall.apikey.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.apikey.entity.AiApiKey;
import com.qiqi.mall.apikey.mapper.AiApiKeyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key 的生成、校验与缓存。
 *
 * <h3>缓存策略（为什么是 Redis 而不是本地 Map）</h3>
 * 校验路径是「每个请求一次 sha256 + 一次查询」，热路径上直接查库会被高频 AI 调用打穿，
 * 所以缓存 60 秒。这里**直接注入 StringRedisTemplate**，没沿用 KvStore 抽象，理由三条：
 * <ol>
 *   <li>KvStore 的 get() 是「计数器语义」（返回 long），塞不下 key 的 JSON 快照；为缓存去扩接口
 *       （加 getString/putString）会把验证码/幂等令牌的抽象污染成通用缓存。</li>
 *   <li>吊销要及时：多实例部署（e2e 会起 4 个后端）时本地 Map 各自为政，删除后别的实例还会
 *       放行最多 60 秒；共用 Redis 时 DELETE 缓存即全局生效（写操作会 evict）。</li>
 *   <li>Redis 不可用时降级：所有 Redis 操作都 try/catch 吞掉异常直接落库，认证不因缓存故障而中断。</li>
 * </ol>
 * 缓存 key 带库名前缀（{@code ai:apikey:<db>:<hash>}）：本仓库多个后端实例共用同一个 Redis，
 * 但各连不同 MySQL 库，不带库名前缀会串库。
 */
@Service
public class AiApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(AiApiKeyService.class);

    /** 固定 9 个权限，不按接口细化（接口 → scope 的映射见 ApiKeyAuthFilter） */
    public static final List<String> ALL_SCOPES = List.of(
            "order:read", "order:write",
            "catalog:read", "catalog:write",
            "member:read", "member:write",
            "config:read", "config:write",
            "dashboard:read");

    private static final String KEY_ALGO_PREFIX = "ak_";
    private static final int KEY_RANDOM_BYTES = 32;      // 32 字节 = 64 位十六进制
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long TOUCH_INTERVAL_MILLIS = 60_000L;

    private final AiApiKeyMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String cachePrefix;
    private final SecureRandom random = new SecureRandom();

    /** last_used_at 节流：keyId -> 上次写库时间（进程内），同一 key 60 秒内只写一次库 */
    private final Map<Long, Long> lastTouchAt = new ConcurrentHashMap<>();

    public AiApiKeyService(AiApiKeyMapper mapper, StringRedisTemplate redis, ObjectMapper objectMapper,
                           @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.mapper = mapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cachePrefix = "ai:apikey:" + databaseName(datasourceUrl) + ":";
    }

    // ------------------------------------------------------------------ 生成

    /** 新建 key：返回明文只在此刻存在，库里只存 sha256 与前 8 位 */
    public IssuedKey issue(String name, List<String> scopes, LocalDateTime expiresAt, String createdBy) {
        String plaintext = KEY_ALGO_PREFIX + HexFormat.of().formatHex(randomBytes(KEY_RANDOM_BYTES));
        AiApiKey row = new AiApiKey();
        row.setName(name);
        row.setKeyPrefix(plaintext.substring(0, 8));
        row.setKeyHash(sha256(plaintext));
        row.setScopes(joinScopes(scopes));
        row.setExpiresAt(expiresAt);
        row.setStatus(1);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return new IssuedKey(row, plaintext);
    }

    public record IssuedKey(AiApiKey row, String plaintext) {
    }

    // ------------------------------------------------------------------ 校验（过滤器热路径）

    /**
     * 按明文反查并做「存在 / 启用 / 未过期」三连校验。
     * 三个失败原因对外统一成 null（调用方回 401「API Key 无效或已过期」），避免泄漏 key 状态细节。
     */
    public AiApiKey findUsableByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        String hash = sha256(plaintext);
        CachedKey cached = readCache(hash);
        if (cached != null) {
            if (!isUsable(cached)) return null;
            // 命中缓存也要判过期：缓存写入时的快照可能已经过期（最多差 60 秒）
            AiApiKey hit = new AiApiKey();
            hit.setId(cached.id());
            hit.setName(cached.name());
            hit.setScopes(cached.scopes());
            hit.setStatus(cached.status());
            hit.setExpiresAt(toLocalDateTime(cached.expiresAtEpochMilli()));
            return hit;
        }
        AiApiKey row = mapper.selectOne(new LambdaQueryWrapper<AiApiKey>().eq(AiApiKey::getKeyHash, hash), false);
        if (row == null) return null;
        writeCache(hash, row);
        return isUsable(row) ? row : null;
    }

    private boolean isUsable(AiApiKey row) {
        return row.getStatus() != null && row.getStatus() == 1
                && (row.getExpiresAt() == null || row.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private boolean isUsable(CachedKey row) {
        if (row.status() == null || row.status() != 1) return false;
        return row.expiresAtEpochMilli() == null || row.expiresAtEpochMilli() > System.currentTimeMillis();
    }

    /** last_used_at 节流写库：同一 key 60 秒内只更新一次（README 口径：避免每个请求都写库） */
    public void touchLastUsed(Long keyId) {
        if (keyId == null) return;
        long now = System.currentTimeMillis();
        Long last = lastTouchAt.get(keyId);
        if (last != null && now - last < TOUCH_INTERVAL_MILLIS) return;
        lastTouchAt.put(keyId, now);
        try {
            mapper.touchLastUsed(keyId);
        } catch (Exception e) {
            // 打点失败不影响业务请求
            log.debug("更新 last_used_at 失败: keyId={} - {}", keyId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 缓存维护

    /** 修改 / 删除 / 禁用后立即失效：删一条记录 = 立即吊销，不用黑名单 */
    public void evict(AiApiKey row) {
        if (row == null) return;
        lastTouchAt.remove(row.getId());
        if (row.getKeyHash() == null) return;
        try {
            redis.delete(cachePrefix + row.getKeyHash());
        } catch (Exception e) {
            log.debug("清理 API Key 缓存失败: {}", e.getMessage());
        }
    }

    private CachedKey readCache(String hash) {
        try {
            String json = redis.opsForValue().get(cachePrefix + hash);
            return json == null ? null : objectMapper.readValue(json, CachedKey.class);
        } catch (Exception e) {
            log.debug("读取 API Key 缓存失败（降级查库）: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String hash, AiApiKey row) {
        try {
            CachedKey snapshot = new CachedKey(row.getId(), row.getName(), row.getScopes(), row.getStatus(),
                    row.getExpiresAt() == null ? null : toEpochMilli(row.getExpiresAt()));
            redis.opsForValue().set(cachePrefix + hash, objectMapper.writeValueAsString(snapshot), CACHE_TTL);
        } catch (Exception e) {
            log.debug("写入 API Key 缓存失败（不影响鉴权）: {}", e.getMessage());
        }
    }

    private record CachedKey(Long id, String name, String scopes, Integer status, Long expiresAtEpochMilli) {
    }

    // ------------------------------------------------------------------ 小工具

    public static String sha256(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定带 SHA-256，走到这里说明运行环境坏了
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    public static String joinScopes(List<String> scopes) {
        return String.join(",", scopes);
    }

    /** 库里的逗号串 → 数组（容忍空格与空段） */
    public static List<String> splitScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : scopes.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** 校验并规整权限列表：去重保序；有不认识的权限就抛出中文提示（由 controller 转 Result.fail） */
    public static List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) throw new IllegalArgumentException("权限不能为空，至少选择一个");
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String s : scopes) {
            String v = s == null ? "" : s.trim();
            if (v.isEmpty()) continue;
            if (!ALL_SCOPES.contains(v)) {
                throw new IllegalArgumentException("未知权限：" + v + "（可用：" + String.join(", ", ALL_SCOPES) + "）");
            }
            cleaned.add(v);
        }
        if (cleaned.isEmpty()) throw new IllegalArgumentException("权限不能为空，至少选择一个");
        return new ArrayList<>(cleaned);
    }

    private byte[] randomBytes(int len) {
        byte[] buf = new byte[len];
        random.nextBytes(buf);
        return buf;
    }

    private static long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static LocalDateTime toLocalDateTime(Long epochMilli) {
        return epochMilli == null ? null
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    /** 从 jdbc url 里取库名做缓存命名空间，避免多实例不同库串缓存 */
    private static String databaseName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) return "default";
        int slash = jdbcUrl.lastIndexOf('/');
        if (slash < 0) return "default";
        String tail = jdbcUrl.substring(slash + 1);
        int q = tail.indexOf('?');
        return q < 0 ? tail : tail.substring(0, q);
    }

    /** 供测试/排查用：把逗号串拆好的权限一次性打印 */
    public static String describeScopes(String scopes) {
        return Arrays.toString(splitScopes(scopes).toArray());
    }
}
