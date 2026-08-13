package com.cream.skywalkingdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.cream.skywalkingdemo.mapper")
public class SkyWalkingDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkyWalkingDemoApplication.class, args);
    }
}
