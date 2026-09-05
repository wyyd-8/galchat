package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.RedisConstant;
import com.me.galchat.groupchat.runtime.GroupActorRef;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TrpgSceneSelectionStore {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public void put(Long conversationId, Long characterId, Long locationId) {
        put(conversationId, new GroupActorRef(
                "character", characterId), locationId);
    }

    public void put(
            Long conversationId,
            GroupActorRef actor,
            Long locationId) {
        put(conversationId, requireTurnId(conversationId),
                actor, locationId);
    }

    public void put(
            Long conversationId,
            Long turnId,
            GroupActorRef actor,
            Long locationId) {
        String key = choicesKey(conversationId, turnId);
        redisTemplate.opsForHash().put(
                key, actorKey(actor), locationId.toString());
        redisTemplate.expire(key, TTL);
    }

    public Map<Long, Long> getAll(Long conversationId) {
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(choicesKey(
                        conversationId,
                        requireTurnId(conversationId)));
        Map<Long, Long> result = new LinkedHashMap<>();
        values.forEach((characterId, locationId) -> {
            String actor = characterId.toString();
            if (actor.startsWith("user:")) {
                return;
            }
            String id = actor.startsWith("character:")
                    ? actor.substring("character:".length()) : actor;
            result.put(Long.valueOf(id),
                    Long.valueOf(locationId.toString()));
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    public Map<String, Long> getSelections(Long conversationId) {
        return getSelections(
                conversationId, requireTurnId(conversationId));
    }

    public Map<String, Long> getSelections(
            Long conversationId, Long turnId) {
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(choicesKey(conversationId, turnId));
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((actor, locationId) -> result.put(
                actor.toString(), Long.valueOf(locationId.toString())));
        return java.util.Collections.unmodifiableMap(result);
    }

    public void putOptions(
            Long conversationId,
            Map<String, LocationOption> options) {
        putOptions(conversationId, 0L, options);
    }

    public void putOptions(
            Long conversationId,
            Long turnId,
            Map<String, LocationOption> options) {
        String key = optionsKey(conversationId, turnId);
        redisTemplate.delete(key);
        options.forEach((number, option) ->
                redisTemplate.opsForHash().put(
                        key, number,
                        option.locationId() + "\t" + option.name()));
        redisTemplate.expire(key, TTL);
        redisTemplate.opsForValue().set(
                activeTurnKey(conversationId),
                turnId.toString(), TTL);
    }

    public Map<String, LocationOption> getOptions(
            Long conversationId) {
        return getOptions(
                conversationId, requireTurnId(conversationId));
    }

    public Map<String, LocationOption> getOptions(
            Long conversationId, Long turnId) {
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(optionsKey(conversationId, turnId));
        Map<String, LocationOption> result = new LinkedHashMap<>();
        values.forEach((number, encoded) -> {
            String[] parts = encoded.toString().split("\t", 2);
            if (parts.length == 2) {
                result.put(number.toString(), new LocationOption(
                        Long.valueOf(parts[0]), parts[1]));
            }
        });
        Map<String, LocationOption> ordered = new LinkedHashMap<>();
        result.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparingInt(
                                Integer::parseInt)))
                .forEach(entry -> ordered.put(
                        entry.getKey(), entry.getValue()));
        return java.util.Collections.unmodifiableMap(ordered);
    }

    public void clear(Long conversationId) {
        Long turnId = currentTurnId(conversationId);
        if (turnId != null) {
            redisTemplate.delete(choicesKey(
                    conversationId, turnId));
            redisTemplate.delete(optionsKey(
                    conversationId, turnId));
        }
        redisTemplate.delete(activeTurnKey(conversationId));
    }

    public Long currentTurnId(Long conversationId) {
        String value = redisTemplate.opsForValue().get(
                activeTurnKey(conversationId));
        return value == null ? null : Long.valueOf(value);
    }

    private Long requireTurnId(Long conversationId) {
        Long turnId = currentTurnId(conversationId);
        return turnId == null ? 0L : turnId;
    }

    private String choicesKey(
            Long conversationId, Long turnId) {
        return baseKey(conversationId, turnId) + ":choices";
    }

    private String optionsKey(
            Long conversationId, Long turnId) {
        return baseKey(conversationId, turnId) + ":options";
    }

    private String activeTurnKey(Long conversationId) {
        return RedisConstant.TRPG_SCENE_SELECTION_PREFIX
                + conversationId + ":active";
    }

    private String baseKey(
            Long conversationId, Long turnId) {
        return RedisConstant.TRPG_SCENE_SELECTION_PREFIX
                + conversationId + ":" + turnId;
    }

    public static String actorKey(GroupActorRef actor) {
        return actor.type() + ":" + actor.id();
    }

    public record LocationOption(Long locationId, String name) {
    }
}
