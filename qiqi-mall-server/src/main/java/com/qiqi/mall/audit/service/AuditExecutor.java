package com.qiqi.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.audit.dto.DecisionRowDto;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.engine.ActionSpec;
import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditSetting;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.PolicyView;
import com.qiqi.mall.audit.entity.AuditRun;
import com.qiqi.mall.audit.entity.OrderAuditDecision;
import com.qiqi.mall.audit.mapper.AuditCandidateMapper;
import com.qiqi.mall.audit.mapper.AuditRunMapper;
import com.qiqi.mall.audit.mapper.OrderAuditDecisionMapper;
import com.qiqi.mall.oms.entity.OrderSetting;
import com.qiqi.mall.oms.service.OrderSettingService;
import com.qiqi.mall.oms.service.OrderStatusService;
import com.qiqi.mall.order.entity.Order;
import com.qiqi.mall.order.mapper.OrderMapper;
import com.qiqi.mall.shared.guard.OptimisticLockGuard;
import com.qiqi.mall.shared.ledger.AdminAuditWriter;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.security.ActorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行层：把引擎的**纯判定**落成事实。
 *
 * <h3>一次 run 的五件事（同一事务）</h3>
 * <ol>
 *   <li>建 {@code audit_run} 行（决策行要引用 run_id，所以先建行、跑完回写计数）；</li>
 *   <li>逐单装配上下文 → 调 {@link AuditEngine#evaluate}（纯函数）；</li>
 *   <li>按结论执行：AUTO_PASS → {@code OrderStatusService.change(order, 8, AUDIT_AUTO_PASS, …)}
 *       （7→8 与状态台账同一事务）；FROZEN → 打标 + 审计；STOCK_SHORT / COST_MISSING → 打挂起标；</li>
 *   <li>落 {@code order_audit_decision}（每单每轮一行）；</li>
 *   <li>回写五类计数 + 写一条 {@code ACTION_AUDIT_RUN} 审计。</li>
 * </ol>
 *
 * <h3>为什么整轮一个事务</h3>
 * 「批量部分成功」在审核场景里是最糟的形态：一半单 7→8、另一半没动，而 run 行还写着 0。
 * 要么这一轮全部生效，要么全部回滚（人工点重试，决策行靠 {@code uk_order_round} 保证不会重复）。
 * 代价是单轮不能太大 —— 由调度周期（默认 30 分钟）与候选范围（只跑待审核 + 冻结单）控制。
 *
 * <h3>并发</h3>
 * 两道闸：进程内 {@link #RUNNING} 原子标记（同一实例重复点「立即执行」直接拒），
 * 数据库 {@code uk_order_round} / {@code uk_idem}（多实例同时跑时撞键 → 整轮回滚，不留半轮）。
 *
 * <h3>幂等</h3>
 * 只有 SCHEDULE 带 {@code idem_key = SCHEDULE:yyyyMMddHHmm}（同一次调度重投 → 直接返回那一轮）。
 * MANUAL 一律不带：人工点两次就是两次 run，第二次的差异本身是证据（与 admin_audit_log 红线同源）。
 */
@Service
public class AuditExecutor {

    private static final Logger log = LoggerFactory.getLogger(AuditExecutor.class);
    private static final DateTimeFormatter MINUTE_KEY = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 进程内互斥：同一实例不允许两轮审计叠着跑（跨实例靠 DB 唯一键兜底） */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final AuditPolicyService policyService;
    private final AuditContextLoader contextLoader;
    private final OrderAuditTagService tagService;
    private final OrderStatusService orderStatusService;
    private final OrderSettingService orderSettingService;
    private final OrderMapper orderMapper;
    private final AuditRunMapper runMapper;
    private final OrderAuditDecisionMapper decisionMapper;
    private final AuditCandidateMapper candidateMapper;
    private final AdminAuditWriter auditWriter;
    private final ObjectMapper json;
    private final AuditEngine engine = new AuditEngine();

    public AuditExecutor(AuditPolicyService policyService, AuditContextLoader contextLoader,
                         OrderAuditTagService tagService, OrderStatusService orderStatusService,
                         OrderSettingService orderSettingService, OrderMapper orderMapper,
                         AuditRunMapper runMapper, OrderAuditDecisionMapper decisionMapper,
                         AuditCandidateMapper candidateMapper, AdminAuditWriter auditWriter, ObjectMapper json) {
        this.policyService = policyService;
        this.contextLoader = contextLoader;
        this.tagService = tagService;
        this.orderStatusService = orderStatusService;
        this.orderSettingService = orderSettingService;
        this.orderMapper = orderMapper;
        this.runMapper = runMapper;
        this.decisionMapper = decisionMapper;
        this.candidateMapper = candidateMapper;
        this.auditWriter = auditWriter;
        this.json = json;
    }

    /** SCHEDULE 触发的幂等键：{trigger}:{yyyyMMddHHmm}（**不许**由业务键派生） */
    public static String scheduleIdemKey(LocalDateTime at) {
        return "SCHEDULE:" + at.format(MINUTE_KEY);
    }

    /**
     * 跑一轮。
     *
     * @param triggerType MANUAL / SCHEDULE
     * @param idemKey     仅 SCHEDULE 用；MANUAL 传 null
     */
    @Transactional(rollbackFor = Exception.class)
    public RunResultDto run(String triggerType, String idemKey) {
        String trigger = "SCHEDULE".equalsIgnoreCase(triggerType) ? "SCHEDULE" : "MANUAL";
        String key = "SCHEDULE".equals(trigger) ? idemKey : null;

        if (key != null) {
            AuditRun existing = runMapper.selectByIdemKey(key);
            if (existing != null) {
                log.info("自动审核 run 重放（idem_key={}）→ 返回既有 run #{}", key, existing.getId());
                return toResult(existing);
            }
        }
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有一轮自动审核在执行中，请稍后再触发");
        }
        try {
            return doRun(trigger, key);
        } finally {
            RUNNING.set(false);
        }
    }

    private RunResultDto doRun(String trigger, String key) {
        LocalDateTime now = LocalDateTime.now();

        OrderSetting setting = orderSettingService.getOne(null, false);
        AuditSetting auditSetting = setting == null
                ? AuditSetting.defaults()
                : new AuditSetting(setting.getAutoAuditEnabled() == null || setting.getAutoAuditEnabled() == 1,
                AuditSetting.StockGuard.of(setting.getAuditStockGuard()));

        AuditRun run = new AuditRun();
        run.setTriggerType(trigger);
        run.setIdemKey(key);
        run.setTotal(0);
        run.setAutoPass(0);
        run.setManual(0);
        run.setBlocked(0);
        run.setFrozen(0);
        run.setNoMatch(0);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // 同一分钟的 SCHEDULE 被并发投递：另一轮已经在跑 → 返回它
            AuditRun existing = runMapper.selectByIdemKey(key);
            if (existing != null) return toResult(existing);
            throw e;
        }

        List<PolicyView> policies = policyService.loadEnginePolicies();
        List<Long> candidates = candidateMapper.selectCandidateIds();
        List<DecisionRowDto> rows = new ArrayList<>();
        int auto = 0, manual = 0, blocked = 0, frozen = 0, noMatch = 0, skipped = 0;

        for (Long orderId : candidates) {
            Order order = orderMapper.selectById(orderId);
            if (order == null) continue;                     // 并发删单：本轮没有它
            int round = decisionMapper.selectMaxRound(orderId) + 1;
            AuditContext ctx = contextLoader.load(order, now);
            AuditVerdict verdict = engine.evaluate(ctx, policies, auditSetting);

            applyActions(order, verdict, run.getId());
            insertDecision(order, verdict, run.getId(), round);

            switch (verdict.result()) {
                case AUTO_PASS -> auto++;
                case MANUAL -> manual++;
                case BLOCKED -> blocked++;
                case FROZEN -> frozen++;
                case NO_MATCH -> noMatch++;
                case SKIPPED -> skipped++;
            }
            rows.add(new DecisionRowDto(orderId, order.getOrderSn(), round, verdict.result().name(),
                    verdict.matchedPolicy() == null ? null : verdict.matchedPolicy().name(),
                    verdict.wouldBePolicy() == null ? null : verdict.wouldBePolicy().name(),
                    verdict.downgradeReason(), verdict.reasonText(),
                    // 店铺 / 渠道 / 毛利 / 种类数：执行时本来就在手上（order + 上下文），不额外查库；
                    // 队列行给的是同一组事实，页面两者对齐显示才不会一列有一列没有
                    ctx.shopName(), ctx.channel(), ctx.itemKindCount(), ctx.grossProfit()));
        }

        int total = rows.size();
        runMapper.bumpCounters(run.getId(), total, auto, manual, blocked, frozen, noMatch);
        run.setTotal(total);
        run.setAutoPass(auto);
        run.setManual(manual);
        run.setBlocked(blocked);
        run.setFrozen(frozen);
        run.setNoMatch(noMatch);

        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_AUDIT_RUN, ReasonCodes.TARGET_ORDER, null, null,
                "自动审核 run #" + run.getId() + "（" + trigger + "）：共 " + total + " 单，自动过审 " + auto
                        + "，转人工 " + manual + "，拦截 " + blocked + "，冻结 " + frozen
                        + "，无匹配 " + noMatch + "，跳过 " + skipped,
                null, null, null,
                AdminAuditWriter.snapshot("run_id", run.getId(), "trigger", trigger)));

        log.info("自动审核 run #{}（{}）：total={} auto={} manual={} blocked={} frozen={} no_match={} skipped={}",
                run.getId(), trigger, total, auto, manual, blocked, frozen, noMatch, skipped);
        return new RunResultDto(run.getId(), trigger, total, auto, manual, blocked, frozen, noMatch, skipped, rows);
    }

    // ================================================================== 执行动作

    private void applyActions(Order order, AuditVerdict verdict, Long runId) {
        switch (verdict.result()) {
            case AUTO_PASS -> {
                int rows = orderStatusService.change(order, com.qiqi.mall.audit.engine.AuditContext.STATUS_PENDING_SHIP,
                        ReasonCodes.AUDIT_AUTO_PASS, verdict.reasonText(), null, ReasonCodes.REF_AUDIT_RUN, runId);
                OptimisticLockGuard.requireAffected(rows, "订单 " + order.getOrderSn() + " 状态已变化，本轮审核未生效");
                // 过审了：库存/成本挂起标不再成立（复跑时清掉，避免「已过审却还挂着 STOCK_SHORT」）
                tagService.release(order.getId(), OrderAuditTagService.TAG_STOCK_SHORT, "system");
                tagService.release(order.getId(), OrderAuditTagService.TAG_COST_MISSING, "system");
            }
            case FROZEN -> {
                ActionSpec action = verdict.matchedPolicy() != null && verdict.matchedPolicy().action() != null
                        ? verdict.matchedPolicy().action()
                        : ActionSpec.freeze(verdict.reasonText());
                tagService.freeze(order, action.tagCode(), action.reason(),
                        "自动审核：策略「" + (verdict.matchedPolicy() == null ? "-" : verdict.matchedPolicy().name()) + "」");
            }
            case MANUAL -> {
                if (AuditVerdict.DOWNGRADE_STOCK_SHORT.equals(verdict.downgradeReason())) {
                    // 决策 D12：不判 7→4，保持 status=7 + 打 STOCK_SHORT 标挂起
                    tagService.ensureActive(order.getId(), OrderAuditTagService.TAG_STOCK_SHORT, verdict.reasonText());
                } else if (AuditVerdict.DOWNGRADE_COST_MISSING.equals(verdict.downgradeReason())) {
                    tagService.ensureActive(order.getId(), OrderAuditTagService.TAG_COST_MISSING, verdict.reasonText());
                }
            }
            default -> {
                // BLOCKED / NO_MATCH / SKIPPED：不动订单（拦是结论，不是动作）
            }
        }
    }

    // ================================================================== 落决策

    private void insertDecision(Order order, AuditVerdict verdict, Long runId, int round) {
        ActorResolver.Actor actor = ActorResolver.resolve();
        OrderAuditDecision d = new OrderAuditDecision();
        d.setOrderId(order.getId());
        d.setRunId(runId);
        d.setRoundNo(round);
        d.setResult(verdict.result().name());
        if (verdict.matchedPolicy() != null) {
            d.setMatchedPolicyId(verdict.matchedPolicy().id());
            d.setMatchedPolicyName(verdict.matchedPolicy().name());
            d.setMatchedPolicyVersionNo(verdict.matchedPolicy().versionNo());
        }
        d.setMatchedConditionJson(toJson(verdict.matchedConditionMaps().isEmpty() ? null : verdict.matchedConditionMaps()));
        d.setExcludedByJson(toJson(verdict.excludedByMaps().isEmpty() ? null : verdict.excludedByMaps()));
        d.setMissReasonJson(toJson(verdict.missReasonMaps().isEmpty() ? null : verdict.missReasonMaps()));
        if (verdict.wouldBePolicy() != null) {
            d.setWouldBeAutoPolicyId(verdict.wouldBePolicy().id());
            d.setWouldBeAutoPolicyName(verdict.wouldBePolicy().name());
        }
        d.setDowngradeReason(verdict.downgradeReason());
        Map<String, Object> evidence = verdict.evidenceCopy();
        evidence.put("reason", verdict.reasonText());     // 人话原因（含具体数值差）落在证据里
        d.setEvidenceJson(toJson(evidence));
        d.setOrderStatusSeq(order.getStatusSeq());
        d.setOperatorType(actor.type());
        d.setOperatorId(actor.id());
        d.setOperatorName(actor.name());
        d.setIdemKey(order.getId() + ":" + round);
        decisionMapper.insert(d);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 证据序列化失败 = 这条决策不可信：抛出去让整轮回滚，绝不写一条没有证据的决策
            throw new IllegalStateException("审核决策证据序列化失败：" + e.getMessage(), e);
        }
    }

    private RunResultDto toResult(AuditRun run) {
        List<DecisionRowDto> rows = decisionMapper.selectByRun(run.getId()).stream()
                // 重放路径（同 idem_key 的 SCHEDULE 重投）只读决策行：订单侧那 4 列没有快照可给，
                // 给 null 而不是现查一次库 —— 重放的语义是「返回那一轮记下的事实」，不是「重新描述现在」
                .map(d -> new DecisionRowDto(d.getOrderId(), null, d.getRoundNo(), d.getResult(),
                        d.getMatchedPolicyName(), d.getWouldBeAutoPolicyName(), d.getDowngradeReason(),
                        evidenceReason(d.getEvidenceJson()), null, null, null, null))
                .toList();
        int skipped = (int) rows.stream().filter(r -> AuditResult.SKIPPED.name().equals(r.result())).count();
        return new RunResultDto(run.getId(), run.getTriggerType(), nz(run.getTotal()), nz(run.getAutoPass()),
                nz(run.getManual()), nz(run.getBlocked()), nz(run.getFrozen()), nz(run.getNoMatch()), skipped, rows);
    }

    private String evidenceReason(String evidenceJson) {
        if (evidenceJson == null) return null;
        try {
            return json.readTree(evidenceJson).path("reason").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /** 供调度器判断「距上一轮够不够久」 */
    public AuditRun latestRun() {
        return runMapper.selectLatest();
    }

    /** 供调度器读设置的间隔（分钟） */
    public int intervalMinutes() {
        OrderSetting setting = orderSettingService.getOne(null, false);
        if (setting == null || setting.getAuditRunIntervalMin() == null) return 30;
        return Math.max(1, setting.getAuditRunIntervalMin());
    }

    /** 供调度器判断总开关 */
    public boolean autoAuditEnabled() {
        OrderSetting setting = orderSettingService.getOne(null, false);
        return setting == null || setting.getAutoAuditEnabled() == null || setting.getAutoAuditEnabled() == 1;
    }
}
