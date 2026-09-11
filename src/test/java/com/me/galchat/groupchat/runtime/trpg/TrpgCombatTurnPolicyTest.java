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
    void firstRoundStartsWithKpBattlefieldIntroduction() {
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
                        "combat_intro",
                        GroupChatConstant.ACTION_COMBAT_ATTACK,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
        assertThat(actions.getFirst().actorType())
                .isEqualTo(GroupChatConstant.ACTOR_KP);
        assertThat(actions.getFirst().subjectCharacterId()).isNull();
        assertThat(actions.get(1).subjectCharacterId())
                .isEqualTo(71L);
        assertThat(actions.get(2).actorType())
                .isEqualTo(GroupChatConstant.ACTOR_KP);
        assertThat(actions.getLast().subjectCharacterId())
                .isEqualTo(71L);
    }

    @Test
    void laterRoundsStartDirectlyWithCombatActions() {
        GroupReplyPlanItem npc = new GroupReplyPlanItem()
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setActorId(null)
                .setSubjectCharacterId(71L)
                .setItemOrder(1);
        GroupReplyPlanSelection selection =
                new GroupReplyPlanSelection(
                        GroupChatConstant.PLAN_SOURCE_COMBAT,
                        200L, "combat:round:2", "战斗第2轮",
                        List.of(npc));

        var actions = new TrpgGroupTurnPolicy().plan(
                new GroupConversation().setId(7L), selection);

        assertThat(actions)
                .extracting(action -> action.actionType())
                .containsExactly(
                        GroupChatConstant.ACTION_COMBAT_ATTACK,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
    }
}
