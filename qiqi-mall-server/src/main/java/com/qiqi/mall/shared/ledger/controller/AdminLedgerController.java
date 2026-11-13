package com.qiqi.mall.shared.ledger.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiqi.mall.shared.common.Result;
import com.qiqi.mall.shared.ledger.dto.LedgerRow;
import com.qiqi.mall.shared.ledger.service.LedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端「台账查询」：只读地翻六本账（订单/包裹/售后状态台账 + 后台审计 + WMS 收发 + 库存流水）。
 *
 * <p>契约（前端 {@code admin-web/src/api/ledger.js} 已定稿，别改参数名）：
 * <pre>
 * GET /admin/ledgers?page=&amp;size=&amp;type=&amp;bizId=&amp;operatorType=&amp;from=&amp;to=
 *   → Result&lt;Page&lt;LedgerRow&gt;&gt;   （JSON: records/total/size/current/pages）
 * </pre>
 * <ul>
 *   <li><b>分页参数必须是 {@code page}/{@code size}</b>（不是 MP 默认的 current/pageSize），
 *       {@code size} 夹在 1..50（与 {@code OrderController#list} 同一口径；MP 的
 *       {@code PaginationInnerInterceptor} 另有一道 {@code maxLimit=100} 兜底）；</li>
 *   <li>{@code type} 留空 = 查全部六类；给了就是单选（前端目前是单选下拉）；</li>
 *   <li>{@code from}/{@code to} 形如 {@code 'YYYY-MM-DD HH:mm:ss'}（按 {@code LocalDateTime} 解析，
 *       另容忍纯日期与 ISO 两种写法，见 {@link LedgerQueryService#parseTime}）。</li>
 * </ul>
 *
 * <p><b>这是只读接口</b>：六本账都是只追加证据表，没有任何写入口，所以这里只有 GET。
 * 它也是全站唯一「一张单/一个包裹到底怎么走到今天」的入口 —— 排查问题时先查它。
 *
 * <p>API Key（AI）访问：<b>刻意没有登记到 {@code ApiKeyAuthFilter.ADMIN_RULES}</b>，
 * 于是 AI 调用会被兜底规则拦成 403。理由见交付报告（台账里有全站审计流水与操作人快照，
 * 现有 scope 里没有能准确表达它的那一档，挂在 order:read / catalog:read 上是越权）。
 */
@Tag(name = "管理端-台账查询", description = "六本台账的统一只读检索：订单/包裹/售后状态、后台审计、WMS 收返、库存流水")
@RestController
@RequestMapping("/admin/ledgers")
public class AdminLedgerController {

    /** 单页上限：与订单列表一致（页面每页最多也就 50 行，再大只会拖慢这条 UNION 查询） */
    private static final long MAX_SIZE = 50;

    private final LedgerQueryService ledgerQueryService;

    public AdminLedgerController(LedgerQueryService ledgerQueryService) {
        this.ledgerQueryService = ledgerQueryService;
    }

    /**
     * 台账分页查询（按发生时间倒序）。
     *
     * <p>{@code bizId} 的口径：单类型时 = 该类型自己的业务主键（订单 id / 包裹 id / 售后 id …）；
     * 全部类型时 = 「一根主键的全链路」—— 订单分支按订单 id、售后分支按冗余 order_id、
     * 包裹与 WMS 分支经 {@code delivery_order.order_id} 搭桥反查（视图的包裹分支没有 order_id）。
     * 所以「订单 29 的台账」用一条不带 type 的请求就能一次查全（含它的包裹与售后）。
     */
    @Operation(summary = "台账分页查询（六类台账 UNION；type 留空查全部，bizId 支持订单/包裹/售后全链路）")
    @GetMapping
    public Result<Page<LedgerRow>> list(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "10") long size,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) Long bizId,
                                        @RequestParam(required = false) String operatorType,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return Result.ok(ledgerQueryService.page(safePage, safeSize, type, bizId, operatorType, from, to));
    }
}
