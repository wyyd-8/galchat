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
class TrpgWeaponStashSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260825__trpg_weapon_stash.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesRunScopedImmutableWeaponSnapshots()
            throws Exception {
        assertThat(MIGRATION).exists();

        jdbcTemplate.execute(Files.readString(MIGRATION));
        Map<String, Object> snapshotColumn = jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'trpg_weapon_stash'
                  AND column_name = 'weapon_snapshot'
                """);

        assertThat(snapshotColumn)
                .containsEntry("data_type", "jsonb")
                .containsEntry("is_nullable", "NO");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'trpg_weapon_stash'
                """, String.class))
                .contains("weapon_id", "run_id", "location_name",
                        "source_character_name", "stash_reason",
                        "weapon_snapshot", "stashed_at")
                .doesNotContain("character_id");
    }
}
