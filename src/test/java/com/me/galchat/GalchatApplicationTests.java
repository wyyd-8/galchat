package com.me.galchat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GalchatApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DeepSeekChatModel deepSeekChatModel;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void autoConfiguresSingleDeepSeekModelWithConfiguredOptions() {
        Map<String, DeepSeekChatModel> models = applicationContext.getBeansOfType(DeepSeekChatModel.class);

        assertThat(models).containsOnlyKeys("deepSeekChatModel");
        assertThat(deepSeekChatModel.getOptions().getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(deepSeekChatModel.getOptions().getTemperature()).isEqualTo(1.0);
    }

    @Test
    void usesCurrentOllamaEmbeddingPropertyNamespace() {
        assertThat(environment.getProperty("spring.ai.ollama.embedding.model"))
                .isEqualTo("bge-m3");
    }

}
