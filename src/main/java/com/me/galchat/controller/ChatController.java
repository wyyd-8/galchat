package com.me.galchat.controller;

import com.me.galchat.domain.po.ConversationInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final ChatClient deepThinkChatClient;

    @GetMapping("/chat")
    public String chat(String message, String conversationId) {
        log.info("Received message: {}", message);
        new ConversationInfo(conversationId);
        return deepThinkChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
