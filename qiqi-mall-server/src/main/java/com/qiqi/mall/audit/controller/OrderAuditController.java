package com.qiqi.mall.audit.controller;

import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.entity.OrderAuditTag;
import com.qiqi.mall.audit.service.AuditExecutor;
import com.qiqi.mall.audit.service.AuditQueryService;
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
 * <p>冻结解冻也刻意不放进 {@code OrderController}：它们改的是**审核标**不是订单状态（决策 J1）。
 * 放在订单控制器里最容易被人顺手写成 {@code setStatus(...)} —— 那正是要避免的事。
 */
@Tag(name = "管理端-订单自动审核", description = "触发 run、人工队列、审核时间线、冻结/解冻")
@RestController
@RequestMapping("/admin")
public class OrderAuditController {

    private final AuditExecutor executor;
    private final AuditQueryService queryService;
    private final OrderAuditTagService tagService;
    private final OrderService orderService;

    public OrderAuditController(AuditExecutor executor, AuditQueryService queryService,
                                OrderAuditTagService tagService, OrderService orderService) {
        this.executor = executor;
        this.queryService = queryService;
        this.tagService = tagService;
        this.orderService = orderService;
    }

    /**
     * 人工队列：每单最新一轮决策，可按 result 过滤（MANUAL/BLOCKED/FROZEN/NO_MATCH/AUTO_PASS）。
     *
     * <p>过滤参数仍接受任意 result 字符串（含历史数据里可能存在的 SKIPPED），但新跑的轮次不再产生
     * SKIPPED 决策行（见 {@code AuditResult#SKIPPED}），所以按 SKIPPED 过滤通常为空集合。
     */
    @GetMapping("/order-audit/queue")
    public Result<Map<String, Object>> queue(@RequestParam(required = false) String result,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(queryService.queue(result, page, size));
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

    /** 人工冻结：打标 + 审计（不动 status，不写状态台账） */
    @PostMapping("/order-audit/orders/{id}/freeze")
    public Result<OrderAuditTag> freeze(@PathVariable Long id, @RequestBody(required = false) FreezeRequest body) {
        Order order = requireOrder(id);
        String tag = body == null || body.tagCode() == null || body.tagCode().isBlank()
                ? OrderAuditTagService.TAG_FROZEN : body.tagCode().trim();
        String reason = body == null || body.reason() == null || body.reason().isBlank()
                ? "人工冻结" : body.reason().trim();
        return Result.ok(tagService.freeze(order, tag, reason, "人工"));
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

    /** 冻结请求体：tagCode 默认 FROZEN（也可给 FROZEN_RETURN_APPLY 这类细分标） */
    public record FreezeRequest(String tagCode, String reason) {
    }
}
