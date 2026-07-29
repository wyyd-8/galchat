package com.me.galchat.service.impl;

import com.me.galchat.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TrpgMaterialStateStore {

    private static final Duration TTL = Duration.ofDays(90);

    private final StringRedisTemplate redisTemplate;

    public Set<Long> shownIds(Long conversationId) {
        Set<String> members = redisTemplate.opsForSet()
                .members(key(conversationId));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String member : members) {
            result.add(Long.valueOf(member));
        }
        return Set.copyOf(result);
    }

    public boolean isShown(Long conversationId, Long materialId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember(key(conversationId), materialId.toString()));
    }

    public void markShown(Long conversationId, Long materialId) {
        String key = key(conversationId);
        redisTemplate.opsForSet().add(key, materialId.toString());
        redisTemplate.expire(key, TTL);
    }

    private String key(Long conversationId) {
        return RedisConstant.TRPG_SHOWN_MATERIALS_PREFIX + conversationId;
    }
}
