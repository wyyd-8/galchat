package com.me.galchat.service.impl.group;

import com.me.galchat.domain.po.GroupConversation;

public interface GroupConversationDeletionStore {

    void delete(GroupConversation conversation);
}
