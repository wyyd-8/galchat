package com.me.galchat.groupchat.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record GroupContextMaterial(List<Message> messages) {

    public GroupContextMaterial {
        messages = List.copyOf(messages);
    }
}
