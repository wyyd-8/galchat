package com.me.galchat.groupchat.context;

import com.me.galchat.domain.po.GroupChatMessage;

import java.util.List;

public interface GroupTopicClassifier {

    boolean isSameTopic(List<GroupChatMessage> currentTopic, GroupChatMessage userMessage);
}
