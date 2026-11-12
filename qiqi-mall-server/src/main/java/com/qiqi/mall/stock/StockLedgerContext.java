package com.qiqi.mall.stock;

import java.util.HashMap;
import java.util.Map;

/**
 * 库存记账上下文（ThreadLocal）：说明「这一次库存变化是谁触发的」。
 *
 * <h3>为什么需要它</h3>
 * 台账要记 {@code orderId / reason / refType / refId}，但 {@code restoreStock(skuId, qty)} 这个
 * 库存接口本身只拿到规格和件数 —— 触发者（哪张订单、哪个入口、哪张单据）只有**调用方**知道。
 * 于是约定：**调用方在动手之前声明上下文，库存接口在里面记账**。
 *
 * <pre>
 * 入口                          reason            refType         refId
 * 下单（OrderTransactionalCreator）  ORDER_CREATE      null            null
 * 审核不通过（OrderController）       AUDIT_REJECT      null            null
 * 取消包裹（DeliveryController）      DELIVERY_CANCEL   DELIVERY        发货单 id
 * 退货退款（ReturnApplyController）   RETURN_REFUND     RETURN_APPLY    退货申请 id
 * </pre>
 *
 * <h3>为什么取消包裹这一条要在 Controller 里声明、而不是进 DeliveryFlowService</h3>
 * {@code DeliveryFlowService#cancel} 里那句 {@code skuService.restoreStock(...)} 是回补的实际发生地，
 * 但那个文件正被另一路改动占用（本次任务铁律：不许改）。所以由**它唯一的入口**
 * {@code DeliveryController#cancel} 声明上下文，回补仍在原来那一句上发生 ——
 * 记账点收敛在库存接口（{@code SkuServiceImpl}）一处，而不是散到每个业务流程里。
 *
 * <h3>没有上下文时怎么办</h3>
 * 直接抛异常（fail-closed）：无台账的库存变更就是这个缺陷本身，宁可报错也不要静默改库存。
 *
 * <h3>「同一次触发内的重复」怎么区分</h3>
 * 一个包裹里可能有两条明细指向同一规格（同一订单两行买同一规格时会出现），
 * 它们必须合并成一行台账、件数相加；而**另一次触发**（重放）必须整笔跳过。
 * 靠 {@link #alreadyTouchedInThisRun} 区分：本次执行内已经记过这个幂等键 → 合并件数；
 * 只有库里已存在该键 → 视为重放，跳过并记 warn。
 */
public final class StockLedgerContext {

    /** 一次库存变更的归属：哪张订单、什么原因、由哪张单据触发 */
    public record Attribution(Long orderId, String reason, String refType, Long refId) {

        /** 幂等键 orderId:skuId:reason:refId（refId 缺省 0）—— 与台账表的 UNIQUE KEY 同口径 */
        public String idemKey(Long skuId) {
            return orderId + ":" + skuId + ":" + reason + ":" + (refId == null ? 0L : refId);
        }
    }

    private static final ThreadLocal<Attribution> CURRENT = new ThreadLocal<>();
    /** 本次执行内已经记过账的幂等键（用于区分「同一次触发的第二条明细」与「重复触发」） */
    private static final ThreadLocal<Map<String, Integer>> TOUCHED =
            ThreadLocal.withInitial(HashMap::new);

    private StockLedgerContext() {
    }

    /**
     * 声明本次库存变更的归属。**必须与 {@link #close()} 成对使用（finally）**。
     * 支持嵌套调用（内层的 close 会还原外层的归属）。
     */
    public static Attribution open(Long orderId, String reason, String refType, Long refId) {
        Attribution previous = CURRENT.get();
        CURRENT.set(new Attribution(orderId, reason, refType, refId));
        TOUCHED.set(new HashMap<>());            // 新的一次触发：重复判定从头开始
        return previous;
    }

    /** 结束声明；传回 {@link #open} 的返回值可以还原外层上下文（没有外层就传 null） */
    public static void close(Attribution previous) {
        if (previous == null) {
            CURRENT.remove();
            TOUCHED.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** 当前归属；没有声明时为 {@code null}（{@code StockLedgerService} 会据此 fail-closed） */
    public static Attribution current() {
        return CURRENT.get();
    }

    /** 本次执行里是否已经记过这个幂等键（true = 同一触发内的第二条明细，件数合并） */
    public static boolean alreadyTouchedInThisRun(String idemKey) {
        return TOUCHED.get().containsKey(idemKey);
    }

    /** 标记「这个幂等键本次执行内已记账」 */
    public static void markTouched(String idemKey) {
        TOUCHED.get().put(idemKey, 1);
    }
}
