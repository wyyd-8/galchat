package com.me.galchat.service;

import com.me.galchat.domain.po.ConversationInfo;
import com.me.galchat.domain.po.UserChatHistory;

import java.util.List;

@FunctionalInterface
public interface ChatUserMessageListener {

    void onUserMessageSaved(Long userMessageId, ConversationInfo conversationInfo, List<UserChatHistory> histories);
}
