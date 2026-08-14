package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class GroupGenerationStreamRegistry {

    private static final Duration DEFAULT_RETENTION = Duration.ofMinutes(5);

    private final ConcurrentHashMap<GenerationKey, GenerationEntry> entries =
            new ConcurrentHashMap<>();
    private final Duration retention;

    public GroupGenerationStreamRegistry() {
        this(DEFAULT_RETENTION);
    }

    GroupGenerationStreamRegistry(Duration retention) {
        this.retention = retention;
    }

    public Flux<GroupChatEvent> start(
            Long conversationId,
            String clientRequestId,
            Flux<GroupChatEvent> source) {
        if (!StringUtils.hasText(clientRequestId)) {
            return source;
        }
        GenerationKey key = new GenerationKey(
                conversationId, clientRequestId.trim());
        GenerationEntry entry = entries.computeIfAbsent(
                key,
                ignored -> new GenerationEntry(
                        conversationId,
                        source,
                        completed -> scheduleRemoval(key, completed)));
        return entry.events();
    }

    public Flux<GroupChatEvent> resume(
            Long conversationId, String clientRequestId) {
        if (!StringUtils.hasText(clientRequestId)) {
            return Flux.error(new UserRequestException("生成标识不能为空"));
        }
        GenerationEntry entry = entries.get(new GenerationKey(
                conversationId, clientRequestId.trim()));
        if (entry == null) {
            return Flux.error(new UserRequestException(
                    "生成流不存在或已过期"));
        }
        return entry.events();
    }

    private void scheduleRemoval(
            GenerationKey key, GenerationEntry completed) {
        Mono.delay(retention).subscribe(
                ignored -> entries.remove(key, completed));
    }

    private record GenerationKey(
            Long conversationId, String clientRequestId) {
    }

    private static final class GenerationEntry {

        private final Long conversationId;
        private final Sinks.Many<GroupChatEvent> events =
                Sinks.many().replay().all();
        private final AtomicInteger eventCount = new AtomicInteger();

        private GenerationEntry(
                Long conversationId,
                Flux<GroupChatEvent> source,
                Consumer<GenerationEntry> onTerminated) {
            this.conversationId = conversationId;
            source.subscribe(
                    event -> {
                        eventCount.incrementAndGet();
                        Sinks.EmitResult result = events.tryEmitNext(event);
                        if (result.isFailure()) {
                            eventCount.decrementAndGet();
                        }
                    },
                    error -> {
                        events.tryEmitError(error);
                        onTerminated.accept(this);
                    },
                    () -> {
                        events.tryEmitComplete();
                        onTerminated.accept(this);
                    });
        }

        private Flux<GroupChatEvent> events() {
            return Flux.defer(() -> {
                int replayCount = eventCount.get();
                Flux<GroupChatEvent> replayed = events.asFlux()
                        .take(replayCount);
                Flux<GroupChatEvent> live = events.asFlux()
                        .skip(replayCount);
                GroupChatEvent caughtUp = GroupChatEvent.builder()
                        .eventType(GroupChatConstant.EVENT_STREAM_CAUGHT_UP)
                        .conversationId(conversationId)
                        .build();
                return Flux.concat(
                        replayed, Flux.just(caughtUp), live);
            });
        }
    }
}
