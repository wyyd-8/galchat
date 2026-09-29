package com.me.galchat.service.impl.group;

import com.me.galchat.service.impl.character.GenerationRequestContext;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.vo.GroupChatEvent;
import com.me.galchat.domain.vo.GenerationErrorDetailVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.exception.TurnCheckpointUnavailableException;
import com.me.galchat.exception.GroupCheckpointUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import com.me.galchat.service.impl.generation.GenerationStreams;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
public class GroupGenerationStreamRegistry {

    private static final Duration DEFAULT_RETENTION = Duration.ofMinutes(5);

    private final GenerationStreams<Long, GroupChatEvent> streams;
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
        this.streams = new GenerationStreams<>(retention);
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
        FailureTrace trace = new FailureTrace();
        trace.requestContext = requestContext;
        // Execution services recover their own failures while holding the conversation lock.
        // A rejected source may never have acquired it, so this layer must not mutate turns.
        Flux<GroupChatEvent> traced = source.map(event -> {
            trace.record(event);
            if (GroupChatConstant.EVENT_REPLY_FAILED.equals(event.getEventType())) {
                return trace.failureEvent(conversationId, event, new IllegalStateException(event.getError()));
            }
            return event;
        }).onErrorResume(error -> {
            return Flux.just(trace.failureEvent(conversationId, null, error));
        });
        return streams.start(conversationId, clientRequestId.trim(), traced, events(conversationId));
    }

    public Flux<GroupChatEvent> resume(Long conversationId, String clientRequestId) {
        return resume(conversationId, clientRequestId, 0);
    }

    public Flux<GroupChatEvent> resume(Long conversationId, String clientRequestId, long after) {
        if (!StringUtils.hasText(clientRequestId)) return Flux.error(new UserRequestException("生成标识不能为空"));
        Flux<GroupChatEvent> stream = streams.resume(conversationId, clientRequestId.trim(), after, events(conversationId));
        if (stream != null) return stream.map(event -> event.getErrorDetail() == null
                ? event : event.toBuilder().errorDetail(null).build());
        if (lockService != null && recoveryService != null) {
            GroupConversationLockService.OwnedLock lock = lockService.tryLock(conversationId);
            if (lock == null) return Flux.error(new UserRequestException("生成仍在执行，请稍后重连"));
            try {
                recoveryService.recoverInterrupted(conversationId);
                return Flux.just(GroupChatEvent.builder().eventType(GroupChatConstant.EVENT_GENERATION_FAILED)
                        .conversationId(conversationId).error("生成连接已失效，请重试此行动轮").build());
            } finally {
                lockService.unlock(lock);
            }
        }
        return Flux.error(new UserRequestException("生成流不存在或已过期"));
    }

    public void evict(Long conversationId) {
        if (conversationId != null) streams.evict(conversationId::equals);
    }

    private GenerationStreams.Events<GroupChatEvent> events(Long conversationId) {
        return new GenerationStreams.Events<>() {
            public GroupChatEvent sequence(GroupChatEvent event, long sequence) {
                return event.toBuilder().eventSequence(sequence).build();
            }
            public boolean failed(GroupChatEvent event) {
                return GroupChatConstant.EVENT_GENERATION_FAILED.equals(event.getEventType());
            }
            public GroupChatEvent failure(Throwable error) {
                return completion(true);
            }
            public GroupChatEvent completion(boolean failed) {
                return GroupChatEvent.builder().conversationId(conversationId)
                        .eventType(failed ? GroupChatConstant.EVENT_GENERATION_FAILED : GroupChatConstant.EVENT_GENERATION_COMPLETED)
                        .error(failed ? "生成已中断，请读取最新状态后重试" : null).build();
            }
            public GroupChatEvent terminal() { return null; } // Domain events close the current turn/step.
            public GroupChatEvent caughtUp() {
                return GroupChatEvent.builder().conversationId(conversationId)
                        .eventType(GroupChatConstant.EVENT_STREAM_CAUGHT_UP).build();
            }
        };
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
            boolean requiresRollback = error instanceof TurnCheckpointUnavailableException;
            boolean requiresWithdrawal = error instanceof GroupCheckpointUnavailableException;
            return GenerationErrorDetailVO.builder()
                    .errorId(UUID.randomUUID().toString())
                    .code(requiresRollback ? TurnCheckpointUnavailableException.CODE
                            : requiresWithdrawal ? GroupCheckpointUnavailableException.CODE : "GENERATION_FAILED")
                    .category("GENERATION")
                    .message(message)
                    .retryable(!requiresRollback && !requiresWithdrawal)
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
