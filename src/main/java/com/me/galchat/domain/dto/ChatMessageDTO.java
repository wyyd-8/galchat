package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class ChatMessageDTO {
    private String type;
    private Long worldId;
    private Long userWorldId;
    private Long characterId;
    private String message;
    private Boolean isTyping;
    private long length = 0;
    private long revision = 0;
    private String triggerType;
}
