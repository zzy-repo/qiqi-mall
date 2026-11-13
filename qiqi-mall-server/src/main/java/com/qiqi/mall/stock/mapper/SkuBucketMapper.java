package com.qiqi.mall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qiqi.mall.stock.dto.InventoryDriftRow;
import com.qiqi.mall.stock.entity.SkuBucket;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存分桶 DAO。<b>库存的唯一裁决处</b>：这里的两条 UPDATE 就是全部「能不能扣 / 扣成功没」的判断，
 * 全部是单条原子 SQL（判断压在 {@code WHERE stock >= ?} 里，绝不在应用层先查后写）。
 *
 * <p>没有任何代码路径允许绕过这张表去改 {@code sku.stock} 来「扣库存」：
 * {@link SkuMapper} 里原来那两条 {@code deductStock / restoreStock} 已随本次改造删除，
 * 保证不会出现「两个地方都能裁决库存」。
 */
@Mapper
public interface SkuBucketMapper extends BaseMapper<SkuBucket> {

    /**
     * 单桶原子扣减：影响 0 行 = 这个桶不够（或桶不存在）→ 调用方换下一个桶。
     *
     * <p>{@code AND stock >= #{qty}} 是防超卖的全部依据：两个并发事务同时命中这里时，
     * InnoDB 的行锁 + 这条条件只有一方能成功。
     */
    @Update("UPDATE sku_bucket SET stock = stock - #{qty} "
            + "WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo} AND stock >= #{qty}")
    int deductOne(@Param("skuId") Long skuId, @Param("bucketNo") int bucketNo, @Param("qty") int qty);

    /** 单桶回补。桶不存在或行数为 0 时返回 0（调用方按「换一个桶」处理） */
    @Update("UPDATE sku_bucket SET stock = stock + #{qty} "
            + "WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo}")
    int restoreOne(@Param("skuId") Long skuId, @Param("bucketNo") int bucketNo, @Param("qty") int qty);

    /** 该 SKU 的权威库存总量（= SUM(桶)）；也是 sku.stock 展示缓存的唯一数据来源 */
    @Select("SELECT COALESCE(SUM(stock), 0) FROM sku_bucket WHERE sku_id = #{skuId}")
    int sumStock(@Param("skuId") Long skuId);

    /** 某个桶当前的量（只用于「跨桶拼单」时算还能拿多少，不用于裁决） */
    @Select("SELECT stock FROM sku_bucket WHERE sku_id = #{skuId} AND bucket_no = #{bucketNo}")
    Integer stockOf(@Param("skuId") Long skuId, @Param("bucketNo") int bucketNo);

    /** 该 SKU 现在有几个桶（0 = 还没初始化的老数据，需要按 sku.stock 回填一次） */
    @Select("SELECT COUNT(*) FROM sku_bucket WHERE sku_id = #{skuId}")
    int countBuckets(@Param("skuId") Long skuId);

    /** 最小的桶号；没有桶时返回 -1（回补时「按 sku 任选一个桶」的确定性选择） */
    @Select("SELECT COALESCE(MIN(bucket_no), -1) FROM sku_bucket WHERE sku_id = #{skuId}")
    int minBucketNo(@Param("skuId") Long skuId);

    /**
     * 把权威总量写回展示缓存 {@code sku.stock}。
     *
     * <p>只做「展示用的一份拷贝」：不动 {@code version}（否则后台编辑会被莫名其妙地判成
     * 409 冲突），不参与任何扣减判断。{@code GREATEST(..., 0)} 只是显示层的兜底，
     * 库里的 {@code CHECK (stock >= 0)} 已保证总量不可能为负。
     */
    @Update("UPDATE sku s JOIN (SELECT sku_id, SUM(stock) AS total FROM sku_bucket "
            + "WHERE sku_id = #{skuId} GROUP BY sku_id) b ON b.sku_id = s.id "
            + "SET s.stock = GREATEST(b.total, 0) WHERE s.id = #{skuId}")
    int refreshSkuCache(@Param("skuId") Long skuId);

    /**
     * 幂等回填：把 {@code sku.stock} 平均拆到 N 个桶（余数进 0 号桶）。
     * 只处理「一个桶都没有」的 SKU（{@code NOT EXISTS} 守卫）—— 重复拆分会凭空多出库存。
     *
     * <p>{@code nos} 是 0..N-1 的数字表（由服务层按配置生成，纯整数，不涉及注入）；
     * {@code skuId} 非空时只补这个 SKU。
     */
    @Insert("<script>"
            + "INSERT INTO sku_bucket (sku_id, bucket_no, stock) "
            + "SELECT s.id, t.n, FLOOR(s.stock / #{n}) + IF(t.n = 0, s.stock - FLOOR(s.stock / #{n}) * #{n}, 0) "
            + "FROM sku s CROSS JOIN ("
            + "<foreach collection='nos' item='no' separator=' UNION ALL '>SELECT #{no} AS n</foreach>"
            + ") t WHERE NOT EXISTS (SELECT 1 FROM sku_bucket b WHERE b.sku_id = s.id)"
            + "<if test='skuId != null'> AND s.id = #{skuId}</if>"
            + "</script>")
    int backfill(@Param("n") int n, @Param("nos") List<Integer> nos, @Param("skuId") Long skuId);

    /** 按给定总量直接铺开 N 个桶（管理端「把库存改成 X」= 用 X 重置桶，清空后重铺） */
    @Insert("<script>"
            + "INSERT INTO sku_bucket (sku_id, bucket_no, stock) "
            + "SELECT #{skuId}, t.n, FLOOR(#{total} / #{n}) + IF(t.n = 0, #{total} - FLOOR(#{total} / #{n}) * #{n}, 0) "
            + "FROM ("
            + "<foreach collection='nos' item='no' separator=' UNION ALL '>SELECT #{no} AS n</foreach>"
            + ") t"
            + "</script>")
    int insertSplit(@Param("skuId") Long skuId, @Param("total") int total,
                    @Param("n") int n, @Param("nos") List<Integer> nos);

    /**
     * 有多少 SKU 的「桶总量」≠ 展示缓存（启动自检 / 体检用，只读）。
     *
     * <p>一个 SKU 有桶但没被回写过也算偏差（LEFT JOIN 后 COALESCE 成 0）—— 那正是「桶还在旧值」的信号。
     */
    @Select("SELECT COUNT(*) FROM sku s "
            + "LEFT JOIN (SELECT sku_id, SUM(stock) AS total FROM sku_bucket GROUP BY sku_id) b "
            + "ON b.sku_id = s.id WHERE s.stock <> COALESCE(b.total, 0)")
    int countCacheDrift();

    /** 清空该 SKU 的桶（只给「管理端重置库存」用；SKU 删除走外键级联，不经过这里） */
    @Delete("DELETE FROM sku_bucket WHERE sku_id = #{skuId}")
    int deleteBySku(@Param("skuId") Long skuId);

    /** 该 SKU 的桶最近一次变动时间（对账页「缓存最后回写」的可观测近似；没有桶时为 null） */
    @Select("SELECT MAX(updated_at) FROM sku_bucket WHERE sku_id = #{skuId}")
    LocalDateTime lastChangedAt(@Param("skuId") Long skuId);

    /**
     * <b>全库偏差分页</b>（{@code |bucketSum - skuStock| > threshold}，偏差大的在前）。
     *
     * <p>为什么必须新增而不是复用现有的两条：{@link #countCacheDrift()} 只给「有多少个 SKU 偏了」
     * 一个数、单 SKU 的对账只给一个 SKU —— 谁需要「偏了多少、偏的是哪几个、怎么翻页」就得有这条。
     *
     * <h3>口径（与 {@code scripts/check-data-integrity.sh} 的 ⑤ 同源，只有符号相反）</h3>
     * <ul>
     *   <li>{@code bucketSum = COALESCE(SUM(桶), 0)} —— 一个桶都没有的 SKU 算 0
     *       （那正是「桶缺失」的信号，会以 {@code drift = -skuStock} 出现在列表里）；</li>
     *   <li>{@code drift = bucketSum - skuStock}（前端契约：正 = 缓存少记，负 = 缓存多记）。
     *       体检脚本里判断用的是 {@code ABS()}，阈值判定两边完全等价。</li>
     * </ul>
     *
     * <p>{@code ABS(...)} 出现在 WHERE 上 ⇒ 这条查询是全表扫（sku + 桶聚合），走不了索引。
     * 这是对账类查询的固有代价（偏差本身是派生值），只读、按需触发，单页 50 行。
     */
    @Select("SELECT s.id AS sku_id, CONCAT('SKU-', s.id) AS sku_code, "
            + "s.stock AS sku_stock, COALESCE(b.total, 0) AS bucket_sum, "
            + "(COALESCE(b.total, 0) - s.stock) AS drift "
            + "FROM sku s "
            + "LEFT JOIN (SELECT sku_id, SUM(stock) AS total FROM sku_bucket GROUP BY sku_id) b "
            + "ON b.sku_id = s.id "
            + "WHERE ABS(COALESCE(b.total, 0) - s.stock) > #{threshold} "
            + "ORDER BY ABS(COALESCE(b.total, 0) - s.stock) DESC, s.id ASC")
    IPage<InventoryDriftRow> pageCacheDrift(IPage<InventoryDriftRow> page, @Param("threshold") int threshold);
}
