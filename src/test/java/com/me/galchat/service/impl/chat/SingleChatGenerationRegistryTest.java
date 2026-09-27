package com.me.galchat.service.impl.chat;

import com.me.galchat.domain.vo.ChatFluxVO;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class SingleChatGenerationRegistryTest {
    @Test
    void completionReleasesTheConversationBeforeNotifyingSubscribers() {
        var registry = new SingleChatGenerationRegistry();
        var source = Sinks.many().unicast().<ChatFluxVO>onBackpressureBuffer();
        var nextStarted = new java.util.concurrent.atomic.AtomicBoolean();
        registry.start(1, 2L, 3L, "first", "hello", source.asFlux())
                .subscribe(event -> {
                    if ("generation.completed".equals(event.getType())) {
                        registry.start(1, 2L, 3L, "next", "next", Flux.empty()).blockLast();
                        nextStarted.set(true);
                    }
                });
        source.tryEmitComplete();
        assertThat(nextStarted).isTrue();
    }

    @Test
    void disconnectedSubscribersDoNotCancelGenerationAndReplayContinuesAfterCursor() {
        var registry = new SingleChatGenerationRegistry();
        var source = Sinks.many().unicast().<ChatFluxVO>onBackpressureBuffer();
        var subscriptions = new AtomicInteger();
        var cancellations = new AtomicInteger();
        var stream = registry.start(1, 2L, 3L, "request", "hello", source.asFlux()
                .doOnSubscribe(s -> subscriptions.incrementAndGet()).doOnCancel(cancellations::incrementAndGet));
        var subscriber = stream.subscribe();
        source.tryEmitNext(new ChatFluxVO("response", "first"));
        subscriber.dispose();
        var resuming = registry.resume(1, 2L, 3L, "request", 2).collectList().toFuture();
        source.tryEmitNext(new ChatFluxVO("response", "second"));
        source.tryEmitComplete();
        var replay = resuming.join();
        assertThat(replay).extracting(ChatFluxVO::getType).containsExactly("response", "generation.completed");
        assertThat(replay.getFirst().getContent()).isEqualTo("second");
        assertThat(replay).extracting(ChatFluxVO::getSequence).containsExactly(3L, 4L);
        registry.start(1, 2L, 3L, "request", "hello", Flux.defer(() -> { subscriptions.incrementAndGet(); return Flux.empty(); })).blockLast();
        assertThat(subscriptions).hasValue(1);
        assertThat(cancellations).hasValue(0);
        assertThat(registry.resume(1, 2L, 3L, "request", 0).collectList().block())
                .extracting(ChatFluxVO::getType).containsExactly("generation.completed");
    }

    @Test
    void anotherAccountWorldOrCharacterCannotReplayAndMissingStreamsExpireExplicitly() {
        var registry = new SingleChatGenerationRegistry();
        registry.start(1, 2L, 3L, "request", "private", Flux.empty()).blockLast();
        for (var stream : java.util.List.of(registry.resume(9, 2L, 3L, "request", 0),
                registry.resume(1, 9L, 3L, "request", 0), registry.resume(1, 2L, 9L, "request", 0))) {
            assertThat(stream.collectList().block()).extracting(ChatFluxVO::getType).containsExactly("generation.expired");
        }
    }

    @Test
    void failureIsTerminalAndReplayedWithoutProviderSecrets() {
        var registry = new SingleChatGenerationRegistry();
        registry.start(1, 2L, 3L, "request", "hello", Flux.error(new IllegalStateException("secret-provider-key"))).blockLast();
        var replay = registry.resume(1, 2L, 3L, "request", 0).collectList().block();
        assertThat(replay).extracting(ChatFluxVO::getType).containsExactly("generation.failed");
        assertThat(replay.getLast().getContent()).doesNotContain("secret-provider-key");
    }
    @Test
    void differentRequestsCannotGenerateConcurrentlyForTheSameCharacter() {
        var registry = new SingleChatGenerationRegistry();
        var source = Sinks.many().unicast().<ChatFluxVO>onBackpressureBuffer();
        registry.start(1, 2L, 3L, "first", "hello", source.asFlux()).subscribe();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.start(1, 2L, 3L,
                "second", "again", Flux.empty()).blockLast())
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class);
        source.tryEmitComplete();
        assertThat(registry.start(1, 2L, 3L, "second", "again", Flux.empty()).collectList().block())
                .extracting(ChatFluxVO::getType).containsExactly("generation.started", "generation.completed");
    }

    @Test
    void historyMutationsInvalidateOnlyTheAffectedReplayCache() {
        var registry = new SingleChatGenerationRegistry();
        registry.start(1, 2L, 3L, "one", "old history", Flux.empty()).blockLast();
        registry.start(1, 2L, 4L, "two", "other character", Flux.empty()).blockLast();
        registry.evict(2L, 3L);
        assertThat(registry.resume(1, 2L, 3L, "one", 0).blockFirst().getType()).isEqualTo("generation.expired");
        assertThat(registry.resume(1, 2L, 4L, "two", 0).blockFirst().getType()).isEqualTo("generation.completed");
        registry.evict(2L, null);
        assertThat(registry.resume(1, 2L, 4L, "two", 0).blockFirst().getType()).isEqualTo("generation.expired");
    }

}
