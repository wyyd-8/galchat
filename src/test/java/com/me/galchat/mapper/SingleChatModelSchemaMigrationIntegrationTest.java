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
class SingleChatModelSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260904__single_chat_model_runtime.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationAddsANullableWeakModelBindingToCharacters() throws Exception {
        assertThat(MIGRATION).exists();
        String schema = "single_chat_model_" + UUID.randomUUID()
                .toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
        jdbcTemplate.execute("""
                CREATE TABLE user_character_info (
                    user_world_id BIGINT NOT NULL,
                    character_id BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'user_character_info'
                  AND column_name = 'model_api_id'
                """, schema))
                .containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = ?
                  AND table_name = 'user_character_info'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class, schema)).isZero();
    }
}
