package com.me.galchat.service.impl;

import com.me.galchat.domain.po.GroupConversation;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.ITrpgRedisStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class GroupConversationDeletionService {

    private final GroupConversationService conversationService;
    private final GroupConversationLockService lockService;
    private final GroupConversationDeletionStore deletionStore;
    private final ITrpgRedisStateService redisStateService;
    private final GroupGenerationStreamRegistry generationRegistry;

    public void delete(Long conversationId) {
        if (conversationId == null) {
            throw new UserRequestException("群聊会话id不能为空");
        }
        GroupConversation conversation =
                conversationService.requireAuthorized(conversationId);
        Long userWorldId = conversation.getUserWorldId();
        GroupConversationLockService.OwnedLock worldLock =
                lockService.tryWorldLock(userWorldId);
        if (worldLock == null) {
            throw new UserRequestException(
                    "当前世界正在存档或读档，请稍后再删除会话");
        }
        GroupConversationLockService.OwnedLock conversationLock = null;
        try {
            conversationLock = lockService.tryLock(conversationId);
            if (conversationLock == null) {
                throw new UserRequestException(
                        "群聊正在生成回复，请稍后再删除");
            }
            GroupConversation lockedConversation =
                    conversationService.requireAuthorized(conversationId);
            if (!Objects.equals(userWorldId,
                    lockedConversation.getUserWorldId())) {
                throw new UserRequestException("群聊会话已发生变化，请重试");
            }
            deletionStore.delete(lockedConversation);
        } finally {
            lockService.unlock(conversationLock);
            lockService.unlock(worldLock);
        }
        clearTransientState(conversationId);
    }

    private void clearTransientState(Long conversationId) {
        try {
            redisStateService.clear(conversationId);
        } catch (RuntimeException exception) {
            log.warn("删除会话后清理TRPG临时状态失败, conversationId:{}",
                    conversationId, exception);
        }
        generationRegistry.evict(conversationId);
    }
}
