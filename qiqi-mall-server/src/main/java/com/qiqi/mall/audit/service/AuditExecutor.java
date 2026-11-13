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
 *   <li>落 {@code order_audit_decision}（每单每轮一行）—— <b>SKIPPED 不落行</b>（见下）；</li>
 *   <li>回写五类计数 + 写一条 {@code ACTION_AUDIT_RUN} 审计。</li>
 * </ol>
 *
 * <h3>SKIPPED 为什么不落决策行</h3>
 * 候选范围刻意包含「有 active FROZEN 标的单」（否则「已冻结」这条判定分支永远不会触发），
 * 于是冻结单每跑一轮就会写一行「本轮跳过」—— 什么都没发生，但 {@code round_no} 一直涨、
 * 证据表被无意义的行撑大，而「它为什么一直没人审」这件事 {@code order_audit_tag} 早就答了。
 * 所以：SKIPPED 只累加 run 的 {@code skipped} 计数（接口返回体 + 审计文本 + 日志），一行都不写。
 * 副作用（正面）：队列取每单 {@code MAX(id)} 那行，冻结单在队列里会继续显示「上一轮的真结论
 * + 冻结标」，而不是被一行 SKIPPED 顶掉。
 *
 * <h3>计数自洽（硬约束，违反就整轮回滚）</h3>
 * 每条决策行<b>恰好</b>落在五类之一（SKIPPED 没有行），所以
 * {@code auto+manual+blocked+frozen+noMatch == 决策行数}，而
 * {@code total == 五类之和 + skipped}（本轮候选范围单数）。见 {@code RunResultDto}。
 * 三支转人工来路（被排除 / STOCK_SHORT / COST_MISSING）在 result 上是同一个 MANUAL，
 * 只在审计文本里拆开说，<b>不参与计数</b> —— 一参与就会与五类之和打架。
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
        Tally tally = new Tally();

        for (Long orderId : candidates) {
            Order order = orderMapper.selectById(orderId);
            if (order == null) continue;                     // 并发删单：本轮没有它
            AuditContext ctx = contextLoader.load(order, now);
            AuditVerdict verdict = engine.evaluate(ctx, policies, auditSetting);

            // SKIPPED：不写决策行、不动订单，只记「本轮跳过了它」（见类注释）
            if (verdict.result() == AuditResult.SKIPPED) {
                tally.skipped++;
                log.debug("自动审核跳过订单 {}：{}", order.getOrderSn(), verdict.reasonText());
                continue;
            }

            int round = decisionMapper.selectMaxRound(orderId) + 1;
            applyActions(order, verdict, run.getId());
            insertDecision(order, verdict, run.getId(), round);
            tally.count(verdict);
            rows.add(new DecisionRowDto(orderId, order.getOrderSn(), round, verdict.result().name(),
                    verdict.matchedPolicy() == null ? null : verdict.matchedPolicy().name(),
                    verdict.wouldBePolicy() == null ? null : verdict.wouldBePolicy().name(),
                    verdict.downgradeReason(), verdict.reasonText(),
                    // 店铺 / 渠道 / 毛利 / 种类数：执行时本来就在手上（order + 上下文），不额外查库；
                    // 队列行给的是同一组事实，页面两者对齐显示才不会一列有一列没有
                    ctx.shopName(), ctx.channel(), ctx.itemKindCount(), ctx.grossProfit()));
        }

        // 计数自洽的硬校验：每条决策行恰好属于五类之一（SKIPPED 没有行）——
        // 一旦哪天新增了结论分支却忘了归入某一类，这里会当场炸掉整轮，而不是让页面上的数字慢慢对不平
        if (tally.verdictSum() != rows.size()) {
            throw new IllegalStateException("审核计数与决策行不一致：五类之和 " + tally.verdictSum()
                    + " ≠ 决策行 " + rows.size() + "（新增结论必须归入五类之一，或明确不进决策行）");
        }
        int total = tally.total();
        runMapper.bumpCounters(run.getId(), total, tally.autoPass, tally.manual, tally.blocked,
                tally.frozen, tally.noMatch);
        run.setTotal(total);
        run.setAutoPass(tally.autoPass);
        run.setManual(tally.manual);
        run.setBlocked(tally.blocked);
        run.setFrozen(tally.frozen);
        run.setNoMatch(tally.noMatch);

        auditWriter.record(new AdminAuditWriter.Event(
                ReasonCodes.ACTION_AUDIT_RUN, ReasonCodes.TARGET_ORDER, null, null,
                "自动审核 run #" + run.getId() + "（" + trigger + "）：范围内 " + total + " 单 —— 自动过审 "
                        + tally.autoPass + "，转人工 " + tally.manual + tally.manualBreakdown()
                        + "，拦截 " + tally.blocked + "，冻结 " + tally.frozen
                        + "，无匹配 " + tally.noMatch + "，未参与 " + tally.skipped + "（不写决策行）",
                null, null, null,
                AdminAuditWriter.snapshot("run_id", run.getId(), "trigger", trigger)));

        log.info("自动审核 run #{}（{}）：total={} auto={} manual={}{} blocked={} frozen={} no_match={} skipped={}",
                run.getId(), trigger, total, tally.autoPass, tally.manual, tally.manualBreakdown(),
                tally.blocked, tally.frozen, tally.noMatch, tally.skipped);
        return new RunResultDto(run.getId(), trigger, total, tally.autoPass, tally.manual, tally.blocked,
                tally.frozen, tally.noMatch, tally.skipped, rows);
    }

    /**
     * 一轮的计数账本。存在的理由：五类计数 + skipped 是<b>同一件事的两种视角</b>
     * （total = 五类 + skipped），散在 5 个局部 int 里就容易出现「某一类忘了 ++」这种
     * 只有用户对账时才发现的错。集中一处之后，{@link #verdictSum()} 与 {@link #total()}
     * 的关系是代码写死的，不是靠调用方记住。
     */
    private static final class Tally {
        int autoPass, manual, blocked, frozen, noMatch, skipped;
        /** 转人工的三支来路（只用于审计文本里把「2 笔转人工」说清是哪两支，**不参与计数**） */
        int manualExcluded, manualStockShort, manualCostMissing;

        void count(AuditVerdict verdict) {
            switch (verdict.result()) {
                case AUTO_PASS -> autoPass++;
                case MANUAL -> {
                    manual++;
                    // STOCK_SHORT 是 MANUAL 的 downgrade 子类：result 仍是 MANUAL、只在这里记一句来源
                    if (AuditVerdict.DOWNGRADE_STOCK_SHORT.equals(verdict.downgradeReason())) {
                        manualStockShort++;
                    } else if (AuditVerdict.DOWNGRADE_COST_MISSING.equals(verdict.downgradeReason())) {
                        manualCostMissing++;
                    } else {
                        manualExcluded++;
                    }
                }
                case BLOCKED -> blocked++;
                case FROZEN -> frozen++;
                case NO_MATCH -> noMatch++;
                case SKIPPED -> skipped++;
            }
        }

        /** 五类之和 == 本轮决策行数 */
        int verdictSum() {
            return autoPass + manual + blocked + frozen + noMatch;
        }

        /** 本轮候选范围单数 = 五类之和 + skipped */
        int total() {
            return verdictSum() + skipped;
        }

        String manualBreakdown() {
            return "（被排除 " + manualExcluded + " / 库存不足 " + manualStockShort
                    + " / 成本缺失 " + manualCostMissing + "）";
        }
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
                // BLOCKED / NO_MATCH：不动订单（拦是结论，不是动作）。
                // SKIPPED 走不到这里 —— 它在 doRun 里就 continue 了（不落决策行，也就不需要动作）
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
        // SKIPPED 不写决策行、audit_run 也没有 skipped 列 → 用「total − 五类之和」还原（两个口径互为补数，
        // 见 RunResultDto 的 total 定义）。这里不再数决策行里的 SKIPPED：那是旧实现的做法，现在恒为 0，
        // 数出来会把「本轮跳过 1 笔」重放成 0，等于撒谎。
        int skipped = Math.max(0, nz(run.getTotal())
                - (nz(run.getAutoPass()) + nz(run.getManual()) + nz(run.getBlocked())
                + nz(run.getFrozen()) + nz(run.getNoMatch())));
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
