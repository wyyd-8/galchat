package com.me.galchat.modelapi;

import org.springframework.ai.chat.model.ChatModel;

import java.net.URI;
import java.util.Map;

@FunctionalInterface
public interface UserModelChatModelFactory {

    ChatModel create(
            URI baseUrl,
            String apiKey,
            String model,
            Map<String, Object> requestOverrides);
}
