package com.qiqi.mall.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.audit.dto.DecisionRowDto;
import com.qiqi.mall.audit.dto.RunResultDto;
import com.qiqi.mall.audit.engine.ActionSpec;
import com.qiqi.mall.audit.engine.AuditContext;
import com.qiqi.mall.audit.engine.ConditionTrace;
import com.qiqi.mall.audit.engine.ConditionView;
import com.qiqi.mall.audit.engine.FieldRegistry;
import com.qiqi.mall.audit.engine.Fmt;
import com.qiqi.mall.audit.engine.OpCode;
import com.qiqi.mall.audit.engine.ValueType;
import com.qiqi.mall.audit.engine.AuditEngine;
import com.qiqi.mall.audit.engine.AuditResult;
import com.qiqi.mall.audit.engine.AuditSetting;
import com.qiqi.mall.audit.engine.AuditVerdict;
import com.qiqi.mall.audit.engine.PolicyView;
import com.qiqi.mall.audit.entity.AuditRun;
import com.qiqi.mall.audit.entity.OrderAuditDecision;
import com.qiqi.mall.audit.entity.OrderAuditTag;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行层：把引擎的**纯判定**落成事实。
 *
 * <h3>一次 run 的五件事（同一事务）</h3>
 * <ol>
 *   <li>建 {@code audit_run} 行（决策行要引用 run_id，所以先建行、跑完回写计数）；</li>
 *   <li>逐单装配上下文 → 调 {@link AuditEngine#evaluate}（纯函数）；</li>
 *   <li>按结论执行：AUTO_PASS → {@code OrderStatusService.change(order, 8, AUDIT_AUTO_PASS, …)}
 *       （7→8 与状态台账同一事务）；FROZEN → 打标 + 审计；
 *       <b>然后每种结论都落一个标</b>（{@link ConclusionTags}：MANUAL → AUDIT_PENDING/STOCK_SHORT/COST_MISSING，
 *       BLOCKED → BLOCKED，NO_MATCH → NO_POLICY_MATCH，AUTO_PASS/FROZEN 不额外落）；
 *       <b>最后落策略配的特征标</b>（{@link #settleFeatureTags}，期 2 步骤 2：并行动作，
 *       三种策略类型都可能配，与结论标互斥规则无关）；</li>
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
 * 两道并发保护：进程内 {@link #RUNNING} 原子标记（同一实例重复点「立即执行」直接拒），
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
    /** 标目录：特征标打完之后审计摘要要写中文名（「码 → 中文名 → 备注」的顺序是契约） */
    private final AuditTagCatalogService tagCatalogService;
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
                         OrderAuditTagService tagService, AuditTagCatalogService tagCatalogService,
                         OrderStatusService orderStatusService,
                         OrderSettingService orderSettingService, OrderMapper orderMapper,
                         AuditRunMapper runMapper, OrderAuditDecisionMapper decisionMapper,
                         AuditCandidateMapper candidateMapper, AdminAuditWriter auditWriter, ObjectMapper json) {
        this.policyService = policyService;
        this.contextLoader = contextLoader;
        this.tagService = tagService;
        this.tagCatalogService = tagCatalogService;
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
                // 过审了（7→8）：这张单身上**所有**结论标都不再是当前结论 ——
                // 库存/成本挂起标（旧实现只解这两个）、上一轮拦过它的 BLOCKED、更早的 NO_POLICY_MATCH…
                // 以前只解前两个，于是「BLOCKED 过一轮 → 下一轮自动过审」的单会带着 blocking=1 的 BLOCKED
                // 一路走到下发环节（期 1.6 修）。逐条按 catalog category='结论' 结算，一条 UPDATE 换空。
                tagService.releaseAllConclusionTags(order.getId(), OrderAuditTagService.RELEASED_BY_SUPERSEDED);
            }
            case FROZEN -> {
                ActionSpec action = verdict.matchedPolicy() != null && verdict.matchedPolicy().action() != null
                        ? verdict.matchedPolicy().action()
                        : ActionSpec.freeze(verdict.reasonText());
                // 原因码三级：策略显式配了 code 就用它；只配了人话就按关键词反查（老配置不必升级）；
                // 反查不到落 POLICY_FREEZE_UNSPECIFIED（"策略命中但没填原因"，selectable=0 但报表可见）。
                String code = action.reasonCode() != null && !action.reasonCode().isBlank()
                        ? action.reasonCode()
                        : AuditFreezeReasonService.inferCodeFromText(action.reason());
                if (code == null) code = AuditFreezeReasonService.CODE_UNSPECIFIED;
                // 标码只有一处判定：ConclusionTags#frozenCodeOf（策略没配细分码 → 通用 FROZEN）。
                // 那边同时是「这轮冻结算哪个结论标」的答案，这边是真正写库的人 —— 同一个函数，
                // 就不会出现"结论唯一性以为是 FROZEN、实际落的却是 FROZEN_RETURN_APPLY"的互相挤掉。
                String tagCode = ConclusionTags.frozenCodeOf(verdict);
                // 期 1.6：冻结与人工冻结走**同一条**写路径 —— freeze 内部先 supersede 其他 active 结论标
                // 再落 FROZEN 类标（以前这里和人工冻结都不调 supersede，策略冻的单会并挂上一轮的结论标）。
                // 互斥只此一处，别在下面 settleConclusionTag 里再来一遍（见那里的 early return）。
                tagService.freeze(order, tagCode, code, action.reason(),
                        "自动审核：策略「" + (verdict.matchedPolicy() == null ? "-" : verdict.matchedPolicy().name()) + "」");
            }
            case MANUAL -> {
                // MANUAL 不动订单、不改状态，只落标 —— 落标统一在 settleConclusionTag 里做，
                // 不再在这里分三支 if：
                //   · 库存不足（downgrade=STOCK_SHORT）→ STOCK_SHORT 标挂起，订单保持 7（补货后重跑即可过审，
                //     过审时这个标会被 AUTO_PASS 分支自动解掉）；绝不能判成 4 已关闭，关掉就再也进不来；
                //   · 成本缺失（downgrade=COST_MISSING）→ COST_MISSING 标；
                //   · 被排除条件挡下（无 downgrade）→ AUDIT_PENDING 标（**以前这一支一个标都不落**，
                //     于是"留人工"那 18 条在队列里一片空白）。
                // 三支的判断只有一处：ConclusionTags#codeOf（谁加了新结论就只有那里要改）。
            }
            default -> {
                // BLOCKED / NO_MATCH：不动订单（拦是结论，不是动作），但要落标（见 settleConclusionTag）。
                // SKIPPED 走不到这里 —— 它在 doRun 里就 continue 了（不落决策行，也就不需要动作）
            }
        }
        settleConclusionTag(order, verdict);
        // 并行动作：结论动作照旧，命中时顺手打的特征标也在这里落（期 2 步骤 2）。
        // 顺序放在结论之后：AUTO_PASS 分支会解掉全部结论标，特征标本就不在它的射程内，
        // 但"先结算结论、再打并行标"读起来才是两件独立的事，反过来容易让人以为特征标是结论的一部分。
        settleFeatureTags(order, verdict);
    }

    /**
     * 落「特征」标（期 2 步骤 2 的并行动作）：<b>策略 A 打标 → 下一轮策略 B 读标</b>的起点。
     *
     * <h3>与结论标的三处差别（照抄会错）</h3>
     * <ol>
     *   <li><b>不参与互斥</b>：结论标一单只有一个当前值，特征标可以并存（既刷单又客诉）。
     *       所以这里<b>不调</b> {@code supersedeOtherConclusionTags} —— 调了就会把上一轮的真结论挤掉；</li>
     *   <li><b>{@code reason_id} / {@code reason_code} 必须留空</b>：{@code order_audit_tag.reason_id}
     *       是 {@code audit_freeze_reason} 的外键，特征是"标"不是"冻结原因"；稳定键已经是
     *       {@code tag_code} 本身，再建第三张字典只会多一份要对齐的副本。
     *       所以走的是 {@code ensureActive(orderId, code, reason)} 这个<b>不解析原因字典</b>的重载；</li>
     *   <li><b>{@code reason} 是"为什么打这个标"</b>：策略配了 note（{@code action_json.reason}）就用它，
     *       没配就拼「策略「X」命中：&lt;第一条条件人话&gt;」—— 后者是 AUTO / BLOCK 的常态
     *       （它们的动作里只有特征标，没有 note）。绝不写"自动审核"这种等于没说的话。</li>
     * </ol>
     *
     * <h3>幂等</h3>
     * {@code ensureActive} 先查 active 同码标、没有才 insert，并发下 {@code uk_active} 兜底 ⇒
     * 同一策略跑一百轮也只有一行。审计只在新打上时写一条：审计记的是「谁在什么时候打了这个标」，
     * 第二轮什么都没发生，再写一条"打了标"就是假证据（与 {@code applyManualTag} 同一口径）。
     *
     * <h3>为什么用 {@code activeTags} 先做一次快照</h3>
     * {@code ensureActive} 幂等时会返回既有行，调用方<b>拿不到</b>"这次到底是不是新插入的"。
     * 想避免每轮重复写审计，就得自己先看一眼这张单现在挂着什么。
     * 一单一轮只查一次（不是每个标一次），代价是一次索引查询（{@code idx_order}）。
     */
    private void settleFeatureTags(Order order, AuditVerdict verdict) {
        PolicyView policy = verdict.matchedPolicy();
        ActionSpec action = policy == null ? null : policy.action();
        if (action == null || !action.hasFeatureTags()) return;      // NO_MATCH / SKIPPED：没有"命中的策略"

        String policyName = policy.name();
        String note = action.reason() != null && !action.reason().isBlank()
                ? action.reason().trim()
                : "策略「" + policyName + "」命中：" + firstConditionText(verdict, policy);

        Set<String> alreadyActive = new HashSet<>();
        for (OrderAuditTag t : tagService.activeTags(order.getId())) {
            if (t.getTagCode() != null) alreadyActive.add(t.getTagCode());
        }

        for (String code : action.featureTags()) {
            if (alreadyActive.contains(code)) continue;              // 已在身上：不重复写行、不重复写审计
            tagService.ensureActive(order.getId(), code, note);
            String name = tagCatalogService.nameOf(code);
            // 摘要顺序沿用打标那套「码 → 中文名 → 备注」：AdminAuditWriter 按 255 从尾部裁，
            // 机器要断言的（码）必须在最前面，人写的备注放最后
            String summary = "自动审核：策略「" + policyName + "」命中，给订单 " + order.getOrderSn()
                    + " 打标：标=" + code + "，中文名=" + name
                    + (note.equals(name) ? "" : "（备注：" + note + "）");
            auditWriter.record(new AdminAuditWriter.Event(
                    ReasonCodes.ACTION_AUDIT_TAG_ADD, ReasonCodes.TARGET_ORDER, order.getId(), order.getOrderSn(),
                    summary,
                    null, order.getId(),
                    AdminAuditWriter.snapshot("tagActive", Boolean.FALSE),
                    AdminAuditWriter.snapshot("tagActive", Boolean.TRUE, "tag", code, "tagName", name,
                            "category", AuditTagCatalogService.CATEGORY_FEATURE,
                            "policy", policyName, "note", note)));
            log.info("订单 {} 命中策略「{}」，已打特征标：{}（{}）",
                    order.getOrderSn(), policyName, code, name);
        }
    }

    /**
     * 「第一条条件的人话」—— 策略没配 note 时，用它回答"为什么平白无故打这个标"。
     *
     * <p>优先用引擎的求值留痕（{@code matchedConditions} / {@code excludedBy} 的 {@code text}）：
     * 那是<b>判定时真的算过</b>的那句话（含实际数值），比按配置重新拼一遍更可信。
     * 只有留痕为空（理论上不发生：命中就一定有留痕）才回退到按策略条件拼。
     */
    private static String firstConditionText(AuditVerdict verdict, PolicyView policy) {
        for (List<ConditionTrace> traces : List.of(verdict.matchedConditions(), verdict.excludedBy())) {
            for (ConditionTrace t : traces) {
                if (t != null && t.text() != null && !t.text().isBlank()) return t.text();
            }
        }
        List<ConditionView> conds = policy == null ? List.of() : policy.matchConditions();
        if (conds.isEmpty()) return "（无匹配条件）";
        ConditionView c = conds.get(0);
        FieldRegistry.FieldDef def = FieldRegistry.find(c.fieldCode());
        String label = def == null ? c.fieldCode() : def.label();
        OpCode op = OpCode.of(c.opCode());
        String symbol = op == null ? String.valueOf(c.opCode()) : op.symbol();
        String expected;
        if (c.valueType() == ValueType.LIST) {
            expected = "「" + c.valueText() + "」";
        } else if (c.valueType() == ValueType.NUM) {
            expected = Fmt.num(c.valueNum(), Fmt.isMoney(c.fieldCode()));
        } else if (c.valueType() == ValueType.BOOL) {
            expected = c.valueNum() != null && c.valueNum().signum() != 0 ? "是" : "否";
        } else {
            expected = String.valueOf(c.valueText());
        }
        return label + " " + symbol + " " + expected;
    }

    /**
     * 按结论落标（期 1 的核心：<b>每种结论都留下一个可读、可筛、可聚合的标</b>）。
     *
     * <p>映射只在 {@link ConclusionTags#codeOf} 一处决定，这里只负责"写下去"：
     * <ul>
     *   <li>AUTO_PASS / SKIPPED → 不落（理由见 {@link ConclusionTags} 的映射表）；</li>
     *   <li>MANUAL → AUDIT_PENDING / STOCK_SHORT / COST_MISSING（三支来路靠标拆开）；</li>
     *   <li>BLOCKED → BLOCKED；NO_MATCH → NO_POLICY_MATCH；</li>
     *   <li>FROZEN → 码由 {@code codeOf} 给出，但<b>写库由 {@link OrderAuditTagService#freeze} 独占</b>
     *       （它要同时写 {@code reason_id}/{@code reason_code}），这里不再写第二条（期 1.6）。</li>
     * </ul>
     *
     * <p><b>幂等靠 {@link OrderAuditTagService#ensureActive}</b>：先查 active 同码标、没有才 insert；
     * 并发下靠 {@code uk_active}（虚拟列 {@code active_tag} 的唯一键）兜住并当成"已存在"。
     * 所以同一单同轮跑一百遍也只有一行 —— <b>不产生第二行、也不报错</b>。
     *
     * <p><b>结论标互斥</b>（期 1.5 立规矩，期 1.6 补齐全部路径）：结论标描述的是"<b>当前</b>判定结论"，
     * 而当前结论天然只有一个 —— 一单不可能同时是"无策略命中"和"待人工审核"。所以落新结论标之前，
     * 先把该单其他 active 的结论档标置 {@code active=0}（{@code released_by=SYSTEM:SUPERSEDED} +
     * {@code released_at}），**同一事务**（见 {@link OrderAuditTagService#supersedeOtherConclusionTags}）。
     *
     * <p>期 1.5 只在"引擎落新标"这一条路上做了互斥，另外三条路漏了（期 1.6 实测补上）：
     * 人工冻结 / 策略冻结（都在 {@code OrderAuditTagService#freeze} 里补）和
     * <b>离开待审核时的结算</b>（{@link #applyActions} 的 AUTO_PASS 分支 +
     * {@code OrderController#auditPass} / {@code #auditReject}）。
     *
     * <p>为什么必须互斥（期 1 的教训，别退回去）：不做互斥时，一单从 NO_MATCH 变成 MANUAL 之后
     * {@code NO_POLICY_MATCH} 与 {@code AUDIT_PENDING} 会并列 active，于是
     * {@code ?tag=NO_POLICY_MATCH} 把它算进两类，「这个月多少单无策略命中」当场被高估。
     *
     * <p>⚠️ 这**不违反"只增不删"**：只增不删管的是"事实"（历史行一行不删、解除留痕迹），
     * 不管"当前状态标"。行的 {@code active=0} + {@code released_at/released_by} 就是
     * "它曾经成立过、什么时候因为什么不再成立"的完整记录。
     * <b>特征标</b>（客诉客户/双地址…）不参与互斥：一单可以既刷单又客诉，它们与"过没过"无关。
     *
     * <p>决策行<b>继续照常写</b>（{@link #insertDecision}）：本期是"两个都写"，不是"标取代决策行"。
     * 当前结论的权威答案有两份且必须一致：{@code order_audit_decision.result}（流水）与
     * 那条 active 的结论标（可筛可聚合的状态）。
     */
    private void settleConclusionTag(Order order, AuditVerdict verdict) {
        String code = ConclusionTags.codeOf(verdict);
        if (code == null) return;                       // AUTO_PASS / SKIPPED：不落标
        // FROZEN：码是结论标码（FROZEN 或策略配的细分码），但**写库与互斥都在 freeze() 里做完了**
        // （它必须同时写 reason_id / reason_code，那是冻结原因的键）。这里若再落一次，
        // 会出现"细分码 + 通用 FROZEN"两条冻结标（通用那条 reason_id 为空），把一条结论说成两条。
        // 所以 FROZEN 只在这里「确认它已被 conclude 过」—— 互斥规则本身仍由 OrderAuditTagService 一处管。
        if (verdict.result() == AuditResult.FROZEN) return;
        // 顺序不能反：先让旧结论让位，再落新标 —— 同一事务，要么都成、要么都回滚。
        // 反过来的话，若 supersede 失败（例如目录缺失），新标已经插进去，就成了"两个结论标"。
        tagService.supersedeOtherConclusionTags(order.getId(), code);
        tagService.ensureActive(order.getId(), code, verdict.reasonText());
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
