package com.me.galchat.modelapi;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiOpenAiChatModelFactoryTest {

    @Test
    void buildsDynamicOpenAiModelWithProviderOverrides() {
        SpringAiOpenAiChatModelFactory factory =
                new SpringAiOpenAiChatModelFactory(Duration.ofSeconds(23));

        OpenAiChatModel model = (OpenAiChatModel) factory.create(
                URI.create("https://models.example.com/v1"),
                "sk-secret", "model-a", Map.of(
                        "thinking", Map.of("type", "enabled"),
                        "reasoning_effort", "high",
                        "max_completion_tokens", 8192));

        OpenAiChatOptions options = model.getOptions();
        assertThat(options.getBaseUrl())
                .isEqualTo("https://models.example.com/v1");
        assertThat(options.getApiKey()).isEqualTo("sk-secret");
        assertThat(options.getModel()).isEqualTo("model-a");
        assertThat(options.getExtraBody()).isEqualTo(Map.of(
                "thinking", Map.of("type", "enabled"),
                "reasoning_effort", "high",
                "max_completion_tokens", 8192));
        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(23));
        assertThat(options.getMaxRetries()).isZero();
    }
}
