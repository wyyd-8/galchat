package com.me.galchat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class GroupReplyPlanDTO {
    private String source;
    private Long contextId;
    private String executionKey;
    private String displayName;
    private List<Item> items;

    @Data
    public static class Item {
        private Integer order;
        private String actorType;
        private Long actorId;
        private Long subjectCharacterId;
        private String subjectCharacterName;
    }
}
