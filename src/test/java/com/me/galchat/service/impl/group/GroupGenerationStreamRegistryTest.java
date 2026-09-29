package com.me.galchat.service.impl.group;

import com.me.galchat.service.impl.character.GenerationRequestContext;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.utils.CurrentHolder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupGenerationStreamRegistryTest {

    @Test
    void resumesOnlyMissingEventsWithoutChangingMessageOrdering() {
        var registry = new GroupGenerationStreamRegistry();
        var source = reactor.core.publisher.Sinks.many().unicast().<GroupChatEvent>onBackpressureBuffer();
        registry.start(7L, "cursor", source.asFlux());
        source.tryEmitNext(GroupChatEvent.builder().eventType("reply.started").sequence(99L).build());
        source.tryEmitNext(GroupChatEvent.builder().eventType("message.delta").delta("first").sequence(99L).build());
        var resumed = registry.resume(7L, "cursor", 1).collectList().toFuture();
        source.tryEmitNext(GroupChatEvent.builder().eventType("message.delta").delta("second").sequence(99L).build());
        source.tryEmitComplete();
        assertThat(resumed.join()).extracting(GroupChatEvent::getEventType)
                .containsExactly("message.delta", "stream.caught_up", "message.delta");
        assertThat(resumed.join()).extracting(GroupChatEvent::getEventSequence).containsExactly(2L, null, 3L);
        assertThat(resumed.join()).extracting(GroupChatEvent::getSequence).containsExactly(99L, null, 99L);
    }

    @Test
    void rejectsADifferentGenerationWhileTheConversationIsStillRunning() {
        var registry = new GroupGenerationStreamRegistry();
        var source = reactor.core.publisher.Sinks.many().unicast().<GroupChatEvent>onBackpressureBuffer();
        registry.start(7L, "active", source.asFlux());
        assertThatThrownBy(() -> registry.start(7L, "duplicate-action", Flux.empty()))
                .isInstanceOf(com.me.galchat.exception.UserRequestException.class);
        source.tryEmitComplete();
        assertThat(registry.start(7L, "next", Flux.just(event("turn.completed"))).collectList().block())
                .isNotEmpty();
    }

    @Test
    void evictsEveryRetainedGenerationForDeletedConversation() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        registry.start(7L, "first", Flux.just(event("message.delta")))
                .collectList().block();
        registry.start(7L, "second", Flux.just(event("message.delta")))
                .collectList().block();
        registry.start(8L, "other", Flux.just(event("message.delta")))
                .collectList().block();

        registry.evict(7L);

        assertThatThrownBy(() -> registry.resume(7L, "first")
                .collectList().block())
                .hasMessageContaining("生成流不存在或已过期");
        assertThatThrownBy(() -> registry.resume(7L, "second")
                .collectList().block())
                .hasMessageContaining("生成流不存在或已过期");
        assertThat(registry.resume(8L, "other").collectList().block())
                .isNotEmpty();
    }

    @Test
    void replaysAllEventsAfterTheFirstClientDisconnectsWithoutCancellingUpstream() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        AtomicReference<FluxSink<GroupChatEvent>> upstream =
                new AtomicReference<>();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        GroupChatEvent first = event("reply.started");
        GroupChatEvent second = event("message.delta");

        Flux<GroupChatEvent> initial = registry.start(
                7L, "generation-1",
                Flux.<GroupChatEvent>create(upstream::set)
                        .doOnCancel(() -> upstreamCancelled.set(true)));

        List<GroupChatEvent> initialEvents = new CopyOnWriteArrayList<>();
        CountDownLatch initialComplete = new CountDownLatch(1);
        initial.take(2).subscribe(
                initialEvents::add,
                ignored -> initialComplete.countDown(),
                initialComplete::countDown);
        upstream.get().next(first);

        assertThat(await(initialComplete)).isTrue();
        assertThat(initialEvents).extracting(GroupChatEvent::getEventType)
                .containsExactly(
                        GroupChatConstant.EVENT_STREAM_CAUGHT_UP,
                        first.getEventType());

        assertThat(upstreamCancelled).isFalse();
        upstream.get().next(second);
        var resuming = registry.resume(7L, "generation-1").collectList().toFuture();
        upstream.get().complete();

        List<GroupChatEvent> replayed = resuming.join();

        assertThat(replayed).extracting(GroupChatEvent::getEventType).containsExactly(
                "reply.started", "message.delta", GroupChatConstant.EVENT_STREAM_CAUGHT_UP);
        assertThat(upstreamCancelled).isFalse();
    }

    @Test
    void releasesCompletedStreamsAndReturnsOnlyCompletionWithoutOrphanRecovery() {
        GroupConversationLockService locks = mock(GroupConversationLockService.class);
        GroupTurnRecoveryService recovery = mock(GroupTurnRecoveryService.class);
        GroupGenerationStreamRegistry registry = new GroupGenerationStreamRegistry(
                Duration.ofMinutes(5), locks, recovery);
        AtomicReference<FluxSink<GroupChatEvent>> upstream = new AtomicReference<>();
        registry.start(7L, "complete", Flux.create(upstream::set));
        upstream.get().next(GroupChatEvent.builder().eventType("message.delta")
                .conversationId(7L).delta("private reply").build());
        // A turn event can precede the remaining persistence work: retain until source completion.
        upstream.get().next(event("turn.completed"));
        assertThat(registry.resume(7L, "complete").take(1).blockFirst().getDelta())
                .isEqualTo("private reply");
        upstream.get().complete();

        List<GroupChatEvent> resumed = registry.resume(7L, "complete").collectList().block();
        assertThat(resumed).extracting(GroupChatEvent::getEventType)
                .containsExactly("generation.completed");
        assertThat(resumed.getFirst().getContent()).isNull();
        assertThat(resumed.getFirst().getDelta()).isNull();
        assertThat(resumed.getFirst().getErrorDetail()).isNull();
        org.mockito.Mockito.verifyNoInteractions(locks, recovery);
    }

    @Test
    void reusesTheExistingGenerationInsteadOfSubscribingToANewSource() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        AtomicInteger subscriptions = new AtomicInteger();
        Flux<GroupChatEvent> source = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.just(event("message.delta"));
        });

        registry.start(7L, "same-id", source);
        registry.start(7L, "same-id", source).collectList().block();

        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void preservesAuthenticatedUserAcrossReactiveSchedulerSwitches() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        GroupChatEvent delta = event("message.delta");
        AtomicReference<Integer> observedUserId = new AtomicReference<>();
        CurrentHolder.setCurrentId(12);

        try {
            List<GroupChatEvent> events = registry.start(
                            7L, "authenticated-generation",
                            Flux.defer(() -> {
                                        observedUserId.set(
                                                CurrentHolder.getCurrentId());
                                        return Flux.just(delta);
                                    })
                                    .subscribeOn(
                                            Schedulers.boundedElastic()))
                    .collectList()
                    .block();

            assertThat(observedUserId).hasValue(12);
            assertThat(events).extracting(GroupChatEvent::getEventType).contains(delta.getEventType());
        } finally {
            CurrentHolder.remove();
        }
    }

    @Test
    void preservesAuthenticatedUserWhenNestedPublisherWritesItsOwnContext() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        AtomicReference<Integer> observedUserId = new AtomicReference<>();
        CurrentHolder.setCurrentId(12);

        try {
            registry.start(
                            7L, "nested-context-generation",
                            Flux.defer(() -> {
                                        observedUserId.set(
                                                CurrentHolder.getCurrentId());
                                        return Flux.just(
                                                event("message.delta"));
                                    })
                                    .contextWrite(context -> context.put(
                                            "nested-library-context", "value"))
                                    .subscribeOn(
                                            Schedulers.boundedElastic()))
                    .collectList()
                    .block();

            assertThat(observedUserId).hasValue(12);
        } finally {
            CurrentHolder.remove();
        }
    }

    @Test
    void preservesAuthenticatedUserWhenGenerationIsNotRetained() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        AtomicReference<Integer> observedUserId = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        CurrentHolder.setCurrentId(12);

        try {
            registry.start(
                            7L, null,
                            Flux.defer(() -> {
                                        observedUserId.set(
                                                CurrentHolder.getCurrentId());
                                        return Flux.just(
                                                event("message.delta"));
                                    })
                                    .contextWrite(context -> context.put(
                                            "nested-library-context", "value"))
                                    .subscribeOn(
                                            Schedulers.boundedElastic()))
                    .subscribe(
                            ignored -> { },
                            ignored -> completed.countDown(),
                            completed::countDown);

            assertThat(await(completed)).isTrue();
            assertThat(observedUserId).hasValue(12);
        } finally {
            CurrentHolder.remove();
        }
    }

    @Test
    void convertsAnUpstreamFailureIntoATerminalGenerationEvent() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));

        List<GroupChatEvent> events = registry.start(
                        7L, "failed-generation",
                        Flux.error(new IllegalStateException(
                                "model request failed")))
                .onErrorComplete()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(GroupChatEvent::getEventType)
                .contains("generation.failed");
    }

    @Test
    void failureEventIncludesKnownGenerationIdsAndDebugDetails()
            throws Exception {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        GroupChatEvent started = GroupChatEvent.builder()
                .eventType(GroupChatConstant.EVENT_REPLY_STARTED)
                .conversationId(7L)
                .turnId(41L)
                .replyStepId(42L)
                .messageId(43L)
                .build();

        List<GroupChatEvent> events = registry.start(
                        7L, "failed-generation-with-context",
                        Flux.concat(
                                Flux.just(started),
                                Flux.error(new IllegalStateException(
                                        "model request failed"))))
                .collectList()
                .block();

        assertThat(events).isNotNull();
        GroupChatEvent failure = events.stream()
                .filter(event -> "generation.failed".equals(
                        event.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(failure.getTurnId()).isEqualTo(41L);
        assertThat(failure.getReplyStepId()).isEqualTo(42L);
        assertThat(failure.getMessageId()).isEqualTo(43L);
        String json = JsonMapper.builder().build()
                .writeValueAsString(failure);
        assertThat(json).contains("errorDetail")
                .contains("model request failed")
                .contains("GroupGenerationStreamRegistryTest");
    }

    @Test
    void failureDebugRequestIsServerSanitized() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        GenerationRequestContext request = new GenerationRequestContext(
                "continue-trpg-turn",
                "POST",
                "/group-chat/conversations/7/turns/continue",
                Map.of(
                        "clientRequestId", "request-7",
                        "token", "must-not-leak",
                        "nested", Map.of(
                                "password", "must-not-leak-either")));

        GroupChatEvent failure = registry.start(
                        7L, "failed-sanitized-generation", request,
                        Flux.error(new IllegalStateException("failed")))
                .filter(event -> "generation.failed".equals(
                        event.getEventType()))
                .blockFirst();

        assertThat(failure).isNotNull();
        assertThat(failure.getErrorDetail().getOperation())
                .isEqualTo("continue-trpg-turn");
        assertThat(failure.getErrorDetail().getRequest())
                .containsEntry("method", "POST")
                .containsEntry("path",
                        "/group-chat/conversations/7/turns/continue");
        assertThat(failure.getErrorDetail().getRequest().toString())
                .contains("[REDACTED]")
                .doesNotContain("must-not-leak");
    }

    @Test
    void convertsLegacyReplyFailureIntoTheOperationFailureContract() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        GroupChatEvent replyFailure = GroupChatEvent.builder()
                .eventType(GroupChatConstant.EVENT_REPLY_FAILED)
                .conversationId(7L)
                .turnId(41L)
                .replyStepId(42L)
                .messageId(43L)
                .error("context failed")
                .build();

        List<GroupChatEvent> events = registry.start(
                        7L, "legacy-reply-failure",
                        Flux.just(replyFailure))
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(GroupChatEvent::getEventType)
                .contains("generation.failed")
                .doesNotContain(GroupChatConstant.EVENT_REPLY_FAILED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedSourcesDoNotRecoverTurnsWithoutOwningTheirLock(boolean domainFailure) {
        var locks = mock(GroupConversationLockService.class);
        var recovery = mock(GroupTurnRecoveryService.class);
        var registry = new GroupGenerationStreamRegistry(Duration.ofMinutes(5), locks, recovery);
        Flux<GroupChatEvent> source = domainFailure
                ? Flux.just(GroupChatEvent.builder().eventType("reply.failed").error("failed").build())
                : Flux.error(new com.me.galchat.exception.UserRequestException("当前群聊正在生成回复"));

        var events = registry.start(7L, "rejected", source).collectList().block();

        assertThat(events).extracting(GroupChatEvent::getEventType).contains("generation.failed");
        org.mockito.Mockito.verifyNoInteractions(locks, recovery);
    }

    @Test
    void missingResumeRecoversOrphanedStateAndReturnsRetryableFailure() {
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        GroupTurnRecoveryService recoveryService =
                mock(GroupTurnRecoveryService.class);
        GroupConversationLockService.OwnedLock lock =
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 7L);
        when(lockService.tryLock(7L)).thenReturn(lock);
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(
                        Duration.ofMinutes(5), lockService,
                        recoveryService);

        List<GroupChatEvent> events = registry.resume(
                        7L, "expired-generation")
                .onErrorComplete()
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(GroupChatEvent::getEventType)
                .containsExactly("generation.failed");
        verify(recoveryService).recoverInterrupted(7L);
        verify(lockService).unlock(lock);
    }

    @Test
    void resumedFailureKeepsRetryStateButDropsDebugDetails() {
        GroupGenerationStreamRegistry registry =
                new GroupGenerationStreamRegistry(Duration.ofMinutes(5));
        registry.start(
                        7L, "failed-before-refresh",
                        Flux.error(new IllegalStateException(
                                "model failed")))
                .collectList()
                .block();

        GroupChatEvent resumedFailure = registry.resume(
                        7L, "failed-before-refresh")
                .filter(event -> "generation.failed".equals(
                        event.getEventType()))
                .blockFirst();

        assertThat(resumedFailure).isNotNull();
        assertThat(resumedFailure.getError())
                .isEqualTo("生成已中断，请读取最新状态后重试");
        assertThat(resumedFailure.getErrorDetail()).isNull();
    }

    private static GroupChatEvent event(String eventType) {
        return GroupChatEvent.builder()
                .eventType(eventType)
                .conversationId(7L)
                .build();
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
