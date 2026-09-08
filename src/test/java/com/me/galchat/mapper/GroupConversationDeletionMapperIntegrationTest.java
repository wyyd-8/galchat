package com.me.galchat.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GroupConversationDeletionMapperIntegrationTest {

    private static final long CONVERSATION_ID = -97001L;
    private static final long OTHER_CONVERSATION_ID = -97002L;
    private static final long TURN_ID = -97011L;
    private static final long STEP_ID = -97012L;
    private static final long PLAN_ID = -97013L;
    private static final long DICE_ID = -97014L;
    private static final long CARD_ID = -97015L;
    private static final long WEAPON_ID = -97016L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GroupConversationDeletionMapper deletionMapper;

    @Test
    void deletesAllConversationOwnedRowsWithoutRevertingFavorValue() {
        jdbcTemplate.execute("CREATE TEMP TABLE trpg_completion (conversation_id BIGINT PRIMARY KEY) ON COMMIT DROP");
        jdbcTemplate.update("INSERT INTO trpg_completion VALUES (?)", CONVERSATION_ID);
        insertFixture();

        int deleted = deletionMapper.deleteConversationData(
                CONVERSATION_ID);

        assertThat(deleted).isEqualTo(1);
        for (String check : List.of(
                "SELECT COUNT(*) FROM group_conversation WHERE id = -97001",
                "SELECT COUNT(*) FROM trpg_completion WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_chat_member WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_actor_runtime_config WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_reply_plan WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_reply_plan_item WHERE plan_id = -97013",
                "SELECT COUNT(*) FROM trpg_runtime_child_scene WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_chat_turn WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_chat_reply_step WHERE id = -97012",
                "SELECT COUNT(*) FROM group_chat_agent_decision WHERE reply_step_id = -97012",
                "SELECT COUNT(*) FROM group_chat_tool_call WHERE reply_step_id = -97012",
                "SELECT COUNT(*) FROM group_chat_message WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_chat_topic WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_context_summary WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM dice_roll_summary WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM dice_roll_result WHERE summary_id = -97014",
                "SELECT COUNT(*) FROM user_character_favor_log WHERE binding_chat = -97012",
                "SELECT COUNT(*) FROM coc_character WHERE run_id = -97001",
                "SELECT COUNT(*) FROM coc_character_skill WHERE character_id = -97015",
                "SELECT COUNT(*) FROM coc_character_weapon WHERE character_id = -97015",
                "SELECT COUNT(*) FROM coc_character_profile WHERE character_id = -97015",
                "SELECT COUNT(*) FROM coc_character_creation_draft WHERE run_id = -97001",
                "SELECT COUNT(*) FROM trpg_weapon_stash WHERE run_id = -97001",
                "SELECT COUNT(*) FROM trpg_investigator_suspension WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM trpg_combat WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM group_turn_checkpoint WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM trpg_save WHERE conversation_id = -97001",
                "SELECT COUNT(*) FROM trpg_auto_save WHERE conversation_id = -97001")) {
            assertThat(jdbcTemplate.queryForObject(check, Long.class))
                    .as(check)
                    .isZero();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT favor_value FROM user_character_info "
                        + "WHERE user_world_id = -97003 "
                        + "AND character_id = -97004",
                Integer.class)).isEqualTo(42);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_conversation "
                        + "WHERE id = -97002", Long.class)).isOne();
    }

    private void insertFixture() {
        jdbcTemplate.update("""
                INSERT INTO group_conversation
                    (id, user_world_id, mode, title, status)
                VALUES (?, ?, 'trpg', 'delete target', 'active'),
                       (?, ?, 'chat', 'keep target', 'active')
                """, CONVERSATION_ID, -97003L,
                OTHER_CONVERSATION_ID, -97003L);
        jdbcTemplate.update("""
                INSERT INTO group_chat_member
                    (id, conversation_id, actor_type, actor_id, position)
                VALUES (-97020, ?, 'character', -97004, 0)
                """, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO group_actor_runtime_config
                    (id, conversation_id, actor_key, actor_type,
                     actor_id, control_mode)
                VALUES (-97021, ?, 'character:-97004', 'character',
                        -97004, 'MODEL')
                """, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO group_reply_plan
                    (id, conversation_id, source, execution_key, display_name)
                VALUES (?, ?, 'SCENE', 'scene:test', 'test scene')
                """, PLAN_ID, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO group_reply_plan_item
                    (id, plan_id, item_order, actor_type, actor_id)
                VALUES (-97022, ?, 1, 'character', -97004)
                """, PLAN_ID);
        jdbcTemplate.update("""
                INSERT INTO group_chat_turn
                    (id, conversation_id, plan_source, status)
                VALUES (?, ?, 'SCENE', 'completed')
                """, TURN_ID, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO group_chat_reply_step
                    (id, turn_id, group_key, group_name, group_order,
                     item_order, step_no, action_type, speaker_type, status)
                VALUES (?, ?, 'scene:test', 'test scene', 1,
                        1, 1, 'reply', 'character', 'completed')
                """, STEP_ID, TURN_ID);
        jdbcTemplate.update("""
                INSERT INTO trpg_runtime_child_scene
                    (plan_id, conversation_id, scene_name, created_step_id)
                VALUES (?, ?, 'child scene', ?)
                """, PLAN_ID, CONVERSATION_ID, STEP_ID);
        jdbcTemplate.update("""
                INSERT INTO group_chat_agent_decision
                    (id, reply_step_id, content)
                VALUES (-97023, ?, 'decision')
                """, STEP_ID);
        jdbcTemplate.update("""
                INSERT INTO dice_roll_summary
                    (id, conversation_id, reason, round_count, status)
                VALUES (?, ?, 'test', 1, 'COMPLETED')
                """, DICE_ID, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO dice_roll_result
                    (id, summary_id, round_no, display_order, reason,
                     result_data, resolution_data)
                VALUES (-97024, ?, 1, 1, 'test', '{}'::jsonb, '{}'::jsonb)
                """, DICE_ID);
        jdbcTemplate.update("""
                INSERT INTO group_chat_tool_call
                    (id, reply_step_id, tool_step_no, tool_call_id,
                     tool_name, dice_roll_summary_id)
                VALUES (-97025, ?, 1, 'call', 'dice', ?)
                """, STEP_ID, DICE_ID);
        jdbcTemplate.update("""
                INSERT INTO group_chat_message
                    (id, conversation_id, turn_id, reply_step_id,
                     speaker_type, message_kind, content,
                     sequence_no, status)
                VALUES (-97026, ?, ?, ?, 'character', 'dialogue',
                        'message', 1, 'completed')
                """, CONVERSATION_ID, TURN_ID, STEP_ID);
        jdbcTemplate.update("""
                INSERT INTO group_chat_topic
                    (id, conversation_id, start_sequence, boundary_reason)
                VALUES (-97027, ?, 1, 'manual')
                """, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO group_context_summary
                    (id, conversation_id, start_sequence,
                     end_sequence, summary)
                VALUES (-97028, ?, 1, 2, 'summary')
                """, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO user_character_info
                    (user_world_id, character_id, favor_value)
                VALUES (-97003, -97004, 42)
                """);
        jdbcTemplate.update("""
                INSERT INTO user_character_favor_log
                    (id, user_world_id, character_id, favor_update,
                     binding_type, binding_chat)
                VALUES (-97029, -97003, -97004, 5,
                        'GROUP_REPLY_STEP', ?)
                """, STEP_ID);
        insertTrpgFixture();
    }

    private void insertTrpgFixture() {
        jdbcTemplate.update("""
                INSERT INTO coc_character
                    (id, run_id, actor_type, name, str, con, siz, dex,
                     app, int_value, pow, edu, damage_bonus, build, mov,
                     hp_current, hp_max, san_current, san_max,
                     mp_current, mp_max)
                VALUES (?, ?, 'NPC', 'delete target npc',
                        50, 50, 50, 50, 50, 50, 50, 50,
                        '0', 0, 8, 10, 10, 50, 50, 10, 10)
                """, CARD_ID, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO coc_character_skill
                    (id, character_id, display_name, value)
                VALUES (-97031, ?, '侦查', 50)
                """, CARD_ID);
        jdbcTemplate.update("""
                INSERT INTO coc_character_weapon
                    (id, character_id, name)
                VALUES (?, ?, 'test weapon')
                """, WEAPON_ID, CARD_ID);
        jdbcTemplate.update("""
                INSERT INTO coc_character_profile (id, character_id)
                VALUES (-97032, ?)
                """, CARD_ID);
        jdbcTemplate.update("""
                INSERT INTO coc_character_creation_draft
                    (id, owner_user_id, run_id, creation_mode, status,
                     current_step, operation_status, version,
                     rules_version, state)
                VALUES (-97033, -97005, ?, 'AUTO', 'IN_PROGRESS',
                        'IDENTITY', 'IDLE', 1, 1, '{}'::jsonb)
                """, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO trpg_weapon_stash
                    (weapon_id, run_id, source_character_name,
                     location_name, stash_reason, weapon_snapshot)
                VALUES (?, ?, 'npc', 'room', 'STASHED', '{}'::jsonb)
                """, WEAPON_ID, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO trpg_investigator_suspension
                    (id, conversation_id, subject_character_id, state,
                     suspension_context, origin_context_id)
                VALUES (-97034, ?, ?, 'SUSPENDED', 'context', ?)
                """, CONVERSATION_ID, CARD_ID, PLAN_ID);
        jdbcTemplate.update("""
                INSERT INTO trpg_combat
                    (id, conversation_id, source_scene_id, status,
                     order_mode, current_round, participants)
                VALUES (-97035, ?, ?, 'active', 'DEX', 1, '[]'::jsonb)
                """, CONVERSATION_ID, PLAN_ID);
        jdbcTemplate.update("""
                INSERT INTO group_turn_checkpoint
                    (conversation_id, turn_id, reply_step_id,
                     checkpoint_type)
                VALUES (?, ?, ?, 'TURN')
                """, CONVERSATION_ID, TURN_ID, STEP_ID);
        jdbcTemplate.update("""
                INSERT INTO trpg_save
                    (id, user_id, conversation_id, saved_at,
                     format_version, snapshot)
                VALUES (-97036, -97005, ?, CURRENT_TIMESTAMP, 1, '{}'::jsonb)
                """, CONVERSATION_ID);
        jdbcTemplate.update("""
                INSERT INTO trpg_auto_save
                    (conversation_id, checkpoint_type, saved_at,
                     format_version, snapshot)
                VALUES (?, 'TURN', CURRENT_TIMESTAMP, 1, '{}'::jsonb)
                """, CONVERSATION_ID);
    }
}
