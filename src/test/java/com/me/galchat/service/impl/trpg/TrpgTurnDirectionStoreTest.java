package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.RedisConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrpgTurnDirectionStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private TrpgTurnDirectionStore store;

    @BeforeEach
    void setUp() {
        store = new TrpgTurnDirectionStore(redisTemplate);
    }

    @Test
    void bindStoresTrimmedDirectionWithTurnScopedTtl() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        store.bind(51L, 77L, "  优先确认地下室入口。  ");

        String key = RedisConstant.TRPG_TURN_DIRECTION_PREFIX + "51";
        verify(hashOperations).putAll(key, Map.of(
                "turnId", "77",
                "direction", "优先确认地下室入口。"));
        verify(redisTemplate).expire(key, Duration.ofHours(6));
    }

    @Test
    void findRejectsDirectionBoundToAnotherTurn() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        String key = RedisConstant.TRPG_TURN_DIRECTION_PREFIX + "51";
        when(hashOperations.entries(key)).thenReturn(Map.of(
                "turnId", "76",
                "direction", "上一轮内容"));

        assertThat(store.find(51L, 77L)).isEmpty();
    }

    @Test
    void findReturnsDirectionForTheBoundTurn() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        String key = RedisConstant.TRPG_TURN_DIRECTION_PREFIX + "51";
        when(hashOperations.entries(key)).thenReturn(Map.of(
                "turnId", "77",
                "direction", "优先确认地下室入口。"));

        assertThat(store.find(51L, 77L))
                .contains("优先确认地下室入口。");
    }

    @Test
    void clearDeletesTheConversationDirection() {
        store.clear(51L);

        verify(redisTemplate).delete(
                RedisConstant.TRPG_TURN_DIRECTION_PREFIX + "51");
    }
}
