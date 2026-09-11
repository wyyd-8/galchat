package com.me.galchat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class GroupConversationCreateDTO {
    private Long userWorldId;
    private Long moduleId;
    private String mode;
    private String title;
    private List<Long> characterIds;
}
