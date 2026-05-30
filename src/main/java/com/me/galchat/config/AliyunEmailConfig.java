package com.me.galchat.config;

import com.aliyun.credentials.Client;
import com.aliyun.teaopenapi.models.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AliyunEmailConfig {

    @Bean
    public com.aliyun.dm20151123.Client dmClient() throws Exception {
        // 使用无 AK 的凭据链（环境变量、实例 RAM 角色等）
        Client credential = new Client();
        Config config = new Config()
                .setCredential(credential);
        config.endpoint = "dm.aliyuncs.com";
        return new com.aliyun.dm20151123.Client(config);
    }
}