package com.me.galchat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class GroupReplyPlanDTO {
    private String source;
    private Long contextId;
    private List<Group> groups;

    @Data
    public static class Group {
        private String key;
        private String name;
        private Integer order;
        private List<Item> items;
    }

    @Data
    public static class Item {
        private Integer order;
        private String actorType;
        private Long actorId;
    }
}
