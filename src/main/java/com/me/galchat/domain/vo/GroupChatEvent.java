package com.me.galchat.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupChatEvent {
    private String eventType;
    private Long conversationId;
    private Long turnId;
    private Long replyStepId;
    private String actionType;
    private String groupKey;
    private String groupName;
    private Integer groupOrder;
    private Integer itemOrder;
    private Long messageId;
    private Long sequence;
    private String messageKind;
    private Speaker speaker;
    private String delta;
    private String content;
    private KpDiceToolResult diceRoll;
    private Map<String, String> sceneOptions;
    private SceneChoice sceneChoice;
    private Boolean autoSelected;
    private String error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Speaker {
        private String type;
        private Long id;
        private String name;
        private String avatar;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SceneChoice {
        private String optionNo;
        private String controllerName;
        private String investigatorName;
        private String locationName;
        private Boolean randomized;
    }
}
