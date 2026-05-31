package com.me.galchat.config;

import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.knuddels.jtokkit.api.EncodingType;

@Configuration
public class EmbeddingConfig {

    @Bean
    public TokenCountBatchingStrategy tokenCountBatchingStrategy() {
        // 1. 填入模型最大输入 Token 数
        int modelMaxInputTokenCount = 4096;
        // 2. 预留 5% 的缓冲区
        double reservePercentage = 0.1;
        // 3. 使用 CL100K_BASE 编码类型
        return new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, modelMaxInputTokenCount, reservePercentage);
    }
}