package com.me.galchat.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GroupActorRuntimeSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260831__group_actor_runtime.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesWeakModelBindingAndStepExecutionSnapshot()
            throws Exception {
        assertThat(MIGRATION).exists();
        String schema = "actor_runtime_" + UUID.randomUUID()
                .toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
        jdbcTemplate.execute("""
                CREATE TABLE group_chat_reply_step (
                    id BIGSERIAL PRIMARY KEY
                )
                """);
        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'group_actor_runtime_config'
                ORDER BY ordinal_position
                """, String.class, schema)).containsExactly(
                "id", "conversation_id", "actor_key", "actor_type",
                "actor_id", "control_mode", "model_api_id",
                "created_at", "updated_at");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'group_chat_reply_step'
                ORDER BY ordinal_position
                """, String.class, schema)).containsExactly(
                "id", "execution_mode", "model_api_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = ?
                  AND table_name = 'group_actor_runtime_config'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class, schema)).isZero();
    }
}
