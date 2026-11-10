package com.qiqi.mall.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.catalog.entity.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkuMapper extends BaseMapper<Sku> {

    /**
     * 原子扣减库存：把「读-判断-写回」压进一条 SQL，由 MySQL 行锁保证串行，
     * 避免 check-then-act 竞态导致的超卖。
     *
     * @return 影响行数；0 表示库存不足（不满足 stock >= qty）
     */
    @Update("UPDATE sku SET stock = stock - #{qty} WHERE id = #{id} AND stock >= #{qty}")
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 回补库存（关单 / 退货退款） */
    @Update("UPDATE sku SET stock = stock + #{qty} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("qty") int qty);
}
