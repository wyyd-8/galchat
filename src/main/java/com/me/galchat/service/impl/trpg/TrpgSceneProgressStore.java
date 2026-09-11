package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TrpgSceneProgressStore {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public void markReady(
            Long conversationId, Long sceneId,
            Long subjectCharacterId) {
        String key = readyKey(conversationId, sceneId);
        redisTemplate.opsForSet().add(
                key, actorKey(subjectCharacterId));
        redisTemplate.expire(key, TTL);
    }

    public Set<Long> readyCharacterIds(
            Long conversationId, Long sceneId) {
        Set<String> values = redisTemplate.opsForSet()
                .members(readyKey(conversationId, sceneId));
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value.startsWith("character-card:")) {
                result.add(Long.valueOf(value.substring(
                        "character-card:".length())));
            }
        }
        return Set.copyOf(result);
    }

    public Set<String> readyActors(
            Long conversationId, Long sceneId) {
        Set<String> values = redisTemplate.opsForSet()
                .members(readyKey(conversationId, sceneId));
        return values == null ? Set.of() : Set.copyOf(values);
    }

    public void clearReady(
            Long conversationId, Long sceneId,
            Long subjectCharacterId) {
        redisTemplate.opsForSet().remove(
                readyKey(conversationId, sceneId),
                actorKey(subjectCharacterId));
    }

    public static String actorKey(Long subjectCharacterId) {
        if (subjectCharacterId == null) {
            throw new IllegalArgumentException(
                    "人物卡ID不能为空");
        }
        return "character-card:" + subjectCharacterId;
    }

    public void requestFinish(Long conversationId, Long sceneId) {
        String key = finishKey(conversationId, sceneId);
        redisTemplate.opsForValue().set(key, "1", TTL);
    }

    public boolean isFinishRequested(Long conversationId, Long sceneId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                finishKey(conversationId, sceneId)));
    }

    public void clear(Long conversationId, Long sceneId) {
        redisTemplate.delete(java.util.List.of(
                readyKey(conversationId, sceneId),
                finishKey(conversationId, sceneId)));
    }

    private String readyKey(Long conversationId, Long sceneId) {
        return baseKey(conversationId, sceneId) + ":ready";
    }

    private String finishKey(Long conversationId, Long sceneId) {
        return baseKey(conversationId, sceneId) + ":finish";
    }

    private String baseKey(Long conversationId, Long sceneId) {
        return RedisConstant.TRPG_SCENE_PROGRESS_PREFIX
                + conversationId + ":" + sceneId;
    }
}
