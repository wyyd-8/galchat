package com.me.galchat.service.impl;

import com.me.galchat.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrpgContextWindowService {

    public static final long SOFT_LIMIT = 100_000L;
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public void recordPrompt(Long conversationId, List<Message> messages) {
        long characterCount = messages == null ? 0L : messages.stream()
                .map(Message::getText)
                .filter(java.util.Objects::nonNull)
                .mapToLong(String::length)
                .sum();
        String key = key(conversationId);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("characterCount", Long.toString(characterCount));
        values.put("softLimit", Long.toString(SOFT_LIMIT));
        values.put("ratio", Double.toString(
                characterCount / (double) SOFT_LIMIT));
        values.put("updatedAt", Instant.now().toString());
        try {
            redisTemplate.opsForHash().putAll(key, values);
            redisTemplate.expire(key, TTL);
        } catch (RuntimeException exception) {
            log.warn("记录KP上下文窗口长度失败, conversationId:{}",
                    conversationId, exception);
        }
    }

    public ContextWindowUsage get(Long conversationId) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash()
                    .entries(key(conversationId));
            if (values == null || values.isEmpty()) {
                return null;
            }
            return new ContextWindowUsage(
                    Long.parseLong(value(values, "characterCount")),
                    Long.parseLong(value(values, "softLimit")),
                    Double.parseDouble(value(values, "ratio")),
                    value(values, "updatedAt"));
        } catch (RuntimeException exception) {
            log.warn("读取KP上下文窗口长度失败, conversationId:{}",
                    conversationId, exception);
            return null;
        }
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private String key(Long conversationId) {
        return RedisConstant.TRPG_CONTEXT_WINDOW_PREFIX + conversationId;
    }

    public record ContextWindowUsage(
            long characterCount,
            long softLimit,
            double ratio,
            String updatedAt) {
    }
}
