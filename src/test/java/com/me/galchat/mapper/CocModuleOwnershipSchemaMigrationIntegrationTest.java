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
class CocModuleOwnershipSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260902__coc_module_ownership_and_lock.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationAddsOnlyOwnershipAndEditLockState() throws Exception {
        assertThat(MIGRATION).exists();
        String schema = "coc_module_management_" + UUID.randomUUID()
                .toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA \"" + schema + "\"");
        jdbcTemplate.execute("SET LOCAL search_path TO \"" + schema + "\"");
        jdbcTemplate.execute("""
                CREATE TABLE coc_module (
                    id BIGSERIAL PRIMARY KEY,
                    visible BOOLEAN NOT NULL DEFAULT TRUE
                )
                """);

        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'coc_module'
                ORDER BY ordinal_position
                """, String.class, schema)).containsExactly(
                "id", "visible", "owner_user_id", "edit_locked");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'coc_module'
                  AND column_name = 'edit_locked'
                """, String.class, schema)).isEqualTo("false");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = ?
                  AND tablename = 'coc_module'
                  AND indexname = 'idx_coc_module_owner_visible'
                """, Integer.class, schema)).isEqualTo(1);
    }
}
