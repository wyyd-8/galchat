package com.me.galchat.groupchat.runtime;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlanItem;
import com.me.galchat.groupchat.runtime.chat.ChatGroupTurnPolicy;
import com.me.galchat.groupchat.runtime.trpg.TrpgGroupTurnPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupTurnPolicyTest {

    @Test
    void chatMapsOrderedPlanItemsToChatReplyActions() {
        GroupReplyPlanItem alice = item(10L, 11L);
        GroupReplyPlanItem bob = item(20L, 12L);

        List<GroupActionSpec> actions = new ChatGroupTurnPolicy().plan(
                new GroupConversation().setMode(GroupChatConstant.MODE_CHAT),
                selection(GroupChatConstant.PLAN_SOURCE_USER, List.of(alice, bob)));

        assertThat(actions).extracting(GroupActionSpec::actionType)
                .containsExactly(GroupChatConstant.ACTION_CHAT_REPLY, GroupChatConstant.ACTION_CHAT_REPLY);
        assertThat(actions).extracting(GroupActionSpec::actorId).containsExactly(11L, 12L);
        assertThat(actions).extracting(GroupActionSpec::groupKey).containsExactly("default", "default");
    }

    @Test
    void trpgUsesSceneOrCombatActionsWithoutCreatingAnotherTurnSystem() {
        TrpgGroupTurnPolicy policy = new TrpgGroupTurnPolicy();
        GroupConversation conversation = new GroupConversation().setMode(GroupChatConstant.MODE_TRPG);
        List<GroupReplyPlanItem> ordered = List.of(item(10L, 11L));

        assertThat(policy.plan(conversation, selection(GroupChatConstant.PLAN_SOURCE_SCENE, ordered)))
                .extracting(GroupActionSpec::actionType)
                .containsExactly(GroupChatConstant.ACTION_TRPG_SCENE);
        assertThat(policy.plan(conversation, selection(GroupChatConstant.PLAN_SOURCE_COMBAT, ordered)))
                .extracting(GroupActionSpec::actionType)
                .containsExactly(
                        GroupChatConstant.ACTION_COMBAT_ATTACK,
                        GroupChatConstant.ACTION_COMBAT_ADJUDICATE);
    }

    @Test
    void trpgSceneOmitsWaitingInvestigatorsFromTheActionRound() {
        TrpgGroupTurnPolicy policy = new TrpgGroupTurnPolicy();
        GroupReplyPlanItem active = item(10L, 11L)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE);
        GroupReplyPlanItem waiting = item(20L, 12L)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_WAITING);

        assertThat(policy.plan(
                new GroupConversation().setMode(GroupChatConstant.MODE_TRPG),
                selection(GroupChatConstant.PLAN_SOURCE_SCENE,
                        List.of(active, waiting))))
                .extracting(GroupActionSpec::actorId)
                .containsExactly(11L);
    }

    @Test
    void trpgSceneOmitsReadyInvestigatorsFromTheActionRound() {
        TrpgGroupTurnPolicy policy = new TrpgGroupTurnPolicy();
        GroupReplyPlanItem active = item(10L, 11L)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_ACTIVE);
        GroupReplyPlanItem ready = item(20L, 12L)
                .setParticipantStatus(GroupChatConstant.PARTICIPANT_READY);

        assertThat(policy.plan(
                new GroupConversation().setMode(GroupChatConstant.MODE_TRPG),
                selection(GroupChatConstant.PLAN_SOURCE_SCENE,
                        List.of(active, ready))))
                .extracting(GroupActionSpec::actorId)
                .containsExactly(11L);
    }

    @Test
    void trpgPostCombatPlanRunsOnlyTheKpTransitionAction() {
        GroupReplyPlanItem kp = new GroupReplyPlanItem()
                .setActorType(GroupChatConstant.ACTOR_KP)
                .setItemOrder(1);
        GroupReplyPlanSelection selection = new GroupReplyPlanSelection(
                GroupChatConstant.PLAN_SOURCE_POST_COMBAT,
                77L, "post-combat:77", "战斗结束后的叙事过渡",
                List.of(kp));

        List<GroupActionSpec> actions = new TrpgGroupTurnPolicy()
                .plan(new GroupConversation(), selection);

        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action.actionType()).isEqualTo(
                    GroupChatConstant.ACTION_TRPG_POST_COMBAT_TRANSITION);
            assertThat(action.actorType()).isEqualTo(
                    GroupChatConstant.ACTOR_KP);
        });
    }

    private GroupReplyPlanSelection selection(String source, List<GroupReplyPlanItem> items) {
        return new GroupReplyPlanSelection(
                source, 100L, "default", "群聊", items);
    }

    private GroupReplyPlanItem item(Long id, Long actorId) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setItemOrder(id.intValue())
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId)
                .setParticipantStatus(
                        GroupChatConstant.PARTICIPANT_ACTIVE);
    }
}
