package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.dto.TrpgSaveSnapshotDTO;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgAutoSaveMapper;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CocModuleRuntimeServiceTest {

    @Test
    void firstActionTurnLocksAUserOwnedModule() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService moduleLockService =
                mock(CocModuleLockService.class);
        when(moduleLockService.tryWriteLock(3L)).thenReturn(
                new CocModuleLockService.OwnedLock(
                        mock(RLock.class), 1L));
        CocModuleRuntimeService service = serviceWith(moduleMapper,
                mock(GroupConversationMapper.class),
                mock(TrpgAutoSaveMapper.class),
                mock(TrpgSaveMapper.class),
                mock(ITrpgSaveSnapshotService.class),
                mock(GroupConversationLockService.class),
                moduleLockService);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(false));

        service.lockForStartedRun(new GroupConversation()
                .setId(101L).setModuleId(3L));

        verify(moduleMapper).updateById(
                org.mockito.ArgumentMatchers.<CocModule>argThat(module ->
                        module.getId().equals(3L)
                                && Boolean.TRUE.equals(
                                module.getEditLocked())));
    }

    @Test
    void firstActionTurnDoesNotEditWhileAnotherModuleMutationOwnsTheLock() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        CocModuleLockService moduleLockService =
                mock(CocModuleLockService.class);
        when(moduleLockService.tryWriteLock(3L)).thenReturn(null);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(false));
        CocModuleRuntimeService service = serviceWith(moduleMapper,
                mock(GroupConversationMapper.class),
                mock(TrpgAutoSaveMapper.class),
                mock(TrpgSaveMapper.class),
                mock(ITrpgSaveSnapshotService.class),
                mock(GroupConversationLockService.class),
                moduleLockService);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.lockForStartedRun(new GroupConversation()
                        .setId(101L).setModuleId(3L)))
                .isInstanceOf(com.me.galchat.exception
                        .UserRequestException.class)
                .hasMessageContaining("正在变更");

        verify(moduleMapper, never()).updateById(any(CocModule.class));
    }

    @Test
    void unlockingRestoresStartedActiveRunsAndInvalidatesFinishedSaves() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgAutoSaveMapper autoSaveMapper = mock(TrpgAutoSaveMapper.class);
        TrpgSaveMapper saveMapper = mock(TrpgSaveMapper.class);
        ITrpgSaveSnapshotService snapshotService =
                mock(ITrpgSaveSnapshotService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        CocModuleRuntimeService service = serviceWith(
                moduleMapper, conversationMapper, autoSaveMapper,
                saveMapper, snapshotService, lockService,
                availableModuleLock());
        CocModule module = new CocModule().setId(3L)
                .setOwnerUserId(7L).setEditLocked(true);
        GroupConversation activeStarted = conversation(
                101L, GroupChatConstant.STATUS_ACTIVE);
        GroupConversation activeNotStarted = conversation(
                102L, GroupChatConstant.STATUS_ACTIVE);
        GroupConversation finished = conversation(
                103L, GroupChatConstant.STATUS_CLOSED);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setConversationId(101L).setModuleId(3L);
        TrpgAutoSave initial = new TrpgAutoSave()
                .setConversationId(101L)
                .setCheckpointType(TrpgSaveServiceImpl.CHECKPOINT_INITIAL)
                .setSavedAt(LocalDateTime.now())
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                .setSnapshot(snapshot);
        when(moduleMapper.selectById(3L)).thenReturn(module);
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                activeStarted, activeNotStarted, finished));
        when(autoSaveMapper.selectByConversationAndType(
                101L, TrpgSaveServiceImpl.CHECKPOINT_INITIAL))
                .thenReturn(initial);
        when(autoSaveMapper.selectByConversationAndType(
                102L, TrpgSaveServiceImpl.CHECKPOINT_INITIAL))
                .thenReturn(null);
        when(lockService.tryLock(any())).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));

        service.unlock(7L, 3L);

        verify(snapshotService).restoreDatabase(activeStarted, snapshot);
        verify(snapshotService).restoreDerivedState(activeStarted, snapshot);
        verify(saveMapper).deleteByConversationId(101L);
        verify(autoSaveMapper).deleteNonInitial(101L);
        verify(saveMapper, never()).deleteByConversationId(102L);
        verify(autoSaveMapper, never()).deleteNonInitial(102L);
        verify(saveMapper).deleteByConversationId(103L);
        verify(autoSaveMapper).deleteNonInitial(103L);
        assertThat(module.getEditLocked()).isFalse();
        verify(moduleMapper).updateById(module);
    }

    @Test
    void transactionalUnlockKeepsRunLocksAndRedisRestoreUntilCommit() {
        CocModuleMapper moduleMapper = mock(CocModuleMapper.class);
        GroupConversationMapper conversationMapper =
                mock(GroupConversationMapper.class);
        TrpgAutoSaveMapper autoSaveMapper = mock(TrpgAutoSaveMapper.class);
        ITrpgSaveSnapshotService snapshotService =
                mock(ITrpgSaveSnapshotService.class);
        GroupConversationLockService lockService =
                mock(GroupConversationLockService.class);
        CocModuleRuntimeService service = serviceWith(
                moduleMapper, conversationMapper, autoSaveMapper,
                mock(TrpgSaveMapper.class), snapshotService, lockService,
                availableModuleLock());
        GroupConversation active = conversation(
                101L, GroupChatConstant.STATUS_ACTIVE);
        TrpgSaveSnapshotDTO snapshot = new TrpgSaveSnapshotDTO()
                .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION);
        when(moduleMapper.selectById(3L)).thenReturn(new CocModule()
                .setId(3L).setOwnerUserId(7L).setEditLocked(true));
        when(conversationMapper.selectList(any()))
                .thenReturn(List.of(active));
        when(autoSaveMapper.selectByConversationAndType(
                101L, TrpgSaveServiceImpl.CHECKPOINT_INITIAL))
                .thenReturn(new TrpgAutoSave()
                        .setFormatVersion(TrpgSaveServiceImpl.FORMAT_VERSION)
                        .setSnapshot(snapshot));
        when(lockService.tryLock(101L)).thenReturn(
                new GroupConversationLockService.OwnedLock(
                        mock(RLock.class), 1L));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.unlock(7L, 3L);

            verify(snapshotService).restoreDatabase(active, snapshot);
            verify(snapshotService, never())
                    .restoreDerivedState(active, snapshot);
            verify(lockService, never()).unlock(any());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(synchronization ->
                    synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_COMMITTED));

            verify(snapshotService).restoreDerivedState(active, snapshot);
            verify(lockService).unlock(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CocModuleRuntimeService serviceWith(
            CocModuleMapper moduleMapper,
            GroupConversationMapper conversationMapper,
            TrpgAutoSaveMapper autoSaveMapper,
            TrpgSaveMapper saveMapper,
            ITrpgSaveSnapshotService snapshotService,
            GroupConversationLockService lockService,
            CocModuleLockService moduleLockService) {
        return new CocModuleRuntimeService(moduleMapper, conversationMapper,
                autoSaveMapper, saveMapper, snapshotService, lockService,
                moduleLockService);
    }

    private CocModuleLockService availableModuleLock() {
        CocModuleLockService lockService = mock(CocModuleLockService.class);
        when(lockService.tryWriteLock(3L)).thenReturn(
                new CocModuleLockService.OwnedLock(
                        mock(RLock.class), 1L));
        return lockService;
    }

    private GroupConversation conversation(Long id, String status) {
        return new GroupConversation().setId(id).setModuleId(3L)
                .setMode(GroupChatConstant.MODE_TRPG).setStatus(status);
    }
}
