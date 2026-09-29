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

    @Test void failedReplyIncludesTheActualRequestAndProviderFailureOnlyOnTheLiveStream() {
        CurrentHolder.setCurrentId(1);
        var request = new ChatMessageDTO();
        request.setWorldId(9L); request.setUserWorldId(2L); request.setCharacterId(3L);
        request.setMessage("原始消息"); request.setClientRequestId("failed-request");
        var cause = new IllegalArgumentException("401: API key expired.");
        when(chat.chat(request)).thenReturn(Flux.concat(
                Flux.just(new ChatFluxVO("generation.user", "20"), new ChatFluxVO("response", "部分回复")),
                Flux.error(new IllegalStateException("模型请求失败", cause))));

        var events = controller.chat(request).collectList().block();
        var failure = events.getLast();
        assertThat(failure.getType()).isEqualTo("generation.failed");
        var detail = failure.getErrorDetail();
        assertThat(detail).isNotNull();
        assertThat(failure.getContent()).isEqualTo("模型请求失败");
        assertThat(detail.getMessage()).isEqualTo(failure.getContent());
        assertThat(detail.getErrorId()).isNotBlank();
        assertThat(detail.getOperation()).isEqualTo("send-direct-message");
        assertThat(detail.getRequest()).containsEntry("method", "POST").containsEntry("path", "/ai/chat")
                .containsEntry("body", java.util.Map.of("worldId", 9L, "userWorldId", 2L,
                        "characterId", 3L, "message", "原始消息", "clientRequestId", "failed-request"));
        assertThat(detail.getResponse().get("events").toString()).contains("generation.user", "部分回复");
        assertThat(detail.getStack()).contains("IllegalStateException: 模型请求失败", "Caused by:", "401: API key expired.");
        assertThat(failure.getSequence()).isEqualTo(4L);

        var replay = registry.resume(1, 2L, 3L, "failed-request", 0).blockLast();
        assertThat(replay.getErrorDetail()).isNull();
        assertThat(replay.getContent()).doesNotContain("API key expired");
    }
}
