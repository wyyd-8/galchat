package com.me.galchat.service.impl.trpg;

import com.me.galchat.service.impl.group.GroupConversationLockService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.me.galchat.constant.GroupChatConstant;
import com.me.galchat.domain.po.CocModule;
import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.domain.po.TrpgAutoSave;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.mapper.CocModuleMapper;
import com.me.galchat.mapper.GroupConversationMapper;
import com.me.galchat.mapper.TrpgAutoSaveMapper;
import com.me.galchat.mapper.TrpgSaveMapper;
import com.me.galchat.service.ITrpgSaveSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CocModuleRuntimeService {

    private final CocModuleMapper moduleMapper;
    private final GroupConversationMapper conversationMapper;
    private final TrpgAutoSaveMapper autoSaveMapper;
    private final TrpgSaveMapper saveMapper;
    private final ITrpgSaveSnapshotService snapshotService;
    private final GroupConversationLockService conversationLockService;
    private final CocModuleLockService moduleLockService;

    @Transactional(rollbackFor = Exception.class)
    public void lockForStartedRun(GroupConversation conversation) {
        if (conversation == null || conversation.getModuleId() == null) {
            return;
        }
        CocModule module = moduleMapper.selectById(conversation.getModuleId());
        if (module == null || module.getOwnerUserId() == null
                || Boolean.TRUE.equals(module.getEditLocked())) {
            return;
        }
        CocModuleLockService.OwnedLock lock =
                moduleLockService.tryWriteLock(conversation.getModuleId());
        if (lock == null) {
            throw new UserRequestException("当前模组正在变更，请稍后开始行动轮");
        }
        boolean unlockAfterTransaction =
                registerModuleUnlockAfterTransaction(lock);
        try {
            module = moduleMapper.selectById(conversation.getModuleId());
            if (module != null && module.getOwnerUserId() != null
                    && !Boolean.TRUE.equals(module.getEditLocked())) {
                module.setEditLocked(true).setUpdatedAt(LocalDateTime.now());
                moduleMapper.updateById(module);
            }
        } finally {
            if (!unlockAfterTransaction) {
                moduleLockService.unlock(lock);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlock(Long userId, Long moduleId) {
        CocModule module = requireOwned(userId, moduleId);
        if (!Boolean.TRUE.equals(module.getEditLocked())) {
            return;
        }
        List<GroupConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<GroupConversation>()
                        .eq(GroupConversation::getModuleId, moduleId)
                        .orderByAsc(GroupConversation::getId));
        List<GroupConversation> ordered = conversations == null
                ? List.of()
                : conversations.stream()
                .sorted(Comparator.comparing(GroupConversation::getId))
                .toList();
        List<GroupConversationLockService.OwnedLock> locks =
                new ArrayList<>();
        CocModuleLockService.OwnedLock moduleLock = null;
        boolean unlockAfterMethod = true;
        boolean unlockModuleAfterMethod = true;
        try {
            for (GroupConversation conversation : ordered) {
                GroupConversationLockService.OwnedLock lock =
                        conversationLockService.tryLock(conversation.getId());
                if (lock == null) {
                    throw new UserRequestException(
                            "有跑团正在生成回复，请稍后再解锁模组");
                }
                locks.add(lock);
            }
            moduleLock = moduleLockService.tryWriteLock(moduleId);
            if (moduleLock == null) {
                throw new UserRequestException(
                        "当前模组正在变更，请稍后再解锁");
            }
            if (TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                registerUnlockAfterTransaction(locks);
                unlockAfterMethod = false;
                registerModuleUnlockAfterTransaction(moduleLock);
                unlockModuleAfterMethod = false;
            }
            module = requireOwned(userId, moduleId);
            if (!Boolean.TRUE.equals(module.getEditLocked())) {
                return;
            }
            for (GroupConversation conversation : ordered) {
                resetOrInvalidate(conversation);
            }
            module.setEditLocked(false).setUpdatedAt(LocalDateTime.now());
            moduleMapper.updateById(module);
        } finally {
            if (moduleLock != null && unlockModuleAfterMethod) {
                moduleLockService.unlock(moduleLock);
            }
            if (unlockAfterMethod) {
                unlockAll(locks);
            }
        }
    }

    private void resetOrInvalidate(GroupConversation conversation) {
        boolean active = GroupChatConstant.STATUS_ACTIVE.equals(
                conversation.getStatus());
        boolean finished = GroupChatConstant.STATUS_CLOSED.equals(
                conversation.getStatus());
        if (!active && !finished) {
            return;
        }
        TrpgAutoSave initial = autoSaveMapper.selectByConversationAndType(
                conversation.getId(), TrpgSaveServiceImpl.CHECKPOINT_INITIAL);
        if (active && initial == null) {
            return;
        }
        if (active) {
            if (!Objects.equals(initial.getFormatVersion(),
                    TrpgSaveServiceImpl.FORMAT_VERSION)
                    || initial.getSnapshot() == null) {
                throw new UserRequestException("跑团初始存档格式不正确");
            }
            snapshotService.restoreDatabase(
                    conversation, initial.getSnapshot());
            restoreDerivedAfterCommit(conversation, initial);
        }
        saveMapper.deleteByConversationId(conversation.getId());
        autoSaveMapper.deleteNonInitial(conversation.getId());
    }

    private void restoreDerivedAfterCommit(
            GroupConversation conversation, TrpgAutoSave initial) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            snapshotService.restoreDerivedState(
                    conversation, initial.getSnapshot());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        snapshotService.restoreDerivedState(
                                conversation, initial.getSnapshot());
                    }
                });
    }

    private void registerUnlockAfterTransaction(
            List<GroupConversationLockService.OwnedLock> locks) {
        List<GroupConversationLockService.OwnedLock> heldLocks =
                List.copyOf(locks);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        unlockAll(heldLocks);
                    }
                });
    }

    private boolean registerModuleUnlockAfterTransaction(
            CocModuleLockService.OwnedLock lock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        moduleLockService.unlock(lock);
                    }
                });
        return true;
    }

    private void unlockAll(
            List<GroupConversationLockService.OwnedLock> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            conversationLockService.unlock(locks.get(index));
        }
    }

    private CocModule requireOwned(Long userId, Long moduleId) {
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        if (moduleId == null) {
            throw new UserRequestException("模组id不能为空");
        }
        CocModule module = moduleMapper.selectById(moduleId);
        if (module == null || !Objects.equals(
                module.getOwnerUserId(), userId)) {
            throw new UserRequestException("模组不存在或无权操作");
        }
        return module;
    }
}
