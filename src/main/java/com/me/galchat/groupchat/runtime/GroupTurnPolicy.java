package com.me.galchat.groupchat.runtime;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;

import java.util.List;

public interface GroupTurnPolicy {

    List<GroupActionSpec> plan(GroupConversation conversation, String planSource,
                               List<GroupReplyPlanItem> orderedItems);
}
