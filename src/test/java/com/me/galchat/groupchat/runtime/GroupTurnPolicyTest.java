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
                GroupChatConstant.PLAN_SOURCE_USER,
                List.of(alice, bob));

        assertThat(actions).extracting(GroupActionSpec::actionType)
                .containsExactly(GroupChatConstant.ACTION_CHAT_REPLY, GroupChatConstant.ACTION_CHAT_REPLY);
        assertThat(actions).extracting(GroupActionSpec::planItemId).containsExactly(10L, 20L);
        assertThat(actions).allMatch(GroupActionSpec::completesPlanItem);
    }

    @Test
    void trpgUsesSceneOrCombatActionsWithoutCreatingAnotherTurnSystem() {
        TrpgGroupTurnPolicy policy = new TrpgGroupTurnPolicy();
        GroupConversation conversation = new GroupConversation().setMode(GroupChatConstant.MODE_TRPG);
        List<GroupReplyPlanItem> ordered = List.of(item(10L, 11L));

        assertThat(policy.plan(conversation, GroupChatConstant.PLAN_SOURCE_SCENE, ordered))
                .extracting(GroupActionSpec::actionType)
                .containsExactly(GroupChatConstant.ACTION_TRPG_SCENE);
        assertThat(policy.plan(conversation, GroupChatConstant.PLAN_SOURCE_COMBAT, ordered))
                .extracting(GroupActionSpec::actionType)
                .containsExactly(GroupChatConstant.ACTION_TRPG_COMBAT);
    }

    private GroupReplyPlanItem item(Long id, Long actorId) {
        return new GroupReplyPlanItem()
                .setId(id)
                .setActorType(GroupChatConstant.ACTOR_CHARACTER)
                .setActorId(actorId);
    }
}
