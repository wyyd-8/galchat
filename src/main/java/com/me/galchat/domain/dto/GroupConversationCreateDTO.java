package com.me.galchat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class GroupConversationCreateDTO {
    private Long userWorldId;
    private String mode;
    private List<Long> characterIds;
}
