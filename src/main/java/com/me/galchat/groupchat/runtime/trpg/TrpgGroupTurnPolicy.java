package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
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
        if (GroupChatConstant.PLAN_SOURCE_POST_COMBAT.equals(
                selection.source())) {
            GroupReplyPlanItem kp = selection.items().stream()
                    .filter(item -> GroupChatConstant.ACTOR_KP.equals(
                            item.getActorType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "战斗结束后的叙事过渡缺少KP"));
            return List.of(new GroupActionSpec(
                    GroupChatConstant.ACTION_TRPG_POST_COMBAT_TRANSITION,
                    GroupChatConstant.ACTOR_KP, null, null,
                    selection.executionKey(), selection.displayName(),
                    1, kp.getItemOrder()));
        }
        boolean combat = GroupChatConstant.PLAN_SOURCE_COMBAT.equals(
                selection.source());
        if (!combat) {
            return selection.items().stream()
                    .filter(item -> !isInvestigator(item)
                            || GroupChatConstant.PARTICIPANT_ACTIVE.equals(
                                    item.getParticipantStatus()))
                    .map(item -> new GroupActionSpec(
                            GroupChatConstant.ACTION_TRPG_SCENE,
                            item.getActorType(), item.getActorId(),
                            item.getSubjectCharacterId(),
                            selection.executionKey(),
                            selection.displayName(),
                            1, item.getItemOrder()))
                    .toList();
        }
        List<GroupActionSpec> actions = new ArrayList<>();
        if ("combat:round:1".equals(selection.executionKey())) {
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_INTRO,
                    GroupChatConstant.ACTOR_KP, null, null,
                    selection.executionKey(), selection.displayName(),
                    1, 0));
        }
        for (var item : selection.items()) {
            int base = (item.getItemOrder() - 1) * 2;
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_ATTACK,
                    item.getActorType(), item.getActorId(),
                    item.getSubjectCharacterId(),
                    selection.executionKey(), selection.displayName(),
                    1, base + 1));
            actions.add(new GroupActionSpec(
                    GroupChatConstant.ACTION_COMBAT_ADJUDICATE,
                    GroupChatConstant.ACTOR_KP, null,
                    item.getSubjectCharacterId(),
                    selection.executionKey(), selection.displayName(),
                    1, base + 2));
        }
        return List.copyOf(actions);
    }

    private boolean isInvestigator(GroupReplyPlanItem item) {
        return GroupChatConstant.ACTOR_USER.equals(item.getActorType())
                || GroupChatConstant.ACTOR_CHARACTER.equals(
                        item.getActorType());
    }
}
