package com.me.galchat.groupchat.runtime;

import com.me.galchat.domain.po.GroupConversation;

import java.util.List;

public interface GroupTurnPolicy {

    List<GroupActionSpec> plan(GroupConversation conversation, GroupReplyPlanSelection selection);
}
