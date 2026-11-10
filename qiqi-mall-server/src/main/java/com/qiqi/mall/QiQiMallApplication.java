package com.qiqi.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QiQiMallApplication {
    public static void main(String[] args) {
        SpringApplication.run(QiQiMallApplication.class, args);
    }
}
