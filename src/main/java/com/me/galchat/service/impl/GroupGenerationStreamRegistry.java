package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GenerationErrorDetailVO;
import com.me.galchat.exception.UserRequestException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class GroupGenerationStreamRegistry {

    private static final Duration DEFAULT_RETENTION = Duration.ofMinutes(5);

    private final ConcurrentHashMap<GenerationKey, GenerationEntry> entries =
            new ConcurrentHashMap<>();
    private final Duration retention;
    private final GroupConversationLockService lockService;
    private final GroupTurnRecoveryService recoveryService;

    public GroupGenerationStreamRegistry() {
        this(DEFAULT_RETENTION, null, null);
    }

    @Autowired
    public GroupGenerationStreamRegistry(
            GroupConversationLockService lockService,
            GroupTurnRecoveryService recoveryService) {
        this(DEFAULT_RETENTION, lockService, recoveryService);
    }

    GroupGenerationStreamRegistry(Duration retention) {
        this(retention, null, null);
    }

    GroupGenerationStreamRegistry(
            Duration retention,
            GroupConversationLockService lockService,
            GroupTurnRecoveryService recoveryService) {
        this.retention = retention;
        this.lockService = lockService;
        this.recoveryService = recoveryService;
    }

    public Flux<GroupChatEvent> start(
            Long conversationId,
            String clientRequestId,
            Flux<GroupChatEvent> source) {
        return start(conversationId, clientRequestId,
                new GenerationRequestContext(
                        "group-generation", null, null, Map.of()),
                source);
    }

    public Flux<GroupChatEvent> start(
            Long conversationId,
            String clientRequestId,
            GenerationRequestContext requestContext,
            Flux<GroupChatEvent> source) {
        if (!StringUtils.hasText(clientRequestId)) {
            return source.contextCapture();
        }
        GenerationKey key = new GenerationKey(
                conversationId, clientRequestId.trim());
        GenerationEntry entry = entries.computeIfAbsent(
                key,
                ignored -> new GenerationEntry(
                        conversationId,
                        requestContext,
                        source,
                        () -> recoverInterrupted(conversationId),
                        completed -> scheduleRemoval(key, completed)));
        return entry.events(true);
    }

    public Flux<GroupChatEvent> resume(
            Long conversationId, String clientRequestId) {
        if (!StringUtils.hasText(clientRequestId)) {
            return Flux.error(new UserRequestException("生成标识不能为空"));
        }
        GenerationEntry entry = entries.get(new GenerationKey(
                conversationId, clientRequestId.trim()));
        if (entry == null) {
            if (lockService != null && recoveryService != null) {
                GroupConversationLockService.OwnedLock lock =
                        lockService.tryLock(conversationId);
                if (lock == null) {
                    return Flux.error(new UserRequestException(
                            "生成仍在执行，请稍后重连"));
                }
                try {
                    recoveryService.recoverInterrupted(conversationId);
                    return Flux.just(GroupChatEvent.builder()
                            .eventType(GroupChatConstant
                                    .EVENT_GENERATION_FAILED)
                            .conversationId(conversationId)
                            .error("生成连接已失效，请重试此行动轮")
                            .build());
                } finally {
                    lockService.unlock(lock);
                }
            }
            return Flux.error(new UserRequestException(
                    "生成流不存在或已过期"));
        }
        return entry.events(false);
    }

    private void scheduleRemoval(
            GenerationKey key, GenerationEntry completed) {
        Mono.delay(retention).subscribe(
                ignored -> entries.remove(key, completed));
    }

    private void recoverInterrupted(Long conversationId) {
        if (recoveryService != null) {
            recoveryService.recoverInterrupted(conversationId);
        }
    }

    private record GenerationKey(
            Long conversationId, String clientRequestId) {
    }

    private static final class GenerationEntry {

        private final Long conversationId;
        private final Sinks.Many<GroupChatEvent> events =
                Sinks.many().replay().all();
        private final AtomicInteger eventCount = new AtomicInteger();
        private final FailureTrace failureTrace = new FailureTrace();
        private final AtomicBoolean failureRecovered =
                new AtomicBoolean();

        private GenerationEntry(
                Long conversationId,
                GenerationRequestContext requestContext,
                Flux<GroupChatEvent> source,
                Runnable beforeFailure,
                Consumer<GenerationEntry> onTerminated) {
            this.conversationId = conversationId;
            failureTrace.requestContext = requestContext;
            source.contextCapture().subscribe(
                    event -> {
                        failureTrace.record(event);
                        if (GroupChatConstant.EVENT_REPLY_FAILED.equals(
                                event.getEventType())) {
                            recoverOnce(beforeFailure);
                            emit(failureTrace.failureEvent(
                                    conversationId, event,
                                    new IllegalStateException(
                                            event.getError())));
                        } else {
                            emit(event);
                        }
                    },
                    error -> {
                        recoverOnce(beforeFailure);
                        GroupChatEvent failed = failureTrace.failureEvent(
                                conversationId, null, error);
                        emit(failed);
                        events.tryEmitComplete();
                        onTerminated.accept(this);
                    },
                    () -> {
                        events.tryEmitComplete();
                        onTerminated.accept(this);
                    });
        }

        private void recoverOnce(Runnable beforeFailure) {
            if (failureRecovered.compareAndSet(false, true)) {
                beforeFailure.run();
            }
        }

        private void emit(GroupChatEvent event) {
            eventCount.incrementAndGet();
            Sinks.EmitResult result = events.tryEmitNext(event);
            if (result.isFailure()) {
                eventCount.decrementAndGet();
            }
        }

        private Flux<GroupChatEvent> events(boolean includeDebugDetails) {
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
                Flux<GroupChatEvent> stream = Flux.concat(
                        replayed, Flux.just(caughtUp), live);
                return includeDebugDetails
                        ? stream
                        : stream.map(event -> event.getErrorDetail() == null
                                ? event
                                : event.toBuilder()
                                        .errorDetail(null)
                                        .build());
            });
        }
    }

    private static final class FailureTrace {

        private static final int MAX_EVENTS = 20;
        private static final int MAX_VALUE_CHARS = 2000;
        private static final int MAX_STACK_CHARS = 64 * 1024;
        private static final int MAX_STACK_FRAMES = 80;
        private static final int MAX_COLLECTION_ITEMS = 100;
        private static final Set<String> SENSITIVE_KEYS = Set.of(
                "authorization", "cookie", "password", "secret",
                "token", "apikey", "verificationcode",
                "systemprompt");

        private final List<Map<String, Object>> responseEvents =
                new ArrayList<>();
        private GenerationRequestContext requestContext;
        private int totalEvents;
        private Long turnId;
        private Long replyStepId;
        private Long messageId;

        private synchronized void record(GroupChatEvent event) {
            totalEvents++;
            if (event.getTurnId() != null) {
                turnId = event.getTurnId();
            }
            if (event.getReplyStepId() != null) {
                replyStepId = event.getReplyStepId();
            }
            if (event.getMessageId() != null) {
                messageId = event.getMessageId();
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("eventType", event.getEventType());
            put(snapshot, "turnId", event.getTurnId());
            put(snapshot, "replyStepId", event.getReplyStepId());
            put(snapshot, "messageId", event.getMessageId());
            put(snapshot, "content", truncated(event.getContent()));
            put(snapshot, "delta", truncated(event.getDelta()));
            put(snapshot, "error", truncated(event.getError()));
            responseEvents.add(snapshot);
            if (responseEvents.size() > MAX_EVENTS) {
                responseEvents.removeFirst();
            }
        }

        private synchronized GenerationErrorDetailVO detail(
                Throwable error) {
            String message = error == null
                    || !StringUtils.hasText(error.getMessage())
                    ? "生成失败" : error.getMessage();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("eventCount", totalEvents);
            response.put("events", List.copyOf(responseEvents));
            return GenerationErrorDetailVO.builder()
                    .errorId(UUID.randomUUID().toString())
                    .code("GENERATION_FAILED")
                    .category("GENERATION")
                    .message(message)
                    .retryable(true)
                    .occurredAt(Instant.now().toString())
                    .operation(requestContext == null
                            || !StringUtils.hasText(
                                    requestContext.operation())
                            ? "group-generation"
                            : requestContext.operation())
                    .request(requestSnapshot())
                    .response(response)
                    .stack(stack(error))
                    .build();
        }

        private synchronized GroupChatEvent failureEvent(
                Long conversationId,
                GroupChatEvent source,
                Throwable error) {
            String message = source != null
                    && StringUtils.hasText(source.getError())
                    ? source.getError()
                    : error == null
                    || !StringUtils.hasText(error.getMessage())
                    ? "生成失败" : error.getMessage();
            return GroupChatEvent.builder()
                    .eventType(GroupChatConstant
                            .EVENT_GENERATION_FAILED)
                    .conversationId(conversationId)
                    .turnId(source != null
                            && source.getTurnId() != null
                            ? source.getTurnId() : turnId)
                    .replyStepId(source != null
                            && source.getReplyStepId() != null
                            ? source.getReplyStepId() : replyStepId)
                    .messageId(source != null
                            && source.getMessageId() != null
                            ? source.getMessageId() : messageId)
                    .error(message)
                    .errorDetail(source != null
                            && source.getErrorDetail() != null
                            ? source.getErrorDetail() : detail(error))
                    .build();
        }

        private Map<String, Object> requestSnapshot() {
            if (requestContext == null) {
                return Map.of();
            }
            Map<String, Object> request = new LinkedHashMap<>();
            put(request, "method", requestContext.method());
            put(request, "path", requestContext.path());
            if (requestContext.body() != null
                    && !requestContext.body().isEmpty()) {
                request.put("body", sanitize(
                        requestContext.body(), null, 0));
            }
            return request;
        }

        private static Object sanitize(
                Object value, String key, int depth) {
            if (key != null && isSensitiveKey(key)) {
                return "[REDACTED]";
            }
            if (value == null || value instanceof Number
                    || value instanceof Boolean) {
                return value;
            }
            if (value instanceof CharSequence characters) {
                return truncated(characters.toString());
            }
            if (depth >= 6) {
                return "[MAX_DEPTH]";
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> sanitized = new LinkedHashMap<>();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ >= MAX_COLLECTION_ITEMS) {
                        sanitized.put("_truncated", true);
                        break;
                    }
                    String nestedKey = String.valueOf(entry.getKey());
                    sanitized.put(nestedKey, sanitize(
                            entry.getValue(), nestedKey, depth + 1));
                }
                return sanitized;
            }
            if (value instanceof Iterable<?> values) {
                List<Object> sanitized = new ArrayList<>();
                int count = 0;
                for (Object item : values) {
                    if (count++ >= MAX_COLLECTION_ITEMS) {
                        sanitized.add("[TRUNCATED]");
                        break;
                    }
                    sanitized.add(sanitize(item, null, depth + 1));
                }
                return sanitized;
            }
            return truncated(String.valueOf(value));
        }

        private static boolean isSensitiveKey(String key) {
            String normalized = key.replaceAll("[^A-Za-z0-9]", "")
                    .toLowerCase();
            return SENSITIVE_KEYS.stream().anyMatch(
                    normalized::contains);
        }

        private static void put(
                Map<String, Object> target, String key, Object value) {
            if (value != null) {
                target.put(key, value);
            }
        }

        private static String truncated(String value) {
            if (value == null || value.length() <= MAX_VALUE_CHARS) {
                return value;
            }
            return value.substring(0, MAX_VALUE_CHARS) + "…[truncated]";
        }

        private static String stack(Throwable error) {
            if (error == null) {
                return "";
            }
            StringBuilder output = new StringBuilder();
            Throwable current = error;
            int frames = 0;
            while (current != null && frames < MAX_STACK_FRAMES
                    && output.length() < MAX_STACK_CHARS) {
                if (!output.isEmpty()) {
                    output.append("Caused by: ");
                }
                output.append(current).append('\n');
                for (StackTraceElement frame
                        : current.getStackTrace()) {
                    if (frames++ >= MAX_STACK_FRAMES
                            || output.length() >= MAX_STACK_CHARS) {
                        break;
                    }
                    output.append("\tat ").append(frame)
                            .append('\n');
                }
                current = current.getCause();
            }
            if (output.length() > MAX_STACK_CHARS) {
                return output.substring(0, MAX_STACK_CHARS);
            }
            return output.toString();
        }
    }
}
