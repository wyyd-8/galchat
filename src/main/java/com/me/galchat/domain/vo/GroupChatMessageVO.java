package com.me.galchat.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class GroupChatMessageVO {
    private Long id;
    private Long conversationId;
    private Long turnId;
    private Long replyStepId;
    private String speakerType;
    private Long speakerId;
    private String speakerName;
    private String messageKind;
    private String content;
    private String thinkingContent;
    private Long sequenceNo;
    private String status;
    private LocalDateTime createdAt;
}
