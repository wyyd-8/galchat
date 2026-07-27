package com.me.galchat.groupchat.tool;

import com.me.galchat.constant.ChatToolContextConstant;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.groupchat.runtime.GroupActionSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroupToolContextFactoryTest {

    @Test
    void copiesKnownExecutionFieldsAndFavorStatusIntoToolContext() {
        GroupToolContextFactory factory = new GroupToolContextFactory();
        GroupConversation conversation = new GroupConversation()
                .setId(8L)
                .setWorldId(2L)
                .setUserWorldId(1L);
        GroupActionSpec action = new GroupActionSpec(
                GroupChatConstant.ACTION_CHAT_REPLY,
                GroupChatConstant.ACTOR_CHARACTER,
                11L,
                "default",
                "群聊",
                1,
                1);

        Map<String, Object> context = factory.create(conversation, action, 41L, "NORMAL");

        assertThat(context)
                .containsEntry(ChatToolContextConstant.WORLD_ID_KEY, 2L)
                .containsEntry(ChatToolContextConstant.USER_WORLD_ID_KEY, 1L)
                .containsEntry(ChatToolContextConstant.GROUP_CONVERSATION_ID_KEY, 8L)
                .containsEntry(ChatToolContextConstant.CHARACTER_ID_KEY, 11L)
                .containsEntry(ChatToolContextConstant.GROUP_REPLY_STEP_ID_KEY, 41L)
                .containsEntry(ChatToolContextConstant.FAVOR_SYSTEM_STATUS_KEY, "NORMAL");
    }

    @Test
    void kpToolContextContainsActorTypeButNoCharacterId() {
        GroupToolContextFactory factory = new GroupToolContextFactory();
        GroupConversation conversation = new GroupConversation()
                .setId(8L)
                .setWorldId(2L)
                .setUserWorldId(1L);
        GroupActionSpec action = new GroupActionSpec(
                GroupChatConstant.ACTION_TRPG_SCENE,
                GroupChatConstant.ACTOR_KP,
                null,
                "scene:1",
                "地下室",
                1,
                1);

        Map<String, Object> context = factory.create(conversation, action, 41L, "NORMAL");

        assertThat(context)
                .containsEntry(ChatToolContextConstant.ACTOR_TYPE_KEY, GroupChatConstant.ACTOR_KP)
                .doesNotContainKey(ChatToolContextConstant.CHARACTER_ID_KEY);
    }
}
