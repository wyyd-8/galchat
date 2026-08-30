package com.me.galchat.modelapi;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SpringAiOpenAiChatModelFactory
        implements UserModelChatModelFactory {

    private final Duration requestTimeout;

    public SpringAiOpenAiChatModelFactory(
            @Value("${galchat.model-api.request-timeout:30s}")
            Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ChatModel create(
            URI baseUrl,
            String apiKey,
            String model,
            Map<String, Object> requestOverrides) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl.toString())
                .apiKey(apiKey)
                .model(model)
                .extraBody(requestOverrides == null
                        ? Map.of()
                        : new LinkedHashMap<>(requestOverrides))
                .timeout(requestTimeout)
                .maxRetries(0)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
