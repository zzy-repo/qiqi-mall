package com.qiqi.mall.shared.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器。
 *
 * <p>① 乐观锁（{@link OptimisticLockerInnerInterceptor}）：让实体上的 {@code @Version} 生效 ——
 * {@code updateById(entity)} 会生成 {@code UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?}，
 * 影响行数 0 就说明「读到的版本已经过期」，由 {@code OptimisticLockGuard} 变成 code=409。
 *
 * <p>② 分页（{@link PaginationInnerInterceptor}）：不配这个，Page 查询不会拼 LIMIT、也不会生成 count ——
 * 分页会静默失效（返回全量）。maxLimit=100：单页上限，防止恶意 size=999999。
 *
 * <p>**顺序：乐观锁在内、分页在外**（MP 官方建议：内层拦截器先于外层改写 SQL，
 * 分页要在最后把 LIMIT 补到最终 SQL 上）。写反了会出现「count SQL 里带上 version 条件」之类的怪问题。
 */
@Configuration
public class MybatisPlusConfig {

    private static final long MAX_LIMIT = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁必须在分页之前（内层）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_LIMIT);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
