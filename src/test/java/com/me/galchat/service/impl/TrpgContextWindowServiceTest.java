package com.me.galchat.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrpgContextWindowServiceTest {

    @Test
    void recordsKpAndInvestigatorPromptsInSeparateActorFields() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash =
                mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        TrpgContextWindowService service =
                new TrpgContextWindowService(redis);

        service.recordPrompt(7L, "kp", null, List.of(
                new SystemMessage("123"),
                new UserMessage("你好")));
        service.recordPrompt(7L, "character", 109L, List.of(
                new SystemMessage("调查员")));

        verify(hash).putAll(eq("trpg:group:context-window:7"),
                org.mockito.ArgumentMatchers.argThat(values ->
                        "5".equals(values.get("kp.characterCount"))
                                && "100000".equals(values.get("kp.softLimit"))
                                && values.keySet().stream()
                                .allMatch(key -> key.toString()
                                        .startsWith("kp."))));
        verify(hash).putAll(eq("trpg:group:context-window:7"),
                org.mockito.ArgumentMatchers.argThat(values ->
                        "3".equals(values.get(
                                "investigator:109.characterCount"))
                                && "100000".equals(values.get(
                                "investigator:109.softLimit"))
                                && values.keySet().stream()
                                .allMatch(key -> key.toString()
                                        .startsWith("investigator:109."))));
        verify(redis, org.mockito.Mockito.times(2)).expire(
                eq("trpg:group:context-window:7"), any());
    }

    @Test
    void returnsKpAndInvestigatorsSortedBySubjectCharacterId() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash =
                mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put("investigator:109.characterCount", "21000");
        values.put("investigator:109.softLimit", "100000");
        values.put("investigator:109.ratio", "0.21");
        values.put("investigator:109.updatedAt", "2026-08-31T02:00:00Z");
        values.put("kp.characterCount", "82000");
        values.put("kp.softLimit", "100000");
        values.put("kp.ratio", "0.82");
        values.put("kp.updatedAt", "2026-08-31T01:00:00Z");
        values.put("investigator:71.characterCount", "31000");
        values.put("investigator:71.softLimit", "100000");
        values.put("investigator:71.ratio", "0.31");
        values.put("investigator:71.updatedAt", "2026-08-31T03:00:00Z");
        when(hash.entries("trpg:group:context-window:7"))
                .thenReturn(values);
        TrpgContextWindowService service =
                new TrpgContextWindowService(redis);

        TrpgContextWindowService.ContextWindowOverview result =
                service.get(7L);

        assertThat(result.kp().characterCount()).isEqualTo(82000L);
        assertThat(result.investigators())
                .extracting(
                        TrpgContextWindowService.InvestigatorContextWindowUsage
                                ::subjectCharacterId)
                .containsExactly(71L, 109L);
        assertThat(result.investigators().getFirst().usage()
                .characterCount()).isEqualTo(31000L);
    }
}
