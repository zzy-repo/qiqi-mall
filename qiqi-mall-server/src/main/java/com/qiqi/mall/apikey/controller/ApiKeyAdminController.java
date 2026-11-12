package com.qiqi.mall.apikey.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.apikey.entity.AiApiKey;
import com.qiqi.mall.apikey.mapper.AiApiKeyMapper;
import com.qiqi.mall.apikey.service.AiApiKeyService;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 接口访问密钥管理（人用：本组接口只认管理员 JWT，API Key 一律 403，见 ApiKeyAuthFilter 红线）。
 *
 * <p>出入参约定：{@code scopes} 对外是数组，落库是逗号串；{@code key} 明文只在 POST 的响应里出现一次。
 */
@Tag(name = "管理端-AI 接口密钥", description = "AI 调接口用的 API Key：建 key / 配 scopes / 设过期 / 删除即吊销")
@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyAdminController {

    /** 宽容解析：前端可能给 "2026-12-31 23:59:59" / ISO 的 T 分隔 / 只给日期 */
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

    private final AiApiKeyService apiKeyService;
    private final AiApiKeyMapper apiKeyMapper;

    public ApiKeyAdminController(AiApiKeyService apiKeyService, AiApiKeyMapper apiKeyMapper) {
        this.apiKeyService = apiKeyService;
        this.apiKeyMapper = apiKeyMapper;
    }

    /** 列表：只有前缀，绝不返回明文（也不返回 key_hash） */
    @GetMapping
    public Result<List<ApiKeyView>> list() {
        List<AiApiKey> rows = apiKeyMapper.selectList(
                new LambdaQueryWrapper<AiApiKey>().orderByDesc(AiApiKey::getId));
        List<ApiKeyView> views = new ArrayList<>(rows.size());
        for (AiApiKey row : rows) views.add(ApiKeyView.of(row));
        return Result.ok(views);
    }

    /** 新建：明文 key 只在此响应里出现一次 */
    @PostMapping
    public Result<CreatedKey> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        try {
            String name = normalizeName(body.name());
            List<String> scopes = AiApiKeyService.normalizeScopes(body.scopes());
            LocalDateTime expiresAt = parseExpiresAt(body.expiresAt());
            String createdBy = CurrentUser.get() == null ? "unknown" : CurrentUser.get().username();
            AiApiKeyService.IssuedKey issued = apiKeyService.issue(name, scopes, expiresAt, createdBy);
            AiApiKey row = issued.row();
            return Result.ok(new CreatedKey(row.getId(), row.getName(), issued.plaintext(),
                    row.getKeyPrefix(), scopes, row.getExpiresAt()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 修改：字段传 null 表示不改。
     * expiresAt 想改成「永不过期」时传空串 ""（null 与「不改」冲突，故用空串表达显式清除）。
     * 改完立即 evict 缓存 —— 禁用 / 收窄权限下一次请求就生效。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateRequest body) {
        AiApiKey exist = apiKeyMapper.selectById(id);
        if (exist == null) return Result.fail("API Key 不存在");
        AiApiKey update = new AiApiKey();
        try {
            if (body.name() != null) update.setName(normalizeName(body.name()));
            if (body.scopes() != null) update.setScopes(AiApiKeyService.joinScopes(AiApiKeyService.normalizeScopes(body.scopes())));
            if (body.status() != null) {
                if (body.status() != 0 && body.status() != 1) return Result.fail("状态只能是 0（禁用）或 1（启用）");
                update.setStatus(body.status());
            }
            if (body.expiresAt() != null) update.setExpiresAt(parseExpiresAt(body.expiresAt()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        update.setId(id);
        apiKeyMapper.updateById(update);
        apiKeyService.evict(exist);
        return Result.ok();
    }

    /** 删除：物理删除。删了记录下次请求就查不到 = 立即吊销（缓存一并失效，不需要黑名单） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        AiApiKey exist = apiKeyMapper.selectById(id);
        if (exist == null) return Result.fail("API Key 不存在");
        apiKeyMapper.deleteById(id);
        apiKeyService.evict(exist);
        return Result.ok();
    }

    // ------------------------------------------------------------------ 入参 / 出参

    public record CreateRequest(String name, List<String> scopes, String expiresAt) {
    }

    /** expiresAt/status 为 null = 不修改；expiresAt 为 "" = 永不过期 */
    public record UpdateRequest(String name, List<String> scopes, String expiresAt, Integer status) {
    }

    public record ApiKeyView(Long id, String name, String keyPrefix, List<String> scopes,
                             LocalDateTime expiresAt, Integer status, LocalDateTime lastUsedAt, LocalDateTime createdAt) {
        static ApiKeyView of(AiApiKey row) {
            return new ApiKeyView(row.getId(), row.getName(), row.getKeyPrefix(),
                    AiApiKeyService.splitScopes(row.getScopes()), row.getExpiresAt(), row.getStatus(),
                    row.getLastUsedAt(), row.getCreatedAt());
        }
    }

    public record CreatedKey(Long id, String name, String key, String keyPrefix, List<String> scopes,
                             LocalDateTime expiresAt) {
    }

    // ------------------------------------------------------------------ 校验

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("用途名称不能为空");
        String trimmed = name.trim();
        if (trimmed.length() > 64) throw new IllegalArgumentException("用途名称不能超过 64 个字符");
        return trimmed;
    }

    private LocalDateTime parseExpiresAt(String raw) {
        if (raw == null || raw.isBlank()) return null;              // null/空 = 永不过期
        String text = raw.trim();
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // 换下一个格式
            }
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(23, 59, 59);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("过期时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }
}
