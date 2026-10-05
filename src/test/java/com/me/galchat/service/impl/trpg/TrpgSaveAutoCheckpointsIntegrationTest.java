package com.me.galchat.service.impl.trpg;

import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgAutoSaveMapper;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import com.me.galchat.service.IUserWorldPrefixService;
import com.me.galchat.service.impl.group.GroupConversationLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class TrpgSaveAutoCheckpointsIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrpgAutoSaveMapper autoSaves;
    @Autowired private TrpgSaveMapper manualSaves;
    @Autowired private TransactionTemplate transactions;

    private TrpgSaveServiceImpl service;
    private GroupConversationMapper conversations;
    private GroupConversationLockService locks;
    private ITrpgSaveSnapshotService snapshots;
    private final GroupConversation conversation = new GroupConversation().setId(51L)
            .setUserWorldId(12L).setWorldId(4L).setModuleId(8L).setMode("trpg").setStatus("active");

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                CREATE TEMP TABLE trpg_auto_save (
                    conversation_id BIGINT NOT NULL, checkpoint_type VARCHAR(16) NOT NULL,
                    saved_at TIMESTAMP NOT NULL, format_version INT NOT NULL, snapshot JSONB NOT NULL,
                    PRIMARY KEY (conversation_id, checkpoint_type)
                ) ON COMMIT DROP
                """);
        jdbc.execute("""
                CREATE TEMP TABLE trpg_save (
                    id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, conversation_id BIGINT NOT NULL,
                    remark VARCHAR(200), saved_at TIMESTAMP NOT NULL,
                    format_version INT NOT NULL, snapshot JSONB NOT NULL
                ) ON COMMIT DROP
                """);
        jdbc.execute("CREATE TEMP TABLE saved_progress (summary TEXT) ON COMMIT DROP");
        jdbc.update("INSERT INTO saved_progress VALUES ('A-paused')");
        conversations = mock(GroupConversationMapper.class);
        when(conversations.selectById(51L)).thenReturn(conversation);
        locks = mock(GroupConversationLockService.class);
        when(locks.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        // Keep the save/restore transaction real; substitute only unrelated game-state persistence.
        snapshots = mock(ITrpgSaveSnapshotService.class);
        when(snapshots.capture(any())).thenAnswer(ignored -> snapshot(progress()));
        doAnswer(call -> {
            TrpgSaveSnapshotDTO saved = call.getArgument(1);
            jdbc.update("UPDATE saved_progress SET summary = ?", saved.getConversationState().getSummary());
            return null;
        }).when(snapshots).restoreDatabase(any(), any());
        com.me.galchat.support.MybatisPlusTestSupport.initialize(GroupChatTurn.class);
        service = service(autoSaves, transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TURN", "SCENE", "INITIAL"})
    void loadingManualSaveRestoresOverwrittenCheckpointsAndTheirRollbackTargets(String type) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 20, 12, 0);
        List<TrpgAutoSave> original = List.of(
                checkpoint("INITIAL", "initial", start),
                checkpoint("SCENE", "scene-A", start.plusMinutes(1)),
                checkpoint("TURN", "A-start", start.plusMinutes(2)));
        original.forEach(autoSaves::upsert);
        // Another conversation's checkpoints must remain untouched.
        autoSaves.upsert(checkpoint("TURN", "other", start).setConversationId(99L));

        service.save(7L, 51L, null);
        assertThat(manualSaves.selectByConversationId(51L).getSnapshot().getAutoSaves())
                .containsExactlyInAnyOrderElementsOf(original)
                .allSatisfy(checkpoint -> assertThat(checkpoint.getSnapshot().getAutoSaves()).isNull());
        jdbc.update("UPDATE saved_progress SET summary = 'B-start'");
        service.saveBeforeTurn(conversation);
        assertThat(autoSaves.selectByConversationId(51L))
                .allSatisfy(checkpoint -> assertThat(checkpoint.getSnapshot().getAutoSaves()).isNull());
        assertThat(autoSaves.selectByConversationAndType(51L, "TURN").getSnapshot()
                .getConversationState().getSummary()).isEqualTo("B-start");

        service.load(7L, 51L);
        service.load(7L, 51L); // Re-loading the same manual save remains idempotent.

        assertThat(progress()).isEqualTo("A-paused");
        assertThat(autoSaves.selectByConversationId(51L)).containsExactlyInAnyOrderElementsOf(original);
        assertThat(autoSaves.selectByConversationAndType(99L, "TURN").getSnapshot()
                .getConversationState().getSummary()).isEqualTo("other");
        switch (type) {
            case "TURN" -> service.rollbackTurn(7L, 51L);
            case "SCENE" -> service.rollbackScene(7L, 51L);
            case "INITIAL" -> service.rollbackInitial(7L, 51L);
            default -> throw new AssertionError(type);
        }
        assertThat(progress()).isEqualTo(switch (type) {
            case "TURN" -> "A-start";
            case "SCENE" -> "scene-A";
            default -> "initial";
        });
    }

    @Test
    void aNewSaveWithNoCheckpointsRestoresAnEmptySetIncludingInitial() {
        service.save(7L, 51L, null);
        assertThat(manualSaves.selectByConversationId(51L).getSnapshot().getAutoSaves()).isEmpty();
        // Timestamp pruning alone would retain these rows, but they were absent at save time.
        autoSaves.upsert(checkpoint("INITIAL", "later-branch", LocalDateTime.of(2026, 1, 1, 0, 0)));
        autoSaves.upsert(checkpoint("TURN", "later-branch", LocalDateTime.of(2026, 1, 1, 0, 0)));

        service.load(7L, 51L);

        assertThat(autoSaves.selectByConversationId(51L)).isEmpty();
    }

    @Test
    void checkpointRestoreFailureRollsBackBothGameStateAndCheckpointDeletion() {
        autoSaves.upsert(checkpoint("TURN", "A-start", LocalDateTime.of(2026, 8, 20, 12, 0)));
        service.save(7L, 51L, null);
        jdbc.update("UPDATE saved_progress SET summary = 'B-start'");
        service.saveBeforeTurn(conversation);
        var beforeLoad = autoSaves.selectByConversationId(51L);
        var failing = mock(TrpgAutoSaveMapper.class, org.mockito.AdditionalAnswers.delegatesTo(autoSaves));
        doThrow(new IllegalStateException("checkpoint write failed")).when(failing).upsert(any());
        // Use a savepoint inside the test's rollback-only transaction to observe the failed load's rollback.
        var nested = new TransactionTemplate(transactions.getTransactionManager(), transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        assertThatThrownBy(() -> service(failing, nested).load(7L, 51L))
                .isInstanceOf(IllegalStateException.class).hasMessage("checkpoint write failed");

        assertThat(progress()).isEqualTo("B-start");
        assertThat(autoSaves.selectByConversationId(51L)).containsExactlyElementsOf(beforeLoad);
        org.mockito.Mockito.verify(snapshots, org.mockito.Mockito.never()).restoreDerivedState(any(), any());
    }

    private TrpgSaveServiceImpl service(TrpgAutoSaveMapper checkpoints, TransactionTemplate template) {
        return new TrpgSaveServiceImpl(mock(IUserWorldPrefixService.class), conversations,
                mock(GroupChatTurnMapper.class), manualSaves, checkpoints, snapshots, locks, template);
    }

    private String progress() {
        return jdbc.queryForObject("SELECT summary FROM saved_progress", String.class);
    }

    private TrpgAutoSave checkpoint(String type, String summary, LocalDateTime savedAt) {
        return new TrpgAutoSave().setConversationId(51L).setCheckpointType(type)
                .setSavedAt(savedAt).setFormatVersion(2).setSnapshot(snapshot(summary));
    }

    private TrpgSaveSnapshotDTO snapshot(String summary) {
        return new TrpgSaveSnapshotDTO().setFormatVersion(2).setConversationId(51L)
                .setUserWorldId(12L).setWorldId(4L).setModuleId(8L)
                .setConversationState(new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                        .setSummary(summary).setStatus("active"));
    }
}
