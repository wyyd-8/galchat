package com.me.galchat.groupchat.runtime;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.exception.UserRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupRuntimeRegistryTest {

    @Test
    void resolvesTheWholeRuntimeByConversationMode() {
        GroupModeRuntime chat = mock(GroupModeRuntime.class);
        GroupModeRuntime trpg = mock(GroupModeRuntime.class);
        when(chat.mode()).thenReturn(GroupChatConstant.MODE_CHAT);
        when(trpg.mode()).thenReturn(GroupChatConstant.MODE_TRPG);
        GroupRuntimeRegistry registry = new GroupRuntimeRegistry(List.of(chat, trpg));

        assertThat(registry.require(GroupChatConstant.MODE_CHAT)).isSameAs(chat);
        assertThat(registry.require(GroupChatConstant.MODE_TRPG)).isSameAs(trpg);
        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("未配置群聊运行时");
    }
}
