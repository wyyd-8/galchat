package com.me.galchat.mapper;

import com.me.galchat.domain.dto.CharacterCardGenerationModels;
import com.me.galchat.domain.dto.StepwiseCharacterCardModels;
import com.me.galchat.domain.po.CocCharacterCreationDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CocCharacterCreationDraftMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CocCharacterCreationDraftMapper mapper;

    @Test
    void atomicallyUpdatesJsonStateAndClearsNullableCursorFields() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_character_creation_draft
                ON COMMIT DROP AS
                SELECT * FROM public.coc_character_creation_draft
                WITH NO DATA
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_character_creation_draft (
                    id, owner_user_id, run_id, participant_id,
                    creation_mode, status, current_step, next_action,
                    operation_status, version, rules_version, state,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, -8801L, 7L, 101L, 12L, "STEP_STANDARD",
                "IN_PROGRESS", "EQUIPMENT", "COMPLETE", "IDLE",
                1, 1, """
                        {"formatVersion":1,"preview":{"character":{"name":"锁定读取测试"},
                        "skills":[],"weapons":[],"profile":{"ideology":"保持谨慎"}}}
                        """);
        CocCharacterCreationDraft draft = mapper.selectByIdForUpdate(-8801L);
        assertThat(draft.getState()).isNotNull();
        assertThat(draft.getState().preview()).isNotNull();
        assertThat(draft.getState().preview().getCharacter().getName())
                .isEqualTo("锁定读取测试");
        assertThat(draft.getState().preview().getProfile().getIdeology())
                .isEqualTo("保持谨慎");
        var identity = new StepwiseCharacterCardModels.Identity(
                101L, 12L, "BOT", "新姓名", "角色", null,
                "记者", 42, "男", "纽约", "波士顿");
        draft.setStatus("ABANDONED").setNextAction(null).setVersion(2)
                .setState(new CharacterCardGenerationModels.DraftState(
                        1, null, null, null, null, null,
                        new StepwiseCharacterCardModels.State(
                                identity, null, null, null, null, null)))
                .setLastAction("ABANDON").setUpdatedAt(LocalDateTime.now());

        int updated = mapper.updateWithExpectedVersion(draft, 1);
        int staleUpdate = mapper.updateWithExpectedVersion(draft, 1);

        assertThat(updated).isEqualTo(1);
        assertThat(staleUpdate).isZero();
        var stored = jdbcTemplate.queryForMap("""
                SELECT status, next_action, version,
                       state #>> '{stepwise,identity,name}' AS name
                FROM coc_character_creation_draft WHERE id = ?
                """, -8801L);
        assertThat(stored.get("status")).isEqualTo("ABANDONED");
        assertThat(stored.get("next_action")).isNull();
        assertThat(stored.get("version")).isEqualTo(2);
        assertThat(stored.get("name")).isEqualTo("新姓名");
    }
}
