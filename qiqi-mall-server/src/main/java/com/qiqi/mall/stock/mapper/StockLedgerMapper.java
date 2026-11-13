package com.qiqi.mall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.stock.dto.InventoryLedgerRow;
import com.qiqi.mall.stock.entity.StockLedger;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存台账 DAO。两条关键 SQL 都是**原子**的，不在应用层做「先查再写」：
 * <ul>
 *   <li>{@link #insertIgnore}（{@code ledger-insert-ignore=true}）：单语句 `INSERT IGNORE ... VALUES`，
 *       唯一索引点插入，不扫表、不产生间隙锁 —— 压测证实这才是 45,790 次死锁的解法；</li>
 *   <li>{@link #insertIfAbsent}（默认，baseline）：`INSERT ... SELECT ... WHERE NOT EXISTS (同一幂等键)`，
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

    /**
     * <b>台账插入（baseline 写法）</b>：`INSERT ... SELECT ... WHERE NOT EXISTS`。
     *
     * <p>⚠️ 压测实证：baseline 6 档累计 45,790 次死锁，LATEST DETECTED DEADLOCK 全部指向这条语句。
     * 机理（已在本机复现验证）：`INSERT ... SELECT` 属于「边读边写」，对**源表**做的是
     * **加锁读**（REPEATABLE READ 下对 uk_idem 取共享的 next-key/gap 锁）；
     * 而并发事务往同一个间隙里插入要拿插入意向锁（IX）→ 共享 gap 锁 × 插入意向锁 = 环，死锁。
     * 也就是说：访问路径本身是 uk_idem 上的等值探查（不是全表扫），**问题出在「读侧加了锁」**。
     * 本机探针（8 连接 × 200 条新键）：naive 出现 3 次 1213；改成 INSERT IGNORE 后 0 次。
     *
     * <p>而且压测的 P2 实验（100% 冷门 SKU、彻底无热点，c16 只比热点快 0.9%）说明：
     * **真正的全局串行点是这张台账表，不是 sku 那一行**。所以这条插入写法的收益可能比分桶更大。
     *
     * <p>记一行台账；幂等键已存在时返回 0（插入 0 行，不覆盖）
     */
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

    /**
     * <b>台账插入（修复写法）</b>：单语句 {@code INSERT IGNORE ... VALUES}。
     *
     * <p>为什么这样就没有死锁：{@code INSERT ... VALUES} 只取**插入意向锁**（彼此不冲突），
     * 不读源表、不产生共享 gap 锁；重复时由 uk_idem 直接判重（0 行 = 已存在 = 重放），
     * 冲突的行上只拿一次共享锁判断后即返回，不会与并发插入形成环。
     *
     * <p><b>不用 {@code ON DUPLICATE KEY UPDATE}</b>：那条语句的返回值（1/2 行）语义有歧义，
     * 而且「重复时更新一行」会去改既有记录 —— 幂等插入不该顺手改别人的账。
     *
     * <p>⚠️ {@code INSERT IGNORE} 会吞掉**所有**错误（字段超长、NOT NULL 等都会静默降级）。
     * 所以服务层在 {@code affected == 0} 时必须回查一次幂等键，
     * 查不到就说明被吞掉的是别的错误 → 直接抛异常，绝不当作「重复」放过
     * （见 {@code StockLedgerService#insertIfAbsent}）。
     */
    @Insert("INSERT IGNORE INTO stock_ledger "
            + "(order_id, sku_id, delta, reason, ref_type, ref_id, bucket_no, idem_key, created_at) "
            + "VALUES (#{orderId}, #{skuId}, #{delta}, #{reason}, #{refType,jdbcType=VARCHAR}, "
            + "#{refId,jdbcType=BIGINT}, #{bucketNo,jdbcType=INTEGER}, #{idemKey}, NOW())")
    int insertIgnore(@Param("orderId") Long orderId,
                     @Param("skuId") Long skuId,
                     @Param("delta") int delta,
                     @Param("reason") String reason,
                     @Param("refType") String refType,
                     @Param("refId") Long refId,
                     @Param("bucketNo") Integer bucketNo,
                     @Param("idemKey") String idemKey);

    /** INSERT IGNORE 的自检：这个幂等键到底在不在库里（0 = 被 IGNORE 吞掉的是别的错误） */
    @Select("SELECT COUNT(*) FROM stock_ledger WHERE idem_key = #{idemKey}")
    int countByIdemKey(@Param("idemKey") String idemKey);

    /**
     * 把「这次落在哪个桶」补写到台账行上（可空列，只用于回补定位）。
     *
     * <p>为什么是事后补写而不是 insert 时就带上：记账必须<b>先于</b>动库存（先记账、后扣货），
     * 而桶号要等扣成功了才知道 —— 顺序是「记一行 → 扣桶 → 回填桶号」。
     * {@code COALESCE} 保证一行台账只认第一个桶号（同一触发的合并行不会来回改）。
     */
    @Update("UPDATE stock_ledger SET bucket_no = COALESCE(bucket_no, #{bucketNo}) WHERE idem_key = #{idemKey}")
    int attachBucketNo(@Param("idemKey") String idemKey, @Param("bucketNo") int bucketNo);

    /**
     * 该订单该规格**扣减时所在的桶**（回补要回到这里）。
     *
     * <p>只看 {@code delta < 0} 的行（扣减方向）且桶号非空；历史台账（分桶之前记的账）没有桶号，
     * 返回 null → 回补按 sku 任选一个桶。
     */
    @Select("SELECT bucket_no FROM stock_ledger WHERE order_id = #{orderId} AND sku_id = #{skuId} "
            + "AND delta < 0 AND bucket_no IS NOT NULL ORDER BY id LIMIT 1")
    Integer findDeductBucketNo(@Param("orderId") Long orderId, @Param("skuId") Long skuId);

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

    /**
     * 该 SKU 的<b>台账净额</b>：{@code SUM(delta)} —— 全生命周期进出和。
     *
     * <p><b>它不是余额，也永远不该等于当前库存</b>：这里把该 SKU 历史上所有扣减（负数）与
     * 回补（正数）加在一起，而当前库存是「初始库存 + 全部进出」—— 两者的差正是那笔
     * 「没有被台账覆盖过的初始库存」（分桶迁移前就存在的老 SKU、直接 INSERT 造出来的数据等）。
     * 所以对账页把它单独摆一块，接口也**只如实返回**：<b>绝不在后端做
     * {@code ledgerNet == bucketSum} 的校验或修正</b>（那会把一个正常的账变成「错误」，
     * 再顺手改掉真库存）。
     */
    @Select("SELECT COALESCE(SUM(delta), 0) FROM stock_ledger WHERE sku_id = #{skuId}")
    int sumDelta(@Param("skuId") Long skuId);

    /** 该 SKU 最近的 N 条库存流水（对账页展示用；按 id 倒序 = 最近的在前） */
    @Select("SELECT id, reason, delta, bucket_no, ref_type, ref_id, created_at, idem_key "
            + "FROM stock_ledger WHERE sku_id = #{skuId} ORDER BY id DESC LIMIT #{limit}")
    List<InventoryLedgerRow> recentBySku(@Param("skuId") Long skuId, @Param("limit") int limit);
}
