package com.qiqi.mall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.stock.entity.StockLedger;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 库存台账 DAO。两条关键 SQL 都是**原子**的，不在应用层做「先查再写」：
 * <ul>
 *   <li>{@link #insertIfAbsent}：`INSERT ... SELECT ... WHERE NOT EXISTS (同一幂等键)`，
 *       返回 1 = 本次真的记了一行（幂等键首次出现），0 = 幂等键已存在（重放）。
 *       为什么不用 {@code INSERT ... ON DUPLICATE KEY UPDATE idem_key = idem_key}：那条语句的
 *       「0 行」语义依赖连接的 CLIENT_FOUND_ROWS 标志（Connector/J 默认设置它，无变更的
 *       duplicate 会返回 1 而不是 0），拿返回值判断去重是不可靠的；INSERT ... SELECT ... WHERE
 *       NOT EXISTS 返回的就是真实插入行数。并发下两个事务同时通过 NOT EXISTS 时，
 *       表上的 UNIQUE KEY 是最后一道硬闸（服务层捕获 DuplicateKeyException，按「已回补过」跳过）。</li>
 *   <li>{@link #addDelta}：同一次触发里同一个 SKU 出现多条明细（例如同一订单两行买同一规格）时，
 *       把件数合并进同一行台账 —— 台账口径是「一次触发 × 一个规格 = 一行」，与 UNIQUE 键一致。</li>
 * </ul>
 */
@Mapper
public interface StockLedgerMapper extends BaseMapper<StockLedger> {

    /** 记一行台账；幂等键已存在时返回 0（插入 0 行，不覆盖） */
    @Insert("INSERT INTO stock_ledger (order_id, sku_id, delta, reason, ref_type, ref_id, idem_key, created_at) "
            + "SELECT #{orderId}, #{skuId}, #{delta}, #{reason}, #{refType,jdbcType=VARCHAR}, "
            + "#{refId,jdbcType=BIGINT}, #{idemKey}, #{createdAt} FROM dual "
            + "WHERE NOT EXISTS (SELECT 1 FROM stock_ledger WHERE idem_key = #{idemKey})")
    int insertIfAbsent(@Param("orderId") Long orderId,
                       @Param("skuId") Long skuId,
                       @Param("delta") int delta,
                       @Param("reason") String reason,
                       @Param("refType") String refType,
                       @Param("refId") Long refId,
                       @Param("idemKey") String idemKey,
                       @Param("createdAt") LocalDateTime createdAt);

    /** 把件数累加进已存在的那行台账（同一次触发内同规格多条明细） */
    @Update("UPDATE stock_ledger SET delta = delta + #{delta} WHERE idem_key = #{idemKey}")
    int addDelta(@Param("idemKey") String idemKey, @Param("delta") int delta);

    /** 补偿用：扣减因库存不足没做成时，把刚记的那行台账撤掉 */
    @Delete("DELETE FROM stock_ledger WHERE idem_key = #{idemKey}")
    int deleteByIdemKey(@Param("idemKey") String idemKey);

    /**
     * 该订单该规格的**下单总量**（数量守恒的基准）：{@code SUM(order_item.product_quantity)}。
     * 回补的累计量不允许超过它 —— 这是「同一件货不会被补两次」的硬约束，
     * 且与「下单那一步有没有记账」无关（历史订单 / 后台建单没有被台账覆盖也照样成立）。
     */
    @Select("SELECT COALESCE(SUM(product_quantity), 0) FROM order_item "
            + "WHERE order_id = #{orderId} AND product_sku_id = #{skuId}")
    int sumOrderedQty(@Param("orderId") Long orderId, @Param("skuId") Long skuId);

    /** 该订单该规格**已回补总量**（只看正数 delta） */
    @Select("SELECT COALESCE(SUM(delta), 0) FROM stock_ledger "
            + "WHERE order_id = #{orderId} AND sku_id = #{skuId} AND delta > 0")
    int sumRestoredQty(@Param("orderId") Long orderId, @Param("skuId") Long skuId);
}
