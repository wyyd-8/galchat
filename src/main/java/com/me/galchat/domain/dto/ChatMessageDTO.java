package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class ChatMessageDTO {
    private String type;
    private String worldId;
    private Long userWorldId;
    private String characterId;
    private String message;
    private Boolean isTyping;
    private long length = 0;
    private long revision = 0;
    private String triggerType;
}
