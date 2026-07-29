package com.me.galchat.domain.vo;

import com.me.galchat.domain.po.GroupConversation;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class GroupConversationVO {

    private Long id;
    private Long userWorldId;
    private Long worldId;
    private Long moduleId;
    private Long activeReplyPlanId;
    private String mode;
    private String title;
    private String summary;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    private String lastChatContent;
    private LocalDateTime lastChatTime;

    public static GroupConversationVO from(GroupConversation conversation) {
        return new GroupConversationVO()
                .setId(conversation.getId())
                .setUserWorldId(conversation.getUserWorldId())
                .setWorldId(conversation.getWorldId())
                .setModuleId(conversation.getModuleId())
                .setActiveReplyPlanId(conversation.getActiveReplyPlanId())
                .setMode(conversation.getMode())
                .setTitle(conversation.getTitle())
                .setSummary(conversation.getSummary())
                .setStatus(conversation.getStatus())
                .setVersion(conversation.getVersion())
                .setCreatedAt(conversation.getCreatedAt())
                .setUpdatedAt(conversation.getUpdatedAt())
                .setClosedAt(conversation.getClosedAt());
    }
}
