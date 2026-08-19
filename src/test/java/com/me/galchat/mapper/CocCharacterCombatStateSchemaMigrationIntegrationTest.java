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
class CocCharacterCombatStateSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260819__coc_character_combat_states.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationBackfillsSafeCombatStateDefaults() throws Exception {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_character (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_character(id, name)
                VALUES (1, '林恩'), (2, '食尸鬼')
                """);

        jdbcTemplate.execute(Files.readString(MIGRATION));
        jdbcTemplate.update("""
                UPDATE coc_character
                SET restrained_by_character_id = 2
                WHERE id = 1
                """);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT in_cover, cover_action_forfeit_pending,
                       stunned_remaining_rounds,
                       restrained_by_character_id,
                       melee_attacked_this_round
                FROM coc_character WHERE id = 1
                """);
        assertThat(row)
                .containsEntry("in_cover", false)
                .containsEntry("cover_action_forfeit_pending", false)
                .containsEntry("stunned_remaining_rounds", 0)
                .containsEntry("restrained_by_character_id", 2L)
                .containsEntry("melee_attacked_this_round", false);
    }
}
