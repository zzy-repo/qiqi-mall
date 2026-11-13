package com.qiqi.mall.shared.ledger.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qiqi.mall.shared.ledger.dto.LedgerRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 台账**只读**查询（跨域：订单/包裹/售后状态台账 + 后台审计 + WMS + 库存流水）。
 *
 * <p>它是全仓库唯一「一次能查全链路流水」的入口：七个分支逐字同构（同一套列序），
 * 由 {@link LedgerSql#PAGE_TIMELINE} 按 service 传入的 {@code branches} 拼成 UNION ALL。
 * 分页交给 MyBatis-Plus 的 {@code PaginationInnerInterceptor}（{@code maxLimit=100}），
 * 所以方法第一个参数是 {@link IPage}，返回也是 {@link IPage}（前端拿到的 JSON 是
 * {@code records/total/size/current/pages}）。
 *
 * <h3>bizId 的语义（前端只有一个输入框，这里说清楚）</h3>
 * <ul>
 *   <li>单类型查询：{@code bizId} = 该类型自己的业务主键（订单 id / 包裹 id / 售后 id …）；</li>
 *   <li>全部类型查询：{@code bizId} = 「一根主键的全链路」——
 *       订单分支按订单 id、售后分支按冗余 order_id、包裹与 WMS 分支经
 *       {@code delivery_order.order_id} 搭桥反查（视图的 DELIVERY 分支没有 order_id，
 *       不搭桥就只能查到订单自己的状态行，看不到它的包裹）。</li>
 * </ul>
 * 因此同一个数字在七个分支里是**各自解释**的，合并后可能出现「订单 5 的行 + 包裹 5 的行」。
 * 这是前端契约（只有一个 bizId 参数、没有「对象种类」参数）带来的固有歧义，
 * 已在交付报告里作为契约问题提出，接口这边选择「宽口径、全都能查到」而不是「窄口径、猜一个」。
 *
 * <h3>为什么不返回实体</h3>
 * 七支的 {id} 各自来自不同的表，没有共同的实体；{@link LedgerRow} 是只读投影，
 * 用 {@code map-underscore-to-camel-case} 自动映射（{@code biz_id → bizId}）。
 */
@Mapper
public interface LedgerQueryMapper {

    String TYPE_ORDER_STATUS = "ORDER_STATUS";
    String TYPE_DELIVERY_STATUS = "DELIVERY_STATUS";
    String TYPE_RETURN_STATUS = "RETURN_STATUS";
    String TYPE_AUDIT = "AUDIT";
    String TYPE_WMS = "WMS";
    String TYPE_STOCK = "STOCK";
    String TYPE_PAYMENT = "PAYMENT";

    /** 七个台账类型（顺序 = 前端下拉顺序：三个状态 → 审计 → WMS → 库存 → 支付）；「全部类型」就是它 */
    List<String> ALL_TYPES = List.of(TYPE_ORDER_STATUS, TYPE_DELIVERY_STATUS, TYPE_RETURN_STATUS,
            TYPE_AUDIT, TYPE_WMS, TYPE_STOCK, TYPE_PAYMENT);

    /**
     * 台账分页查询（按 {@code created_at, id} 倒序 = 最近的在最前）。
     *
     * @param page         分页参数（current/size 已由 controller 夹在 1..50）
     * @param branches     要查的分支：全部类型传 {@link #ALL_TYPES}，单类型传一个元素
     * @param bizId        业务对象 id（可空；语义见类注释）
     * @param operatorType 操作人类型过滤（可空；STOCK 与 PAYMENT 两支不参与，见 {@link LedgerSql}）
     * @param from         起始时间（含，可空）
     * @param to           截止时间（含，可空）
     */
    @Select(LedgerSql.PAGE_TIMELINE)
    IPage<LedgerRow> pageTimeline(IPage<LedgerRow> page,
                                  @Param("branches") List<String> branches,
                                  @Param("bizId") Long bizId,
                                  @Param("operatorType") String operatorType,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);
}
