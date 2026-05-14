package com.me.galchat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@MapperScan("com.me.galchat.mapper")
public class GalchatApplication {

    public static void main(String[] args) {
        SpringApplication.run(GalchatApplication.class, args);
    }

}
