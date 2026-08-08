package com.me.galchat.mapper;

import com.me.galchat.domain.po.WorldEventLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class WorldEventLogMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorldEventLogMapper eventLogMapper;

    @Test
    void selectLastRestorableDeserializesVisibleCharacterArray() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE group_conversation (
                    id BIGINT PRIMARY KEY,
                    mode VARCHAR(20) NOT NULL
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE world_event_log (
                    id BIGINT PRIMARY KEY,
                    user_world_id BIGINT NOT NULL,
                    event_description TEXT,
                    visible_characters BIGINT[],
                    timestamp TIMESTAMP,
                    title VARCHAR(255),
                    conversation_id BIGINT
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO group_conversation (id, mode)
                VALUES (?, 'chat')
                """, -9101L);
        jdbcTemplate.update("""
                INSERT INTO world_event_log (
                    id, user_world_id, event_description,
                    visible_characters, timestamp, title, conversation_id
                ) VALUES (?, ?, ?, ARRAY[?, ?]::BIGINT[], CURRENT_TIMESTAMP, ?, ?)
                """, -9102L, -9103L, "array mapper regression",
                101L, 102L, "test event", -9101L);

        WorldEventLog event = eventLogMapper.selectLastRestorable(-9103L);

        assertThat(event).isNotNull();
        assertThat(event.getVisibleCharacters()).containsExactly(101L, 102L);
    }
}
