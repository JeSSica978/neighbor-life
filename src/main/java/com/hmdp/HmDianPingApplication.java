package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.hmdp.mapper")
@EnableRetry
@EnableKafka
@EnableScheduling
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        // JDBC/MySQL 与订单时间统一 UTC；签到按业务时区 Asia/Shanghai 单独计算。
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
