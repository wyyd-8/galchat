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
class ShotgunDamageBandMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260829__normalize_shotgun_damage_bands.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationNormalizesLiveAndStashedLegacyShotgunDamage() throws Exception {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_character_weapon (
                    id BIGINT,
                    damage VARCHAR(100)
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_weapon_stash (
                    weapon_id BIGINT,
                    weapon_snapshot JSONB
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_module_character (
                    id BIGINT,
                    card_data JSONB
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_save (
                    id BIGINT,
                    snapshot JSONB
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_auto_save (
                    conversation_id BIGINT,
                    snapshot JSONB
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_character_weapon(id, damage)
                VALUES (1, '近4D6；中2D6；远1D6'),
                       (2, '近4D6；中1D6；远无效'),
                       (3, '1D10')
                """);
        jdbcTemplate.update("""
                INSERT INTO trpg_weapon_stash(weapon_id, weapon_snapshot)
                VALUES (11, '{"name":"双管霰弹枪","damage":"近4D6；中2D6；远1D6"}'),
                       (12, '{"name":"锯短霰弹枪","damage":"近4D6；中1D6；远无效"}'),
                       (13, '{"name":"手枪","damage":"1D10"}')
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_module_character(id, card_data)
                VALUES (21, '{"weapons":[{"damage":"近4D6；中2D6；远1D6"},{"damage":"1D10"}]}')
                """);
        String saveSnapshot = """
                {"characterWeapons":[{"damage":"近4D6；中2D6；远1D6"}],
                 "weaponStash":[{"weaponSnapshot":{"damage":"近4D6；中1D6；远无效"}}]}
                """;
        jdbcTemplate.update(
                "INSERT INTO trpg_save(id, snapshot) VALUES (31, ?::jsonb)",
                saveSnapshot);
        jdbcTemplate.update("""
                INSERT INTO trpg_auto_save(conversation_id, snapshot)
                VALUES (41, ?::jsonb)
                """, saveSnapshot);

        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT damage FROM coc_character_weapon ORDER BY id
                """, String.class)).containsExactly(
                        "4D6/2D6/1D6", "4D6/1D6/0", "1D10");
        assertThat(jdbcTemplate.queryForList("""
                SELECT weapon_snapshot ->> 'damage'
                FROM trpg_weapon_stash ORDER BY weapon_id
                """, String.class)).containsExactly(
                        "4D6/2D6/1D6", "4D6/1D6/0", "1D10");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT card_data #>> '{weapons,0,damage}'
                FROM coc_module_character WHERE id = 21
                """, String.class)).isEqualTo("4D6/2D6/1D6");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT card_data #>> '{weapons,1,damage}'
                FROM coc_module_character WHERE id = 21
                """, String.class)).isEqualTo("1D10");
        assertSaveSnapshotNormalized("trpg_save", "id", 31L);
        assertSaveSnapshotNormalized(
                "trpg_auto_save", "conversation_id", 41L);
    }

    private void assertSaveSnapshotNormalized(
            String table, String idColumn, long id) {
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT snapshot::text FROM " + table
                        + " WHERE " + idColumn + " = ?",
                String.class, id);
        assertThat(snapshot)
                .contains("4D6/2D6/1D6", "4D6/1D6/0")
                .doesNotContain("近4D6");
    }
}
