package com.qiqi.mall.shared.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分页插件。不配这个，Page 查询不会拼 LIMIT、也不会生成 count —— 分页会静默失效（返回全量）。
 * maxLimit=100：单页上限，防止恶意 size=999999。
 */
@Configuration
public class MybatisPlusConfig {

    private static final long MAX_LIMIT = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_LIMIT);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
