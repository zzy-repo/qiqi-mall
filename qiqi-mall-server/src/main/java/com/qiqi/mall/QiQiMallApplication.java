package com.qiqi.mall;

import org.springframework.boot.SpringApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// MyBatis 映射器按域分散在 catalog/oms/order/member 各模块包下
@MapperScan("com.qiqi.mall.**.mapper")
public class QiQiMallApplication {
    public static void main(String[] args) {
        SpringApplication.run(QiQiMallApplication.class, args);
    }
}
