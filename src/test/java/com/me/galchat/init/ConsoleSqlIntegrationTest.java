package com.me.galchat.init;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.me.galchat.support.ConsoleSqlTestSupport.initializeSchema;

@SpringBootTest
@Transactional
class ConsoleSqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTheCompleteCurrentSchemaFromAnEmptyNamespace()
            throws Exception {
        String schema = initializeSchema(jdbcTemplate);

        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class, schema))
                .hasSize(47)
                .contains(
                        "group_actor_runtime_config",
                        "trpg_investigator_suspension",
                        "trpg_weapon_stash",
                        "user_model_api", "trpg_completion")
                .doesNotContain(
                        "world_detail_vector_store", "chat_history_vector_store",
                        "group_topic_vector_store", "trpg_turn_vector_store",
                        "world_event_log",
                        "world_story_event",
                        "world_story_event_character");

        assertThat(columns(schema, "group_chat_reply_step")).contains(
                "parent_step_id", "root_step_id", "interaction_type",
                "interaction_seq", "prompt_message_id",
                "execution_mode", "model_api_id");
        assertThat(columns(schema, "user_character_info"))
                .contains("model_api_id");
        assertThat(columns(schema, "trpg_completion"))
                .containsExactly("conversation_id", "turn_id", "data");
        for (var table : com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfos()) {
            assertThat(columns(schema, table.getTableName()))
                    .as("Columns for %s", table.getTableName())
                    .contains(table.getKeyColumn())
                    .containsAll(table.getFieldList().stream()
                            .map(com.baomidou.mybatisplus.core.metadata.TableFieldInfo::getColumn).toList());
        }
        assertThat(columns(schema, "trpg_investigator_suspension"))
                .containsExactly(
                        "id", "conversation_id", "subject_character_id",
                        "state", "suspension_context", "origin_context_id",
                        "reentry_context", "recovery_scene_name",
                        "recovery_plan_id", "created_at", "updated_at");

        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = ?
                """, String.class, schema)).contains(
                "idx_reply_step_parent",
                "idx_reply_step_root_interaction",
                "idx_reply_step_prompt_message",
                "idx_binding_chat",
                "uk_tool_call_id");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT base_value FROM coc_skill_def WHERE name = '侦查'", Integer.class))
                .isEqualTo(25);
    }

    private List<String> columns(String schema, String table) {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                ORDER BY ordinal_position
                """, String.class, schema, table);
    }
}
