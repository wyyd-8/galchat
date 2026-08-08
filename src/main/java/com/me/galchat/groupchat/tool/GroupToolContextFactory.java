package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class GroupToolContextFactory {

    public Map<String, Object> create(GroupConversation conversation, GroupActionSpec action, Long replyStepId,
                                      String favorSystemStatus) {
        return create(conversation, action, null,
                replyStepId, favorSystemStatus);
    }

    public Map<String, Object> create(
            GroupConversation conversation,
            GroupActionSpec action,
            Long turnId,
            Long replyStepId,
            String favorSystemStatus) {
        return create(conversation, action, turnId, replyStepId,
                favorSystemStatus, null);
    }

    public Map<String, Object> create(
            GroupConversation conversation,
            GroupActionSpec action,
            Long turnId,
            Long replyStepId,
            String favorSystemStatus,
            Long userId) {
        Map<String, Object> context = new HashMap<>();
        context.put(ChatToolContextConstant.WORLD_ID_KEY, conversation.getWorldId());
        if (userId != null) {
            context.put(ChatToolContextConstant.USER_ID_KEY, userId);
        }
        context.put(ChatToolContextConstant.USER_WORLD_ID_KEY, conversation.getUserWorldId());
        context.put(ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, conversation.getId());
        context.put(ChatToolContextConstant.ACTOR_TYPE_KEY, action.actorType());
        if (action.subjectCharacterId() != null) {
            context.put(ChatToolContextConstant.SUBJECT_CHARACTER_ID_KEY,
                    action.subjectCharacterId());
        }
        if (action.actorId() != null) {
            context.put(ChatToolContextConstant.CHARACTER_ID_KEY, action.actorId());
        }
        context.put(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, replyStepId);
        if (turnId != null) {
            context.put(ChatToolContextConstant.GROUP_TURN_ID_KEY, turnId);
        }
        if (favorSystemStatus != null) {
            context.put(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, favorSystemStatus);
        }
        return context;
    }
}
