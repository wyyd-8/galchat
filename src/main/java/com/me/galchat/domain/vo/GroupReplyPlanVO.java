package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class GroupReplyPlanVO {
    private Long id;
    private String source;
    private Long contextId;
    private Long resumePlanId;
    private List<Group> groups;

    @Data
    @AllArgsConstructor
    public static class Group {
        private String key;
        private String name;
        private Integer order;
        private List<Item> items;
    }

    @Data
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private Integer order;
        private String actorType;
        private Long actorId;
    }
}
