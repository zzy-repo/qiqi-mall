package com.qiqi.mall.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.catalog.entity.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * SKU DAO。
 *
 * <h3>关于下面这两条「改库存」的 SQL</h3>
 * 它们是<b>legacy 扣减路径专用</b>（{@code qiqi.stock.bucket-enabled=false}，默认）：
 * 库存权威就在 {@code sku.stock} 这一行上，扣减 = 一条带 {@code stock >= N} 的原子 UPDATE。
 * 这正是分桶改造<b>之前</b>的 baseline（同一条 SQL、同一把行锁），
 * 压测要拿它当对照基准，所以必须留着。
 *
 * <p><b>唯一调用方</b>是 {@code LegacyStockStore}。分桶模式（{@code bucket-enabled=true}）下
 * 库存权威在 {@code sku_bucket}，那时谁调用这两个方法都等于改坏缓存、库存却没动 ——
 * 所以别在业务代码里直接用它们，一律走 {@code StockStore}（由配置在启动时二选一）。
 */
@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    /**
     * legacy 原子扣减：把「读-判断-写回」压进一条 SQL，由 MySQL 行锁保证串行，
     * 避免 check-then-act 竞态导致的超卖。
     *
     * @return 影响行数；0 表示库存不足（不满足 stock >= qty）
     */
    @Update("UPDATE sku SET stock = stock - #{qty} WHERE id = #{id} AND stock >= #{qty}")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** legacy 回补库存（关单 / 退货退款） */
    @Update("UPDATE sku SET stock = stock + #{qty} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);

    /** legacy 语义下「把库存设成绝对值」（管理端编辑库存 / 测试还原）—— 只改 sku.stock */
    @Update("UPDATE sku SET stock = #{stock} WHERE id = #{id}")
    int overwriteStock(@Param("id") Long id, @Param("stock") int stock);
}
