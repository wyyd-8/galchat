package com.me.galchat.domain.dto;

import lombok.Data;

@Data
public class GroupChatRequestDTO {
    private String clientRequestId;
    private String content;
}
