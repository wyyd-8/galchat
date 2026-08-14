package com.me.galchat.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTrpgProposalOrderStoreTest {

    @Test
    void loadReadsTheAtomicCursorAndActorOrderValue() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values =
                mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(
                "trpg:group:proposal-order:51:state"))
                .thenReturn("""
                        {"cursorTurnId":40,"actorKeys":["character-card:112","character-card:71"]}
                        """);
        RedisTrpgProposalOrderStore store =
                new RedisTrpgProposalOrderStore(
                        redis, new ObjectMapper());

        assertThat(store.load(51L)).contains(
                new TrpgProposalOrderStore.State(
                        40L,
                        List.of("character-card:112", "character-card:71")));
    }

    @Test
    void saveWritesOneJsonValueWithTransientTtl() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values =
                mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper objectMapper = new ObjectMapper();
        RedisTrpgProposalOrderStore store =
                new RedisTrpgProposalOrderStore(redis, objectMapper);

        store.save(51L, new TrpgProposalOrderStore.State(
                41L,
                List.of("character-card:71", "character-card:112")));

        ArgumentCaptor<String> json =
                ArgumentCaptor.forClass(String.class);
        verify(values).set(
                org.mockito.ArgumentMatchers.eq(
                        "trpg:group:proposal-order:51:state"),
                json.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofDays(7)));
        JsonNode written = objectMapper.readTree(json.getValue());
        assertThat(written.get("cursorTurnId").longValue())
                .isEqualTo(41L);
        assertThat(written.get("actorKeys"))
                .extracting(JsonNode::textValue)
                .containsExactly("character-card:71", "character-card:112");
    }
}
