package com.me.galchat.service.impl;

import com.me.galchat.domain.dto.KpQuickNpcDTOs;
import com.me.galchat.domain.po.CocCharacter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrpgQuickNpcTemplateServiceIntegrationTest {

    @Autowired
    private TrpgQuickNpcTemplateService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void materializesSeveralQuickNpcsThroughTheRealPostgresqlSchema() {
        List<CocCharacter> created = service.materialize(
                -9301L,
                List.of(
                        new KpQuickNpcDTOs.Spec(
                                "集成测试守卫甲", "WEAK", "UNARMED"),
                        new KpQuickNpcDTOs.Spec(
                                "集成测试守卫乙", "STRONG", "PISTOL")));

        assertThat(created)
                .extracting(
                        CocCharacter::getName,
                        CocCharacter::getHpMax,
                        CocCharacter::getCreationMethod)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "集成测试守卫甲", 11,
                                "QUICK_NPC_TEMPLATE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "集成测试守卫乙", 13,
                                "QUICK_NPC_TEMPLATE"));
        assertThat(jdbcTemplate.queryForList("""
                        SELECT display_name, value
                        FROM coc_character_skill
                        WHERE character_id = ?
                        """,
                created.get(0).getId()))
                .isEmpty();
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT display_name, value
                        FROM coc_character_skill
                        WHERE character_id = ?
                        """,
                created.get(1).getId()))
                .containsEntry("display_name", "射击:手枪")
                .containsEntry("value", 70);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT name, remaining_ammo, can_impale, is_broken
                        FROM coc_character_weapon
                        WHERE character_id = ?
                        """,
                created.get(1).getId()))
                .containsEntry("name", ".38/9mm自动手枪")
                .containsEntry("remaining_ammo", 8)
                .containsEntry("can_impale", true)
                .containsEntry("is_broken", false);
    }
}
