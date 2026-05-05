package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class ChatMessage {
    private String type;
    private String worldId;
    private String characterId;
    private String message;
    private Boolean isTyping;
    private int length = 0;
}
