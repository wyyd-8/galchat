package com.me.galchat.groupchat.runtime;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

public record GroupModelInvocation(ChatClient chatClient, Prompt prompt, List<Object> tools) {

    public GroupModelInvocation {
        tools = List.copyOf(tools);
    }
}
