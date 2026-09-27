package com.me.galchat.service.impl.chat;

import com.me.galchat.domain.vo.ChatFluxVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.generation.GenerationStreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Objects;

@Service
@Slf4j
public class SingleChatGenerationRegistry {
    private record Conversation(Integer userId, Long worldId, Long characterId) { }
    private final GenerationStreams<Conversation, ChatFluxVO> streams = new GenerationStreams<>(Duration.ofMinutes(5));
    private final GenerationStreams.Events<ChatFluxVO> events = new GenerationStreams.Events<>() {
        public ChatFluxVO sequence(ChatFluxVO event, long sequence) {
            return new ChatFluxVO(event.getType(), event.getContent(), sequence);
        }
        public boolean failed(ChatFluxVO event) { return "generation.failed".equals(event.getType()); }
        public ChatFluxVO failure(Throwable error) {
            log.warn("Single chat generation failed", error);
            return new ChatFluxVO("generation.failed", error instanceof UserRequestException
                    ? error.getMessage() : "角色回复生成失败，请稍后重试。");
        }
        public ChatFluxVO completion(boolean failed) {
            return new ChatFluxVO(failed ? "generation.failed" : "generation.completed",
                    failed ? "角色回复生成失败，请读取历史后重试。" : null);
        }
    };

    public Flux<ChatFluxVO> start(Integer userId, Long worldId, Long characterId,
                                  String requestId, String message, Flux<ChatFluxVO> source) {
        if (!StringUtils.hasText(requestId)) return source.contextCapture();
        validateRequestId(requestId);
        return streams.start(new Conversation(userId, worldId, characterId), requestId,
                Flux.concat(Flux.just(new ChatFluxVO("generation.started", message)), source), events);
    }

    public Flux<ChatFluxVO> resume(Integer userId, Long worldId, Long characterId, String requestId, long after) {
        validateRequestId(requestId);
        Flux<ChatFluxVO> stream = streams.resume(new Conversation(userId, worldId, characterId), requestId, after, events);
        return stream != null ? stream : Flux.just(new ChatFluxVO("generation.expired", "回复续接已失效，请读取聊天历史。"));
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
}
