package com.me.galchat.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgContextWindowServiceTest {

    @Test
    void recordsFinalKpPromptCharacterCountForFrontend() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash =
                mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        TrpgContextWindowService service =
                new TrpgContextWindowService(redis);

        service.recordPrompt(7L, List.of(
                new SystemMessage("123"),
                new UserMessage("你好")));

        verify(hash).putAll(eq("trpg:group:context-window:7"),
                org.mockito.ArgumentMatchers.argThat(values ->
                        "5".equals(values.get("characterCount"))
                                && "100000".equals(values.get("softLimit"))));
        verify(redis).expire(
                eq("trpg:group:context-window:7"), any());
    }
}
