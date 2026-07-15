package com.me.galchat.groupchat.context;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.GroupContextSummary;
import com.me.galchat.domain.po.GroupConversation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupContextStrategyRouterTest {

    @Test
    void routesChatAndTrpgToDifferentStrategies() {
        GroupContextStrategy chat = mock(GroupContextStrategy.class);
        GroupContextStrategy trpg = mock(GroupContextStrategy.class);
        when(chat.supports(GroupChatConstant.MODE_CHAT)).thenReturn(true);
        when(trpg.supports(GroupChatConstant.MODE_TRPG)).thenReturn(true);
        GroupContextSummary chatSummary = new GroupContextSummary().setSummary("普通群聊概要");
        when(chat.latestSummary(1L)).thenReturn(chatSummary);
        GroupContextStrategyRouter router = new GroupContextStrategyRouter(List.of(chat, trpg));

        assertThat(router.latestSummary(new GroupConversation().setId(1L).setMode(GroupChatConstant.MODE_CHAT)))
                .isSameAs(chatSummary);
        router.compactIfNeeded(new GroupConversation().setId(2L).setMode(GroupChatConstant.MODE_TRPG));

        verify(chat).latestSummary(1L);
        verify(trpg).compactIfNeeded(org.mockito.ArgumentMatchers.argThat(conversation -> conversation.getId() == 2L));
    }
}
