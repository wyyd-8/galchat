package com.me.galchat.service.impl.trpg;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.mapper.CocCharacterMapper;
import com.me.galchat.mapper.CocCharacterProfileMapper;
import com.me.galchat.mapper.CocCharacterSkillMapper;
import com.me.galchat.mapper.CocCharacterWeaponMapper;
import com.me.galchat.mapper.DiceRollResultMapper;
import com.me.galchat.mapper.DiceRollSummaryMapper;
import com.me.galchat.mapper.GroupChatAgentDecisionMapper;
import com.me.galchat.mapper.GroupChatMessageMapper;
import com.me.galchat.mapper.GroupChatReplyStepMapper;
import com.me.galchat.mapper.GroupChatToolCallMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupReplyPlanItemMapper;
import com.me.galchat.mapper.GroupReplyPlanMapper;
import com.me.galchat.mapper.GroupTurnCheckpointMapper;
import com.me.galchat.mapper.TrpgCombatMapper;
import com.me.galchat.mapper.TrpgSaveRestoreMapper;
import com.me.galchat.mapper.TrpgWeaponStashMapper;
import com.me.galchat.mapper.TrpgRuntimeChildSceneMapper;
import com.me.galchat.mapper.VectorStoreCleanupMapper;
import com.me.galchat.service.ITrpgRedisStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class TrpgSaveSnapshotServiceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GroupConversationMapper conversationMapper;

    @Autowired
    private com.me.galchat.mapper.TrpgCompletionMapper completionMapper;

    @Test
    void restoreDatabaseClearsAConversationStateValueSavedAsNull() throws Exception {
        jdbcTemplate.execute(java.nio.file.Files.readString(java.nio.file.Path.of("data/maintenance/2026-09-07-trpg-completion.sql"))
                .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMP TABLE").replace("\n);", "\n) ON COMMIT DROP;"));
        jdbcTemplate.execute("""
                CREATE TEMP TABLE group_conversation (
                    id BIGINT PRIMARY KEY,
                    user_world_id BIGINT NOT NULL,
                    world_id BIGINT,
                    module_id BIGINT,
                    active_reply_plan_id BIGINT,
                    mode VARCHAR(50) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    summary TEXT,
                    status VARCHAR(50) NOT NULL,
                    version INT,
                    game_day_no INT,
                    game_time_period VARCHAR(20),
                    game_time_revision INT NOT NULL DEFAULT 0,
                    game_time_changed_step_id BIGINT,
                    game_time_updated_at TIMESTAMP,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP,
                    closed_at TIMESTAMP
                ) ON COMMIT DROP
                """);
        jdbcTemplate.update("""
                INSERT INTO group_conversation (
                    id, user_world_id, world_id, module_id,
                    active_reply_plan_id, mode, title, summary,
                    status, version, game_time_revision,
                    created_at, updated_at, closed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                -9201L, -9202L, -9203L, -9204L,
                -9214L, GroupChatConstant.MODE_TRPG, "current title",
                "current summary", GroupChatConstant.STATUS_ACTIVE, 9, 3,
                LocalDateTime.of(2026, 8, 8, 0, 0),
                LocalDateTime.of(2026, 8, 8, 0, 10),
                LocalDateTime.of(2026, 8, 8, 0, 20));

        GroupReplyPlanMapper planMapper = mock(GroupReplyPlanMapper.class);
        CocCharacterMapper characterMapper = mock(CocCharacterMapper.class);
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(characterMapper.selectList(any())).thenReturn(List.of());
        TrpgSaveSnapshotService service = new TrpgSaveSnapshotService(completionMapper,
                mock(TrpgSaveRestoreMapper.class),
                conversationMapper,
                planMapper,
                mock(GroupReplyPlanItemMapper.class),
                mock(TrpgRuntimeChildSceneMapper.class),
                characterMapper,
                mock(CocCharacterProfileMapper.class),
                mock(CocCharacterSkillMapper.class),
                mock(CocCharacterWeaponMapper.class),
                mock(TrpgWeaponStashMapper.class),
                mock(TrpgCombatMapper.class),
                mock(GroupTurnCheckpointMapper.class),
                mock(GroupChatTurnMapper.class),
                mock(GroupChatReplyStepMapper.class),
                mock(GroupChatMessageMapper.class),
                mock(GroupChatToolCallMapper.class),
                mock(GroupChatAgentDecisionMapper.class),
                mock(DiceRollSummaryMapper.class),
                mock(DiceRollResultMapper.class),
                mock(ITrpgRedisStateService.class),
                mock(VectorStoreCleanupMapper.class));
        GroupConversation conversation = new GroupConversation()
                .setId(-9201L)
                .setUserWorldId(-9202L)
                .setWorldId(-9203L)
                .setModuleId(-9204L)
                .setActiveReplyPlanId(-9214L)
                .setMode(GroupChatConstant.MODE_TRPG)
                .setTitle("current title")
                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                .setVersion(9)
                .setGameTimeRevision(3);
        TrpgSaveSnapshotDTO snapshot = snapshotWithNoActivePlan();

        service.restoreDatabase(conversation, snapshot);

        assertThat(jdbcTemplate.queryForMap("""
                        SELECT active_reply_plan_id, summary, closed_at
                        FROM group_conversation
                        WHERE id = -9201
                        """))
                .containsEntry("active_reply_plan_id", null)
                .containsEntry("summary", null)
                .containsEntry("closed_at", null);
        // Loading the pre-summary save reopens an archived run and restores the pending request.
        jdbcTemplate.update("UPDATE group_conversation SET status = 'closed', closed_at = now(), summary = 'final' WHERE id = -9201");
        var material = new com.me.galchat.domain.dto.TrpgCompletionModels.Materials("灯塔", null, 42, 3, List.of(), List.of(), List.of());
        completionMapper.insert(new com.me.galchat.domain.po.TrpgCompletion().setConversationId(-9201L).setTurnId(-93L)
                .setData(new com.me.galchat.domain.dto.TrpgCompletionModels.Data(material, List.of(),
                        new com.me.galchat.domain.dto.TrpgCompletionModels.Overview("概要", "结局", List.of()))));
        var pending = new com.me.galchat.domain.po.TrpgCompletion().setConversationId(-9201L).setTurnId(-93L);
        service.restoreDatabase(conversation, snapshot.setCompletion(pending));
        assertThat(completionMapper.selectById(-9201L)).isEqualTo(pending);
        assertThat(conversationMapper.selectById(-9201L).getStatus()).isEqualTo("active");
        assertThat(conversationMapper.selectById(-9201L).getClosedAt()).isNull();
    }

    private TrpgSaveSnapshotDTO snapshotWithNoActivePlan() {
        return new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(-9201L)
                .setUserWorldId(-9202L)
                .setWorldId(-9203L)
                .setModuleId(-9204L)
                .setCursors(new TrpgSaveSnapshotDTO.CursorSnapshot()
                        .setMaxMessageId(0L)
                        .setMaxTurnId(0L)
                        .setMaxReplyStepId(0L)
                        .setMaxToolCallId(0L)
                        .setMaxAgentDecisionId(0L)
                        .setMaxContextSummaryId(0L)
                        .setMaxTopicId(0L)
                        .setMaxDiceSummaryId(0L)
                        .setMaxDiceResultId(0L))
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setTitle("saved title")
                                .setStatus(GroupChatConstant.STATUS_ACTIVE)
                                .setVersion(0)
                                .setGameTimeRevision(0)
                                .setUpdatedAt(LocalDateTime.of(
                                        2026, 8, 7, 20, 44)))
                .setReplyPlans(List.of())
                .setReplyPlanItems(List.of())
                .setCharacters(List.of())
                .setCharacterProfiles(List.of())
                .setCharacterSkills(List.of())
                .setCharacterWeapons(List.of())
                .setCombats(List.of());
    }
}
