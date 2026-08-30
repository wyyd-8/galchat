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
class UserModelApiSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260829_2__user_model_api.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesStructuredLatestProbeStateAndUserScopedName()
            throws Exception {
        assertThat(MIGRATION).exists();
        String schema = "model_api_" + UUID.randomUUID()
                .toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'user_model_api'
                ORDER BY ordinal_position
                """, String.class, schema)).containsExactly(
                "id", "user_id", "name", "base_url", "model_name",
                "api_key_encrypted", "api_key_hint", "request_overrides", "status",
                "chat_capability", "streaming_capability",
                "tool_calling_capability", "reasoning_output_status",
                "last_test_code", "last_test_message", "last_test_at",
                "created_at", "updated_at");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = ?
                  AND tablename = 'user_model_api'
                  AND indexname = 'uk_user_model_api_user_name'
                """, Integer.class, schema)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'user_model_api'
                  AND column_name IN ('api_key_encrypted', 'api_key_hint')
                  AND is_nullable = 'YES'
                """, Integer.class, schema)).isZero();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'user_model_api'
                  AND column_name = 'request_overrides'
                """, schema))
                .containsEntry("data_type", "jsonb")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "'{}'::jsonb");
    }

}
