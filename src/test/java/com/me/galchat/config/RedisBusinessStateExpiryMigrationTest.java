package com.me.galchat.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.*;
import java.time.Duration;
import java.util.List;
import static org.mockito.Mockito.*;

class RedisBusinessStateExpiryMigrationTest {
    @Test
    void removesExistingDeadlinesFromSceneProgressAndSelectionOnly() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.scan(any())).thenAnswer(call -> {
            String pattern = call.<ScanOptions>getArgument(0).getPattern();
            var entries = switch (pattern) {
                case "trpg:group:scene-progress:*" -> List.of("trpg:group:scene-progress:7:20:ready", "trpg:group:scene-progress:7:20:finish");
                case "trpg:group:scene-selection:*" -> List.of("trpg:group:scene-selection:7:active", "trpg:group:scene-selection:7:9:choices", "trpg:group:scene-selection:7:9:options");
                default -> List.<String>of();
            };
            var iterator = entries.iterator();
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(ignored -> iterator.hasNext());
            when(cursor.next()).thenAnswer(ignored -> iterator.next());
            return cursor;
        });

        new RedisCacheExpiryMigration(redis, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))).run(null);

        verify(redis).persist("trpg:group:scene-progress:7:20:ready");
        verify(redis).persist("trpg:group:scene-progress:7:20:finish");
        verify(redis).persist("trpg:group:scene-selection:7:active");
        verify(redis).persist("trpg:group:scene-selection:7:9:choices");
        verify(redis).persist("trpg:group:scene-selection:7:9:options");
        verify(redis, times(5)).persist(anyString());
    }
}
