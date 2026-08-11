package com.me.galchat.mapper;

import com.me.galchat.constant.DiceRollConstant;
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
class DiceRollSchemaMigrationIntegrationTest {

    private static final Path DISPLAY_TYPE_MIGRATION = Path.of(
            "docs/sql/V20260811__expand_dice_roll_display_type.sql");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void displayTypeMigrationAllowsTheLongestKnownType() throws Exception {
        assertThat(DISPLAY_TYPE_MIGRATION).exists();
        jdbcTemplate.execute("""
                CREATE TEMP TABLE dice_roll_result (
                    display_type VARCHAR(20)
                ) ON COMMIT DROP
                """);

        jdbcTemplate.execute(Files.readString(DISPLAY_TYPE_MIGRATION));
        jdbcTemplate.update(
                "INSERT INTO dice_roll_result (display_type) VALUES (?)",
                DiceRollConstant.TYPE_TEMPORARY_INSANITY_DURATION);

        String storedType = jdbcTemplate.queryForObject(
                "SELECT display_type FROM dice_roll_result",
                String.class);
        assertThat(storedType)
                .isEqualTo("TEMPORARY_INSANITY_DURATION");
    }
}
