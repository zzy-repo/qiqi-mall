package com.qiqi.mall.apikey.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.apikey.entity.AiApiKey;
import com.qiqi.mall.apikey.mapper.AiApiKeyMapper;
import com.qiqi.mall.apikey.service.AiApiKeyService;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.security.ApiKeyAuthFilter;
import com.qiqi.mall.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * 中文标签的两个零件：域（冒号前）× 动作（冒号后）。
     *
     * <p>为什么是「拼」而不是「一张 scope → 中文」的表：后一张表就是又抄一遍目录 ——
     * 后端哪天加了 {@code refund:read}，抄本里没有它，页面就退化成显示生值。
     * 按域拼则只要域是认识的（10 个域基本不动），新增 scope 的标签**自动**是对的；
     * 域不认识就原样返回 value，宁可显示生值，也不许编一个像样的中文糊过去。
     */
    private static final Map<String, String> DOMAIN_LABELS = Map.ofEntries(
            Map.entry("order", "订单"),
            Map.entry("catalog", "商品"),
            Map.entry("member", "会员"),
            Map.entry("config", "配置"),
            Map.entry("dashboard", "概览"),
            Map.entry("ledger", "台账"),
            Map.entry("stock", "库存"),
            Map.entry("audit", "审核"),
            Map.entry("channel", "渠道"),
            Map.entry("payment", "支付"));

    /** 动作后缀 → 中文（{@code ALL_SCOPES} 里只有这两种后缀） */
    private static final Map<String, String> ACTION_LABELS = Map.of("read", "读", "write", "写");

    private final AiApiKeyService apiKeyService;
    private final AiApiKeyMapper apiKeyMapper;
    /** 后台操作审计（密钥增删改 = 权限变更，必须留证） */
    private final AdminAuditWriter auditWriter;

    public ApiKeyAdminController(AiApiKeyService apiKeyService, AiApiKeyMapper apiKeyMapper,
                                 AdminAuditWriter auditWriter) {
        this.apiKeyService = apiKeyService;
        this.auditWriter = auditWriter;
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

    /**
     * 权限目录：令牌管理页渲染勾选框的**唯一来源**（前端不再抄一份 scope 清单）。
     *
     * <p>为什么要这个接口：目录原本是前端 {@code scopeCatalog.js} 抄的一份后端镜像，
     * 而后端 2026-11-13 给 MCP 补了 {@code ledger:read / stock:read / audit:read / channel:read}
     * 四个只读域时，抄本没跟上 —— 页面上勾不到这四个域，整整一天只能由接口直接写库。
     * 「抄一遍必然走散」不是自觉问题，所以这里把目录本身变成接口：前端只在运行时读，
     * 后端加一个 scope，页面当天就多一个勾选框，没有第二处需要改。
     *
     * <p>数据**直接**来自 {@link AiApiKeyService#ALL_SCOPES}（13 个可授予 = 9 读 + 4 写）与
     * {@link ApiKeyAuthFilter#unGrantableWriteScopes()}（5 个刻意不可授予的写 scope），
     * 本类不另立清单 —— 这里加一张表，就等于再造一个抄本。
     *
     * <p>权限口径：本组端点整体在 {@code ApiKeyAuthFilter} 的 {@code JWT_ONLY_PREFIXES} 里
     * （{@code /admin/api-keys**}），所以**任何 API Key 都是 403**，这个目录只给管理端 JWT 看，
     * 不挂任何 API Key scope；如果哪天红线放开要给它一个 scope，该是 {@code config:read}
     * （它是权限配置的元数据，不是订单数据，与配置文件同档），不是 {@code order:read}。
     *
     * <p>{@code grantable} 原样回 {@code ALL_SCOPES}（顺序 = 页面顺序），前端拿它当保存白名单：
     * 后端没授予的值，前端连提交都不会提交。
     */
    @GetMapping("/scopes")
    public Result<ScopeCatalog> scopes() {
        List<ScopeOption> read = new ArrayList<>();
        List<ScopeOption> write = new ArrayList<>();
        for (String scope : AiApiKeyService.ALL_SCOPES) {
            // ALL_SCOPES 只有 :read / :write 两种后缀；万一将来出现别的后缀，归到「可写」组
            // —— 让它被看见（并随保存进白名单）比静默丢在页面上看不见更安全。
            ScopeOption option = new ScopeOption(scope, labelOf(scope));
            if (scope.endsWith(":read")) read.add(option);
            else write.add(option);
        }
        List<ScopeOption> unGrantable = new ArrayList<>();
        for (String scope : ApiKeyAuthFilter.unGrantableWriteScopes()) {
            unGrantable.add(new ScopeOption(scope, labelOf(scope)));
        }
        return Result.ok(new ScopeCatalog(read, write, unGrantable, AiApiKeyService.ALL_SCOPES));
    }

    /** 新建：明文 key 只在此响应里出现一次 */
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<CreatedKey> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        try {
            String name = normalizeName(body.name());
            List<String> scopes = AiApiKeyService.normalizeScopes(body.scopes());
            LocalDateTime expiresAt = parseExpiresAt(body.expiresAt());
            String createdBy = CurrentUser.get() == null ? "unknown" : CurrentUser.get().username();
            AiApiKeyService.IssuedKey issued = apiKeyService.issue(name, scopes, expiresAt, createdBy);
            AiApiKey row = issued.row();
            // 审计：只记名称/权限范围/过期时间，**绝不记明文或 hash**（台账是只追加表，写进去就去不掉）
            auditWriter.record(new AdminAuditWriter.Event(
                    ReasonCodes.ACTION_APIKEY_CREATE, ReasonCodes.TARGET_APIKEY, row.getId(), row.getName(),
                    "新建 API Key " + row.getName() + "（id=" + row.getId() + "，前缀 " + row.getKeyPrefix() + "）",
                    null, null,
                    null,
                    AdminAuditWriter.snapshot("name", row.getName(), "scopes", row.getScopes(),
                            "expiresAt", String.valueOf(row.getExpiresAt()))));
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
    @Transactional(rollbackFor = Exception.class)
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
        // 0 行 = 这把 Key 在 selectById 之后被别人删了（ai_api_key 无 version 列）→ 409「记录已被删除」。
        // 必须抛在 evict 之前：否则缓存被清掉、库里却什么都没改，下一次请求看起来「生效了」。
        int rows = apiKeyMapper.updateById(update);
        OptimisticLockGuard.requireAffected(rows, OptimisticLockGuard.MSG_RECORD_DELETED);
        apiKeyService.evict(exist);
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_APIKEY_UPDATE, ReasonCodes.TARGET_APIKEY, id, exist.getName(),
                "修改 API Key " + exist.getName() + "（id=" + id + "）的权限/状态/过期时间",
                null, null,
                AdminAuditWriter.snapshot("name", exist.getName(), "scopes", exist.getScopes(),
                        "status", exist.getStatus(), "expiresAt", String.valueOf(exist.getExpiresAt())),
                AdminAuditWriter.snapshot("name", update.getName(), "scopes", update.getScopes(),
                        "status", update.getStatus(), "expiresAt", String.valueOf(update.getExpiresAt()))));
        return Result.ok();
    }

    /** 删除：物理删除。删了记录下次请求就查不到 = 立即吊销（缓存一并失效，不需要黑名单） */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(@PathVariable Long id) {
        AiApiKey exist = apiKeyMapper.selectById(id);
        if (exist == null) return Result.fail("API Key 不存在");
        apiKeyMapper.deleteById(id);
        apiKeyService.evict(exist);
        // 审计（吊销证据）：名称与权限范围留快照，绝不记明文/hash
        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_APIKEY_DELETE, ReasonCodes.TARGET_APIKEY, id, exist.getName(),
                "删除（吊销）API Key " + exist.getName() + "（id=" + id + "）",
                null, null,
                AdminAuditWriter.snapshot("name", exist.getName(), "scopes", exist.getScopes(),
                        "status", exist.getStatus()),
                AdminAuditWriter.snapshot("deleted", Boolean.TRUE)));
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

    /** 目录里的一项：{@code value} 是落库/提交用的 scope 值，{@code label} 是页面上的中文 */
    public record ScopeOption(String value, String label) {
    }

    /**
     * 权限目录：{@code read}（9）+ {@code write}（4）+ {@code unGrantable}（5）。
     * {@code grantable} = read + write 的全部 value，前端拿它当保存白名单与「可勾」判据。
     */
    public record ScopeCatalog(List<ScopeOption> read, List<ScopeOption> write,
                              List<ScopeOption> unGrantable, List<String> grantable) {
    }

    // ------------------------------------------------------------------ 校验

    /** scope → 页面中文：按「域-动作」拼，不维护 scope 级抄本（见 DOMAIN_LABELS 的说明） */
    private static String labelOf(String scope) {
        int sep = scope.indexOf(':');
        if (sep <= 0 || sep == scope.length() - 1) return scope;      // 形态不认识：原样返回
        String domain = DOMAIN_LABELS.get(scope.substring(0, sep));
        String action = ACTION_LABELS.get(scope.substring(sep + 1));
        if (domain == null || action == null) return scope;            // 域/动作不认识：不编中文
        return domain + "-" + action;
    }

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
