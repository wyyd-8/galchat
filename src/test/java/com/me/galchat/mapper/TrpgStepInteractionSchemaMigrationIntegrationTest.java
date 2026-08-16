package com.me.galchat.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgStepInteractionSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260816__trpg_step_interactions.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesInteractionColumnsAndIndexesOnPostgresql() throws Exception {
        assertThat(MIGRATION).exists();
        jdbcTemplate.execute("""
                CREATE TEMP TABLE group_chat_reply_step (
                    step_no INT NOT NULL
                ) ON COMMIT DROP
                """);

        jdbcTemplate.execute(Files.readString(MIGRATION));

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT attname
                FROM pg_attribute
                WHERE attrelid = 'group_chat_reply_step'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                ORDER BY attnum
                """, String.class);
        assertThat(columns).containsExactly(
                "step_no",
                "parent_step_id",
                "root_step_id",
                "interaction_type",
                "interaction_seq",
                "prompt_message_id");

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname LIKE 'pg_temp_%'
                  AND tablename = 'group_chat_reply_step'
                ORDER BY indexname
                """, String.class);
        assertThat(indexes).containsExactlyInAnyOrder(
                "idx_reply_step_parent",
                "idx_reply_step_prompt_message",
                "idx_reply_step_root_interaction");
    }
}
