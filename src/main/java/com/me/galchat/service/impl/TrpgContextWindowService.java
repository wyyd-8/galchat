package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
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
import java.util.TreeSet;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrpgContextWindowService {

    public static final long SOFT_LIMIT = 100_000L;
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public void recordPrompt(Long conversationId, String actorType,
                             Long subjectCharacterId,
                             List<Message> messages) {
        String actorKey = actorKey(actorType, subjectCharacterId);
        if (actorKey == null) {
            log.warn("跳过无法识别角色的上下文窗口统计, conversationId:{}, actorType:{}, subjectCharacterId:{}",
                    conversationId, actorType, subjectCharacterId);
            return;
        }
        long characterCount = messages == null ? 0L : messages.stream()
                .map(Message::getText)
                .filter(java.util.Objects::nonNull)
                .mapToLong(String::length)
                .sum();
        String key = key(conversationId);
        Map<String, String> values = new LinkedHashMap<>();
        values.put(field(actorKey, "characterCount"),
                Long.toString(characterCount));
        values.put(field(actorKey, "softLimit"),
                Long.toString(SOFT_LIMIT));
        values.put(field(actorKey, "ratio"), Double.toString(
                characterCount / (double) SOFT_LIMIT));
        values.put(field(actorKey, "updatedAt"),
                Instant.now().toString());
        try {
            redisTemplate.opsForHash().putAll(key, values);
            redisTemplate.expire(key, TTL);
        } catch (RuntimeException exception) {
            log.warn("记录角色上下文窗口长度失败, conversationId:{}, actorKey:{}",
                    conversationId, actorKey, exception);
        }
    }

    public ContextWindowOverview get(Long conversationId) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash()
                    .entries(key(conversationId));
            if (values == null || values.isEmpty()) {
                return null;
            }
            ContextWindowUsage kp = usage(values, "kp");
            List<InvestigatorContextWindowUsage> investigators =
                    investigatorIds(values).stream()
                            .map(subjectCharacterId ->
                                    new InvestigatorContextWindowUsage(
                                            subjectCharacterId,
                                            usage(values, "investigator:"
                                                    + subjectCharacterId)))
                            .filter(item -> item.usage() != null)
                            .toList();
            if (kp == null && investigators.isEmpty()) {
                return null;
            }
            return new ContextWindowOverview(kp, investigators);
        } catch (RuntimeException exception) {
            log.warn("读取角色上下文窗口长度失败, conversationId:{}",
                    conversationId, exception);
            return null;
        }
    }

    private ContextWindowUsage usage(Map<Object, Object> values,
                                     String actorKey) {
        String characterCount = value(values,
                field(actorKey, "characterCount"));
        String softLimit = value(values, field(actorKey, "softLimit"));
        String ratio = value(values, field(actorKey, "ratio"));
        String updatedAt = value(values, field(actorKey, "updatedAt"));
        if (characterCount == null || softLimit == null || ratio == null
                || updatedAt == null) {
            return null;
        }
        return new ContextWindowUsage(
                Long.parseLong(characterCount),
                Long.parseLong(softLimit),
                Double.parseDouble(ratio),
                updatedAt);
    }

    private TreeSet<Long> investigatorIds(Map<Object, Object> values) {
        TreeSet<Long> result = new TreeSet<>();
        String prefix = "investigator:";
        for (Object candidate : values.keySet()) {
            String key = candidate == null ? "" : candidate.toString();
            if (!key.startsWith(prefix)) {
                continue;
            }
            int fieldSeparator = key.indexOf('.', prefix.length());
            if (fieldSeparator < 0) {
                continue;
            }
            try {
                result.add(Long.parseLong(
                        key.substring(prefix.length(), fieldSeparator)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed cache fields instead of failing the API.
            }
        }
        return result;
    }

    private String actorKey(String actorType, Long subjectCharacterId) {
        if (GroupChatConstant.ACTOR_KP.equals(actorType)) {
            return "kp";
        }
        if (GroupChatConstant.ACTOR_CHARACTER.equals(actorType)
                && subjectCharacterId != null) {
            return "investigator:" + subjectCharacterId;
        }
        return null;
    }

    private String field(String actorKey, String name) {
        return actorKey + "." + name;
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
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

    public record InvestigatorContextWindowUsage(
            Long subjectCharacterId,
            ContextWindowUsage usage) {
    }

    public record ContextWindowOverview(
            ContextWindowUsage kp,
            List<InvestigatorContextWindowUsage> investigators) {

        public ContextWindowOverview {
            investigators = List.copyOf(investigators);
        }
    }
}
