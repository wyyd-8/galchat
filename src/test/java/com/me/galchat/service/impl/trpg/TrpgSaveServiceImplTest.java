package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.me.galchat.service.impl.group.GroupTurnRecoveryService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupChatTurn;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.GroupReplyPlan;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.domain.vo.TrpgRollbackResultVO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.GroupChatTurnMapper;
import com.me.galchat.mapper.TrpgAutoSaveMapper;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import com.me.galchat.service.IUserWorldPrefixService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrpgSaveServiceImplTest {

    @Mock
    private IUserWorldPrefixService userWorldPrefixService;
    @Mock
    private GroupConversationMapper conversationMapper;
    @Mock
    private GroupChatTurnMapper turnMapper;
    @Mock
    private TrpgSaveMapper saveMapper;
    @Mock
    private TrpgAutoSaveMapper autoSaveMapper;
    @Mock
    private ITrpgSaveSnapshotService snapshotService;
    @Mock
    private GroupTurnRecoveryService recoveryService;
    @Mock
    private GroupConversationLockService lockService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private TrpgSaveServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TrpgSaveServiceImpl(
                userWorldPrefixService,
                conversationMapper,
                turnMapper,
                saveMapper,
                autoSaveMapper,
                snapshotService,
                recoveryService,
                lockService,
                transactionTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void saveRejectsOrdinaryGroupBeforeCapturingAnything() {
        GroupConversation conversation = conversation(51L, GroupChatConstant.MODE_CHAT);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);

        assertThatThrownBy(() -> service.save(7L, 51L, null))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("TRPG");

        verify(snapshotService, never()).capture(any());
        verify(saveMapper, never()).insert(any(TrpgSave.class));
    }

    @Test
    void savePersistsAConversationScopedCheckpoint() {
        GroupConversation conversation = conversation(51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(51L)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));
        when(snapshotService.capture(conversation)).thenReturn(snapshot);
        when(saveMapper.selectByConversationId(51L)).thenReturn(null);

        service.save(7L, 51L, new TrpgSaveCreateDTO().setRemark("  门后  "));

        ArgumentCaptor<TrpgSave> captor = ArgumentCaptor.forClass(TrpgSave.class);
        verify(saveMapper).insert(captor.capture());
        TrpgSave saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getConversationId()).isEqualTo(51L);
        assertThat(saved.getRemark()).isEqualTo("门后");
        assertThat(saved.getFormatVersion())
                .isEqualTo(TrpgSaveServiceImpl.FORMAT_VERSION);
        assertThat(saved.getSnapshot()).isSameAs(snapshot);
        assertThat(saved.getSavedAt()).isNotNull();
        verify(recoveryService).assertConversationHasNoNonTerminalTurns(51L);
    }

    @Test
    void saveOverviewContainsOnlyUserAndBotInvestigators() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setCharacters(List.of(
                        character(1L, "PLAYER", "林恩"),
                        character(2L, "BOT", "米娅"),
                        character(3L, "NPC", "守门人")));
        TrpgSave save = new TrpgSave()
                .setId(9L)
                .setUserId(7L)
                .setConversationId(51L)
                .setSnapshot(snapshot);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(saveMapper.selectByConversationId(51L)).thenReturn(save);

        var overview = service.getSave(7L, 51L);

        assertThat(overview.getInvestigators())
                .extracting(state -> state.getName())
                .containsExactly("林恩", "米娅");
    }

    @Test
    void saveOverviewExposesTheSnapshotMessageBoundaryForLoadPreview() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setCursors(new TrpgSaveSnapshotDTO.CursorSnapshot()
                        .setMaxMessageId(321L));
        TrpgSave save = new TrpgSave()
                .setId(9L)
                .setUserId(7L)
                .setConversationId(51L)
                .setSnapshot(snapshot);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(saveMapper.selectByConversationId(51L)).thenReturn(save);

        var overview = service.getSave(7L, 51L);

        assertThat(overview.getMessageBoundaryId()).isEqualTo(321L);
    }

    @Test
    void loadRejectsSnapshotFromAnotherConversationBeforeMutation() {
        GroupConversation conversation = conversation(51L, GroupChatConstant.MODE_TRPG);
        TrpgSave save = new TrpgSave()
                .setUserId(7L)
                .setConversationId(51L)
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSavedAt(LocalDateTime.now())
                .setSnapshot(new TrpgSaveSnapshotDTO()
                        .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                        .setConversationId(99L)
                        .setUserWorldId(12L)
                        .setWorldId(4L)
                        .setModuleId(8L));
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(saveMapper.selectByConversationId(51L)).thenReturn(save);

        assertThatThrownBy(() -> service.load(7L, 51L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("不属于当前跑团");

        verify(snapshotService, never()).restoreDatabase(any(), any());
    }

    @Test
    void loadRestoresDatabaseAndDeletesOnlyNewerAutoCheckpoints() {
        GroupConversation conversation = conversation(51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(51L)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L);
        TrpgSave save = new TrpgSave()
                .setUserId(7L)
                .setConversationId(51L)
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSavedAt(LocalDateTime.of(2026, 8, 20, 12, 0))
                .setSnapshot(snapshot);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(saveMapper.selectByConversationId(51L)).thenReturn(save);
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));

        service.load(7L, 51L);

        var order = inOrder(snapshotService);
        order.verify(snapshotService).restoreDatabase(conversation, snapshot);
        order.verify(snapshotService).restoreDerivedState(conversation, snapshot);
        verify(autoSaveMapper).deleteAfter(
                51L, LocalDateTime.of(2026, 8, 20, 12, 0));
        verify(recoveryService).assertConversationHasNoNonTerminalTurns(51L);
    }

    @Test
    void saveBeforeFirstTurnCreatesTurnInitialAndSceneCheckpoints() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L)
                .setRestorableTurns(List.of())
                .setReplyPlans(List.of())
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot());
        when(snapshotService.capture(conversation)).thenReturn(snapshot);
        when(turnMapper.selectCount(any())).thenReturn(0L);

        service.saveBeforeTurn(conversation);

        ArgumentCaptor<TrpgAutoSave> captor =
                ArgumentCaptor.forClass(TrpgAutoSave.class);
        verify(autoSaveMapper, times(3)).upsert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TrpgAutoSave::getCheckpointType)
                .containsExactlyInAnyOrder("TURN", "INITIAL", "SCENE");
        assertThat(captor.getAllValues())
                .allSatisfy(checkpoint -> {
                    assertThat(checkpoint.getConversationId()).isEqualTo(51L);
                    assertThat(checkpoint.getFormatVersion())
                            .isEqualTo(TrpgSaveServiceImpl.FORMAT_VERSION);
                    assertThat(checkpoint.getSnapshot()).isSameAs(snapshot);
                    assertThat(checkpoint.getSavedAt()).isNotNull();
                });
        assertThat(captor.getAllValues())
                .extracting(TrpgAutoSave::getSavedAt)
                .containsOnly(captor.getAllValues().getFirst().getSavedAt());
        verify(snapshotService).capture(conversation);
    }

    @Test
    void saveBeforeSceneSelectionCreatesSceneCheckpoint() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG)
                .setActiveReplyPlanId(null);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L)
                .setReplyPlans(List.of())
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setActiveReplyPlanId(null))
                .setRestorableTurns(List.of(restorableTurn(200L, 301L)));
        when(snapshotService.capture(conversation)).thenReturn(snapshot);
        when(turnMapper.selectCount(any())).thenReturn(4L);

        service.saveBeforeTurn(conversation);

        ArgumentCaptor<TrpgAutoSave> captor =
                ArgumentCaptor.forClass(TrpgAutoSave.class);
        verify(autoSaveMapper, times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TrpgAutoSave::getCheckpointType)
                .containsExactlyInAnyOrder("TURN", "SCENE");
        assertThat(captor.getAllValues())
                .extracting(TrpgAutoSave::getSnapshot)
                .containsOnly(snapshot);
    }

    @Test
    void saveBeforeFirstMainSceneTurnDoesNotOverwriteSceneCheckpoint() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(301L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(null);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L)
                .setReplyPlans(List.of(scene))
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setActiveReplyPlanId(301L))
                .setRestorableTurns(List.of(restorableTurn(200L, null)));
        when(snapshotService.capture(conversation)).thenReturn(snapshot);
        when(turnMapper.selectCount(any())).thenReturn(1L, 0L);

        service.saveBeforeTurn(conversation);

        ArgumentCaptor<TrpgAutoSave> captor =
                ArgumentCaptor.forClass(TrpgAutoSave.class);
        verify(autoSaveMapper).upsert(captor.capture());
        assertThat(captor.getValue().getCheckpointType()).isEqualTo("TURN");
    }

    @Test
    void saveBeforeChildSceneTurnDoesNotOverwriteMainSceneCheckpoint() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        GroupReplyPlan childScene = new GroupReplyPlan()
                .setId(302L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(301L);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L)
                .setReplyPlans(List.of(childScene))
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setActiveReplyPlanId(302L))
                .setRestorableTurns(List.of(restorableTurn(200L, 301L)));
        when(snapshotService.capture(conversation)).thenReturn(snapshot);
        when(turnMapper.selectCount(any())).thenReturn(1L);

        service.saveBeforeTurn(conversation);

        ArgumentCaptor<TrpgAutoSave> captor =
                ArgumentCaptor.forClass(TrpgAutoSave.class);
        verify(autoSaveMapper).upsert(captor.capture());
        assertThat(captor.getValue().getCheckpointType()).isEqualTo("TURN");
    }

    @Test
    void completedTurnsDoNotRecreateInitialOrOverwriteCurrentMainScene() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        GroupReplyPlan scene = new GroupReplyPlan()
                .setId(301L)
                .setSource(GroupChatConstant.PLAN_SOURCE_SCENE)
                .setParentPlanId(null);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L)
                .setReplyPlans(List.of(scene))
                .setConversationState(
                        new TrpgSaveSnapshotDTO.ConversationStateSnapshot()
                                .setActiveReplyPlanId(301L))
                .setRestorableTurns(List.of());
        when(snapshotService.capture(conversation)).thenReturn(snapshot);
        when(turnMapper.selectCount(any())).thenReturn(5L, 2L);

        service.saveBeforeTurn(conversation);

        ArgumentCaptor<TrpgAutoSave> captor =
                ArgumentCaptor.forClass(TrpgAutoSave.class);
        verify(autoSaveMapper).upsert(captor.capture());
        assertThat(captor.getValue().getCheckpointType()).isEqualTo("TURN");
        verify(autoSaveMapper, never()).selectByConversationAndType(
                51L, "INITIAL");
    }

    @Test
    void rollbackTurnDeletesNewerManualSaveAndFutureAutoCheckpoints() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_CLOSED);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L);
        LocalDateTime checkpointTime = LocalDateTime.of(
                2026, 8, 20, 12, 0);
        TrpgAutoSave autoSave = new TrpgAutoSave()
                .setConversationId(51L)
                .setCheckpointType("TURN")
                .setSavedAt(checkpointTime)
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSnapshot(snapshot);
        TrpgSave manualSave = new TrpgSave()
                .setId(91L)
                .setUserId(7L)
                .setConversationId(51L)
                .setSavedAt(checkpointTime.plusMinutes(1));
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectByConversationAndType(51L, "TURN"))
                .thenReturn(autoSave);
        when(saveMapper.selectByConversationId(51L)).thenReturn(manualSave);
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));

        TrpgRollbackResultVO result = service.rollbackTurn(7L, 51L);

        var order = inOrder(snapshotService);
        order.verify(snapshotService).restoreDatabase(conversation, snapshot);
        order.verify(snapshotService).restoreDerivedState(conversation, snapshot);
        assertThat(result.getManualSaveDeleted()).isTrue();
        assertThat(result.getCheckpointType()).isEqualTo("TURN");
        verify(saveMapper).deleteById(91L);
        verify(autoSaveMapper).deleteAfter(51L, checkpointTime);
        verify(recoveryService)
                .assertConversationHasNoNonTerminalTurns(51L);
    }

    @Test
    void rollbackKeepsManualSaveAtTheSameTimestamp() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        LocalDateTime checkpointTime = LocalDateTime.of(
                2026, 8, 20, 12, 0);
        TrpgAutoSave autoSave = new TrpgAutoSave()
                .setConversationId(51L)
                .setCheckpointType("SCENE")
                .setSavedAt(checkpointTime)
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSnapshot(snapshot(51L));
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectByConversationAndType(51L, "SCENE"))
                .thenReturn(autoSave);
        when(saveMapper.selectByConversationId(51L)).thenReturn(
                new TrpgSave().setId(91L).setSavedAt(checkpointTime));
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));

        TrpgRollbackResultVO result = service.rollbackScene(7L, 51L);

        assertThat(result.getManualSaveDeleted()).isFalse();
        verify(saveMapper, never()).deleteById(any());
        verify(autoSaveMapper).deleteAfter(51L, checkpointTime);
    }

    @Test
    void rollbackTurnRejectsMissingAutoCheckpointBeforeLocking() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectByConversationAndType(51L, "TURN"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.rollbackTurn(7L, 51L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("没有可回滚");

        verify(lockService, never()).tryLock(any());
        verify(snapshotService, never()).restoreDatabase(any(), any());
    }

    @Test
    void rollbackRejectsUnsupportedCheckpointRowBeforeLocking() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        TrpgAutoSave unsupported = new TrpgAutoSave()
                .setConversationId(51L)
                .setCheckpointType("INITIAL")
                .setSavedAt(LocalDateTime.now())
                .setFormatVersion(1)
                .setSnapshot(snapshot(51L));
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectByConversationAndType(51L, "INITIAL"))
                .thenReturn(unsupported);

        assertThatThrownBy(() -> service.rollbackInitial(7L, 51L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("不支持");

        verify(lockService, never()).tryLock(any());
        verify(snapshotService, never()).restoreDatabase(any(), any());
    }

    @Test
    void rollbackOverviewDisablesMissingAndInvalidCheckpoints() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        LocalDateTime turnTime = LocalDateTime.of(2026, 8, 20, 12, 0);
        TrpgAutoSave turn = new TrpgAutoSave()
                .setConversationId(51L)
                .setCheckpointType("TURN")
                .setSavedAt(turnTime)
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSnapshot(snapshot(51L)
                        .setCursors(new TrpgSaveSnapshotDTO.CursorSnapshot()
                                .setMaxMessageId(314L))
                        .setCharacters(List.of(
                                character(1L, "PLAYER", "林恩"),
                                character(2L, "BOT", "米娅"),
                                character(3L, "NPC", "守门人"))));
        TrpgAutoSave invalidScene = new TrpgAutoSave()
                .setConversationId(51L)
                .setCheckpointType("SCENE")
                .setSavedAt(turnTime.minusMinutes(1))
                .setFormatVersion(1)
                .setSnapshot(snapshot(51L).setFormatVersion(1));
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectByConversationId(51L))
                .thenReturn(List.of(turn, invalidScene));
        when(saveMapper.selectByConversationId(51L)).thenReturn(
                new TrpgSave().setUserId(7L)
                        .setSavedAt(turnTime.plusMinutes(1)));

        var overview = service.getRollbackOverview(7L, 51L);

        assertThat(overview.getTurn().getAvailable()).isTrue();
        assertThat(overview.getTurn().getSavedAt()).isEqualTo(turnTime);
        assertThat(overview.getTurn().getMessageBoundaryId()).isEqualTo(314L);
        assertThat(overview.getTurn().getWillDeleteManualSave()).isTrue();
        assertThat(overview.getTurn().getInvestigators())
                .extracting(state -> state.getName())
                .containsExactly("林恩", "米娅");
        assertThat(overview.getScene().getAvailable()).isFalse();
        assertThat(overview.getScene().getMessageBoundaryId()).isNull();
        assertThat(overview.getScene().getInvestigators()).isEmpty();
        assertThat(overview.getInitial().getAvailable()).isFalse();
    }

    private TrpgSaveSnapshotDTO.RestorableTurnSnapshot restorableTurn(
            Long turnId, Long planId) {
        return new TrpgSaveSnapshotDTO.RestorableTurnSnapshot()
                .setTurn(new GroupChatTurn().setId(turnId).setPlanId(planId));
    }

    private GroupConversation conversation(Long id, String mode) {
        return new GroupConversation()
                .setId(id)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L)
                .setMode(mode)
                .setTitle("雾港")
                .setStatus(GroupChatConstant.STATUS_ACTIVE);
    }

    private CocCharacter character(Long id, String actorType, String name) {
        return new CocCharacter()
                .setId(id)
                .setActorType(actorType)
                .setName(name);
    }

    private TrpgSaveSnapshotDTO snapshot(Long conversationId) {
        return new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(conversationId)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L);
    }
}
