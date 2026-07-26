package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrpgGroupTurnPolicy implements GroupTurnPolicy {

    @Override
    public List<GroupActionSpec> plan(GroupConversation conversation, GroupReplyPlanSelection selection) {
        String actionType = GroupChatConstant.PLAN_SOURCE_COMBAT.equals(selection.source())
                ? GroupChatConstant.ACTION_TRPG_COMBAT
                : GroupChatConstant.ACTION_TRPG_SCENE;
        return selection.items().stream()
                .map(item -> new GroupActionSpec(
                        actionType,
                        item.getActorType(),
                        item.getActorId(),
                        item.getGroupKey(),
                        item.getGroupName(),
                        item.getGroupOrder(),
                        item.getItemOrder()))
                .toList();
    }
}
