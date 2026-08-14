package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.UserWorldSaveSnapshotDTO;
import com.me.galchat.domain.po.CharacterTemplate;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.domain.po.UserWorldSave;
import com.me.galchat.domain.po.WorldTemplate;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.mapper.UserWorldSaveMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class NullClearingIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GroupReplyPlanService replyPlanService;

    @Autowired
    private TrpgTemporaryInsanityService temporaryInsanityService;

    @Autowired
    private CharacterCardServiceImpl characterCardService;

    @Autowired
    private WorldTemplateServiceImpl worldTemplateService;

    @Autowired
    private CharacterTemplateServiceImpl characterTemplateService;

    @Autowired
    private UserWorldSaveServiceImpl userWorldSaveService;

    @Autowired
    private TrpgSaveMapper trpgSaveMapper;

    @Autowired
    private UserWorldSaveMapper userWorldSaveMapper;

    @Test
    void finishingLastSceneClearsActivePlanInDatabase() {
        createTemporaryPlanTables();
        jdbcTemplate.update("""
                INSERT INTO group_conversation
                    (id, mode, status, active_reply_plan_id)
                VALUES (?, ?, ?, ?)
                """, -9101L, GroupChatConstant.MODE_TRPG,
                GroupChatConstant.STATUS_ACTIVE, -9201L);
        jdbcTemplate.update("""
                INSERT INTO group_reply_plan
                    (id, conversation_id, source,
                     execution_key, display_name)
                VALUES (?, ?, ?, ?, ?)
                """, -9201L, -9101L,
                GroupChatConstant.PLAN_SOURCE_SCENE,
                "scene:test", "测试场景");
        GroupConversation conversation =
                jdbcTemplate.queryForObject("""
                                SELECT id, mode, status,
                                       active_reply_plan_id
                                FROM group_conversation
                                WHERE id = ?
                                """,
                        (resultSet, rowNum) -> new GroupConversation()
                                .setId(resultSet.getLong("id"))
                                .setMode(resultSet.getString("mode"))
                                .setStatus(resultSet.getString("status"))
                                .setActiveReplyPlanId(resultSet.getLong(
                                        "active_reply_plan_id")),
                        -9101L);

        replyPlanService.finishActiveUnderLock(conversation);

        Long activePlanId = jdbcTemplate.queryForObject("""
                SELECT active_reply_plan_id
                FROM group_conversation
                WHERE id = ?
                """, Long.class, -9101L);
        Integer remainingPlans = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM group_reply_plan
                WHERE conversation_id = ?
                """, Integer.class, -9101L);
        assertThat(activePlanId).isNull();
        assertThat(remainingPlans).isZero();
    }

    @Test
    void expiringTemporaryInsanityClearsPhaseAndRemainingHoursInDatabase() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE coc_character
                ON COMMIT DROP AS
                SELECT * FROM public.coc_character
                WITH NO DATA
                """);
        jdbcTemplate.update("""
                INSERT INTO coc_character
                    (id, run_id, temporary_insanity,
                     temporary_insanity_phase,
                     temporary_insanity_remaining_rounds)
                VALUES (?, ?, ?, ?, ?)
                """, -9301L, -9401L, true, "REAL_TIME", 3);

        temporaryInsanityService.advanceAfterLargeScene(-9401L);

        var state = jdbcTemplate.queryForMap("""
                SELECT temporary_insanity,
                       temporary_insanity_phase,
                       temporary_insanity_remaining_rounds
                FROM coc_character
                WHERE id = ?
                """, -9301L);
        assertThat(state.get("temporary_insanity")).isEqualTo(false);
        assertThat(state.get("temporary_insanity_phase")).isNull();
        assertThat(state.get("temporary_insanity_remaining_rounds"))
                .isNull();
    }

    @Test
    void emptyQuickNotesClearsExistingNotesInDatabase() {
        createTemporaryTable("coc_character");
        jdbcTemplate.update("""
                INSERT INTO coc_character
                    (id, run_id, name, quick_notes)
                VALUES (?, ?, ?, ?)
                """, -9501L, -9502L, "林恩", "旧笔记");

        characterCardService.updateQuickNotes(-9502L, "林恩", "   ");

        String quickNotes = jdbcTemplate.queryForObject("""
                SELECT quick_notes
                FROM coc_character
                WHERE id = ?
                """, String.class, -9501L);
        assertThat(quickNotes).isNull();
    }

    @Test
    void updatingTrpgSaveWithNoRemarkClearsExistingRemarkInDatabase() {
        createTemporaryTable("trpg_save");
        jdbcTemplate.update("""
                INSERT INTO trpg_save
                    (id, user_id, conversation_id, remark, saved_at,
                     format_version, snapshot)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CAST(? AS JSONB))
                """, -9601L, -9602L, -9603L, "旧备注", 1, "{}");

        trpgSaveMapper.updateById(new TrpgSave()
                .setId(-9601L)
                .setRemark(null)
                .setSavedAt(LocalDateTime.now()));

        String remark = jdbcTemplate.queryForObject("""
                SELECT remark FROM trpg_save WHERE id = ?
                """, String.class, -9601L);
        assertThat(remark).isNull();
    }

    @Test
    void updatingWorldSaveWithNoRemarkClearsExistingRemarkInDatabase() {
        createTemporaryTable("user_world_save");
        jdbcTemplate.update("""
                INSERT INTO user_world_save
                    (id, user_id, user_world_id, remark, saved_at,
                     format_version, character_favors, snapshot)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?,
                        CAST(? AS JSONB), CAST(? AS JSONB))
                """, -9701L, -9702L, -9703L, "旧备注", 1, "[]", "{}");

        userWorldSaveMapper.updateById(new UserWorldSave()
                .setId(-9701L)
                .setRemark(null)
                .setSavedAt(LocalDateTime.now()));

        String remark = jdbcTemplate.queryForObject("""
                SELECT remark FROM user_world_save WHERE id = ?
                """, String.class, -9701L);
        assertThat(remark).isNull();
    }

    @Test
    void loadingWorldClearsNullableCharacterStateFromSnapshot() {
        createTemporaryTable("user_character_info");
        jdbcTemplate.update("""
                INSERT INTO user_character_info
                    (user_world_id, character_id, character_name,
                     character_image, last_chat_time, last_chat_content,
                     favor_value, user_info_prompt)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?)
                """, -9801L, -9802L, "当前名称", "当前图片",
                "当前消息", 25, "当前提示词");
        UserWorldSaveSnapshotDTO.CharacterStateSnapshot state =
                new UserWorldSaveSnapshotDTO.CharacterStateSnapshot()
                        .setCharacterId(-9802L)
                        .setCharacterName(null)
                        .setCharacterImage(null)
                        .setLastChatTime(null)
                        .setLastChatContent(null)
                        .setFavorValue(7)
                        .setUserInfoPrompt(null);

        ReflectionTestUtils.invokeMethod(userWorldSaveService,
                "restoreCharacterStates", -9801L, List.of(state));

        var restored = jdbcTemplate.queryForMap("""
                SELECT character_name, character_image, last_chat_time,
                       last_chat_content, favor_value, user_info_prompt
                FROM user_character_info
                WHERE user_world_id = ? AND character_id = ?
                """, -9801L, -9802L);
        assertThat(restored.get("character_name")).isNull();
        assertThat(restored.get("character_image")).isNull();
        assertThat(restored.get("last_chat_time")).isNull();
        assertThat(restored.get("last_chat_content")).isNull();
        assertThat(restored.get("favor_value")).isEqualTo(7);
        assertThat(restored.get("user_info_prompt")).isEqualTo("");
    }

    @Test
    void replacingWorldTemplateClearsNullableFieldsInDatabase() {
        createTemporaryTable("world_template");
        jdbcTemplate.update("""
                INSERT INTO world_template
                    (id, name, image, author, background, author_id, visible)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, -9901L, "旧世界", "旧图片", "旧作者", "旧背景",
                -9902L, true);

        worldTemplateService.updateWorldTemplate(-9902L, -9901L,
                new WorldTemplate().setName("新世界"));

        var updated = jdbcTemplate.queryForMap("""
                SELECT name, image, author, background, visible
                FROM world_template WHERE id = ?
                """, -9901L);
        assertThat(updated.get("name")).isEqualTo("新世界");
        assertThat(updated.get("image")).isNull();
        assertThat(updated.get("author")).isNull();
        assertThat(updated.get("background")).isNull();
        assertThat(updated.get("visible")).isNull();
    }

    @Test
    void replacingCharacterTemplateClearsNullableFieldsInDatabase() {
        createTemporaryTable("world_template");
        createTemporaryTable("character_template");
        jdbcTemplate.update("""
                INSERT INTO world_template (id, name, author_id)
                VALUES (?, ?, ?)
                """, -9911L, "世界", -9912L);
        jdbcTemplate.update("""
                INSERT INTO character_template
                    (id, world_id, name, image, background, personality,
                     coc_play_style, favorability, init_favor)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
                """, -9913L, -9911L, "旧角色", "旧图片", "旧背景",
                "旧性格", "旧玩法", "{\"友好\":\"10\"}", 10);

        characterTemplateService.updateCharacterTemplate(
                -9912L, -9911L, -9913L,
                new CharacterTemplate().setName("新角色"));

        var updated = jdbcTemplate.queryForMap("""
                SELECT name, image, background, personality,
                       coc_play_style, favorability, init_favor
                FROM character_template WHERE id = ?
                """, -9913L);
        assertThat(updated.get("name")).isEqualTo("新角色");
        assertThat(updated.get("image")).isNull();
        assertThat(updated.get("background")).isNull();
        assertThat(updated.get("personality")).isNull();
        assertThat(updated.get("coc_play_style")).isNull();
        assertThat(updated.get("favorability")).isNull();
        assertThat(updated.get("init_favor")).isNull();
    }

    private void createTemporaryPlanTables() {
        jdbcTemplate.execute("""
                CREATE TEMP TABLE group_conversation (
                    id BIGINT PRIMARY KEY,
                    mode VARCHAR(20) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    active_reply_plan_id BIGINT,
                    updated_at TIMESTAMP
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE group_reply_plan (
                    id BIGINT PRIMARY KEY,
                    conversation_id BIGINT NOT NULL,
                    source VARCHAR(20) NOT NULL,
                    context_id BIGINT,
                    execution_key VARCHAR(100) NOT NULL,
                    display_name VARCHAR(200) NOT NULL,
                    parent_plan_id BIGINT,
                    resume_plan_id BIGINT,
                    next_plan_id BIGINT,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE group_reply_plan_item (
                    id BIGINT PRIMARY KEY,
                    plan_id BIGINT NOT NULL
                ) ON COMMIT DROP
                """);
        jdbcTemplate.execute("""
                CREATE TEMP TABLE trpg_runtime_child_scene (
                    plan_id BIGINT PRIMARY KEY
                ) ON COMMIT DROP
                """);
    }

    private void createTemporaryTable(String tableName) {
        jdbcTemplate.execute("CREATE TEMP TABLE " + tableName
                + " ON COMMIT DROP AS SELECT * FROM public." + tableName
                + " WITH NO DATA");
    }
}
