package com.me.galchat.service.impl;

import com.me.galchat.constant.RedisConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RedisTrpgProposalOrderStore
        implements TrpgProposalOrderStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<State> load(Long conversationId) {
        String raw = redisTemplate.opsForValue()
                .get(key(conversationId));
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        try {
            State state = objectMapper.readValue(raw, State.class);
            return valid(state) ? Optional.of(state)
                    : Optional.empty();
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void save(Long conversationId, State state) {
        try {
            redisTemplate.opsForValue().set(
                    key(conversationId),
                    objectMapper.writeValueAsString(state),
                    TrpgRedisStateService.TRANSIENT_TTL);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "无法序列化TRPG提案顺序缓存", exception);
        }
    }

    @Override
    public void evict(Long conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private boolean valid(State state) {
        if (state == null || state.cursorTurnId() == null
                || state.cursorTurnId() < 0L
                || state.actorKeys() == null) {
            return false;
        }
        Set<String> unique = new HashSet<>();
        for (String actorKey : state.actorKeys()) {
            if (!StringUtils.hasText(actorKey)
                    || !actorKey.startsWith("character-card:")
                    || !unique.add(actorKey)) {
                return false;
            }
        }
        return true;
    }

    private String key(Long conversationId) {
        return RedisConstant.TRPG_PROPOSAL_ORDER_PREFIX
                + conversationId + ":state";
    }
}
