package com.me.galchat.modelapi;

import com.me.galchat.domain.po.UserModelApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

public record ResolvedUserModelRuntime(
        Long userId,
        Long modelApiId,
        UserModelApi configuration,
        ChatModel chatModel) {

    public ChatClient.Builder newChatClientBuilder() {
        return ChatClient.builder(chatModel);
    }
}
