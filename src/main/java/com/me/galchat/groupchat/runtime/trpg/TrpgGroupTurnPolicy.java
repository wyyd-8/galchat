package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrpgGroupTurnPolicy implements GroupTurnPolicy {

    @Override
    public List<GroupActionSpec> plan(GroupConversation conversation, String planSource,
                                      List<GroupReplyPlanItem> orderedItems) {
        String actionType = GroupChatConstant.PLAN_SOURCE_COMBAT.equals(planSource)
                ? GroupChatConstant.ACTION_TRPG_COMBAT
                : GroupChatConstant.ACTION_TRPG_SCENE;
        return orderedItems.stream()
                .map(item -> new GroupActionSpec(
                        actionType,
                        item.getActorType(),
                        item.getActorId(),
                        item.getId(),
                        true))
                .toList();
    }
}
