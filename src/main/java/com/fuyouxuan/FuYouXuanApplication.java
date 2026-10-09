package com.fuyouxuan;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.fuyouxuan.mapper")
@SpringBootApplication
public class FuYouXuanApplication {

    public static void main(String[] args) {
        SpringApplication.run(FuYouXuanApplication.class, args);
    }

}
