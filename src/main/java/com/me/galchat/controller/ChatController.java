package com.me.galchat.controller;

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

    private final ChatClient chatClient;
    private final ChatClient titleClient;

    @GetMapping("/chat")
    public String chat(String message, String conversationId) {
        log.info("Received message: {}", message);
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    @GetMapping("/title")
    public String getTitle(String message) {
        log.info("Received message: {}", message);
        return titleClient.prompt()
                .user("为以下内容生成一个不超过10个字的简短的标题，用于标记这段对话的主题内容：\n" + message)
                .call()
                .content();
    }
}
