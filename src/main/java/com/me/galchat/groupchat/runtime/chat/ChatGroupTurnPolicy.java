package com.me.galchat.groupchat.runtime.chat;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChatGroupTurnPolicy implements GroupTurnPolicy {

    @Override
    public List<GroupActionSpec> plan(GroupConversation conversation, String planSource,
                                      List<GroupReplyPlanItem> orderedItems) {
        return orderedItems.stream()
                .map(item -> new GroupActionSpec(
                        GroupChatConstant.ACTION_CHAT_REPLY,
                        item.getActorType(),
                        item.getActorId(),
                        item.getId(),
                        true))
                .toList();
    }
}
