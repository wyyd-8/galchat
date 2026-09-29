package com.me.galchat.service.impl.chat;

import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.domain.vo.GenerationErrorDetailVO;
import com.me.galchat.domain.dto.ChatMessageDTO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.generation.GenerationStreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class SingleChatGenerationRegistry {
    private record Conversation(Integer userId, Long worldId, Long characterId) { }
    private final GenerationStreams<Conversation, ChatFluxVO> streams = new GenerationStreams<>(Duration.ofMinutes(5));
    private GenerationStreams.Events<ChatFluxVO> events(FailureTrace trace) {
        return new GenerationStreams.Events<>() {
            public ChatFluxVO sequence(ChatFluxVO event, long sequence) {
                return new ChatFluxVO(event.getType(), event.getContent(), sequence, event.getErrorDetail());
            }
            public boolean failed(ChatFluxVO event) { return "generation.failed".equals(event.getType()); }
            public ChatFluxVO failure(Throwable error) {
                log.warn("Single chat generation failed", error);
                if (trace != null) {
                    GenerationErrorDetailVO detail = trace.detail(error);
                    return new ChatFluxVO("generation.failed", detail.getMessage(), null, detail);
                }
                return new ChatFluxVO("generation.failed", error instanceof UserRequestException
                        ? error.getMessage() : "角色回复生成失败，请稍后重试。");
            }
            public ChatFluxVO completion(boolean failed) {
                return new ChatFluxVO(failed ? "generation.failed" : "generation.completed",
                        failed ? "角色回复生成失败，请读取历史后重试。" : null);
            }
        };
    }

    public Flux<ChatFluxVO> start(Integer userId, Long worldId, Long characterId,
                                  String requestId, String message, Flux<ChatFluxVO> source) {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setUserWorldId(worldId); request.setCharacterId(characterId);
        request.setClientRequestId(requestId); request.setMessage(message);
        return start(userId, request, source);
    }

    public Flux<ChatFluxVO> start(Integer userId, ChatMessageDTO request, Flux<ChatFluxVO> source) {
        String requestId = request.getClientRequestId();
        if (!StringUtils.hasText(requestId)) return source.contextCapture();
        validateRequestId(requestId);
        FailureTrace trace = new FailureTrace(request);
        return streams.start(new Conversation(userId, request.getUserWorldId(), request.getCharacterId()), requestId,
                Flux.concat(Flux.just(new ChatFluxVO("generation.started", request.getMessage())), source)
                        .doOnNext(trace::record), events(trace));
    }

    public Flux<ChatFluxVO> resume(Integer userId, Long worldId, Long characterId, String requestId, long after) {
        validateRequestId(requestId);
        Flux<ChatFluxVO> stream = streams.resume(new Conversation(userId, worldId, characterId), requestId, after, events(null));
        return stream != null ? stream.map(event -> event.getErrorDetail() == null ? event
                : new ChatFluxVO(event.getType(), "角色回复生成失败，请读取历史后重试。", event.getSequence()))
                : Flux.just(new ChatFluxVO("generation.expired", "回复续接已失效，请读取聊天历史。"));
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new UserRequestException("生成标识无效");
        }
    }

    public void evict(Long worldId, Long characterId) {
        streams.evict(conversation -> Objects.equals(conversation.worldId(), worldId)
                && (characterId == null || Objects.equals(conversation.characterId(), characterId)));
    }

    /** Bounded diagnostics for the initial subscriber; completed replay retains only the outcome. */
    private static final class FailureTrace {
        private final Map<String, Object> request;
        private final List<Map<String, Object>> responseEvents = new ArrayList<>();
        private int eventCount;

        private FailureTrace(ChatMessageDTO dto) {
            // Only the public chat payload is included, never model credentials or system prompts.
            Map<String, Object> body = new LinkedHashMap<>();
            if (dto.getWorldId() != null) body.put("worldId", dto.getWorldId());
            body.put("userWorldId", dto.getUserWorldId());
            body.put("characterId", dto.getCharacterId());
            body.put("clientRequestId", dto.getClientRequestId());
            body.put("message", truncated(dto.getMessage(), 2000));
            request = Map.of("method", "POST", "path", "/ai/chat", "body", body);
        }

        private synchronized void record(ChatFluxVO event) {
            eventCount++;
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("type", event.getType());
            if (event.getContent() != null) snapshot.put("content", truncated(event.getContent(), 2000));
            responseEvents.add(snapshot);
            if (responseEvents.size() > 20) responseEvents.removeFirst();
        }

        private synchronized GenerationErrorDetailVO detail(Throwable error) {
            String message = StringUtils.hasText(error.getMessage()) ? error.getMessage() : "角色回复生成失败";
            return GenerationErrorDetailVO.builder()
                    .errorId(UUID.randomUUID().toString()).code("GENERATION_FAILED").category("GENERATION")
                    .message(truncated(message, 2000)).retryable(true).occurredAt(Instant.now().toString())
                    .operation("send-direct-message").request(request)
                    .response(Map.of("eventCount", eventCount, "events", List.copyOf(responseEvents)))
                    .stack(stack(error)).build();
        }

        private static String truncated(String value, int limit) {
            return value == null || value.length() <= limit ? value : value.substring(0, limit) + "…[truncated]";
        }

        private static String stack(Throwable error) {
            StringBuilder output = new StringBuilder();
            Throwable current = error;
            int frames = 0;
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            while (current != null && seen.add(current) && seen.size() <= 10
                    && output.length() < 64 * 1024) {
                if (!output.isEmpty()) output.append("Caused by: ");
                output.append(current).append('\n');
                for (StackTraceElement frame : current.getStackTrace()) {
                    if (frames++ >= 80 || output.length() >= 64 * 1024) break;
                    output.append("\tat ").append(frame).append('\n');
                }
                current = current.getCause();
            }
            return truncated(output.toString(), 64 * 1024);
        }
    }
}
