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
    private static final Path MELEE_MIGRATION = Path.of(
            "docs/sql/V20260817_2__coc_melee_weapon_impale.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationAddsAndBackfillsImpaleCapability() throws Exception {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_character_weapon (
                    name VARCHAR(255),
                    skill_name VARCHAR(255),
                    damage VARCHAR(100)
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_character_weapon(name, skill_name, damage)
                VALUES ('左轮手枪', '射击:手枪', '1D10'),
                       ('泰瑟枪', '射击:手枪', '1D3+眩晕'),
                       ('小型刀具（折叠刀等）', '斗殴', '1D4+DB'),
                       ('自制尖刺', '斗殴', '1D4+DB')
                """);

        jdbcTemplate.execute(Files.readString(MIGRATION));
        jdbcTemplate.execute(Files.readString(MELEE_MIGRATION));

        assertThat(jdbcTemplate.queryForList("""
                SELECT name FROM coc_character_weapon
                WHERE can_impale = TRUE ORDER BY name
                """, String.class)).containsExactly(
                        "小型刀具（折叠刀等）", "左轮手枪");
    }
}
