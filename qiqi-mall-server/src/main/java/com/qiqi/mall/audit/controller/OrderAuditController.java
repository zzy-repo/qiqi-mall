package com.qiqi.mall.audit.controller;

import com.qiqi.mall.audit.dto.FreezeReasonDto;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.dto.TagCatalogDto;
import com.qiqi.mall.audit.entity.OrderAuditTag;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditFreezeReasonService;
import com.qiqi.mall.audit.service.AuditQueryService;
import com.qiqi.mall.audit.service.AuditTagCatalogService;
import com.qiqi.mall.audit.service.OrderAuditTagService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.oms.service.OrderService;
import com.qiqi.mall.shared.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端-订单自动审核（执行 / 队列 / run 列表 / 单长时间线 / 冻结解冻）。
 *
 * <h3>为什么这些路径全在 {@code /admin/order-audit/**} 下，而不是 {@code /admin/orders/{id}/…}</h3>
 * {@link com.qiqi.mall.shared.security.ApiKeyAuthFilter} 按**路径前缀（最长优先）**匹配，不看方法：
 * 挂在 {@code /admin/orders} 下面就会被那条规则先接住 ——
 * {@code GET …/audit-trail} 落成 {@code order:read}、{@code POST …/audit-freeze} 落成 {@code order:write}，
 * 于是**任何带 order:write 的 API Key 都能冻结/解冻订单**，与「AI 绝不能解冻」直接冲突。
 * 挪到独立前缀后：读方法要 {@code audit:read}（可授予），写方法要 {@code audit:write}（不存在 ⇒ 永远 403）。
 *
 * <p>冻结解冻也刻意不放进 {@code OrderController}：它们改的是**审核标**（{@code order_audit_tag}）
 * 而不是 {@code order.status} —— 冻一张单不推进状态机，订单仍停在 7 待审核。
 * 放在订单控制器里最容易被人顺手写成 {@code setStatus(...)} —— 那正是要避免的事。
 */
@Tag(name = "管理端-订单自动审核", description = "触发 run、人工队列、审核时间线、冻结/解冻")
@RestController
@RequestMapping("/admin")
public class OrderAuditController {

    private final AuditExecutor executor;
    private final AuditQueryService queryService;
    private final OrderAuditTagService tagService;
    private final AuditFreezeReasonService freezeReasonService;
    private final AuditTagCatalogService tagCatalogService;
    private final OrderService orderService;

    public OrderAuditController(AuditExecutor executor, AuditQueryService queryService,
                                OrderAuditTagService tagService, OrderService orderService,
                                AuditFreezeReasonService freezeReasonService,
                                AuditTagCatalogService tagCatalogService) {
        this.executor = executor;
        this.queryService = queryService;
        this.tagService = tagService;
        this.orderService = orderService;
        this.freezeReasonService = freezeReasonService;
        this.tagCatalogService = tagCatalogService;
    }

    /**
     * 人工队列：每单最新一轮决策，可按 result 过滤（MANUAL/BLOCKED/FROZEN/NO_MATCH/AUTO_PASS），
     * 也可按 {@code tag} 过滤（标码，如 {@code ?tag=NO_POLICY_MATCH}）——两个条件正交、可并列。
     *
     * <p>每行带三件互相独立的事：{@code result}（本轮判了什么）、{@code frozen}/{@code frozenReason}
     * （现在是否冻着）、{@code tags}（现在身上有哪些标，含中文名/说明/打标时间）。
     * {@code ?tag=} 是期 1 **平行新增**的筛选入口，本期不改 result 那六个页签的语义（期 3 才改）。
     *
     * <p>过滤参数仍接受任意 result 字符串（含历史数据里可能存在的 SKIPPED），但新跑的轮次不再产生
     * SKIPPED 决策行（见 {@code AuditResult#SKIPPED}），所以按 SKIPPED 过滤通常为空集合。
     */
    @GetMapping("/order-audit/queue")
    public Result<Map<String, Object>> queue(@RequestParam(required = false) String result,
                                             @RequestParam(required = false) String tag,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(queryService.queue(result, tag, page, size));
    }

    /** 立即触发一轮（MANUAL）：返回 runId 与五类计数 */
    @PostMapping("/order-audit/run")
    public Result<RunResultDto> run() {
        return Result.ok(executor.run("MANUAL", null));
    }

    @GetMapping("/audit-runs")
    public Result<Map<String, Object>> runs(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(queryService.runs(page, size));
    }

    /** 单张订单的审核时间线：决策 + 状态台账 + 操作审计三段按时间拼 */
    @GetMapping("/order-audit/orders/{id}/trail")
    public Result<List<Map<String, Object>>> trail(@PathVariable Long id) {
        return Result.ok(queryService.trail(id));
    }

    /**
     * 冻结原因字典（下拉 / 报表用）。
     *
     * <p>为什么挂在 {@code /admin/order-audit/**} 而不是新开一个前缀：
     * {@link com.qiqi.mall.shared.security.ApiKeyAuthFilter} 对**未登记前缀一律 fail-closed（403）**，
     * 而这块已经在 {@code audit:read}（读）/ {@code audit:write}（写）规则下 ——
     * 字典读取属于审核域读，将来维护页的写正好落进 {@code audit:write}（对任何 API Key 都不可授予，
     * 与「AI 不能改审核配置」同一条红线）。开新前缀就得再写一条规则，且容易写成默认可写。
     *
     * @param all false/缺省 = 只回「启用的 + 允许人工选的」（下拉就这么用）；
     *            true = 回全量（含停用项与「未分类/历史未填」这类兜底项，**报表与维护页必须用它**，
     *            否则"无法归因的那几单"会从统计里消失）
     */
    @GetMapping("/order-audit/freeze-reasons")
    public Result<List<FreezeReasonDto>> freezeReasons(@RequestParam(required = false) Boolean all) {
        boolean includeAll = Boolean.TRUE.equals(all);
        return Result.ok((includeAll ? freezeReasonService.all() : freezeReasonService.selectable())
                .stream().map(FreezeReasonDto::of).toList());
    }

    /**
     * 标目录（{@code audit_tag_catalog}）：标码 → 中文名 / 一句话说明 / 分组 / 是否阻断。
     *
     * <p>前端**不许**自己抄一份标码常量表 —— 抄一份的下场是"后端有 NO_POLICY_MATCH、前端没有"，
     * 显示成空白英文码且没人看得出来（和字段注册表同一条纪律）。
     *
     * @param all false/缺省 = 只回「启用 + 业务可见」的（队列/抽屉用）；true = 全量（含停用/仅排障可见）
     */
    @GetMapping("/order-audit/tags")
    public Result<List<TagCatalogDto>> tags(@RequestParam(required = false) Boolean all) {
        return Result.ok(Boolean.TRUE.equals(all)
                ? tagCatalogService.all().stream().map(TagCatalogDto::of).toList()
                : tagCatalogService.visible());
    }

    /**
     * 新增一条自定义标（期 1：字典**从一开始就能新增**，否则人工遇到新情况只能写自由文本）。
     *
     * <p>只做「新增」：改名 / 停用 / 排序是期 2 维护页的事。
     * 新增出来的项 {@code builtin=0}（可停用、不可删除），category 固定「特征」
     * （「结论」标由判定链产出，人工自建一个永远不会出现的字典项没有意义）；code 缺省自动生成。
     */
    @PostMapping("/order-audit/tags")
    public Result<TagCatalogDto> createTag(@RequestBody TagCreateRequest body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空（至少要有 name）");
        return Result.ok(TagCatalogDto.of(tagCatalogService.createCustom(
                body.code(), body.name(), body.description(), body.category(), body.blocking())));
    }

    /**
     * 人工给订单打一个标（特征标：客诉客户 / 双地址 / 绿旗急发…），并写审计 {@code AUDIT_TAG_ADD}。
     *
     * <p>约束（见 {@code OrderAuditTagService#applyManualTag}）：标码必须来自标目录、幂等、
     * 必须留审计；**不许人工打「结论」标**（那等于伪造一条判定结论的证据，要按住这单请用冻结）。
     */
    @PostMapping("/order-audit/orders/{id}/tags")
    public Result<OrderAuditTag> addTag(@PathVariable Long id, @RequestBody TagApplyRequest body) {
        Order order = requireOrder(id);
        return Result.ok(tagService.applyManualTag(order,
                body == null ? null : body.tagCode(), body == null ? null : body.note()));
    }

    /** 人工冻结：打标 + 审计（不动 status，不写状态台账） */
    @PostMapping("/order-audit/orders/{id}/freeze")
    public Result<OrderAuditTag> freeze(@PathVariable Long id, @RequestBody(required = false) FreezeRequest body) {
        Order order = requireOrder(id);
        String tag = body == null || body.tagCode() == null || body.tagCode().isBlank()
                ? OrderAuditTagService.TAG_FROZEN : body.tagCode().trim();
        String reasonCode = body == null ? null : body.reasonCode();
        // note 是新的（补充说明）；reason 是旧字段名，仍接受 —— 老前端/脚本不该因为这次改造失效
        String note = body == null ? null : (body.note() != null && !body.note().isBlank()
                ? body.note() : body.reason());
        if (note == null || note.isBlank()) note = "人工冻结";
        return Result.ok(tagService.freeze(order, tag, reasonCode, note.trim(), "人工"));
    }

    /** 人工解冻：解除所有 FROZEN 类标 + 审计；下一轮 run 才会重新判定 */
    @PostMapping("/order-audit/orders/{id}/unfreeze")
    public Result<Map<String, Object>> unfreeze(@PathVariable Long id) {
        Order order = requireOrder(id);
        int released = tagService.unfreeze(order);
        return Result.ok(Map.of("released", released, "orderId", id));
    }

    private Order requireOrder(Long id) {
        Order order = orderService.getById(id);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在：" + id);
        }
        return order;
    }

    /**
     * 冻结请求体。
     *
     * @param tagCode    默认 FROZEN（也可给 FROZEN_RETURN_APPLY 这类细分标）
     * @param reasonCode 冻结原因码（{@code audit_freeze_reason.code}）—— **新前端应当给这个**；
     *                   这是 P1「不再以此原因冻结该单」的键，也是统计口径。给了就按它算，
     *                   不给则按 {@code note/reason} 的人话反查，再不行落「未分类（待归类）」
     * @param note       补充说明（运营手打的那句人话；进 {@code order_audit_tag.reason} 与审计摘要）
     * @param reason     旧字段（= 旧的自由文本原因），保留兼容：没给 note 时当 note 用
     */
    public record FreezeRequest(String tagCode, String reasonCode, String note, String reason) {
    }

    /**
     * 新增自定义标请求。
     *
     * @param code        可选；为空则自动生成 {@code CUSTOM_<id>}。给了就必须是
     *                    {@code ^[A-Z][A-Z0-9_]{1,31}$}（code 是永不修改的稳定键，别让它长成一句人话）
     * @param name        中文名（**必填** —— 它就是业务人员在队列里看到的那几个字）
     * @param description 一句话说明（给业务看：这标什么意思、下一步做什么）
     * @param category    只接受「特征」（缺省即它）；「结论」标由判定链产出，人工不能自建
     * @param blocking    1 = 会拦住下单往下走（缺省 0：人工打的标默认不拦流程）
     */
    public record TagCreateRequest(String code, String name, String description,
                                   String category, Integer blocking) {
    }

    /**
     * 人工打标请求。
     *
     * @param tagCode 标码（必须已在标目录里且启用；不许自由文本）
     * @param note    备注（可选；进 {@code order_audit_tag.reason} 与审计摘要）
     */
    public record TagApplyRequest(String tagCode, String note) {
    }
}
