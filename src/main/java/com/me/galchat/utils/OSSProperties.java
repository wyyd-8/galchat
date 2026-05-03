package com.me.galchat.utils;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
public class OSSProperties {
    private String endpoint;
    private String bucketName;
    private String region;
}