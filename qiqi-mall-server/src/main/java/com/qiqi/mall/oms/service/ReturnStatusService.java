package com.qiqi.mall.oms.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiqi.mall.oms.entity.ReturnApply;
import com.qiqi.mall.shared.ledger.ReasonCodes;
import com.qiqi.mall.shared.ledger.StatusLedgerWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 退货申请状态写入的<b>唯一入口</b>（P2 第一步：收敛写点，行为不变）。
 *
 * <p>收敛的对象有两处：
 * <ol>
 *   <li>{@code ReturnApplyController#update} 里那句手写的
 *       {@code UPDATE return_apply SET status=? WHERE id=? AND status=0}
 *       （用具名 {@code LambdaUpdateWrapper} 拼的）—— 它是全项目<b>唯一</b>不走 {@code setStatus}
 *       的状态写点，最容易在「统一入口」改造里被漏掉；</li>
 *   <li>两处「出生」：买家自助申请（{@code PortalReturnController#apply}）、
 *       后台代客登记（{@code ReturnApplyController#create}），都是 {@code status=0 待处理}。</li>
 * </ol>
 *
 * <h3>与订单/包裹入口的关键差别：本表没有 version 列</h3>
 * {@code return_apply} 没有 {@code @Version}，并发闸门是「把「待处理」写进 UPDATE 的 WHERE」：
 * {@code ... WHERE id=? AND status=0}。两个管理员同时审同一张申请时，InnoDB 行锁把两次 UPDATE 排成先后，
 * 后一个的 {@code WHERE status=0} 不再命中 → 影响 0 行 → 409，不会各退一次款。
 *
 * <p>所以这里<b>必须</b>把「读到的状态」写成 WHERE 条件（而不是只做一次 Java 层 {@code if}）——
 * 后者两个请求都能读到 0、都能通过，就退两次款了。这不是新增规则，是把原来那条 SQL 里的守卫
 * 原样搬进入口。
 */
@Service
public class ReturnStatusService {

    private static final Logger log = LoggerFactory.getLogger(ReturnStatusService.class);

    // ---- 事件原因（reason 列口径）----
    // 常量唯一来源是 shared/ledger/ReasonCodes；这里保留别名，P2 第一步的调用点不用改。
    public static final String REASON_CREATE = ReasonCodes.RETURN_CREATE;
    public static final String REASON_IMPORT = ReasonCodes.RETURN_IMPORT;
    public static final String REASON_APPLY = ReasonCodes.RETURN_APPLY;
    public static final String REASON_APPROVE = ReasonCodes.RETURN_APPROVE;
    public static final String REASON_REJECT = ReasonCodes.RETURN_REJECT;

    /** 出生：新申请只能是「待处理(0)」（通过/拒绝是审核结论，只能由审核入口写入） */
    private static final Set<Integer> BIRTH = Set.of(0);

    /** 流转表：0 待处理 → 1 通过 / 2 拒绝（两个都是终局） */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            0, Set.of(1, 2),
            1, Set.of(),
            2, Set.of());

    private static final String STATUS_SEQ_INCREMENT = "status_seq = status_seq + 1";

    private final ReturnApplyService returnApplyService;
    /** 状态台账写入口（与状态 UPDATE 同一事务；开关关闭时它自己 no-op） */
    private final StatusLedgerWriter ledger;

    public ReturnStatusService(ReturnApplyService returnApplyService, StatusLedgerWriter ledger) {
        this.returnApplyService = returnApplyService;
        this.ledger = ledger;
    }

    /** 出生：给**尚未落库**的新申请定初始状态（{@code save} 之前调用，不产生 UPDATE） */
    public void init(ReturnApply apply, int to) {
        if (!BIRTH.contains(to)) {
            throw new IllegalStateException("新建退货申请只能是「待处理」状态，收到 " + to);
        }
        apply.setStatus(to);
    }

    /**
     * 出生台账（{@code from_status = NULL}，并写下 {@code order_id} 冗余）：必须在 {@code save()}
     * 之后、同一事务内调用（幂等键 {@code {returnApplyId}:0} 要真实 id）。
     *
     * <p>后台直接写入历史结论（status=1/2）时同样要记出生：
     * 那一行也是「一张申请从无到有」，只是没有再经过流程（reason 用
     * {@link ReasonCodes#RETURN_IMPORT}，与正常申请区分得开）。
     */
    public void recordBirth(ReturnApply apply, String reason, String reasonDetail) {
        ledger.returnBirth(apply, reason, reasonDetail);
    }

    /** 流转（不带附带列） */
    public int change(ReturnApply apply, int to, String reason, String reasonDetail) {
        return change(apply, to, reason, reasonDetail, null);
    }

    /**
     * 流转：一条 UPDATE 写 {@code status} + {@code status_seq+1}，条件带 {@code id} 与<b>读到的状态</b>。
     *
     * @param extraSets 需要与状态同一条 UPDATE 一起写的其它列（审核意见 {@code description} 走这里）
     * @return 影响行数；0 = 这条申请刚被别人审过 / 被删了 → 调用方抛 409
     *         （{@code return_apply} 没有 version 列，0 行不可能是版本冲突，但错误码沿用 409 + 「刷新」文案，
     *         与旧实现逐字一致）
     */
    public int change(ReturnApply apply, int to, String reason, String reasonDetail,
                      Consumer<LambdaUpdateWrapper<ReturnApply>> extraSets) {
        return change(apply, to, reason, reasonDetail, extraSets, null, null);
    }

    /** 流转（带触发单据）：refType/refId 记这次流转被哪张单据触发 */
    public int change(ReturnApply apply, int to, String reason, String reasonDetail,
                      Consumer<LambdaUpdateWrapper<ReturnApply>> extraSets, String refType, Long refId) {
        Integer from = apply.getStatus();
        requireAllowed(apply.getId(), from, to, reason);

        LambdaUpdateWrapper<ReturnApply> update = new LambdaUpdateWrapper<ReturnApply>()
                .eq(ReturnApply::getId, apply.getId())
                // 并发闸门：把「读到的状态」写成 UPDATE 条件（原 SQL 的 AND status=0 原样保留）
                .eq(ReturnApply::getStatus, from)
                .set(ReturnApply::getStatus, to)
                .setSql(STATUS_SEQ_INCREMENT);
        if (extraSets != null) extraSets.accept(update);

        int rows = returnApplyService.getBaseMapper().update(null, update);
        log.debug("退货申请 {} 状态 {} → {}（reason={}，影响 {} 行）", apply.getId(), from, to, reason, rows);
        if (rows > 0) {
            // 台账：只在 UPDATE 成功之后写（0 行 = 申请刚被别人审过/被删 → 调用方 409 回滚）
            ledger.returnChanged(apply, from, to, reason, reasonDetail, refType, refId);
        }
        // 与旧写法一致：**不**回写内存实体（原来那处 wrapper 更新也没有改 exist 的状态）
        return rows;
    }

    /** 合法性判定：0 待处理 → 1 通过 / 2 拒绝；其余箭头现网不存在 */
    private void requireAllowed(Long applyId, Integer from, int to, String reason) {
        if (from == null) {
            throw new IllegalStateException("退货申请 " + applyId + " 没有当前状态，无法流转到 " + to);
        }
        Set<Integer> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException(
                    "非法的退货申请状态流转：" + from + " → " + to + "（reason=" + reason + "，申请 " + applyId + "）");
        }
    }
}
