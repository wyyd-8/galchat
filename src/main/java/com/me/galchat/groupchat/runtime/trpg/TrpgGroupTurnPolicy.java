package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import com.me.galchat.groupchat.runtime.GroupTurnPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;

@Component
public class TrpgGroupTurnPolicy implements GroupTurnPolicy {

    @Override
    public List<GroupActionSpec> plan(GroupConversation conversation, GroupReplyPlanSelection selection) {
        boolean combat = GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                selection.source());
        if (!combat) {
            return selection.items().stream()
                    .filter(item -> !GroupChatConstant.PARTICIPANT_WAITING
                            .equals(item.getParticipantStatus()))
                    .map(item -> new GroupActionSpec(
                            GroupChatConstant.ACTION_TRPG_SCENE,
                            item.getActorType(), item.getActorId(),
                            item.getSubjectCharacterId(),
                            item.getGroupKey(), item.getGroupName(),
                            item.getGroupOrder(), item.getItemOrder()))
                    .toList();
        }
        List<GroupActionSpec> actions = new ArrayList<>();
        for (var item : selection.items()) {
            int base = (item.getItemOrder() - 1) * 4;
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_ATTACK,
                    item.getActorType(), item.getActorId(),
                    item.getSubjectCharacterId(),
                    item.getGroupKey(), item.getGroupName(),
                    item.getGroupOrder(), base + 1));
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_REACTION_ROUTE,
                    GroupChatConstant.ACTOR_KP, null,
                    item.getSubjectCharacterId(),
                    item.getGroupKey(), item.getGroupName(),
                    item.getGroupOrder(), base + 2));
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_DEFENSE,
                    GroupChatConstant.ACTOR_KP, null, null,
                    item.getGroupKey(), item.getGroupName(),
                    item.getGroupOrder(), base + 3));
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_ADJUDICATE,
                    GroupChatConstant.ACTOR_KP, null,
                    item.getSubjectCharacterId(),
                    item.getGroupKey(), item.getGroupName(),
                    item.getGroupOrder(), base + 4));
        }
        return List.copyOf(actions);
    }
}
