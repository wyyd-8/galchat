package com.me.galchat.groupchat.runtime;

import com.me.galchat.domain.po.GroupChatMessage;
import com.me.galchat.domain.po.GroupConversation;

public interface GroupContextPolicy {

    void onTurnStarted(GroupConversation conversation, GroupChatMessage userMessage);

    default Runnable prepareTurnStarted(GroupConversation conversation, GroupChatMessage userMessage) {
        return () -> onTurnStarted(conversation, userMessage);
    }

    GroupContextMaterial load(GroupConversation conversation, GroupActionSpec action);
}
