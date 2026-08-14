package com.me.galchat.groupchat.runtime.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.GroupReplyPlanSelection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrpgCombatTurnPolicyTest {

    @Test
    void expandsEveryAttackerIntoRouteOptionalDefenseAndAdjudication() {
        GroupReplyPlanItem npc = new GroupReplyPlanItem()
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setActorId(null)
                .setSubjectCharacterId(71L)
                .setItemOrder(1);
        GroupReplyPlanSelection selection =
                new GroupReplyPlanSelection(
                        GroupChatConstant.PLAN_SOURCE_COMBAT,
                        200L, "combat:round:1", "战斗第1轮",
                        List.of(npc));

        var actions = new TrpgGroupTurnPolicy().plan(
                new GroupConversation().setId(7L), selection);

        assertThat(actions)
                .extracting(action -> action.actionType())
                .containsExactly(
                        GroupChatConstant.ACTION_COMBAT_ATTACK,
                        GroupChatConstant
                                .ACTION_COMBAT_REACTION_ROUTE,
                        GroupChatConstant.ACTION_COMBAT_DEFENSE,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
        assertThat(actions.getFirst().subjectCharacterId())
                .isEqualTo(71L);
        assertThat(actions.get(1).actorType())
                .isEqualTo(GroupChatConstant.ACTOR_KP);
        assertThat(actions.get(2).subjectCharacterId()).isNull();
        assertThat(actions.getLast().subjectCharacterId())
                .isEqualTo(71L);
    }
}
