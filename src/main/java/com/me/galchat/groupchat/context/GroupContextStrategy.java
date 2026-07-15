package com.me.galchat.groupchat.context;

import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;

public interface GroupContextStrategy {
    boolean supports(String mode);

    void compactIfNeeded(GroupConversation conversation);

    GroupContextSummary latestSummary(Long conversationId);
}
