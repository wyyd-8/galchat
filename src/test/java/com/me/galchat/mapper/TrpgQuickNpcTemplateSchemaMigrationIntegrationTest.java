package com.me.galchat.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgQuickNpcTemplateSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260824__trpg_quick_npc_templates.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationBackfillsAndDefaultsPendingQuickNpcSpecs()
            throws Exception {
        assertThat(MIGRATION).exists();
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_combat (
                    id BIGINT PRIMARY KEY
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("INSERT INTO trpg_combat(id) VALUES (1)");

        jdbcTemplate.execute(Files.readString(MIGRATION));
        jdbcTemplate.update("INSERT INTO trpg_combat(id) VALUES (2)");

        assertThat(jdbcTemplate.queryForList("""
                        SELECT id, quick_npc_specs
                        FROM trpg_combat
                        ORDER BY id
                        """))
                .allSatisfy(row -> assertThat(row.get("quick_npc_specs"))
                        .hasToString("[]"));
        Map<String, Object> column = jdbcTemplate.queryForMap("""
                SELECT is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema LIKE 'pg_temp_%'
                  AND table_name = 'trpg_combat'
                  AND column_name = 'quick_npc_specs'
                """);
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(column.get("column_default").toString())
                .contains("[]");
    }
}
