package com.me.galchat.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class GroupReplyPlanVO {
    private Long id;
    private String source;
    @JsonIgnore
    private Long contextId;
    @JsonIgnore
    private String executionKey;
    private String displayName;
    private Long nextPlanId;
    private Long resumePlanId;
    private Long parentPlanId;
    private List<Item> items;

    @Data
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private Integer order;
        private String actorType;
        private Long actorId;
        private Long subjectCharacterId;
        private String subjectCharacterName;
        private String participantStatus;

        public Item(
                Long id, Integer order,
                String actorType, Long actorId) {
            this(id, order, actorType, actorId,
                    null, null, null);
        }
    }
}
