package com.me.galchat.service.impl;

import com.me.galchat.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TrpgTurnDirectionStore {

    static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;

    public void bind(
            Long conversationId,
            Long turnId,
            String direction) {
        if (!StringUtils.hasText(direction)) {
            clear(conversationId);
            return;
        }
        String key = key(conversationId);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "turnId", turnId.toString(),
                "direction", direction.trim()));
        redisTemplate.expire(key, TTL);
    }

    public Optional<String> find(
            Long conversationId,
            Long turnId) {
        if (conversationId == null || turnId == null) {
            return Optional.empty();
        }
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(key(conversationId));
        Object storedTurnId = values.get("turnId");
        Object direction = values.get("direction");
        if (storedTurnId == null
                || !turnId.toString().equals(storedTurnId.toString())
                || direction == null
                || !StringUtils.hasText(direction.toString())) {
            return Optional.empty();
        }
        return Optional.of(direction.toString().trim());
    }

    public void clear(Long conversationId) {
        if (conversationId != null) {
            redisTemplate.delete(key(conversationId));
        }
    }

    private String key(Long conversationId) {
        return RedisConstant.TRPG_TURN_DIRECTION_PREFIX + conversationId;
    }
}
