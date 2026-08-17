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
class CocCharacterWeaponSchemaMigrationIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "docs/sql/V20260817__coc_character_weapon_impale.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationAddsAndBackfillsImpaleCapability() throws Exception {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_character_weapon (
                    skill_name VARCHAR(255),
                    damage VARCHAR(100)
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_character_weapon(skill_name, damage)
                VALUES ('射击:手枪', '1D10'), ('射击:手枪', '1D3+眩晕')
                """);

        jdbcTemplate.execute(Files.readString(MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT can_impale FROM coc_character_weapon ORDER BY damage
                """, Boolean.class)).containsExactly(true, false);
    }
}
