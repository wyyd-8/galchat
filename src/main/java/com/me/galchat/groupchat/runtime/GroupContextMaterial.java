package com.me.galchat.groupchat.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Set;

public record GroupContextMaterial(
        List<Message> messages,
        Set<Long> relevantCharacterIds) {

    public GroupContextMaterial {
        messages = List.copyOf(messages);
        relevantCharacterIds = relevantCharacterIds == null
                ? Set.of() : Set.copyOf(relevantCharacterIds);
    }

    public GroupContextMaterial(List<Message> messages) {
        this(messages, Set.of());
    }
}
