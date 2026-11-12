package com.qiqi.mall.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SKU 库存台账（一条 = 一次库存变化的记账）。
 *
 * <p>为什么需要它：修「同一件货被回补两次」这个缺陷。原实现里「审核不通过（按整单回补）」
 * 与「取消包裹（按包裹回补）」都是裸的 {@code UPDATE sku SET stock = stock + N}，
 * 没有记录 / 没有查重 / 没有互斥 —— 两条入口叠加就补两次，库存虚增。
 * 现在**每一次扣/补都先写一行台账再动库存桶（{@code sku_bucket}）**：
 * <ul>
 *   <li>{@code idem_key} = {@code orderId:skuId:reason:refId}（refId 缺省 0）UNIQUE ——
 *       同一个触发（同一张包裹 / 同一张退货申请 / 同一次审核不通过）重放时，第二遍一律跳过；</li>
 *   <li>{@code delta} 正数=回补、负数=扣减 —— 把台账按 (order_id, sku_id) 求和就能对账；</li>
 *   <li>{@code bucket_no} 记「这次扣/补落在哪个桶」（分桶后新增）：回补据此回到原桶，
 *       但它<b>不参与守恒计算</b> —— 守恒只看 delta 的和；</li>
 *   <li>同一 (order_id, sku_id) 的「已回补总量」被夹紧在「该订单该规格的下单总量」以内
 *       （数量守恒，见 {@code StockLedgerService}）—— 不同触发、不同 reason 也不会补第二次。</li>
 * </ul>
 *
 * <p>表结构见 {@code sql/migrate-stock-ledger.sql}；**没有外键**：台账是审计证据，
 * 不允许随订单/规格被级联删除。
 */
@Data
@TableName("stock_ledger")
public class StockLedger {

    /** 下单扣减（refType/refId 为空：下单没有单据，靠订单 id + 幂等令牌兜住重放） */
    public static final String REASON_ORDER_CREATE = "ORDER_CREATE";
    /** 审核不通过：按整单回补 */
    public static final String REASON_AUDIT_REJECT = "AUDIT_REJECT";
    /** 取消包裹：按该包裹明细回补（refId = 发货单 id） */
    public static final String REASON_DELIVERY_CANCEL = "DELIVERY_CANCEL";
    /** 退货退款：按整单回补（refId = 退货申请 id） */
    public static final String REASON_RETURN_REFUND = "RETURN_REFUND";

    /** 触发单据类型：发货单 */
    public static final String REF_TYPE_DELIVERY = "DELIVERY";
    /** 触发单据类型：退货申请 */
    public static final String REF_TYPE_RETURN_APPLY = "RETURN_APPLY";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long skuId;

    /** 库存变化量：负数=扣减，正数=回补 */
    private Integer delta;

    private String reason;

    private String refType;

    private Long refId;

    /** 幂等键 orderId:skuId:reason:refId（refId 缺省 0），UNIQUE */
    private String idemKey;

    /**
     * 落点桶号（可空）：扣减时记「从哪个桶出去的」，回补时记「补回了哪个桶」。
     *
     * <p><b>只用于回补定位，不参与数量守恒</b>：守恒仍然只对 {@code delta} 求和
     * （见 {@code StockLedgerMapper#sumRestoredQty} / {@code sumOrderedQty}）。
     * 历史台账行为 NULL（分桶之前记的账），回补时按 sku 任选一个桶。
     */
    private Integer bucketNo;

    private LocalDateTime createdAt;
}
