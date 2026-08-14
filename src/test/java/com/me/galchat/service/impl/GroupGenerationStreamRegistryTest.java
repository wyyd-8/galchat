package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.vo.GroupChatEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GroupGenerationStreamRegistryTest {

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
        upstream.get().complete();

        List<GroupChatEvent> replayed = registry.resume(
                7L, "generation-1").collectList().block();

        assertThat(replayed).containsExactly(
                first, second,
                event(GroupChatConstant.EVENT_STREAM_CAUGHT_UP));
        assertThat(upstreamCancelled).isFalse();
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
