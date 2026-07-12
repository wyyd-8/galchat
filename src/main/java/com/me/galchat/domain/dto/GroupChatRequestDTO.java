package com.me.galchat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class GroupChatRequestDTO {
    private String clientRequestId;
    private String content;
    private List<ReplyTarget> replyPlan;

    @Data
    public static class ReplyTarget {
        private String speakerType;
        private Long speakerId;
        private Boolean force;
    }
}
