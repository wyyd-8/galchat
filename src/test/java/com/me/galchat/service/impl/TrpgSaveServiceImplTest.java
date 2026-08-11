package com.me.galchat.service.impl;

import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveCreateDTO;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocCharacter;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.domain.po.TrpgSave;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.GroupConversationMapper;
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
                .setFormatVersion(1)
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
        assertThat(saved.getFormatVersion()).isEqualTo(1);
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
    void loadRejectsSnapshotFromAnotherConversationBeforeMutation() {
        GroupConversation conversation = conversation(51L, GroupChatConstant.MODE_TRPG);
        TrpgSave save = new TrpgSave()
                .setUserId(7L)
                .setConversationId(51L)
                .setFormatVersion(1)
                .setSavedAt(LocalDateTime.now())
                .setSnapshot(new TrpgSaveSnapshotDTO()
                        .setFormatVersion(1)
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
    void loadRestoresDatabaseThenRunScopedDerivedState() {
        GroupConversation conversation = conversation(51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(1)
                .setConversationId(51L)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L);
        TrpgSave save = new TrpgSave()
                .setUserId(7L)
                .setConversationId(51L)
                .setFormatVersion(1)
                .setSnapshot(snapshot);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(saveMapper.selectByConversationId(51L)).thenReturn(save);
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(mock(RLock.class), 1L));

        service.load(7L, 51L);

        var order = inOrder(snapshotService);
        order.verify(snapshotService).restoreDatabase(conversation, snapshot);
        order.verify(snapshotService).restoreDerivedState(conversation, snapshot);
        verify(recoveryService).assertConversationHasNoNonTerminalTurns(51L);
    }

    @Test
    void saveBeforeTurnOverwritesTheConversationAutoCheckpoint() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L);
        when(snapshotService.capture(conversation)).thenReturn(snapshot);

        service.saveBeforeTurn(conversation);

        ArgumentCaptor<TrpgAutoSave> captor =
                ArgumentCaptor.forClass(TrpgAutoSave.class);
        verify(autoSaveMapper).upsert(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo(51L);
        assertThat(captor.getValue().getFormatVersion()).isEqualTo(1);
        assertThat(captor.getValue().getSnapshot()).isSameAs(snapshot);
        assertThat(captor.getValue().getSavedAt()).isNotNull();
    }

    @Test
    void rollbackTurnRestoresClosedRunAndKeepsTheAutoCheckpoint() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG)
                .setStatus(GroupChatConstant.STATUS_CLOSED);
        TrpgSaveSnapshotDTO snapshot = snapshot(51L);
        TrpgAutoSave autoSave = new TrpgAutoSave()
                .setConversationId(51L)
                .setFormatVersion(1)
                .setSnapshot(snapshot);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectById(51L)).thenReturn(autoSave);
        when(lockService.tryLock(51L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));

        service.rollbackTurn(7L, 51L);
        service.rollbackTurn(7L, 51L);

        var order = inOrder(snapshotService);
        order.verify(snapshotService).restoreDatabase(conversation, snapshot);
        order.verify(snapshotService).restoreDerivedState(conversation, snapshot);
        order.verify(snapshotService).restoreDatabase(conversation, snapshot);
        order.verify(snapshotService).restoreDerivedState(conversation, snapshot);
        verify(autoSaveMapper, times(2)).selectById(51L);
        verify(autoSaveMapper, never()).deleteById(51L);
        verify(recoveryService, times(2))
                .assertConversationHasNoNonTerminalTurns(51L);
    }

    @Test
    void rollbackTurnRejectsMissingAutoCheckpointBeforeLocking() {
        GroupConversation conversation = conversation(
                51L, GroupChatConstant.MODE_TRPG);
        when(conversationMapper.selectById(51L)).thenReturn(conversation);
        when(autoSaveMapper.selectById(51L)).thenReturn(null);

        assertThatThrownBy(() -> service.rollbackTurn(7L, 51L))
                .isInstanceOf(UserRequestException.class)
                .hasMessageContaining("没有可回滚");

        verify(lockService, never()).tryLock(any());
        verify(snapshotService, never()).restoreDatabase(any(), any());
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
                .setFormatVersion(1)
                .setConversationId(conversationId)
                .setUserWorldId(12L)
                .setWorldId(4L)
                .setModuleId(8L);
    }
}
