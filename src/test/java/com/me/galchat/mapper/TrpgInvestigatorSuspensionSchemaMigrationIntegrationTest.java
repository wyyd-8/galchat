package com.me.galchat.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgInvestigatorSuspensionSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260827__trpg_investigator_suspension.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesOnlyTheRuntimeFieldsNeededForNarrativeSuspension()
            throws Exception {
        assertThat(MIGRATION).exists();

        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'trpg_investigator_suspension'
                ORDER BY ordinal_position
                """, String.class)).containsExactly(
                "id", "conversation_id", "subject_character_id",
                "state", "suspension_context", "origin_context_id",
                "reentry_context", "recovery_scene_name",
                "recovery_plan_id", "created_at", "updated_at");
    }
}
