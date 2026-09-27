package com.me.galchat.service.impl.chat;

import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SingleChatAuthorizationTest {
    private final IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
    private final SingleChatLockService locks = mock(SingleChatLockService.class);
    private final SingleChatRuntimeService runtime = mock(SingleChatRuntimeService.class);
    private final ChatServiceImpl service = new ChatServiceImpl(null, runtime, null,
            null, worlds, null, null, locks, null);

    @AfterEach
    void clearCurrentUser() {
        CurrentHolder.remove();
    }

    @Test
    void rejectsAnonymousChatBeforeAcquiringLocksOrResolvingModels() {
        assertThatThrownBy(() -> service.chat(request()).blockLast())
                .isInstanceOf(UserAuthException.class);
        verifyNoInteractions(locks, runtime);
    }

    @Test
    void rejectsAnotherUsersConversationBeforeAnyGeneration() {
        CurrentHolder.setCurrentId(7);
        when(worlds.checkUserWorldAuth(7L, 10L, true))
                .thenThrow(new UserAuthException("forbidden"));

        assertThatThrownBy(() -> service.chat(request()).blockLast())
                .isInstanceOf(UserAuthException.class);
        verifyNoInteractions(locks, runtime);
    }

    @Test
    void rejectsForeignWorldTemplateEvenWhenTheUserOwnsTheConversation() {
        CurrentHolder.setCurrentId(7);
        when(worlds.checkUserWorldAuth(7L, 10L, true))
                .thenReturn(new UserWorldPrefix().setId(10L).setUserId(7L).setWorldId(21L));

        assertThatThrownBy(() -> service.chat(request()).blockLast())
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("世界");
        verifyNoInteractions(locks, runtime);
    }

    private ChatMessageDTO request() {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setUserWorldId(10L);
        request.setWorldId(20L);
        request.setCharacterId(30L);
        request.setMessage("hello");
        return request;
    }
}
