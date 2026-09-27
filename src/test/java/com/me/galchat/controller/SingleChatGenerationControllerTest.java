package com.me.galchat.controller;

import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.domain.po.UserWorldPrefix;
import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.exception.UserAuthException;
import com.me.galchat.service.IChatService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.chat.SingleChatGenerationRegistry;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SingleChatGenerationControllerTest {
    final IChatService chat = mock(IChatService.class);
    final IUserWorldPrefixService worlds = mock(IUserWorldPrefixService.class);
    final SingleChatGenerationRegistry registry = new SingleChatGenerationRegistry();
    final ChatController controller = new ChatController(chat, registry, worlds);
    @AfterEach void clear() { CurrentHolder.remove(); }

    @Test void startsOnceAndResumesThroughTheAuthenticatedRoute() {
        CurrentHolder.setCurrentId(1);
        var request = new ChatMessageDTO();
        request.setUserWorldId(2L); request.setCharacterId(3L); request.setMessage("hello"); request.setClientRequestId("request");
        var calls = new AtomicInteger();
        when(chat.chat(request)).thenReturn(Flux.defer(() -> { calls.incrementAndGet(); return Flux.just(new ChatFluxVO("response", "answer")); }));
        when(worlds.checkUserWorldAuth(1L, 2L, true)).thenReturn(new UserWorldPrefix());
        controller.chat(request).blockLast(); controller.chat(request).blockLast();
        var replay = controller.resume(2L, 3L, "request", 1).collectList().block();
        assertThat(replay).extracting(ChatFluxVO::getType).containsExactly("generation.completed");
        assertThat(calls).hasValue(1);
    }

    @Test void rejectsAnonymousAndRevokedAccessBeforeReturningCachedContent() {
        registry.start(1, 2L, 3L, "request", "private", Flux.empty()).blockLast();
        assertThatThrownBy(() -> controller.resume(2L, 3L, "request", 0)).isInstanceOf(UserAuthException.class);
        CurrentHolder.setCurrentId(1);
        when(worlds.checkUserWorldAuth(1L, 2L, true)).thenThrow(new UserAuthException("forbidden"));
        assertThatThrownBy(() -> controller.resume(2L, 3L, "request", 0)).isInstanceOf(UserAuthException.class);
    }
}
