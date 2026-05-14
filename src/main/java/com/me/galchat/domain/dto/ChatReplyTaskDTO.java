package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class ChatReplyTaskDTO {
    private Long userWorldId;
    private Long characterId;
    private String message;
    private long length = 0;
    private long revision = 0;
    private String triggerType;
}
