package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class ChatMessageDTO {
    private String clientRequestId;
    private Long worldId;
    private Long userWorldId;
    private Long characterId;
    private String message;
}
